package org.sempods.client

import com.nimbusds.oauth2.sdk.GrantType
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.client.ClientMetadata
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.DateTimeException
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
 * var installing = new SempodsPodServiceClients(new SempodsSession(alice, SempodsRequestAuth.bearer(installerToken)), client);
 * SempodsServiceClientRegistration service = installing.register("Notes Sync").getBody();
 * HttpUrl grant = installing.grantConsentUrl(installer, redirectUri, state, service.getClientId(), List.of(notes + "#write"));
 * ```
 *
 * `docs/pod-client.md` §"Installing a service client" has the whole sequence.
 *
 * **A registration is an installation**, whatever the grant consent answers. A refused, abandoned or
 * unreadable consent leaves a service with its secret and no grants, which the owner can grant later.
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
    exchange.run(registration(clientName), ANSWERS, REGISTRATION)

  /** The same answer with the body as the text the server sent, malformed or not. It holds the secret. */
  @Throws(IOException::class)
  fun registerJson(clientName: String): SempodsResponse<String> = exchange.run(registration(clientName), ANSWERS, BodyReading.TEXT)

  /**
   * Where to send the owner's browser to grant [serviceClientId] the context [scopes]:
   * `{pod}/_system/auth/grant`. The pod shows the owner the service's name, identifier and
   * registration time, and sends the browser back to [redirectUri] with [state];
   * [SempodsGrantOutcome.readQuery] reads what it brings.
   *
   * [callerClientId] and [redirectUri] name the caller as `/authorize` knows it; the installer's own
   * public client qualifies. A pair the pod does not know gets no redirect at all.
   *
   * @throws IllegalArgumentException when [redirectUri] is not a URL, or its query already carries a
   *   member of the answer (`result`, `scope`, `state`, `error`, `error_description`, `error_uri`),
   *   which would make the answer ambiguous.
   */
  fun grantConsentUrl(callerClientId: String, redirectUri: String, state: String, serviceClientId: String, scopes: Collection<String>): HttpUrl {
    val redirect = requireNotNull(redirectUri.toHttpUrlOrNull()) { "'$redirectUri' is not a redirect URL." }
    val carried = redirect.queryParameterNames.firstOrNull { it in GRANT_ANSWER_MEMBERS }
    require(carried == null) { "'$redirectUri' carries '$carried', which the grant consent's answer adds itself." }
    return session.podBase.resolve(GRANT).newBuilder()
      .addQueryParameter("client_id", callerClientId)
      .addQueryParameter("redirect_uri", redirectUri)
      .addQueryParameter("state", state)
      .addQueryParameter("service_client", serviceClientId)
      .addQueryParameter("scope", scopeText(scopes))
      .build()
  }

  /** Every service client on the pod, with its grants and when it was last used. */
  @Throws(IOException::class)
  fun list(): SempodsResponse<List<SempodsServiceClient>> = exchange.run(listRequest(), ANSWERS, LIST)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun listJson(): SempodsResponse<String> = exchange.run(listRequest(), ANSWERS, BodyReading.TEXT)

  /**
   * A new secret for [clientId], answered once. The old secret stops working at once; tokens it
   * already minted keep working until they expire.
   */
  @Throws(IOException::class)
  fun rotateSecret(clientId: String): SempodsResponse<SempodsServiceClientSecret> = exchange.run(rotation(clientId), ANSWERS, SECRET)

  /** The same answer with the body as the text the server sent, malformed or not. It holds the secret. */
  @Throws(IOException::class)
  fun rotateSecretJson(clientId: String): SempodsResponse<String> = exchange.run(rotation(clientId), ANSWERS, BodyReading.TEXT)

  /**
   * Takes [scopes] away from [clientId] and answers what it holds afterwards. Removing the last one
   * keeps the registration.
   */
  @Throws(IOException::class)
  fun removeGrants(clientId: String, scopes: Collection<String>): SempodsResponse<SempodsServiceClient> =
    exchange.run(grantRemoval(clientId, scopes), ANSWERS, DESCRIBED)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun removeGrantsJson(clientId: String, scopes: Collection<String>): SempodsResponse<String> =
    exchange.run(grantRemoval(clientId, scopes), ANSWERS, BodyReading.TEXT)

  /**
   * Removes [clientId]'s registration; the contexts it wrote stay. It mints no token afterwards, and a
   * token it already holds reaches nothing.
   *
   * `true` for `204`, `false` for `404`: no such client — which is also what a resend hears after the
   * first attempt removed it and its answer was lost.
   */
  @Throws(IOException::class)
  fun revoke(clientId: String): Boolean = exchange.status(clientRequest("DELETE", clientId), REVOKE_ANSWERS) == 204

  private fun registration(clientName: String) =
    session.newRequest("POST", REGISTER_ROUTE)
      .header("Accept", "application/json")
      .post(
        ClientMetadata().apply {
          name = clientName
          grantTypes = setOf(GrantType.CLIENT_CREDENTIALS)
          tokenEndpointAuthMethod = ClientAuthenticationMethod.CLIENT_SECRET_BASIC
        }.toJSONObject().toJSONString().toRequestBody(JSON_MEDIA_TYPE),
      )
      .build()

  private fun listRequest() = session.newRequest("GET", SERVICE_CLIENTS).header("Accept", "application/json").build()

  private fun rotation(clientId: String) = clientRequest("POST", clientId, "secret")

  private fun grantRemoval(clientId: String, scopes: Collection<String>) =
    clientRequest("DELETE", clientId, "grants", query = mapOf("scope" to scopeText(scopes)))

  /** A request to one client's route, its identifier encoded as one path segment ([podPath]). */
  private fun clientRequest(method: String, clientId: String, below: String? = null, query: Map<String, String> = emptyMap()) =
    session.newRequest(method, podPath(SERVICE_CLIENTS, listOfNotNull(clientId, below), query)).header("Accept", "application/json").build()

  private companion object {

    const val GRANT = "_system/auth/grant"

    val GRANT_ANSWER_MEMBERS = setOf("result", "scope", "state", "error", "error_description", "error_uri")

    const val SERVICE_CLIENTS = "_system/auth/service-clients"

    val ANSWERS = (200..299).toSet()

    val REVOKE_ANSWERS = setOf(204, 404)

    val REGISTRATION = BodyReading<SempodsServiceClientRegistration> { bytes, _ ->
      val document = decodeObject(bytes)
      // RFC 7591 §3.2.1: required with a secret. `0` is a secret that does not expire.
      val expires = instant(document, "client_secret_expires_at") ?: throw ProtocolViolation("/client_secret_expires_at: expected an integer")
      SempodsServiceClientRegistration.of(
        clientId = document.string("client_id"),
        clientSecret = document.string("client_secret"),
        clientName = document.stringOrNull("client_name"),
        issuedAt = instant(document, "client_id_issued_at") ?: throw ProtocolViolation("/client_id_issued_at: expected an integer"),
        secretExpiresAt = expires.takeIf { it != Instant.EPOCH },
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
      // Null says it never minted a token, so a missing member is not read as that.
      lastUsedAt = if ("last_used_at" in document.names()) instant(document, "last_used_at") else throw document.violation("last_used_at: expected a member"),
      scopes = scopesOf(document.string("scope")),
      origin = document.string("origin"),
    )

    /** Seconds since the epoch, as RFC 7591 writes a time. One beyond what an [Instant] holds is a [ProtocolViolation]. */
    fun instant(document: ProtocolObject, name: String): Instant? = document.longOrNull(name)?.let {
      try {
        Instant.ofEpochSecond(it)
      } catch (_: DateTimeException) {
        throw document.violation("$name: expected a time an Instant can hold")
      }
    }
  }
}
