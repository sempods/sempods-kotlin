package org.sempods

import com.google.inject.Inject
import org.sempods.api.pod.system.auth.PodTokenIssuer
import org.sempods.client.SempodsAuth
import org.sempods.client.SempodsClient
import org.sempods.client.SempodsPodClient
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.mongo.persist.PodDao
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Pod coordinates for the suite's own seeding, so pod seeding in a test goes through
 * [SempodsPodClient] against the in-JVM server rather than through an in-process shortcut.
 *
 * **The name resolution is the suite's own, and that is the point.** A pod client is bound to one
 * pod base URL and one credential; a suite that creates a pod per test holds names and resolves
 * them itself, exactly as any multi-pod consumer does. That resolution used to be a published
 * library tier and is a dozen lines here — see
 * `docs/pod-client.md` §"The tiers".
 *
 * The seeding credential is an ordinary OAuth caller, because nothing else would work: the
 * write path
 * ([org.sempods.api.pod.resources.PodContextWriteAuthorizer.authorizeWriteOrThrow]) matches
 * `<context>#write` or a covering `<root>#manage` and has no pod-owner branch, and a bare
 * `<pod-base>#manage` is refused by the scope validator by design. So there is no "seed as the
 * owner" shortcut to take, and the seeder gets exactly the permissions a client would have to
 * hold.
 *
 * **Its own `clientId`/`webId`.** Grants are stored per `(pod, clientId, webId)` and
 * [PodGrantsDao.replaceGrants] is authoritative for that triple, so sharing one with
 * [SempodsIntegrationTest.mintScopedToken] would let seeding silently revoke the scopes a test
 * just minted for itself.
 */
class SempodsTestPodAccess @Inject constructor(
  private val podDao: PodDao,
  private val podContextsDao: PodContextsDao,
  private val podGrantsDao: PodGrantsDao,
  private val podTokenIssuer: PodTokenIssuer,
) {

  private val tokens = ConcurrentHashMap<String, CachedToken>()

  /**
   * The client for [pod], built fresh per call rather than cached: it is four fields against an
   * HTTP round trip, and the token behind it caches itself. [tokenFor] is not consulted until the
   * client asks, so binding one costs no mint — which is what lets the anonymous reads
   * ([SempodsPodClient.exists], [SempodsPodClient.lastModifiedAt],
   * [SempodsPodClient.publicContexts]) work for a pod this suite holds no credential for.
   */
  fun clientFor(pod: String): SempodsPodClient = SempodsPodClient(
    podBaseUrl = baseUrlFor(pod),
    auth = authFor(pod),
    client = client,
  )

  fun authFor(pod: String): SempodsAuth = object : SempodsAuth {
    override fun token(): String? = tokenFor(pod)
    override fun invalidate() = this@SempodsTestPodAccess.invalidate(pod)
  }

  fun baseUrlFor(pod: String): URI =
    URI("${SempodsModule.config.apiBaseUrl}$pod/")

  /**
   * A token covering every context registered for [pod] right now.
   *
   * Re-derived per call rather than minted once: a test registers contexts as it goes, and a token
   * minted before the context existed would carry no scope for it — surfacing as a 403 halfway
   * through a fixture instead of at its start. The cache is keyed by the scope set, so an
   * unchanged pod costs one Mongo read and repeated seeding does not re-sign a JWT per call.
   *
   * `null` for an unknown pod. That is not a failure to report here: an anonymous request is a
   * supported mode of [SempodsAuth], and the routes answer 401/404 on their own — a test seeding
   * into a pod it never created should read that answer, not an exception from the credential
   * side.
   */
  fun tokenFor(pod: String): String? {
    val podId = podDao.fetchByName(pod)?.id ?: return null
    val scopes = podContextsDao.fetchByPod(podId)
      .flatMap { listOf("${it.contextUri}#read", "${it.contextUri}#write") }
      .toSet()

    tokens[pod]?.takeIf { it.scopes == scopes }?.let { return it.token }

    // Context permissions are resolved server-side from the grant rows, so the mint has to write
    // them; the scopes on the token itself are not what authorizes the call.
    podGrantsDao.replaceGrants(
      podId = podId,
      appId = CLIENT_ID,
      webId = WEB_ID,
      grants = scopes,
      subjectUris = listOf(WEB_ID),
      grantedBy = WEB_ID,
    )
    val token = podTokenIssuer.issue(pod = pod, webId = WEB_ID, clientId = CLIENT_ID, scopes = scopes)
    tokens[pod] = CachedToken(scopes = scopes, token = token)
    return token
  }

  fun invalidate(pod: String) {
    tokens.remove(pod)
  }

  private data class CachedToken(val scopes: Set<String>, val token: String)

  companion object {

    /**
     * One transport for the whole suite, so seeding does not mint a connection pool per pod. The
     * default construction — everything configurable about it is a property of the transport.
     */
    private val client = SempodsClient()

    /**
     * Deliberately distinct from `mintScopedToken`'s `did:web:test.example` — see the class KDoc:
     * a shared identity would make seeding and a test's own token overwrite each other's grants.
     */
    private const val CLIENT_ID = "did:web:seeder.test.example"
    private const val WEB_ID = "https://id.test/seeder"
  }
}
