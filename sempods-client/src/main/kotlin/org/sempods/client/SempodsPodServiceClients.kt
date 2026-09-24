package org.sempods.client

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/**
 * A pod owner's service clients: installing one, granting it contexts, and managing the ones that
 * exist. This is an experimental 0.2 extension of the pod's OAuth profile; `docs/auth/oauth.md`
 * §"Installing a service client" has the pod's side.
 *
 * An installation takes two consents in the owner's browser, and the client sees three lifetimes:
 *
 * | What | Lives | |
 * |---|---|---|
 * | the installer's token, `service-clients:install` | about an hour, with no refresh token | registers once, then is spent; it never reaches the owner's data |
 * | the registration | until it is revoked | survives having no grants |
 * | its secret | until it is rotated or the registration is revoked | answered once, by [register] or [rotateSecret] |
 *
 * ```java
 * // 1. The owner approves the installation: SempodsPodAuthorization, then
 * SempodsTokenResponse installer = tokens.authorizationCode(clientId, code, redirectUri, pkce.getVerifier()).getBody();
 * // 2. Register, and store the secret before anything else can fail.
 * var installing = new SempodsPodServiceClients(new SempodsSession(alice, SempodsRequestAuth.bearer(installer.getAccessToken())), client);
 * SempodsServiceClientRegistration service = installing.register("Notes Sync").getBody();
 * store(service.getClientId(), service.getClientSecret());
 * // 3. The owner grants it contexts in a second browser round trip.
 * HttpUrl grant = installing.grantConsentUrl(clientId, redirectUri, state, service.getClientId(), List.of(notes + "#write"));
 * SempodsGrantOutcome outcome = SempodsGrantOutcome.readQuery(redirectQuery, state);
 * // 4. The service mints its own tokens: SempodsPodTokens.clientCredentials.
 * ```
 *
 * `docs/pod-client.md` §"Installing a service client" has the whole program, with the loopback
 * redirect.
 *
 * **Two outcomes, reported apart.** A registration that succeeded is an installation, whatever the
 * grant consent answers: a refused or abandoned consent leaves a service with a secret and no grants,
 * which can be granted later, and an installation with no grants is a valid one.
 *
 * **Built on a session of its own**, whose credential is the authority the operation needs: the
 * installer's bearer for [register], a `service-clients:manage` bearer for the rest. Neither is a pod
 * session's bearer, and the installer's reaches no management route. [grantConsentUrl] sends nothing.
 *
 * **What is safe to send again:**
 *
 * | Operation | After a lost connection |
 * |---|---|
 * | [register] | not sent again. A second registration with the same token is `401 invalid_token`, so a lost answer costs the installation: the owner approves a new one, and the orphan holds no grants |
 * | [rotateSecret] | not sent again. A lost answer leaves a secret nobody holds; rotate once more |
 * | [list], [removeGrants], [revoke] | sent once more, as any idempotent request |
 *
 * A refusal is a [SempodsStatusException] with the status, the headers and the pod's error document:
 *
 * | The pod answers | Means |
 * |---|---|
 * | `400 invalid_client_metadata` | [register]: the body; the token is not spent, and a corrected call may use it |
 * | `401` with `WWW-Authenticate: Bearer error="invalid_token"` | the token is unknown, expired, withdrawn or — for [register] — spent |
 * | `403` with `error="insufficient_scope"` | the token lacks the scope, or its person does not own the pod |
 * | `403` without a challenge | a service client the host operator provisioned, which is not changed here |
 * | `404` | no such service client |
 * | `409` | [rotateSecret]: it changed in between; read it again |
 * | `429 slow_down` | [register]: the pod's registration budget; the token is not spent |
 */
