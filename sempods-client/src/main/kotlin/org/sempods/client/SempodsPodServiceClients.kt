package org.sempods.client

import com.nimbusds.oauth2.sdk.GrantType
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.client.ClientMetadata
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.time.DateTimeException
import java.time.Instant

/**
 * A pod's service clients: a service registering itself, sending the owner to its consent, and the
 * owner registering services and deciding what each reaches. This is an experimental 0.2 extension
 * of the pod's OAuth profile; `docs/auth/service-clients.md` has the pod's side.
 *
 * | What | Lives | |
 * |---|---|---|
 * | the registration | until it is revoked, once it is active | until [SempodsServiceClientRegistration.activationExpiresAt] while it is provisional: a registration the owner never confirms is removed |
 * | its secret | until it is rotated or the registration is removed | answered once, by [register] or [rotateSecret] |
 *
 * ```java
 * var registering = new SempodsPodServiceClients(new SempodsSession(pod), client);
 * SempodsServiceClientRegistration service = registering.register("Notes Sync").getBody();
 * HttpUrl consent = registering.consentUrl(service.getClientId(), state);
 * ```
 *
 * The owner's own tool registers the service active and gives it its grants itself:
 *
 * ```java
 * var managing = new SempodsPodServiceClients(new SempodsSession(pod, SempodsRequestAuth.bearer(manageToken)), client);
 * String clientId = managing.register("Notes Sync").getBody().getClientId();
 * long version = managing.get(clientId).getBody().getGrantsVersion();
 * managing.replaceGrants(clientId, List.of(notes + "#read"), version);
 * ```
 *
 * `docs/pod-client.md` §"Registering a service client" has the whole sequence.
 *
 * **Built on a session of its own.** [register] needs no credential: without one the registration
 * is provisional. With a `service-clients:manage` bearer it is active, and the rest need that bearer.
 * [consentUrl] sends nothing.
 *
 * **What is safe to send again:**
 *
 * | Operation | After a lost connection |
 * |---|---|
 * | [register] | not sent again. A second call registers a second service; the one whose answer was lost holds no grants and is removed at its deadline |
 * | [rotateSecret] | not sent again. A lost answer leaves a secret nobody holds; rotate once more |
 * | [list], [get], [replaceGrants], [revoke] | sent once more, as any idempotent request. A [replaceGrants] whose answer was lost hears `412` on the resend if the first attempt landed: read the grants again |
 *
 * A refusal is a [SempodsStatusException] with the status, the headers and the pod's error document:
 *
 * | The pod answers | Means |
 * |---|---|
 * | `400 invalid_client_metadata`, `400 invalid_redirect_uri` | [register]: the body |
 * | `401` with `WWW-Authenticate: Bearer error="invalid_token"` | the token is unknown, expired or withdrawn |
 * | `400` | [replaceGrants]: a scope a service cannot hold, or one on no context of the pod |
 * | `403` with `error="insufficient_scope"` | the token lacks the scope, its person does not own the pod, or it was approved before the consent promised registering and assigning; ask for it again |
 * | `403` without a challenge | [rotateSecret], [revoke]: a service client the host operator provisioned |
 * | `404` | no such service client |
 * | `409` | [rotateSecret]: it changed in between; read it again |
 * | `412` | [replaceGrants]: the grants changed since [SempodsServiceClient.grantsVersion]; read them again |
 * | `429 slow_down` | [register]: the pod's registration budget; try later |
 */
