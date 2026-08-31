package org.sempods.mcp.pods

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.AccessTokenResponse
import com.nimbusds.oauth2.sdk.AuthorizationRequest
import com.nimbusds.oauth2.sdk.ErrorObject
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.`as`.AuthorizationServerMetadata
import com.nimbusds.oauth2.sdk.client.ClientInformation
import com.nimbusds.oauth2.sdk.client.ClientMetadata
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.SoftwareID
import com.nimbusds.oauth2.sdk.id.SoftwareVersion
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.token.AccessTokenType
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils
import net.minidev.json.JSONObject
import org.sempods.auth.core.JwtVerification
import org.sempods.auth.core.JwtVerifier
import org.sempods.auth.core.OAuthErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.client.SempodsBody
import org.sempods.client.SempodsHttpTransport
import org.sempods.client.SempodsResponse
import org.sempods.mcp.forLog
import org.sempods.mcp.oauth.SempodsClientHttpTransport
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Raised when a pod's OAuth surface responds unexpectedly during a connect/refresh.
 *
 * @param oauthErrorCode the `error` member of an RFC 6749 §5.2 error response, when the pod sent
 *   one. It is the only thing that separates a grant that is gone from a pod that is briefly
 *   unwell — see [isDeadGrant].
 */
class PodOAuthException(message: String, val oauthErrorCode: String? = null) : RuntimeException(message) {

  /**
   * Whether this says the grant itself is finished, rather than that the attempt failed.
   *
   * RFC 6749 §5.2 gives exactly one code for it. Everything else — `server_error`,
   * `temporarily_unavailable`, a 502 from a proxy, a pod mid-deploy — is worth trying again, and
   * treating it as a dead grant asks the user to reconnect a pod that was never disconnected.
   */
  val isDeadGrant: Boolean get() = oauthErrorCode == OAuthErrorCode.INVALID_GRANT.code
}

/**
 * The service acting as an OAuth **client** toward a pod (the service → pod layer of M2).
 * Discovers the pod's OAuth metadata, registers via DCR, builds the authorize URL, and
 * exchanges/refreshes tokens. Uses the shared timeout-hardened [SempodsHttpTransport]; pods rotate
 * refresh tokens, so callers must persist the NEW refresh token after every [refresh].
 *
 * The transport blocks, this surface suspends: every fetch goes through [podIo], which runs it on a
 * virtual thread and wires coroutine cancellation to the call.
 *
 * Resolves the pod's endpoints via RFC 9728/8414 discovery rather than hard-coding the pod path,
 * so it works against a pod this service does not host.
 */
