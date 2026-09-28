package org.sempods.client

import com.nimbusds.oauth2.sdk.GrantType
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.client.ClientMetadata
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.time.DateTimeException
import java.time.Instant

/**
 * A pod's service clients: a service registering itself, the owner granting it contexts, and the
 * owner managing the ones that exist. This is an experimental 0.2 extension of the pod's OAuth
 * profile; `docs/auth/oauth.md` §"Registering a service client" has the pod's side.
 *
 * | What | Lives | |
 * |---|---|---|
 * | the registration | until it is revoked, once the owner grants it contexts | until [SempodsServiceClientRegistration.activationExpiresAt] before that: a registration the owner never activates is removed |
 * | its secret | until it is rotated or the registration is removed | answered once, by [register] or [rotateSecret] |
 *
 * ```java
 * var registering = new SempodsPodServiceClients(new SempodsSession(alice), client);
 * SempodsServiceClientRegistration service = registering.register("Notes Sync").getBody();
 * HttpUrl grant = registering.grantConsentUrl(app, redirectUri, state, service.getClientId(), List.of(notes + "#write"));
 * ```
 *
 * `docs/pod-client.md` §"Registering a service client" has the whole sequence.
 *
 * **Built on a session of its own.** [register] needs no credential, and a session without one is
 * enough; the rest need a `service-clients:manage` bearer. [grantConsentUrl] sends nothing.
 *
 * **What is safe to send again:**
 *
 * | Operation | After a lost connection |
 * |---|---|
 * | [register] | not sent again. A second call registers a second service; the one whose answer was lost holds no grants and is removed at its deadline |
 * | [rotateSecret] | not sent again. A lost answer leaves a secret nobody holds; rotate once more |
 * | [list], [removeGrants], [revoke] | sent once more, as any idempotent request |
 *
 * A refusal is a [SempodsStatusException] with the status, the headers and the pod's error document:
 *
 * | The pod answers | Means |
 * |---|---|
 * | `400 invalid_client_metadata`, `400 invalid_redirect_uri` | [register]: the body |
 * | `401` with `WWW-Authenticate: Bearer error="invalid_token"` | the token is unknown, expired or withdrawn |
 * | `403` with `error="insufficient_scope"` | the token lacks the scope, or its person does not own the pod |
 * | `403` without a challenge | a service client the host operator provisioned, which is not changed here |
 * | `404` | no such service client |
 * | `409` | [rotateSecret]: it changed in between; read it again |
 * | `429 slow_down` | [register]: the pod's registration budget; try later |
 */
class SempodsPodServiceClients(
  val session: SempodsSession,
  val calls: Call.Factory,
) {

  private val exchange = Exchange(calls)

  /**
   * Registers a service at `POST {pod}/_system/auth/register`, named [clientName], with no grants.
   * The registration is provisional until the owner grants it contexts, and removed at
   * [SempodsServiceClientRegistration.activationExpiresAt] if they never do.
   */
  @Throws(IOException::class)
  fun register(clientName: String): SempodsResponse<SempodsServiceClientRegistration> = register(clientName, emptyList())

  /**
   * The same, with [redirectUris] the owner's browser may return to after the consent: https, or
   * http on a loopback host.
   */
  @Throws(IOException::class)
  fun register(clientName: String, redirectUris: List<String>): SempodsResponse<SempodsServiceClientRegistration> =
    exchange.run(registration(clientName, redirectUris), ANSWERS, REGISTRATION)

  /** The same answer with the body as the text the server sent, malformed or not. It holds the secret. */
  @Throws(IOException::class)
  fun registerJson(clientName: String): SempodsResponse<String> =
    exchange.run(registration(clientName, emptyList()), ANSWERS, BodyReading.TEXT)

  /**
   * Where to send the owner's browser to grant [serviceClientId] the context [scopes]:
   * `{pod}/_system/auth/grant`. The pod shows the owner the service's name, identifier and
   * registration time, and sends the browser back to [redirectUri] with [state];
   * [SempodsGrantOutcome.readQuery] reads what it brings.
   *
   * [callerClientId] and [redirectUri] name the caller as `/authorize` knows it; the program's own
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

  private fun registration(clientName: String, redirectUris: List<String>) =
    session.newRequest("POST", REGISTER_ROUTE)
      .header("Accept", "application/json")
      .post(
        ClientMetadata().apply {
          name = clientName
          grantTypes = setOf(GrantType.CLIENT_CREDENTIALS)
          tokenEndpointAuthMethod = ClientAuthenticationMethod.CLIENT_SECRET_BASIC
          if (redirectUris.isNotEmpty()) redirectionURIs = redirectUris.mapTo(LinkedHashSet(), URI::create)
        }.toJSONObject().apply {
          // The SDK writes `response_types: ["code"]` beside redirect URIs, which a client
          // authenticating with a secret does not have.
          remove("response_types")
        }.toJSONString().toRequestBody(JSON_MEDIA_TYPE),
      )
      .build()

  private fun listRequest() = session.newRequest("GET", SERVICE_CLIENTS).header("Accept", "application/json").build()

  private fun rotation(clientId: String) = clientRequest("POST", clientId, "secret")

  private fun grantRemoval(clientId: String, scopes: Collection<String>) =
    clientRequest("DELETE", clientId, "grants", query = mapOf("scope" to scopeText(scopes)))

  /** A request to one client's route, its identifier added as one path segment. */
  private fun clientRequest(method: String, clientId: String, below: String? = null, query: Map<String, String> = emptyMap()): Request {
    val built = session.newRequest(method, SERVICE_CLIENTS, *listOfNotNull(clientId, below).toTypedArray())
      .header("Accept", "application/json")
      .build()
    val url = built.url.newBuilder().apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
    return built.newBuilder().url(url).build()
  }

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
        redirectUris = if ("redirect_uris" in document.names()) document.strings("redirect_uris") else emptyList(),
        activationExpiresAt = instant(document, "activation_expires_at"),
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
      activationExpiresAt = instant(document, "activation_expires_at"),
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
