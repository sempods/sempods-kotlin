package org.sempods.pods.oauth.serviceclients

import com.google.inject.Inject
import com.mongodb.MongoWriteException
import org.sempods.auth.core.DidWeb
import org.sempods.auth.core.Secrets
import org.sempods.commons.mongo.isDuplicateKey
import org.sempods.commons.utils.HashUtil.sha256Hex
import org.sempods.pods.HostedPod
import org.sempods.pods.PodId
import org.sempods.pods.mongo.persist.objectId
import org.sempods.pods.grants.PodScopeValidator
import org.sempods.pods.grants.ScopeValidationResult
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDbo
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import org.bson.types.ObjectId
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * Issues and verifies secrets for pod service clients: service access, a client acting as itself
 * through `client_credentials`.
 *
 * Bcrypt (OpenBSD variant via BouncyCastle) is used for the secret-at-rest
 * hash: deliberately slow, salt embedded in the encoded form, constant-time
 * comparison from the library. The plaintext is generated once at
 * registration and returned to the caller — never persisted. A `did:web` client ([registerDidWeb])
 * holds no secret: it proves itself with its DID document's keys, which
 * [org.sempods.pods.oauth.didweb.DidWebClientAuthentication] checks.
 */
class PodServiceClientStore @Inject constructor(
  private val dao: PodServiceClientDao,
  private val podScopeValidator: PodScopeValidator,
) {

  internal data class Registered(
    val registration: ServiceClientRegistration,
    val secret: String,
  )

  private val random = SecureRandom()

  /**
   * Registers a new service client on [pod], and answers with it plus the freshly minted
   * plaintext secret — the only moment the secret is available outside the registering caller.
   *
   * Each scope is validated against [podScopeValidator]: only well-formed
   * `<context-iri>#read|write|manage` strings inside the pod's own namespace are
   * accepted. OIDC scopes (`openid`, `offline_access`) and the `public-read`
   * pseudo-scope are not applicable to service clients (no person behind
   * the token, no public-anonymous use case) and are rejected. Any other malformed
   * input — most importantly `<pod-base>#manage` (pod root, not a context),
   * which a downstream string-matching authorizer could mistake for a
   * pod-wide wildcard — throws [IllegalArgumentException] before the row is
   * persisted, so the bad scope never reaches a JWT or the resource layer.
   *
   * [scopes] may be empty. Such a registration holds a credential and no authority: the token
   * endpoint refuses it `invalid_scope`, and [revokeByContextScope] leaves it where it is. A
   * self-registered service waits in that state until the owner's consent ([registerProvisional]).
   */
  internal fun register(
    pod: HostedPod,
    clientId: String,
    scopes: Set<String>,
    label: String? = null,
    redirectUris: List<String> = emptyList(),
    pendingUntil: Instant? = null,
  ): Registered {
    requireGrantable(pod, clientId, scopes)

    val secret = mintSecret()
    val dbo = PodServiceClientDbo(
      podId = pod.id.objectId(),
      clientId = clientId,
      secretHash = hashSecret(secret),
      scopes = scopes,
      label = label,
      redirectUris = redirectUris,
      pendingUntil = pendingUntil,
    )
    // The stored row rather than the one handed in: `insertOne` does not write the generated `_id`
    // back into the instance it was passed, and that id is what the admin API returns as
    // `registrationId` and what the compare-and-swap delete filters on. Discarding this return
    // value hands out a `null` one.
    val stored = try {
      dao.create(dbo)
    } catch (e: MongoWriteException) {
      // The unique index on `(podId, clientId)`. Named as this store's own so a caller can answer
      // it without naming the driver — every other write failure stays what it was.
      if (e.isDuplicateKey()) throw ServiceClientAlreadyRegistered(clientId) else throw e
    }
    return Registered(stored.toRegistration(), secret)
  }

  /**
   * Why each of [scopes] cannot be held by a service client, by scope; empty when all can. A scope is
   * held as written, so one the validator would have to trim first is refused rather than stored
   * padded.
   */
  internal fun ungrantable(pod: HostedPod, scopes: Set<String>): Map<String, String> =
    scopes.mapNotNull { scope ->
      if (scope != scope.trim()) return@mapNotNull scope to "a scope carries no surrounding whitespace"
      when (val parsed = podScopeValidator.validate(scope, pod.baseUrl)) {
        is ScopeValidationResult.Context -> null
        is ScopeValidationResult.Invalid -> scope to parsed.reason
        is ScopeValidationResult.Oidc ->
          scope to "OIDC scope '${parsed.scope}' is not applicable to service clients"
        is ScopeValidationResult.Feature ->
          scope to "feature scope '${parsed.scope}' is not applicable to service clients"
      }
    }.toMap()

  private fun requireGrantable(pod: HostedPod, clientId: String, scopes: Set<String>) {
    val invalid = ungrantable(pod, scopes)
    require(invalid.isEmpty()) {
      "rejected scopes for service client '$clientId': " +
        invalid.entries.joinToString(", ") { (s, reason) -> "'$s' ($reason)" }
    }
  }

  /** Every registration on [pod], oldest first. No secret: see [ServiceClientRegistration]. */
  internal fun list(pod: PodId): List<ServiceClientRegistration> =
    dao.findByPod(pod.objectId()).map { it.toRegistration() }

  /**
   * Drops [scopes] from the registration [expected] names, for the server's check after a grant
   * write; one re-created under the same `clientId` is left alone. `false` where it is gone.
   */
  internal fun dropScopes(pod: PodId, clientId: String, expected: ServiceClientRegistrationId, scopes: Set<String>): Boolean =
    dao.dropScopes(pod.objectId(), clientId, expected.objectId(), scopes)

  /**
   * Makes [scopes] the grants of the registration [expected] names, if they are still at
   * [expectedVersion]. Throws, like [register], for a scope a service client cannot hold.
   *
   * One conditional write: [ScopeReplacement.Conflict] and [ScopeReplacement.NotFound] wrote
   * nothing. The two are told apart by reading afterwards, so a registration removed in that gap
   * reads as not found, which it is by then.
   */
  internal fun replaceScopes(
    pod: HostedPod,
    clientId: String,
    expected: ServiceClientRegistrationId,
    expectedVersion: Long,
    scopes: Set<String>,
    changedBy: String,
  ): ScopeReplacement {
    requireGrantable(pod, clientId, scopes)
    val podId = pod.id.objectId()
    val id = expected.objectId()
    return when {
      dao.replaceScopes(podId, clientId, id, expectedVersion, scopes, changedBy) -> ScopeReplacement.Replaced
      dao.exists(podId, clientId, id) -> ScopeReplacement.Conflict
      else -> ScopeReplacement.NotFound
    }
  }

  /** What [replaceScopes] did; the facade answers it as `GrantReplacement`. */
  internal enum class ScopeReplacement { Replaced, Conflict, NotFound }

  /**
   * Replaces the secret and answers the new one, the only time it is readable. The registration and
   * its grants stay. [ServiceClientSecretRotation.Conflict] where another rotation landed in between: answering a
   * second secret would hand out one that does not work. [expectedSecretId], where given, is the
   * secret the caller decided on; another one standing now is such a rotation.
   */
  internal fun rotateSecret(pod: PodId, clientId: String, expectedSecretId: String? = null): ServiceClientSecretRotation {
    val row = dao.findByClientId(pod.objectId(), clientId) ?: return ServiceClientSecretRotation.NotFound
    val current = row.secretHash ?: return ServiceClientSecretRotation.HoldsNoSecret
    if (expectedSecretId != null && row.toRegistration().secretId != expectedSecretId) return ServiceClientSecretRotation.Conflict
    val secret = mintSecret()
    val hash = hashSecret(secret)
    val replaced = dao.replaceSecretHash(pod.objectId(), clientId, current, hash)
    return if (replaced) ServiceClientSecretRotation.Rotated(row.copy(secretHash = hash).toRegistration(), secret) else ServiceClientSecretRotation.Conflict
  }

  /** The registration for `(pod, clientId)`, or `null`. */
  internal fun find(pod: PodId, clientId: String): ServiceClientRegistration? =
    dao.findByClientId(pod.objectId(), clientId)?.toRegistration()

  /**
   * Removes the one registration [expected] names — see [PodServiceClientDao.delete] for why the
   * condition is there. `false` means somebody else replaced it in between.
   */
  internal fun remove(pod: PodId, clientId: String, expected: ServiceClientRegistrationId): Boolean =
    dao.delete(pod.objectId(), clientId, expectedId = expected.objectId())

  /**
   * Registers a service that asked for itself: an identifier this server assigns, no grants, and
   * [ACTIVATION_WINDOW] for the owner to activate it before it is removed.
   *
   * The identifier is the pod's to give. A service that could name itself could take the name of
   * one the owner already trusts. Its class is [SERVICE_CLIENT_PREFIX], which
   * [PodClientDirectory][org.sempods.pods.oauth.flows.PodClientDirectory] places nowhere.
   */
  internal fun registerProvisional(pod: HostedPod, label: String, redirectUris: List<String>): Registered =
    registerService(pod, label, redirectUris, provisional = true)

  /**
   * Registers a service as [registerProvisional] does, or active from the start where it is not
   * [provisional]: what the owner's own authority registers.
   */
  internal fun registerService(pod: HostedPod, label: String, redirectUris: List<String>, provisional: Boolean): Registered =
    register(
      pod,
      SERVICE_CLIENT_PREFIX + Secrets.newOpaqueId(),
      scopes = emptySet(),
      label = label,
      redirectUris = redirectUris,
      pendingUntil = if (provisional) Instant.now().plus(ACTIVATION_WINDOW) else null,
    )

  /**
   * Registers the `did:web` service [clientId] with no secret: it authenticates with the keys of its
   * DID document (`private_key_jwt`). Provisional, with no grants and [ACTIVATION_WINDOW] for the
   * owner, unless [provisional] is `false` — what the owner's own authority registers.
   *
   * The caller has proved that [clientId] signed the registration, which is what makes it safe to
   * let a client name itself here when a `svc:` one may not. A provisional registration of
   * [clientId] whose window has passed gives way: it is gone to every read already.
   *
   * @param management the authority to manage the pod, where the operator provisions the client
   *   for the owner. Such a registration is active.
   * @throws ServiceClientAlreadyRegistered where [clientId] is registered on [pod] already.
   */
  internal fun registerDidWeb(
    pod: HostedPod,
    clientId: String,
    label: String,
    redirectUris: List<String>,
    provisional: Boolean,
    management: ServiceManagementAuthority? = null,
  ): ServiceClientRegistration {
    require(clientId.startsWith(DidWeb.PREFIX)) { "not a did:web identifier: '$clientId'" }
    require(management == null || !provisional) { "a registration holding the management authority is active" }
    val createdAt = Instant.now()
    val dbo = PodServiceClientDbo(
      podId = pod.id.objectId(),
      clientId = clientId,
      secretHash = null,
      scopes = emptySet(),
      label = label,
      createdAt = createdAt,
      // See `PodServiceClientDbo.grantsVersion`: the service can register this identifier again.
      grantsVersion = createdAt.toEpochMilli(),
      redirectUris = redirectUris,
      pendingUntil = if (provisional) createdAt.plus(ACTIVATION_WINDOW) else null,
      managementGrantedBy = management?.grantedBy,
      managementSubjectUris = management?.subjectUris.orEmpty(),
      managementGrantedAt = management?.grantedAt,
    )
    dao.deleteExpired(dbo.podId, clientId)
    val stored = try {
      dao.create(dbo)
    } catch (e: MongoWriteException) {
      if (e.isDuplicateKey()) throw ServiceClientAlreadyRegistered(clientId) else throw e
    }
    return stored.toRegistration()
  }

  /**
   * Grants the registration [expected] names the lasting authority to manage [pod] on behalf of
   * [grantedBy], recognised by [subjectUris], and activates it. `false` where it is gone.
   */
  internal fun grantManagement(
    pod: PodId,
    clientId: String,
    expected: ServiceClientRegistrationId,
    grantedBy: String,
    subjectUris: Set<String>,
  ): Boolean {
    require(grantedBy in subjectUris) { "the URIs a person was recognised by include the one they are" }
    return dao.grantManagement(pod.objectId(), clientId, expected.objectId(), grantedBy, subjectUris)
  }

  private fun PodServiceClientDbo.toRegistration() = ServiceClientRegistration(
    // A row read back always carries its `_id`; the type is nullable only because the DBO doubles
    // as the pre-insert shape. Asserting it keeps a `null` from reaching [remove] as
    // "delete unconditionally".
    id = ServiceClientRegistrationId(checkNotNull(id) { "registration without id: '$clientId'" }.toHexString()),
    clientId = clientId,
    scopes = scopes,
    label = label,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
    grantsVersion = grantsVersion,
    redirectUris = redirectUris,
    pendingUntil = pendingUntil,
    // Derived from the hash, whose salt is new with every secret, so no row needs a field for it.
    secretId = secretHash?.let { sha256Hex(it).take(SECRET_ID_LENGTH) },
    management = managementGrantedBy?.let { webId ->
      ServiceManagementAuthority(
        grantedBy = webId,
        subjectUris = managementSubjectUris.ifEmpty { setOf(webId) },
        grantedAt = managementGrantedAt ?: createdAt,
      )
    },
  )

  /**
   * Validates `(clientId, secret)` against the persisted hash. Returns the
   * registration when the secret verifies, `null` otherwise.
   *
   * Constant-ish-time: we always run one bcrypt verification, even when no
   * row matches [clientId]. Without that an unknown clientId returns in
   * microseconds while a wrong-secret-on-known-clientId burns ~250 ms of
   * bcrypt work, letting an attacker enumerate valid clientIds at the
   * `/token` endpoint by timing the response. [dummyHash] is that target.
   */
  // TODO: replace bcrypt with HMAC-SHA256 over a pod-local verifier key. Each
  //   call here burns ~250 ms of CPU (cost-12 bcrypt) regardless of
  //   correctness — a steady ~800 RPS flood at `/token` saturates the Jetty
  //   pool. Service-client secrets are 32 random bytes (high-entropy, not
  //   password-derived), so a constant-time HMAC compare is the right
  //   primitive; bcrypt's slowness buys nothing here. Migration is free only
  //   while no production service clients exist; after that it becomes a
  //   forced secret rotation (re-running the provisioning with a stale
  //   `expectedRegistrationId` rotates an operator's client, an owner rotates
  //   a `svc:` one).
  //   Touch points: [mintSecret], [hashSecret],
  //   [verifySecret], plus a new pod-scoped verifier key alongside the RSA
  //   signing key.
  internal fun authenticate(pod: PodId, clientId: String, secret: String): ServiceClientRegistration? {
    // A `did:web` client has no hash and is verified against [dummyHash] like an unknown one: it
    // authenticates with `private_key_jwt`, never with a secret.
    val dbo = dao.findByClientId(pod.objectId(), clientId)?.takeIf { it.secretHash != null }
    val hash = dbo?.secretHash ?: dummyHash
    val matches = verifySecret(secret, hash)
    return if (dbo != null && matches) dbo.toRegistration() else null
  }

  /** Records that [clientId] just minted a token — what an owner-facing list shows as `lastUsedAt`. */
  internal fun touchLastUsed(pod: PodId, clientId: String): Boolean = dao.touchLastUsed(pod.objectId(), clientId)

  /**
   * Strips the scopes anchored at [contextUri] from this pod's registrations, and answers how many
   * lost one.
   *
   * A registration's context scopes *are* the authority the resolver reads, so a deleted context
   * has to reach them the way it reaches a grant — otherwise the secret keeps minting tokens for a
   * root the owner removed. A registration left holding nothing stays: see [register] on what an
   * empty scope set is worth.
   */
  internal fun revokeByContextScope(pod: PodId, contextUri: String): Long =
    dao.revokeByContextScope(pod.objectId(), contextUri)

  /** Everything this pod registered, for the pod's own deletion. */
  internal fun deleteByPod(pod: PodId): Long = dao.deleteByPod(pod.objectId())

  private fun mintSecret(): String = SECRET_PREFIX + Secrets.newSecret()

  private fun hashSecret(secret: String): String {
    val salt = ByteArray(16).also(random::nextBytes)
    return OpenBSDBCrypt.generate(secret.toCharArray(), salt, BCRYPT_COST)
  }

  private fun verifySecret(plaintext: String, hash: String): Boolean {
    return try {
      OpenBSDBCrypt.checkPassword(hash, plaintext.toCharArray())
    } catch (_: Exception) {
      false
    }
  }

  /**
   * A bcrypt hash of a random plaintext nobody knows, computed once on first
   * use. Verifying against it takes what verifying a real hash takes, which
   * is what makes an unknown clientId and a wrong secret cost the same.
   */
  private val dummyHash: String by lazy { hashSecret(mintSecret()) }

  companion object {

    /**
     * The identifier class of a client this server named, held apart from the `dyn:` an
     * unauthenticated registration earns.
     *
     * The `:` is what makes the two namespaces disjoint: `AdminPodsEndpoint` accepts
     * `[A-Za-z0-9._-]+` for an operator-chosen `clientId`, because that identifier becomes a
     * context path segment. It survives HTTP Basic because a client encodes its credentials the
     * way [org.sempods.client.SempodsRequestAuth] does — `sempods-server/docs/auth/service-clients.md`
     * §"Token exchange".
     */
    internal const val SERVICE_CLIENT_PREFIX = "svc:"

    /** How long a self-registered service waits for the owner's consent before it is removed. */
    internal val ACTIVATION_WINDOW: Duration = Duration.ofHours(24)

    /** Hex characters of [ServiceClientRegistration.secretId]: 128 bits. */
    private const val SECRET_ID_LENGTH = 32

    /** Lets operators recognise pod service-client secrets at a glance. */
    private const val SECRET_PREFIX = "sc_"

    /**
     * bcrypt cost factor. 12 is a balanced default for an interactive token
     * exchange — verification is in the ~250 ms range on commodity hardware,
     * comfortably faster than a request budget and slow enough to push
     * brute force out of reach.
     */
    private const val BCRYPT_COST = 12
  }
}

