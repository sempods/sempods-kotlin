package org.sempods.pods

import io.mockk.every
import io.mockk.mockk
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.sempods.SempodsFacade
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * [PodRepositoryCache.invalidate] by id against a repository still being initialized — the
 * interleaving an integration test cannot hold open: the first access sits inside the key's compute
 * while the owner-checked delete evicts.
 */
class PodRepositoryCacheEvictionTest {

  private val podId = ObjectId()
  private val initializing = CountDownLatch(1)
  private val release = CountDownLatch(1)

  private val sempodsFacade = mockk<SempodsFacade> {
    every { getPodId("alice") } answers {
      initializing.countDown()
      release.await(10, TimeUnit.SECONDS)
      podId
    }
  }

  private val cache = PodRepositoryCache(
    sempodsFacade = sempodsFacade,
    rdfResourceBackupDao = mockk(relaxed = true),
    podDao = mockk(relaxed = true),
    podChangeDispatcher = mockk(relaxed = true),
  )

  @Test
  fun `an eviction waits for an initialization in progress and evicts what it publishes`() {
    var published: PodRepository? = null
    val reader = thread { published = cache.get("alice") }
    assertTrue(initializing.await(10, TimeUnit.SECONDS))

    val evictor = thread { cache.invalidate("alice", podId) }
    // Blocked on the key while the initialization holds it — or, if it did not wait, already done.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (evictor.state != Thread.State.BLOCKED && evictor.state != Thread.State.TERMINATED && System.nanoTime() < deadline) {
      Thread.onSpinWait()
    }
    release.countDown()
    reader.join()
    evictor.join()

    assertNotNull(published)
    assertNotSame(published, cache.get("alice"), "the repository published during the eviction is gone")
  }
}
