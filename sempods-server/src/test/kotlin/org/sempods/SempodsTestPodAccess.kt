package org.sempods

import com.google.inject.Inject
import org.sempods.api.pod.system.auth.PodTokenIssuer
import okhttp3.OkHttpClient
import org.eclipse.rdf4j.model.Model
import org.sempods.client.core.SempodsAuthAttempt
import org.sempods.client.core.SempodsOkHttp
import org.sempods.client.core.SempodsPod
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsRequestAuth
import org.sempods.client.core.SempodsSession
import org.sempods.client.core.SempodsWriteOptions
import org.sempods.client.rdf4j.SempodsRdf4jPod
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.mongo.persist.PodDao
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Pod coordinates for the suite's own seeding, so pod seeding in a test goes over HTTP against the
 * in-JVM server rather than through an in-process shortcut.
 *
 * **The name resolution is the suite's own, and that is the point.** A session is bound to one pod
 * base URL and one credential; a suite that creates a pod per test holds names and resolves them
 * itself, exactly as any multi-pod consumer does — see `docs/pod-client.md`.
 *
 * **So are the conveniences below.** The client answers the catalogue as a graph and a write as a
 * status; what a test wants is a set of context IRIs and a seeded resource. Those few lines are what
 * a consumer of the published client writes for itself, and writing them here is the first check
 * that they are few.
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
   * [pod] under this suite's credential, built fresh per call rather than cached: it is a handful of
   * fields against an HTTP round trip, and the token behind it caches itself. [tokenFor] is not
   * consulted until a call is made, so building one costs no mint.
   */
  fun podFor(pod: String): SempodsPod = SempodsPod(SempodsSession(baseOf(pod), authFor(pod)), http)

  /** [podFor] reading and writing RDF4J values. */
  fun rdfFor(pod: String): SempodsRdf4jPod = SempodsRdf4jPod(podFor(pod))

  /**
   * [pod] with no credential at all, which is what lets the reads below work for a pod this suite
   * holds none for — and what makes [publicContextsOf] answer the public contexts rather than this
   * seeder's own.
   */
  fun anonymousPodFor(pod: String): SempodsPod = SempodsPod(SempodsSession(baseOf(pod)), http)

  /**
   * The bearer for [pod], asked per attempt because [tokenFor] re-derives it: a token acquired
   * before a test registered a context carries no scope for it, and the pod answers that with a 403
   * no recovery retries. A refused one is dropped and one further attempt made, which is what a
   * rotated credential needs.
   */
  fun authFor(pod: String): SempodsRequestAuth = object : SempodsRequestAuth {

    override fun apply(request: okhttp3.Request.Builder, attempt: SempodsAuthAttempt) {
      tokenFor(pod)?.let { request.header("Authorization", "Bearer $it") }
    }

    override fun recover(response: okhttp3.Response, attempt: SempodsAuthAttempt): Boolean {
      if (response.code != 401 || response.request.header("Authorization") == null) return false
      invalidate(pod)
      return true
    }
  }

  /** Seeds [model] as the whole of [resourceUri] in [contextUri], the way any client writes it. */
  fun seed(pod: String, resourceUri: URI, contextUri: URI, model: Model) {
    rdfFor(pod).resources().put(
      resourceUri.toString(),
      model,
      SempodsWriteOptions.inContext(contextUri.toString()),
    )
  }

  /** The contexts of [pod] this suite's credential can see. Unknown pod → empty. */
  fun contextsOf(pod: String): Set<URI> = catalogueOf(rdfFor(pod))

  /**
   * The public contexts of [pod], which is what the catalogue answers a caller with no bearer: a
   * credential would narrow it to that client's own grants rather than widen it.
   */
  fun publicContextsOf(pod: String): Set<URI> = catalogueOf(SempodsRdf4jPod(anonymousPodFor(pod)))

  /** Whether [pod] exists, asked anonymously — the route serves it without a bearer. */
  fun exists(pod: String): Boolean = anonymousPodFor(pod).metadata().exists()

  private fun catalogueOf(rdf: SempodsRdf4jPod): Set<URI> =
    rdf.contexts().listIris().body.orEmpty().mapTo(LinkedHashSet()) { URI(it.stringValue()) }

  private fun baseOf(pod: String) = SempodsPodBase.of(baseUrlFor(pod).toString())

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
   * supported mode, and the routes answer 401/404 on their own — a test seeding
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
     * One client for the whole suite, so seeding does not mint a connection pool per pod. It carries
     * the session policy, without which a session's request does not resolve at all.
     */
    private val http: OkHttpClient = SempodsOkHttp.install(OkHttpClient.Builder()).build()

    /**
     * Deliberately distinct from `mintScopedToken`'s `did:web:test.example` — see the class KDoc:
     * a shared identity would make seeding and a test's own token overwrite each other's grants.
     */
    private const val CLIENT_ID = "did:web:seeder.test.example"
    private const val WEB_ID = "https://id.test/seeder"
  }
}