/**
 * One registration, without the row it is stored in.
 *
 * The secret is not here: it exists for one return value at registration and is stored only as a
 * hash, so a type that could carry it later would be promising something the store cannot keep.
 */
internal data class ServiceClientRegistration(
  val id: ServiceClientRegistrationId,
  val clientId: String,
  val scopes: Set<String>,
  /** What an operator called it — a server-assigned `clientId` alone gives them nothing to recognise. */
  val label: String?,
  /** When this registration was made. RFC 7591 §3.2.1's `client_id_issued_at`. */
  val createdAt: Instant,
  /** When it last minted a token, or `null` if it never has. */
  val lastUsedAt: Instant? = null,
  /** See `PodServiceClientDbo.grantsVersion`. Only meaningful together with [id]. */
  val grantsVersion: Long = 0,
  /** See `PodServiceClientDbo.redirectUris`. */
  val redirectUris: List<String> = emptyList(),
  /** See `PodServiceClientDbo.pendingUntil`: set until the owner activates it, `null` after. */
  val pendingUntil: Instant? = null,
  /**
   * Names the secret that authenticates now, and changes with every secret issued; it tells nothing
   * about the secret itself. The admin API hands it out as `secretId`, so a caller can tell whether
   * the secret it holds is still the current one while [id] stays the same. `null` for a `did:web`
   * client, which holds none.
   */
  val secretId: String?,
  /** The lasting authority to manage the pod this client holds, or `null`. */
  val management: ServiceManagementAuthority? = null,
) {

  /**
   * Whether it registered at the pod (`svc:`, or a `did:web` that proved its identifier) rather than
   * being provisioned by the operator, whose identifiers carry no `:`.
   */
  val registered: Boolean
    get() = clientId.startsWith(PodServiceClientStore.SERVICE_CLIENT_PREFIX) || clientId.startsWith(DidWeb.PREFIX)

  /** Whether it authenticates with its DID document's keys (`private_key_jwt`) rather than a secret. */
  val authenticatesWithKeys: Boolean get() = clientId.startsWith(DidWeb.PREFIX)
}

