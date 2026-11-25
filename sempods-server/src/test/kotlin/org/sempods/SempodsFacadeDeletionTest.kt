package org.sempods

import com.google.inject.Provider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.sempods.pods.InMemoryPodRepository
import org.sempods.pods.PodRepository
import org.sempods.pods.PodRepositoryCache
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.toPodId
import org.sempods.pods.oauth.PodRefreshTokenStore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * [SempodsFacade.deletePod] against reads that run while it deletes — the interleavings an
 * integration test cannot place. The facade and the repository cache are the real ones; the stores
 * behind them are mocks holding one pod row.
 */
class SempodsFacadeDeletionTest {

  private val owner = "https://id.example/e/" + "0".repeat(64)
  private val checked = PodDbo(id = ObjectId(), name = "alice", owner = owner)
  private val checkedId = checkNotNull(checked.id)

  /** The row the name resolves to now. */
  private var row: PodDbo? = checked

  private val podDao = mockk<PodDao>(relaxed = true) {
    every { fetchByName("alice") } answers { row }
    every { fetchById(any()) } answers { row?.takeIf { it.id == firstArg() } }
    every { delete(any()) } answers { (row?.id == firstArg<ObjectId>()).also { if (it) row = null } }
  }
  private val refreshTokenStore = mockk<PodRefreshTokenStore>(relaxed = true)

  private lateinit var cache: PodRepositoryCache

  private val facade = SempodsFacade(
    podDao = podDao,
    backupDao = mockk(relaxed = true),
    podMediaDao = mockk(relaxed = true),
    podContextsDao = mockk(relaxed = true),
    podGrantsDao = mockk(relaxed = true),
    podWebIdGrantsDao = mockk(relaxed = true),
    refreshTokenStore = refreshTokenStore,
    consentDecisionStore = mockk(relaxed = true),
    signOutStore = mockk(relaxed = true),
    dynamicClientRegistrationDao = mockk(relaxed = true),
    podServiceClientStore = mockk(relaxed = true),
    podServiceAuditLogDao = mockk(relaxed = true),
    podRepositoryCacheProvider = Provider { cache },
  ).also {
    cache = PodRepositoryCache(
      sempodsFacade = it,
      rdfResourceBackupDao = mockk(relaxed = true),
      podDao = podDao,
      podChangeDispatcher = mockk(relaxed = true),
    )
  }

  private fun PodRepository?.id() = (assertNotNull(this) as InMemoryPodRepository).podId

  @Test
  fun `a repository loaded while the pod is deleted does not outlive the delete`() {
    // A read arriving after the first eviction, while the row still resolves.
    var loadedDuring: PodRepository? = null
    every { refreshTokenStore.deleteByPod(any()) } answers { loadedDuring = cache.get("alice"); 0L }

    facade.deletePod(checkedId)
    assertEquals(checkedId.toPodId(), loadedDuring.id(), "the read loaded the pod being deleted")

    val recreated = PodDbo(id = ObjectId(), name = "alice", owner = owner)
    row = recreated
    assertEquals(checkNotNull(recreated.id).toPodId(), cache.get("alice").id(), "the pod created later gets its own repository")
  }

  @Test
  fun `a lookup that read the row before the delete does not cache its id after it`() {
    // The lookup reads the row, and the whole delete runs before its result is cached.
    every { podDao.fetchByName("alice") } answers {
      val read = row
      every { podDao.fetchByName("alice") } answers { row }
      facade.deletePod(checkedId)
      read
    }

    assertEquals(checkedId, facade.getPodId("alice"), "the lookup answers what it read")
    assertEquals(null, facade.getPodId("alice"), "the next one reads again and finds no pod")
    verify(exactly = 2) { podDao.fetchByName("alice") }
  }
}