class SempodsPodServiceClients(
  val session: SempodsSession,
  val calls: Call.Factory,
) {

  private val exchange = Exchange(calls)

  /**
   * Registers a service at `POST {pod}/_system/auth/register`, named [clientName], with no grants.
   *
   * Without a credential the registration is provisional until the owner confirms its consent
   * ([consentUrl]), and removed at [SempodsServiceClientRegistration.activationExpiresAt] if they
   * never do. With a `service-clients:manage` bearer it is active, and [replaceGrants] decides what it
   * reaches.
   */
  @Throws(IOException::class)
  fun register(clientName: String): SempodsResponse<SempodsServiceClientRegistration> = register(clientName, emptyList())

  /**
   * The same, with [redirectUris] registered for the service: https, or http on a loopback host.
   * The consent returns only to one of these ([consentUrl]); a loopback address matches on any port.
   */
  @Throws(IOException::class)
  fun register(clientName: String, redirectUris: List<String>): SempodsResponse<SempodsServiceClientRegistration> =
    exchange.run(registration(clientName, redirectUris), ANSWERS, REGISTRATION)

  /** The same answer with the body as the text the server sent, malformed or not. It holds the secret. */
  @Throws(IOException::class)
  fun registerJson(clientName: String): SempodsResponse<String> =
    exchange.run(registration(clientName, emptyList()), ANSWERS, BodyReading.TEXT)

  /**
   * Where to send the owner's browser to decide what [serviceClientId] reaches:
   * `{pod}/_system/auth/service-consent`. The pod shows the owner the service's name as its claim,
   * its identifier, its registration time and the grants it holds now. The request suggests no
   * contexts; the owner picks them.
   *
   * With [redirectUri] the browser returns there with [state] once the owner decides, and with
   * `error=access_denied` beside it on a cancel. It must be one the service registered. Without it
   * the pod tells the owner to go back to the program. Either way nothing about the grants comes
   * back: [SempodsServiceAccessWait] learns them the way the service uses them.
   */
  @JvmOverloads
  fun consentUrl(serviceClientId: String, state: String, redirectUri: String? = null): HttpUrl =
    session.podBase.resolve(CONSENT).newBuilder()
      .addQueryParameter("client_id", serviceClientId)
      .addQueryParameter("state", state)
      .apply { redirectUri?.let { addQueryParameter("redirect_uri", it) } }
      .build()

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

  /** One service client, with its grants and the [SempodsServiceClient.grantsVersion] they are at. */
  @Throws(IOException::class)
  fun get(clientId: String): SempodsResponse<SempodsServiceClient> = exchange.run(clientRequest("GET", clientId), ANSWERS, DESCRIBED)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun getJson(clientId: String): SempodsResponse<String> = exchange.run(clientRequest("GET", clientId), ANSWERS, BodyReading.TEXT)

  /**
   * Makes [scopes] the grants of [clientId], such as `<context-iri>#read`, and answers the service
   * afterwards. Anything not in [scopes] is taken away; an empty list removes every grant and keeps
   * the registration. A provisional registration becomes active.
   *
   * Only if the grants are still at [grantsVersion], the [SempodsServiceClient.grantsVersion] of a
   * read: otherwise `412`, and nothing changes.
   */
  @Throws(IOException::class)
  fun replaceGrants(clientId: String, scopes: Collection<String>, grantsVersion: Long): SempodsResponse<SempodsServiceClient> =
    exchange.run(grantsReplace(clientId, scopes, grantsVersion), ANSWERS, DESCRIBED)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun replaceGrantsJson(clientId: String, scopes: Collection<String>, grantsVersion: Long): SempodsResponse<String> =
    exchange.run(grantsReplace(clientId, scopes, grantsVersion), ANSWERS, BodyReading.TEXT)

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

  private fun grantsReplace(clientId: String, scopes: Collection<String>, grantsVersion: Long): Request {
    require(grantsVersion >= 0) { "A grants version is not negative." }
    return clientRoute("PUT", clientId, "grants")
      .header("If-Match", "\"$grantsVersion\"")
      .put(encodeStrings(scopes.distinct()).toRequestBody(JSON_MEDIA_TYPE))
      .build()
  }

  /** A request to one client's route, its identifier added as one path segment. */
  private fun clientRequest(method: String, clientId: String, below: String? = null): Request = clientRoute(method, clientId, below).build()

  private fun clientRoute(method: String, clientId: String, below: String? = null): Request.Builder =
    session.newRequest(method, SERVICE_CLIENTS, *listOfNotNull(clientId, below).toTypedArray())
      .header("Accept", "application/json")

  private companion object {

    const val CONSENT = "_system/auth/service-consent"

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
      grantsVersion = document.longOrNull("grants_version") ?: throw document.violation("grants_version: expected an integer"),
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