/**
 * A service client's lasting authority to manage the pod for its owner: the scopes
 * [org.sempods.pods.grants.PodScopeValidator.serviceManagementScopes] in its tokens, and no data. It
 * holds while its owner does: every call compares the pod's current owner with [subjectUris]
 * (`PodOwnerAuthority`). It ends with the registration.
 *
 * @param grantedBy the owner who granted it, in the consent or through the operator provisioning
 *   the pod for them.
 * @param subjectUris every URI [grantedBy] was recognised by when granting, [grantedBy] among them.
 */
internal data class ServiceManagementAuthority(
  val grantedBy: String,
  val subjectUris: Set<String>,
  val grantedAt: Instant,
)

/**
 * A handle to one registration, opaque to everyone holding it.
 *
 * The admin API hands it out as `registrationId` and takes it back as `expectedRegistrationId`,
 * to decide whether a stored credential still pairs with the registration behind it. What the
 * store keys on underneath stays the store's business.
 */
internal data class ServiceClientRegistrationId(val value: String) {

  init {
    require(value.isNotEmpty()) { "a registration id is not empty" }
  }

  override fun toString(): String = value
}

/**
 * This implementation's key for a registration — the same split [org.sempods.pods.PodId] keeps,
 * and the same reason: a DAO speaks its driver's type and everything above speaks the handle.
 */
internal fun ServiceClientRegistrationId.objectId(): ObjectId =
  checkNotNull(if (ObjectId.isValid(value)) ObjectId(value) else null) {
    "not a registration id this server minted: $this"
  }

/** `(pod, clientId)` was taken between reading it and inserting — the unique index said so. */
internal class ServiceClientAlreadyRegistered(clientId: String) :
  RuntimeException("service client '$clientId' was registered concurrently")