class SempodsPodServiceClients(
  val session: SempodsSession,
  val calls: Call.Factory,
) {

  private val exchange = Exchange(calls)

  /**
   * Registers a service client at `POST {pod}/_system/auth/register`, named [clientName], with no
   * grants. The session carries a `service-clients:install` bearer; the registration spends it.
   */
  @Throws(IOException::class)
  fun register(clientName: String): SempodsResponse<SempodsServiceClientRegistration> =
    exchange.run(registration(clientName), SUCCESS, REGISTRATION)

  /** The same answer with the body as the text the server sent, malformed or not. It holds the secret. */
  @Throws(IOException::class)
  fun registerJson(clientName: String): SempodsResponse<String> = exchange.run(registration(clientName), SUCCESS, BodyReading.TEXT)

  /**
   * Where to send the owner's browser to grant [serviceClientId] the context [scopes]:
   * `{pod}/_system/auth/grant`. The pod shows the owner the service's name, identifier and
   * registration time, and sends the browser back to [redirectUri] with [state];
   * [SempodsGrantOutcome.readQuery] reads what it brings.
   *
   * [callerClientId] and [redirectUri] name the caller as `/authorize` knows it; the installer's own
   * public client qualifies. A pair the pod does not know gets no redirect at all.
   */
  fun grantConsentUrl(callerClientId: String, redirectUri: String, state: String, serviceClientId: String, scopes: Collection<String>): HttpUrl =
    session.podBase.resolve(GRANT).newBuilder()
      .addQueryParameter("client_id", callerClientId)
      .addQueryParameter("redirect_uri", redirectUri)
      .addQueryParameter("state", state)
      .addQueryParameter("service_client", serviceClientId)
      .addQueryParameter("scope", scopes.joinToString(" "))
      .build()

  /** Every service client on the pod, with its grants and when it was last used. */
  @Throws(IOException::class)
  fun list(): SempodsResponse<List<SempodsServiceClient>> = exchange.run(listRequest(), SUCCESS, LIST)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun listJson(): SempodsResponse<String> = exchange.run(listRequest(), SUCCESS, BodyReading.TEXT)

  /**
   * A new secret for [clientId], answered once. The old secret stops working at once; tokens it
   * already minted keep working until they expire.
   */
  @Throws(IOException::class)
  fun rotateSecret(clientId: String): SempodsResponse<SempodsServiceClientSecret> = exchange.run(rotation(clientId), SUCCESS, SECRET)

  /** The same answer with the body as the text the server sent, malformed or not. It holds the secret. */
  @Throws(IOException::class)
  fun rotateSecretJson(clientId: String): SempodsResponse<String> = exchange.run(rotation(clientId), SUCCESS, BodyReading.TEXT)

  /**
   * Takes [scopes] away from [clientId] and answers what it holds afterwards. Removing the last one
   * keeps the registration.
   */
  @Throws(IOException::class)
  fun removeGrants(clientId: String, scopes: Collection<String>): SempodsResponse<SempodsServiceClient> =
    exchange.run(grantRemoval(clientId, scopes), SUCCESS, DESCRIBED)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun removeGrantsJson(clientId: String, scopes: Collection<String>): SempodsResponse<String> =
    exchange.run(grantRemoval(clientId, scopes), SUCCESS, BodyReading.TEXT)

  /**
   * Removes [clientId]'s registration; the contexts it wrote stay. It mints no token afterwards, and a
   * token it already holds reaches nothing.
   *
   * `true` for `204`, `false` for `404`: no such client — which is also what a resend hears after the
   * first attempt removed it and its answer was lost.
   */
  @Throws(IOException::class)
  fun revoke(clientId: String): Boolean = exchange.status(clientRequest("DELETE", clientId).build(), REVOKE_ANSWERS) == 204

  private fun registration(clientName: String) =
    session.newRequest("POST", REGISTER)
      .header("Accept", "application/json")
      .post(
        encodeObject(
          linkedMapOf(
            "client_name" to clientName,
            "grant_types" to listOf("client_credentials"),
            "token_endpoint_auth_method" to "client_secret_basic",
          ),
        ).toRequestBody(JSON),
      )
      .build()

  private fun listRequest() = session.newRequest("GET", SERVICE_CLIENTS).header("Accept", "application/json").build()

  private fun rotation(clientId: String) = clientRequest("POST", clientId, "secret").build()

  private fun grantRemoval(clientId: String, scopes: Collection<String>): Request {
    val request = clientRequest("DELETE", clientId, "grants").build()
    return request.newBuilder().url(request.url.newBuilder().addQueryParameter("scope", scopes.joinToString(" ")).build()).build()
  }

  /** A request to one client's route. Its identifier is one path segment, encoded as one. */
  private fun clientRequest(method: String, clientId: String, vararg below: String): Request.Builder {
    val built = session.newRequest(method, SERVICE_CLIENTS).header("Accept", "application/json").build()
    val url = built.url.newBuilder().addPathSegment(clientId).apply { below.forEach(::addPathSegment) }.build()
    return built.newBuilder().url(url)
  }

  private companion object {

    const val REGISTER = "_system/auth/register"

    const val GRANT = "_system/auth/grant"

    const val SERVICE_CLIENTS = "_system/auth/service-clients"

    val JSON = "application/json".toMediaType()

    val SUCCESS = (200..299).toSet()

    val REVOKE_ANSWERS = setOf(204, 404)

    val REGISTRATION = BodyReading<SempodsServiceClientRegistration> { bytes, _ ->
      val document = decodeObject(bytes)
      val expires = document.longOrNull("client_secret_expires_at")
      SempodsServiceClientRegistration.of(
        clientId = document.string("client_id"),
        clientSecret = document.string("client_secret"),
        clientName = document.stringOrNull("client_name"),
        issuedAt = instant(document, "client_id_issued_at") ?: throw ProtocolViolation("/client_id_issued_at: expected an integer"),
        secretExpiresAt = expires?.takeIf { it != 0L }?.let(Instant::ofEpochSecond),
      )
    }

    val SECRET = BodyReading<SempodsServiceClientSecret> { bytes, _ ->
      val document = decodeObject(bytes)
      SempodsServiceClientSecret.of(document.string("client_id"), document.string("client_secret"))
    }

    val DESCRIBED = BodyReading<SempodsServiceClient> { bytes, _ -> described(decodeObject(bytes)) }

    val LIST = BodyReading<List<SempodsServiceClient>> { bytes, _ -> decodeObject(bytes).objects("serviceClients").map(::described) }

    fun described(document: ProtocolObject): SempodsServiceClient = SempodsServiceClient.of(
      clientId = document.string("client_id"),
      clientName = document.stringOrNull("client_name"),
      issuedAt = instant(document, "client_id_issued_at") ?: throw document.violation("client_id_issued_at: expected an integer"),
      lastUsedAt = instant(document, "last_used_at"),
      scopes = document.string("scope").split(' ').filter { it.isNotEmpty() }.toCollection(LinkedHashSet()),
      origin = document.string("origin"),
    )

    /** Seconds since the epoch, as RFC 7591 writes a time. */
    fun instant(document: ProtocolObject, name: String): Instant? = document.longOrNull(name)?.let(Instant::ofEpochSecond)
  }
}