class PodOAuthClient(
  private val transport: SempodsHttpTransport,
  private val objectMapper: ObjectMapper,
  private val podUrlPolicy: PodUrlPolicy,
) {

  /**
   * One verifier per advertised `jwks_uri`. See [verifierFor].
   *
   * TODO: unbounded, and unlike `OidcRelyingPartyCache` — whose key is *our own* redirect URI —
   *  this one is keyed by a value the pod authors. A pod that advertises a fresh `jwks_uri` on
   *  every discovery adds an entry per refresh (roughly one per hour per connection) that nothing
   *  reaps. Small entries and bounded by real connections, so not urgent; the fix is an eviction
   *  policy, which is its own decision rather than a line to slip in here.
   */
  private val jwksVerifiers = ConcurrentHashMap<String, JwtVerifier>()

  /**
   * RFC 9728 → RFC 8414 discovery: PRM at the pod, then AS metadata at its issuer.
   *
   * Every URL the service then fetches or posts to (issuer, registration/token/jwks endpoints)
   * is run through [podUrlPolicy] — a public pod must not be able to point its discovered
   * endpoints at localhost/private/metadata services (SSRF). The authorization endpoint is also
   * checked (it only drives a browser redirect, but the same guard keeps it consistent).
   */
  suspend fun discoverMetadata(podBaseUrl: String): PodOAuthMetadata {
    val base = podBaseUrl.trimEnd('/')
    val prm = getJson("$base/.well-known/oauth-protected-resource")
    val issuer = prm["authorization_servers"]?.takeIf { it.isArray && it.size() > 0 }?.get(0)?.asText()
      ?.trimEnd('/')
      ?: throw PodOAuthException("pod protected-resource metadata has no authorization_servers")

    // The resource's half of `scopes_supported` (RFC 9728 §2). It is the fallback, not the answer:
    // the authorization server is the party that answers `invalid_scope`, so where it publishes a
    // list of its own that list wins, even one that omits what the resource advertises. Both are
    // optional, and both RFCs say a server may leave supported values out of them — which is why a
    // pod that publishes neither answers the empty set and gets asked for nothing, rather than
    // being guessed at.
    val prmScopes = scopeList(prm["scopes_supported"])

    // Prefer RFC 8414 AS metadata when the pod publishes it (the full sempods pod, with DCR). Only a
    // genuine **404** means "this pod does not publish AS metadata" → fall back to the sempods
    // **convention**: the AS endpoints sit directly under the issuer (`/authorize`, `/token`), there
    // is no DCR (a static `did:web` client is used instead) and no JWKS (the token's subject is
    // trusted via the direct TLS token — see [verifyAccessTokenSubject]). Any OTHER failure (5xx,
    // timeout, TLS, malformed JSON) is PROPAGATED, not swallowed: silently downgrading a pod that DOES
    // use RFC 8414/DCR/JWKS to the weaker convention path over a transient blip would bind it to the
    // wrong endpoints and drop its signature verification.
    val asmBody = getRawOrNullOn404("$issuer/.well-known/oauth-authorization-server")
    val metadata = if (asmBody != null) {
      val asm = runCatching { JSONObjectUtils.parse(asmBody) }.getOrElse {
        throw PodOAuthException("pod AS metadata is not a JSON object")
      }
      fun str(field: String): String? = (asm[field] as? String)?.trim()?.takeIf { it.isNotBlank() }
      // The pod declares RFC 8414 → require a complete document (endpoints must be present).
      // nimbus makes both endpoints optional, so the completeness rule stays ours; what nimbus
      // decides is the **issuer**, which RFC 8414 §2 says carries no query and no fragment.
      // A document it refuses that way still connects, on the RFC 9728 issuer the pod itself
      // named — the strictness is worth having, but not at the price of a pod that worked before.
      fun req(field: String): String = str(field)
        ?: throw PodOAuthException("pod AS metadata missing '$field'")
      val declaredIssuer = runCatching { AuthorizationServerMetadata.parse(asm).issuer.value }
        .onFailure {
          // nimbus quotes the rejected URI back in its message, control characters included —
          // so the exception text is pod-authored too, not only the values around it.
          logger.info {
            "pod '${forLog(base)}' AS metadata has no usable issuer (${forLog(it.message)}) — keeping '${forLog(issuer)}'"
          }
        }
        .getOrNull()
      PodOAuthMetadata(
        issuer = declaredIssuer?.trimEnd('/') ?: issuer,
        authorizationEndpoint = req("authorization_endpoint"),
        tokenEndpoint = req("token_endpoint"),
        registrationEndpoint = str("registration_endpoint"),
        jwksUri = str("jwks_uri"),
        // Present-and-empty is still the AS speaking; only an absent (or unreadable) member falls
        // back to the resource's list.
        scopesSupported = (asm["scopes_supported"] as? List<*>)?.let(::scopeList) ?: prmScopes,
      )
    } else {
      logger.info {
        "pod '${forLog(base)}' publishes no RFC 8414 metadata — " +
          "using the sempods convention (issuer='${forLog(issuer)}', no DCR/JWKS)"
      }
      PodOAuthMetadata(
        issuer = issuer,
        authorizationEndpoint = "$issuer/authorize",
        tokenEndpoint = "$issuer/token",
        registrationEndpoint = null,
        jwksUri = null,
        scopesSupported = prmScopes,
      )
    }
    // Fail fast: vet every discovered endpoint against the SSRF guard before any use.
    requireAllowed(metadata.authorizationEndpoint)
    requireAllowed(metadata.tokenEndpoint)
    metadata.registrationEndpoint?.let { requireAllowed(it) }
    metadata.jwksUri?.let { requireAllowed(it) }
    return metadata
  }

  /**
   * The strings of a `scopes_supported` array, or empty for anything that is not one. Two
   * overloads because the two documents arrive in two shapes: the protected-resource metadata is
   * read with Jackson (it has no type in the OAuth SDK), the AS metadata through the SDK's own
   * JSON reader. Neither may throw on a pod that publishes junk here — a scope list this cannot
   * read is a pod that gets asked for nothing, not a connect that fails.
   */
  private fun scopeList(node: JsonNode?): Set<String> =
    node?.takeIf { it.isArray }
      ?.mapNotNullTo(mutableSetOf()) { it.takeIf(JsonNode::isTextual)?.asText()?.trim()?.takeIf(String::isNotEmpty) }
      .orEmpty()

  private fun scopeList(values: List<*>?): Set<String> =
    values?.mapNotNullTo(mutableSetOf()) { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

  /** A pod access token's subject (the pod-local WebID) plus whether it was signature-verified. */
  data class PodSubject(val webId: String, val verified: Boolean)

  /**
   * The outcome of reading a pod access token's subject — deliberately three-valued so callers can
   * treat a **definitive verification failure** differently from a merely **inconclusive** read:
   *  - [Readable] — a usable subject (JWKS-verified, or, with no JWKS, trusted via the direct TLS token).
   *  - [VerificationFailed] — the token IS a JWT but its signature did not verify against the pod's
   *    **advertised, successfully-fetched** JWKS. A positive tamper/misconfig signal: refuse it.
   *  - [Unreadable] — the token could not be read at all (opaque/unparseable, no `sub`) OR the
   *    advertised JWKS was unfetchable/unusable (a possibly-transient condition). Inconclusive.
   */
  sealed interface SubjectOutcome {
    data class Readable(val subject: PodSubject) : SubjectOutcome
    data object VerificationFailed : SubjectOutcome
    data object Unreadable : SubjectOutcome
  }

  /**
   * Reads a pod access token's `sub` (the pod-local WebID), verifying its signature against the pod's
   * JWKS when one is advertised. When **no** `jwks_uri` is advertised, the token was fetched directly
   * from the pod's token endpoint over TLS, so its origin is already authenticated by the transport:
   * `sub` is trusted as the pod's own claim and returned [SubjectOutcome.Readable] with
   * `verified = false`. See [SubjectOutcome] for how the failure cases are split.
   */
  suspend fun verifyAccessTokenSubject(metadata: PodOAuthMetadata, accessToken: String): SubjectOutcome {
    val jwt = runCatching { SignedJWT.parse(accessToken) }.getOrNull() ?: return SubjectOutcome.Unreadable
    val sub = runCatching { jwt.jwtClaimsSet.subject?.trim()?.takeIf { it.isNotBlank() } }.getOrNull()
      ?: return SubjectOutcome.Unreadable

    // No JWKS advertised → trust the directly-fetched (TLS) token's own subject, mark it unverified.
    val jwksUri = metadata.jwksUri ?: return SubjectOutcome.Readable(PodSubject(sub, verified = false))

    // JWKS advertised → the pod is signalling "verify me". The three-valued answer is the whole
    // point and maps straight across: a JWKS that could not be fetched, a `kid` that matches
    // nothing, or an algorithm we cannot check are all "we could not ATTEMPT it" → Unreadable,
    // never a positive VerificationFailed. Otherwise a pod signing with e.g. EdDSA, or one whose
    // JWKS host blipped, would be treated as tampered and bricked on refresh.
    val verifier = verifierFor(jwksUri) ?: return SubjectOutcome.Unreadable
    // One `podIo` per verification, and only here: the verifier fetches synchronously the first
    // time it is asked about a given JWKS, and the transport underneath it must not open a second
    // call slot — `SempodsCallSlot` is thread-local and a nested one breaks the outer cancel handle.
    return when (podIo { verifier.verify(accessToken) }) {
      is JwtVerification.Verified -> SubjectOutcome.Readable(PodSubject(sub, verified = true))
      JwtVerification.Inconclusive -> SubjectOutcome.Unreadable
      // Not definitive yet — the cache may be holding the key this token's predecessor was signed
      // with. See [verifyAgainstFreshJwks].
      is JwtVerification.Rejected -> verifyAgainstFreshJwks(jwksUri, accessToken, sub)
    }
  }

  /**
   * Second opinion on a rejection, against a JWKS fetched now.
   *
   * **A cached rejection is not the same claim as a fetched one, and the difference is a dead
   * connection.** Nimbus re-fetches when the key *selector* comes back empty — an unknown `kid` —
   * but a pod that rotates its signing key while keeping the `kid`, or that publishes no `kid` at
   * all, still matches the stale key. The selector is satisfied, nothing is re-fetched, and a
   * perfectly good token reads as a bad signature for up to the cache's five minutes. On the
   * refresh path that is unrecoverable rather than merely wrong: `PodTokenProvider` treats
   * [SubjectOutcome.VerificationFailed] as a tamper signal and discards the freshly rotated refresh
   * token, while the pod has already consumed the old one — so the connection needs a manual
   * reconnect. The pre-cache code could not hit this, because it fetched the JWKS every time.
   *
   * So a rejection costs one fetch, which is what *every* verification cost before the cache
   * existed. Only a rejection that survives fresh keys is [SubjectOutcome.VerificationFailed]; if
   * the refetch fails we no longer know, and inconclusive is the honest answer rather than an
   * accusation.
   */
  private suspend fun verifyAgainstFreshJwks(jwksUri: String, accessToken: String, sub: String): SubjectOutcome {
    val fresh = rebuiltVerifierFor(jwksUri) ?: return SubjectOutcome.Unreadable
    return when (podIo { fresh.verify(accessToken) }) {
      is JwtVerification.Verified -> SubjectOutcome.Readable(PodSubject(sub, verified = true))
      is JwtVerification.Rejected -> SubjectOutcome.VerificationFailed
      JwtVerification.Inconclusive -> SubjectOutcome.Unreadable
    }
  }

  /**
   * The verifier for one pod's JWKS, built once and kept.
   *
   * Before this cache the JWKS was re-fetched *and the verifier rebuilt* on **every** token refresh,
   * under the per-key lock. Keeping the verifier removes the rebuild. It does not remove the fetch:
   * nimbus caches the JWKS itself with a five-minute default TTL, and [JwtVerifier.remoteJwks] builds
   * that source with `.refreshAheadCache(false)`, so nothing reloads it in the background. At the
   * refresh sweep's ~55-minute cadence the entry is always stale by the time it is asked for, and a
   * rotation still costs one JWKS GET — four requests per refresh against a pod that serves one.
   *
   * Built outside the map and inserted with `putIfAbsent`, for the reason `OidcRelyingPartyCache`
   * records: `computeIfAbsent` would run construction under the map's bin lock, and overwriting a
   * loser's entry would pull a self-refreshing key view out from under a caller already holding it.
   *
   * `null` when the URL is one [podUrlPolicy] refuses or cannot be parsed — the SSRF guard, which
   * has to run here because after this returns the fetch is nimbus's to schedule. A refusal is
   * [SubjectOutcome.Unreadable] to the caller, which is what a blocked JWKS answered before too.
   *
   * A stale entry is replaced by [rebuiltVerifierFor], not expired here.
   */
  private fun verifierFor(jwksUri: String): JwtVerifier? {
    jwksVerifiers[jwksUri]?.let { return it }
    val built = buildVerifier(jwksUri) ?: return null
    return jwksVerifiers.putIfAbsent(jwksUri, built) ?: built
  }

  /**
   * A verifier with an empty cache, replacing whatever was held for [jwksUri].
   *
   * `put` rather than `putIfAbsent`, and that is the one place the rule `OidcRelyingPartyCache`
   * states is deliberately inverted: the entry being displaced is the one just found to be stale,
   * so keeping it is the failure. A caller still holding the old instance is unaffected — it keeps
   * working, with the keys it had.
   */
  private fun rebuiltVerifierFor(jwksUri: String): JwtVerifier? {
    val built = buildVerifier(jwksUri) ?: return null
    jwksVerifiers[jwksUri] = built
    return built
  }

  /** `null` when the URL is one [podUrlPolicy] refuses or cannot be parsed. */
  private fun buildVerifier(jwksUri: String): JwtVerifier? = runCatching {
    requireAllowed(jwksUri)
    // `signatureOnly`: the pod's own token, on the pod's own clock. This call asks whether the
    // subject is still the one we recorded, and that is not an occasion to overrule a pod about
    // when its token dies — `PodTokenProvider` decides expiry from `expires_in`, not from here.
    JwtVerifier.remoteJwks(jwksUri, SempodsClientHttpTransport(transport))
  }.getOrNull()

  /** RFC 7591 DCR at the pod. The pod dedups by fingerprint, so this is idempotent per pod. */
  suspend fun registerClient(metadata: PodOAuthMetadata, redirectUri: String, softwareVersion: String): String {
    val registrationEndpoint = metadata.registrationEndpoint
      ?: throw PodOAuthException("pod publishes no registration endpoint (use the static did:web client)")
    requireAllowed(registrationEndpoint)
    val body = ClientMetadata().apply {
      setRedirectionURI(URI(redirectUri))
      name = CLIENT_NAME
      softwareID = SoftwareID(CLIENT_NAME)
      setSoftwareVersion(SoftwareVersion(softwareVersion))
    }.toJSONObject().toJSONString()
    val response = podIo {
      transport.send(
        transport.newRequest(URI(registrationEndpoint))
          .header("Content-Type", "application/json")
          .header("User-Agent", CLIENT_NAME)
          .POST(SempodsBody.text(body))
          .build(),
      )
    }
    if (!response.isSuccess) failResponse("DCR", registrationEndpoint, response)
    val json = runCatching { JSONObjectUtils.parse(response.body) }.getOrElse {
      throw PodOAuthException("pod DCR response is not a JSON object")
    }
    // `ClientInformation.parse` validates the whole registration document, which is more than this
    // needs: the one field used is `client_id`, and a pod whose echoed metadata nimbus dislikes
    // registered successfully all the same. So nimbus reads it, and the single field is the
    // fallback — the strict parse must not turn a working registration into a failure.
    return runCatching { ClientInformation.parse(json).id.value }.getOrNull()
      ?: (json["client_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
      ?: throw PodOAuthException("pod DCR response has no client_id")
  }

  fun buildAuthorizeUrl(
    metadata: PodOAuthMetadata,
    clientId: String,
    redirectUri: String,
    codeChallenge: String,
    state: String,
    scope: String?,
  ): String {
    requireAllowed(metadata.authorizationEndpoint)
    // Built by nimbus rather than by hand, and the reason is the endpoint URI: RFC 6749 §3.1 lets
    // an authorization endpoint carry a query of its own, which the hand-built version appended a
    // second `?` to. `toURI()` merges that query with the request parameters instead.
    val request = AuthorizationRequest.Builder(ResponseType.CODE, ClientID(clientId))
      .endpointURI(URI(metadata.authorizationEndpoint))
      .redirectionURI(URI(redirectUri))
      .codeChallenge(CodeChallenge.parse(codeChallenge), CodeChallengeMethod.S256)
      .state(State(state))
      .apply { scope?.takeIf { it.isNotBlank() }?.let { scope(Scope.parse(it)) } }
      .build()
    return request.toURI().toString()
  }

  suspend fun exchangeCode(
    metadata: PodOAuthMetadata,
    code: String,
    redirectUri: String,
    clientId: String,
    codeVerifier: String,
  ): PodTokenResponse {
    requireAllowed(metadata.tokenEndpoint)
    val response = postForm(
      metadata.tokenEndpoint,
      "grant_type" to "authorization_code",
      "code" to code,
      "redirect_uri" to redirectUri,
      "client_id" to clientId,
      "code_verifier" to codeVerifier,
    )
    if (!response.isSuccess) failResponse("token exchange", metadata.tokenEndpoint, response)
    return parseTokenResponse(response.body)
  }

  suspend fun refresh(metadata: PodOAuthMetadata, refreshToken: String, clientId: String): PodTokenResponse {
    requireAllowed(metadata.tokenEndpoint)
    val response = postForm(
      metadata.tokenEndpoint,
      "grant_type" to "refresh_token",
      "refresh_token" to refreshToken,
      "client_id" to clientId,
    )
    if (!response.isSuccess) failResponse("token refresh", metadata.tokenEndpoint, response)
    return parseTokenResponse(response.body)
  }

  /**
   * The one document still read with Jackson, and deliberately so: RFC 9728 protected-resource
   * metadata has no type in the OAuth SDK, so there is nothing to parse it into. Everything the
   * SDK does model — AS metadata, token responses, error objects, DCR — goes through it instead.
   */
  private suspend fun getJson(url: String): JsonNode =
    objectMapper.readTree(getRaw(url))

  /**
   * SSRF-guarded GET that returns null **only** on a genuine 404 (the resource is definitively
   * absent). Any other non-2xx or a transport error propagates — a caller must not mistake a
   * transient failure for "absent" and guess a fallback.
   */
  private suspend fun getRawOrNullOn404(url: String): String? {
    val response = get(url)
    if (response.statusCode == 404) return null
    if (!response.isSuccess) failResponse("fetch", url, response)
    return response.body
  }

  /** SSRF-guarded GET returning the raw body. Every server-side fetch goes through here. */
  private suspend fun getRaw(url: String): String {
    val response = get(url)
    if (!response.isSuccess) failResponse("fetch", url, response)
    return response.body
  }

  private suspend fun get(url: String): SempodsResponse<String> {
    requireAllowed(url)
    return podIo { transport.send(transport.newRequest(URI(url)).GET().build()) }
  }

  /** `application/x-www-form-urlencoded` per RFC 6749 §2.3.1 — the shape a token endpoint expects. */
  private suspend fun postForm(url: String, vararg fields: Pair<String, String>): SempodsResponse<String> {
    requireAllowed(url)
    val form = fields.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    return podIo {
      transport.send(
        transport.newRequest(URI(url))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(SempodsBody.text(form))
          .build(),
      )
    }
  }

  private val SempodsResponse<*>.isSuccess: Boolean get() = statusCode / 100 == 2

  /** Rejects a URL the [podUrlPolicy] disallows (SSRF guard for discovered endpoints). */
  private fun requireAllowed(url: String) {
    podUrlPolicy.reject(url)?.let { reason -> throw PodOAuthException("blocked URL ($reason): $url") }
  }

  private fun parseTokenResponse(body: String): PodTokenResponse {
    val json = runCatching { JSONObjectUtils.parse(body) }.getOrElse {
      throw PodOAuthException("pod token response is not a JSON object")
    }
    val rawTokenType = json["token_type"] as? String
    val podTokenType = rawTokenType?.trim()?.takeIf { it.isNotBlank() }
    // The lifetime is read from the raw member, not from nimbus, because nimbus cannot express the
    // distinction that matters downstream: it answers 0 both for an absent `expires_in` and for an
    // explicit one, and the two are opposites. An **unknown** expiry is held optimistically and
    // never refreshed (`PodTokenProvider.isDue` returns false for a null one); an explicit 0 — or a
    // negative — is a token that is already stale and whose refresh token should be used at once.
    // Non-numeric is treated as absent, which is what the hand-written parse did.
    val declaredLifetime = (json["expires_in"] as? Number)?.toLong()
    // nimbus refuses a token response whose `token_type` is missing **or** names something it does
    // not know, and it also sits in judgement over `expires_in` — refusing one that is present but
    // not a number, and, since 11.38, one that is negative. Every such response was a pod that
    // connected before, so nimbus gets a normalised copy: it decides what the tokens are, not
    // whether the pod is allowed to be sloppy about the fields read above.
    //
    // The lifetime was already read from the raw member above and is never taken from nimbus, so
    // nimbus has no need to see `expires_in` at all: the copy always drops it. That way no present
    // or future tightening of how nimbus validates that member can refuse a token the pod returned.
    //
    // The token_type comparison is against the **raw** member, because that is what nimbus reads: a
    // padded `" Bearer "` satisfies the trimmed check above and is still refused. When the pod's
    // word is one nimbus knows, the copy gets it trimmed; otherwise the copy gets `Bearer` and
    // [PodTokenResponse] keeps the pod's own word regardless.
    val nimbusTokenType = podTokenType?.takeIf { it in NIMBUS_KNOWN_TOKEN_TYPES }
      ?: AccessTokenType.BEARER.value
    val needsNormalising = rawTokenType != nimbusTokenType || json.containsKey("expires_in")
    val normalised = if (!needsNormalising) json else JSONObject(json).apply {
      this["token_type"] = nimbusTokenType
      remove("expires_in")
    }
    val tokens = runCatching { AccessTokenResponse.parse(normalised).tokens }.getOrElse {
      throw PodOAuthException("pod token response has no access_token")
    }
    return PodTokenResponse(
      accessToken = tokens.accessToken.value,
      tokenType = podTokenType ?: AccessTokenType.BEARER.value,
      expiresInSeconds = declaredLifetime,
      refreshToken = tokens.refreshToken?.value,
      scope = tokens.accessToken.scope?.toString(),
    )
  }

  private fun failResponse(op: String, url: String, response: SempodsResponse<String>): Nothing {
    val body = response.body
    // An OAuth failure is a JSON document that names the reason (RFC 6749 §5.2). Reading it is
    // what lets a caller tell a revoked grant from a pod that is briefly unwell; the status code
    // alone cannot, because both arrive as 400.
    // `ErrorObject.parse` does not throw and answers nulls for what is absent, which is what this
    // needs: the same method reports a failed metadata or JWKS fetch, where the body is not an
    // OAuth error document at all and often not JSON.
    val error = runCatching { ErrorObject.parse(JSONObjectUtils.parse(body)) }.getOrNull()
    val code = error?.code?.trim()?.takeIf { it.isNotEmpty() }
    val description = error?.description?.trim()?.takeIf { it.isNotEmpty() }
    // The body is whatever the pod sent, and `url` is an endpoint it advertised: both are
    // another host's text, and this line predates `forLog` rather than being exempt from it.
    logger.warn { "Pod $op failed at ${forLog(url)}: HTTP ${response.statusCode} ${forLog(body)}" }
    throw PodOAuthException(
      message = "pod $op failed: HTTP ${response.statusCode}" +
        (code?.let { " ($it${description?.let { d -> ": $d" }.orEmpty()})" } ?: ""),
      oauthErrorCode = code,
    )
  }

  private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

  companion object {
    private val logger = KotlinLogging.logger {}
    const val CLIENT_NAME = "sempods-mcp"

    /** The `token_type` values nimbus will parse. Anything else is normalised — see the KDoc there. */
    private val NIMBUS_KNOWN_TOKEN_TYPES = setOf(
      AccessTokenType.BEARER.value,
      AccessTokenType.DPOP.value,
      AccessTokenType.N_A.value,
    )
  }
}
