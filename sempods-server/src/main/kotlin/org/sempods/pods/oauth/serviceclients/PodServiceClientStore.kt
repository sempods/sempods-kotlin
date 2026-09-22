package org.sempods.pods.oauth.serviceclients

import com.google.inject.Inject
import com.mongodb.MongoWriteException
import org.sempods.commons.mongo.isDuplicateKey
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
import java.util.Base64

/**
 * Issues and verifies secrets for statically-registered pod service clients
 * (OAuth 2-leg, `client_credentials`).
 *
 * Bcrypt (OpenBSD variant via BouncyCastle) is used for the secret-at-rest
 * hash: memory-hard, salt embedded in the encoded form, constant-time
 * comparison from the library. The plaintext is generated once at
 * registration and returned to the caller — never persisted.
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
   * pseudo-scope are not applicable to 2-leg service clients (no end-user,
   * no public-anonymous use case) and are rejected. Any other malformed
   * input — most importantly `<pod-base>#manage` (pod root, not a context),
   * which a downstream string-matching authorizer could mistake for a
   * pod-wide wildcard — throws [IllegalArgumentException] before the row is
   * persisted, so the bad scope never reaches a JWT or the resource layer.
   */
  internal fun register(
    pod: HostedPod,
    clientId: String,
    scopes: Set<String>,
    label: String? = null,
  ): Registered {
    require(scopes.isNotEmpty()) { "service client must be registered with at least one scope" }
    val namespace = pod.baseUrl
    val invalid = scopes.mapNotNull { scope ->
      when (val parsed = podScopeValidator.validate(scope, namespace)) {
        is ScopeValidationResult.Context -> null
        is ScopeValidationResult.Invalid -> scope to parsed.reason
        is ScopeValidationResult.Oidc ->
          scope to "OIDC scope '${parsed.scope}' is not applicable to service clients"
        is ScopeValidationResult.Feature ->
          scope to "feature scope '${parsed.scope}' is not applicable to service clients"
      }
    }
    require(invalid.isEmpty()) {
      "rejected scopes for service client '$clientId': " +
        invalid.joinToString(", ") { (s, reason) -> "'$s' ($reason)" }
    }

    val secret = mintSecret()
    val dbo = PodServiceClientDbo(
      podId = pod.id.objectId(),
      clientId = clientId,
      secretHash = hashSecret(secret),
      scopes = scopes,
      label = label,
    )
    // The stored row rather than the one handed in. `datastore.save()` used to write the generated
    // `_id` back into the instance it was passed, so reading it off `dbo` worked; `insertOne` does
    // not, and the id is what the admin API returns as `registrationId` and what the
    // compare-and-swap delete filters on. Discarding this return value hands out a `null` one.
    val stored = try {
      dao.create(dbo)
    } catch (e: MongoWriteException) {
      // The unique index on `(podId, clientId)`. Named as this store's own so a caller can answer
      // it without naming the driver — every other write failure stays what it was.
      if (e.isDuplicateKey()) throw ServiceClientAlreadyRegistered(clientId) else throw e
    }
    return Registered(stored.toRegistration(), secret)
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

  private fun PodServiceClientDbo.toRegistration() = ServiceClientRegistration(
    // A row read back always carries its `_id`; the type is nullable only because the DBO doubles
    // as the pre-insert shape. Asserting it keeps a `null` from reaching [remove] as
    // "delete unconditionally".
    id = ServiceClientRegistrationId(checkNotNull(id) { "registration without id: '$clientId'" }.toHexString()),
    clientId = clientId,
    scopes = scopes,
    label = label,
  )

  /**
   * Validates `(clientId, secret)` against the persisted hash. Returns the
   * registration when the secret verifies, `null` otherwise.
   *
   * Constant-ish-time: we always run one bcrypt verification, even when no
   * row matches [clientId]. Without that an unknown clientId returns in
   * microseconds while a wrong-secret-on-known-clientId burns ~250 ms of
   * bcrypt work, letting an attacker enumerate valid clientIds at the
   * `/token` endpoint by timing the response. The dummy hash lives in
   * memory and is computed once at class load.
   */
  // TODO: replace bcrypt with HMAC-SHA256 over a pod-local verifier key. Each
  //   call here burns ~250 ms of CPU (cost-12 bcrypt) regardless of
  //   correctness — a steady ~800 RPS flood at `/token` saturates the Jetty
  //   pool. Service-client secrets are 32 random bytes (high-entropy, not
  //   password-derived), so a constant-time HMAC compare is the right
  //   primitive; bcrypt's slowness buys nothing here. Migration is free only
  //   while no production service clients exist — i.e. before the
  //   live service-client bootstrap registered a client on every
  //   pod; after that it becomes a forced secret rotation (unregister every
  //   affected client, then re-run the idempotent bootstrap to re-mint).
  //   Touch points: [mintSecret], [hashSecret],
  //   [verifySecret], plus a new pod-scoped verifier key alongside the RSA
  //   signing key.
  internal fun authenticate(pod: PodId, clientId: String, secret: String): ServiceClientRegistration? {
    val dbo = dao.findByClientId(pod.objectId(), clientId)
    val hash = dbo?.secretHash ?: dummyHash
    val matches = verifySecret(secret, hash)
    return if (dbo != null && matches) dbo.toRegistration() else null
  }

  /** Records that [clientId] just minted a token — what an owner-facing list shows as `lastUsedAt`. */
  internal fun touchLastUsed(pod: PodId, clientId: String): Boolean = dao.touchLastUsed(pod.objectId(), clientId)

  /**
   * Strips the scopes anchored at [contextUri] and removes the registrations left holding none.
   *
   * A registration's context scopes *are* the authority the resolver reads, so a deleted context
   * has to reach them the way it reaches a grant — otherwise the secret keeps minting tokens for a
   * root the owner removed. Answers how many registrations went.
   */
  internal fun revokeByContextScope(pod: PodId, contextUri: String): Long =
    dao.revokeByContextScope(pod.objectId(), contextUri)

  /** Everything this pod registered, for the pod's own deletion. */
  internal fun deleteByPod(pod: PodId): Long = dao.deleteByPod(pod.objectId())

  private fun mintSecret(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

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
   * Pre-computed bcrypt hash of a random plaintext nobody knows. Used as the
   * comparison target when no row matches the supplied clientId so the
   * timing of an unknown-client failure matches the timing of a
   * wrong-secret-on-known-client failure. Computed lazily once.
   */
  private val dummyHash: String by lazy {
    val dummySecret = ByteArray(32).also(random::nextBytes)
    val salt = ByteArray(16).also(random::nextBytes)
    OpenBSDBCrypt.generate(
      Base64.getUrlEncoder().withoutPadding().encodeToString(dummySecret).toCharArray(),
      salt,
      BCRYPT_COST,
    )
  }

  companion object {
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
