package org.sempods

import com.google.inject.Inject
import com.google.inject.Provider
import org.sempods.api.pod.system.auth.DynamicClientRegistrationDao
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.PodRepositoryCache
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.grants.persist.PodWebIdGrantsDao
import org.sempods.pods.media.persist.PodMediaDao
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.RdfResourceBackupDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceAuditLogDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.bson.types.ObjectId
import io.github.oshai.kotlinlogging.KotlinLogging

class SempodsFacade @Inject constructor(
  private val podDao: PodDao,
  private val backupDao: RdfResourceBackupDao,
  private val podMediaDao: PodMediaDao,
  private val podContextsDao: PodContextsDao,
  private val podGrantsDao: PodGrantsDao,
  private val podWebIdGrantsDao: PodWebIdGrantsDao,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val dynamicClientRegistrationDao: DynamicClientRegistrationDao,
  private val podServiceClientDao: PodServiceClientDao,
  private val podServiceAuditLogDao: PodServiceAuditLogDao,
  // Provider breaks the SempodsFacade ↔ PodRepositoryCache injection cycle (the cache depends on
  // this facade).
  private val podRepositoryCacheProvider: Provider<PodRepositoryCache>,
) {

  private val podIdCache = ConcurrentHashMap<String, ObjectId>()

  internal fun getPodId(pod: String): ObjectId? {

    // TODO: this cache will not be invalidate, if a pod gets deleted on another vm. Since S1 the
    //  authorization path reads through here too (`PodFacade.getPodId`, called per authenticated
    //  request now that credentials carry a `PodRef` rather than the row), so a stale entry is no
    //  longer only a lookup that misses — it is a request resolving grants against a pod id that
    //  another replica has deleted. Fails closed today: the grant rows go with the pod, so the
    //  resolved context set is empty.
    podIdCache[pod]?.let { return it }

    val podId = podDao.fetchByName(name = pod)?.id
    if (podId != null) {
      podIdCache[pod] = podId
    }

    return podId
  }

  internal fun existsPod(pod: String): Boolean {
    return getPodId(pod = pod) != null
  }

  internal fun deletePod(pod: String) {
    logger.info { "Delete pod $pod" }
    podIdCache.clear()
    getPodId(pod = pod)?.let { podId ->
      // Security-sensitive rows first: tokens, DCR rows and statically-registered
      // service-client credentials so a stale session or client cannot keep any
      // handle on data that's about to disappear. The in-memory RDF cache is
      // invalidated by the calling ServiceImpl (cycle avoidance with
      // PodRepositoryCache).
      refreshTokenStore.deleteByPod(podId)
      dynamicClientRegistrationDao.deleteByPod(podId)
      podServiceClientDao.deleteByPod(podId)
      podServiceAuditLogDao.deleteByPod(podId)
      backupDao.deleteByPod(podId)
      podContextsDao.deleteByPod(podId)
      // The pod's own media, on the other path: stamped unreferenced rather than deleted, so the
      // sweep's grace period cushions an accidental pod deletion the way it cushions an accidental
      // unassign. The rows outlive the pod on purpose — they still carry `podId` and `mediaId`, so
      // the row *is* the tombstone and the sweep needs nothing else to reach the bytes. That is also
      // why the sweep route is host-level: after `podDao.delete` below there is no pod left to scope
      // it to. The DAO directly, because this touches no byte and must keep working on a deployment
      // that configured no store at all (`docs/media.md` §"The seam").
      //
      // **After the contexts, not before.** An upload authorized moments ago re-checks its context's
      // registration immediately before writing its assignment; once that check fails, no new row can
      // appear. Marking first left the gap between the two statements open — a row written in it
      // would carry no stamp, name contexts that no longer exist, and be invisible to the sweep for
      // ever. The residual gap is now one statement wide, and `PodMediaFacade.sweepUnreferenced`
      // catches whatever still slips through it by marking the media of pods that no longer exist.
      val mediaMarked = podMediaDao.markPodUnreferenced(podId = podId, now = Instant.now())
      if (mediaMarked > 0) {
        logger.info { "Marked $mediaMarked media row(s) of pod $pod unreferenced — the sweep reclaims them" }
      }
      podGrantsDao.deleteByPod(podId)
      podWebIdGrantsDao.deleteByPod(podId)
    }
    podDao.delete(pod)
    podIdCache.clear()
  }

  /**
   * Strong ETag validator (content hash) for a resource, read from the store, or null if the
   * resource — or the pod — does not exist. The validator is global
   * across contexts (LOD identity is global); see [org.sempods.pods.ResourceValidator].
   */
  internal fun getResourceValidator(pod: String, resourceUri: URI): String? {
    val repo = podRepositoryCacheProvider.get().get(pod) ?: return null
    return repo.fetchResourceValidator(resourceUri)
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
