package org.sempods.api.pod.system.auth

import org.sempods.auth.core.DynamicClientFingerprint
import com.google.inject.Inject
import org.bson.types.ObjectId
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Persistent store for clients registered via RFC 7591 Dynamic Client Registration.
 *
 * MCP clients (Claude Desktop/Code/Web, ChatGPT, …) discover this pod's authorization server,
 * then POST to the advertised `registration_endpoint` to obtain a `client_id` they can use in
 * `/authorize`. We accept the full RFC 7591 metadata set so we can observe what real agents
 * actually send — later stages will use this to derive a stable per-agent identity across pods.
 *
 * Storage is dedup-on-write via [DynamicClientRegistrationDao] +
 * [DynamicClientFingerprint]: a repeat `/register` from the same logical client
 * (matching fingerprint inputs) returns the existing row's `clientId` rather
 * than inserting a new document. Only first registrations write a fresh row,
 * preserving the verbatim request body for Stage-2 analysis. Hot-path lookups
 * from `/authorize` hit the DAO directly (no in-memory cache — lookup is once per OAuth flow).
 */
class DynamicClientStore @Inject constructor(
  private val dao: DynamicClientRegistrationDao,
) {

  data class Registration(
    val clientId: String,
    val redirectUris: Set<String>,
    val clientName: String?,
    val clientUri: String?,
    val logoUri: String?,
    val softwareId: String?,
    val softwareVersion: String?,
    val contacts: List<String>,
    val tosUri: String?,
    val policyUri: String?,
    val registeredAt: Instant,
    // Verbatim request body — kept so later stages can mine fields we don't explicitly model yet.
    val rawRequest: Map<String, Any?>,
    // When set, this is an existing row returned by fingerprint-dedup rather than a freshly
    // inserted one. Lets callers log the dedup hit and differentiate observations.
    val deduplicatedFromRegisteredAt: Instant? = null,
  )

  private val random = SecureRandom()

  internal fun register(
    registeredForPodId: ObjectId,
    registeredForPodName: String,
    redirectUris: Set<String>,
    clientName: String?,
    clientUri: String? = null,
    logoUri: String? = null,
    softwareId: String? = null,
    softwareVersion: String? = null,
    contacts: List<String> = emptyList(),
    tosUri: String? = null,
    policyUri: String? = null,
    rawRequest: Map<String, Any?> = emptyMap(),
    remoteAddr: String? = null,
    userAgent: String? = null,
  ): Registration {
    // Pod-scoped dedup: a repeat `/register` from the same client (stable clientName +
    // userAgent, redirect URIs equal up to loopback port) reuses the existing clientId.
    // Keeps consent + grants anchored to one row per logical agent instead of accumulating
    // an orphan per reconnect. The realm slot the fingerprint offers stays empty here — a
    // pod has one MCP surface, so there is nothing to fork registrations by.
    val fingerprint = DynamicClientFingerprint.compute(clientName, userAgent, realm = null, redirectUris)

    // Look, then insert, and let the loop settle whichever of the two the other party won. A
    // second registration of this client landing in the gap is all it takes to make the lookup
    // miss and the insert be refused — two people clicking "Connect" for one pod in the same
    // second, since the fingerprint carries nothing that tells them apart — and the next pass's
    // lookup then answers the winner's row, which the caller cannot tell from an ordinary dedup
    // hit because it is one: one logical client is one `client_id`, and that is what the pod's
    // grants are keyed by.
    //
    // Looping rather than re-reading once, because the other direction happens too: the winner's
    // row can be gone by the time the loser reads it (the pod-deletion cascade clears every
    // registration), and a single re-read would answer nothing and turn an unauthenticated
    // `/register` into a 500. The next pass just inserts.
    repeat(ATTEMPTS) {
      dao.findByFingerprint(registeredForPodId, fingerprint)?.let { existing ->
        return existing.toRegistration(deduplicatedFromRegisteredAt = existing.registeredAt)
      }
      val dbo = dao.create(
        clientId = "dyn:" + newOpaqueId(),
        registeredForPodId = registeredForPodId,
        registeredForPodName = registeredForPodName,
        redirectUris = redirectUris,
        clientName = clientName,
        clientUri = clientUri,
        logoUri = logoUri,
        softwareId = softwareId,
        softwareVersion = softwareVersion,
        contacts = contacts,
        tosUri = tosUri,
        policyUri = policyUri,
        rawRequest = rawRequest,
        remoteAddr = remoteAddr,
        userAgent = userAgent,
        fingerprint = fingerprint,
      )
      if (dbo != null) return dbo.toRegistration()
    }
    // Losing every pass means the two are alternating in step, which is not a race any more.
    error("registration neither found nor inserted in $ATTEMPTS passes: pod=$registeredForPodId")
  }

  internal fun lookup(podId: ObjectId, clientId: String): Registration? =
    dao.findByClientId(podId, clientId)?.toRegistration()

  /**
   * Best-effort liveness bump. Returns `true` if the DCR row was updated (dyn:-clients),
   * `false` for did:web-clients that have no DCR row.
   */
  internal fun touchLastAuthorized(podId: ObjectId, clientId: String): Boolean =
    dao.touchLastAuthorized(podId, clientId)

  private fun DynamicClientRegistrationDbo.toRegistration(
    deduplicatedFromRegisteredAt: Instant? = null,
  ) = Registration(
    clientId = clientId,
    redirectUris = redirectUris,
    clientName = clientName,
    clientUri = clientUri,
    logoUri = logoUri,
    softwareId = softwareId,
    softwareVersion = softwareVersion,
    contacts = contacts,
    tosUri = tosUri,
    policyUri = policyUri,
    registeredAt = registeredAt,
    rawRequest = rawRequest,
    deduplicatedFromRegisteredAt = deduplicatedFromRegisteredAt,
  )

  private fun newOpaqueId(): String {
    val bytes = ByteArray(18)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private companion object {
    /** Lookup-then-insert passes before a client racing itself is somebody's problem. */
    const val ATTEMPTS = 3
  }
}
