package org.sempods.mcp.pods

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.crypto.SecretCipher
import org.sempods.commons.utils.HashUtil.sha256Hex
import org.bson.Document
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Mongo-backed CSRF-state store for the pod-connect OAuth round-trip (service → pod) — durable
 * across restarts and shared across replicas (M6.3), so a connect started on one replica
 * completes on another.
 *
 * The opaque `state` sent to the pod binds to the full flow context so the callback resumes the
 * exact connect it was started for — the mix-up defense the concept doc calls for. One-time use
 * ([consume] = atomic `findOneAndDelete`), 15-min TTL. The `_id` is the SHA-256 of the state;
 * the PKCE [Pending.codeVerifier] is additionally encrypted at rest — unlike the other fields
 * it is a credential redeemable at an *external* token endpoint (verifier + intercepted pod
 * authorization code), so key-hashing alone would not cover it.
 */
/**
 * @param collectionName the production name is the default; a test points an instance at a
 *   collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md` §"Conventions" states.
 */
class PodConnectStateStore(
  db: MongoDatabase,
  private val cipher: SecretCipher,
  collectionName: String = SempodsMcpCollections.OAUTH_POD_CONNECT_STATES,
) {

  data class Pending(
    val user: String,
    val profile: String,
    val pod: String,
    val metadata: PodOAuthMetadata,
    val podClientId: String,
    val codeVerifier: String,
    val redirectUri: String,
    val expiresAt: Long,
    /**
     * Where the callback sends the browser once the pod is connected. Null = the default
     * `/_system/ui` dashboard (the plain dashboard connect). When the connect was started from the
     * inline consent flow this is the consent-resume URL, so the user lands back on the consent
     * screen (now showing the pod) instead of the dashboard. Always an internal `$base/…` URL —
     * validated at creation, never reflected from untrusted input into an open redirect.
     */
    val returnTo: String? = null,
  )

  private val states = db.getCollection(collectionName)
  private val random = SecureRandom()

  init {
    states.createIndex(Indexes.ascending("expiresAt"), IndexOptions().expireAfter(0, TimeUnit.SECONDS))
  }

  fun create(build: (expiresAt: Long) -> Pending): String {
    val state = newState()
    states.insertOne(build(System.currentTimeMillis() + TTL_MS).toDocument(sha256Hex(state)))
    return state
  }

  fun consume(state: String): Pending? {
    val doc = states.findOneAndDelete(Filters.eq("_id", sha256Hex(state))) ?: return null
    // An unreadable row (undecryptable codeVerifier — changed MCP_SECRET_KEY mid-flow, tampered
    // data) is an invalid connect state, not a crash: same soft semantics as an unreadable
    // token-vault row. The row is already deleted, so a retry starts a clean fresh connect.
    return runCatching { doc.toPending() }
      .onFailure { logger.warn(it) { "unreadable pod-connect state row — treating as invalid" } }
      .getOrNull()
      ?.takeIf { System.currentTimeMillis() < it.expiresAt }
  }

  private fun newState(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private fun Pending.toDocument(id: String) = Document().apply {
    put("_id", id)
    put("user", user)
    put("profile", profile)
    put("pod", pod)
    put("issuer", metadata.issuer)
    put("authorizationEndpoint", metadata.authorizationEndpoint)
    put("tokenEndpoint", metadata.tokenEndpoint)
    put("registrationEndpoint", metadata.registrationEndpoint)
    put("jwksUri", metadata.jwksUri)
    put("podClientId", podClientId)
    put("codeVerifier", cipher.encrypt(codeVerifier))
    put("redirectUri", redirectUri)
    put("returnTo", returnTo)
    put("expiresAt", Date(expiresAt))
  }

  private fun Document.toPending() = Pending(
    user = getString("user"),
    profile = getString("profile"),
    pod = getString("pod"),
    metadata = PodOAuthMetadata(
      issuer = getString("issuer"),
      authorizationEndpoint = getString("authorizationEndpoint"),
      tokenEndpoint = getString("tokenEndpoint"),
      registrationEndpoint = getString("registrationEndpoint"),
      jwksUri = getString("jwksUri"),
    ),
    podClientId = getString("podClientId"),
    codeVerifier = cipher.decrypt(getString("codeVerifier")),
    redirectUri = getString("redirectUri"),
    returnTo = getString("returnTo"),
    expiresAt = getDate("expiresAt").time,
  )

  companion object {
    private val logger = KotlinLogging.logger {}

    private const val TTL_MS = 15 * 60 * 1000L
  }
}
