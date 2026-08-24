package org.sempods.pods

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.RdfResourceBackupDao
import org.eclipse.rdf4j.model.util.ModelBuilder
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cache's lifecycle contract: a pod's MemoryStore is volatile, so every (re-)initialization
 * rebuilds it from the pod's durable persistence — the per-context backup collection, the only
 * source since the legacy decommission.
 *
 * The tests write through the repository (which is what feeds the backup sink), then [invalidate]
 * to force the rebuild — the same sequence a server restart produces.
 */
class PodRepositoryCacheTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var podRepositoryCache: PodRepositoryCache

  @Inject
  private lateinit var podDao: PodDao

  @Inject
  private lateinit var backupDao: RdfResourceBackupDao

  private fun model(resourceUri: URI, context: URI, name: String) = ModelBuilder()
    .namedGraph(context.toString())
    .subject(resourceUri.toString())
    .add(RDF.TYPE, "https://schema.org/Thing")
    .add("https://schema.org/name", name)
    .build()

  @Test
  fun `an invalidated cache rebuilds the store from what was persisted`() {
    val pod = sempodsTestFactory.newPod()
    val resourceUri = URI("https://example.org/resource-${randomId()}")
    val context = URI("https://contexts.example.org/${pod.name}/${randomId()}")

    assertTrue(podRepositoryCache.get(pod.name)!!.putResource(resourceUri, model(resourceUri, context, "Test Resource")))

    podRepositoryCache.invalidate(pod.name)

    podRepositoryCache.get(pod.name)!!.withConnection { connection ->
      assertFalse(connection.isEmpty)
      assertEquals(1, connection.getStatements(null, Values.iri("https://schema.org/name"), null).count())
    }
  }

  @Test
  fun `a rebuilt store reflects the latest write, not an earlier one`() {
    val pod = sempodsTestFactory.newPod()
    val resourceUri = URI("https://example.org/resource-${randomId()}")
    val context = URI("https://contexts.example.org/${pod.name}/${randomId()}")

    val repo = podRepositoryCache.get(pod.name)!!
    assertTrue(repo.putResource(resourceUri, model(resourceUri, context, "Original Name")))
    assertTrue(repo.putResource(resourceUri, model(resourceUri, context, "Updated Name")))

    podRepositoryCache.invalidate(pod.name)

    podRepositoryCache.get(pod.name)!!.withConnection { connection ->
      val names = connection.getStatements(null, Values.iri("https://schema.org/name"), null)
        .map { it.`object`.stringValue() }
        .toList()
      assertEquals(listOf("Updated Name"), names, "the backup row must have been replaced, not appended to")
    }
  }

  @Test
  fun `a rebuilt store does not resurrect a deleted resource`() {
    val pod = sempodsTestFactory.newPod()
    val resourceUri = URI("https://example.org/resource-${randomId()}")
    val context = URI("https://contexts.example.org/${pod.name}/${randomId()}")

    val repo = podRepositoryCache.get(pod.name)!!
    assertTrue(repo.putResource(resourceUri, model(resourceUri, context, "Doomed")))
    assertTrue(repo.deleteResource(resourceUri))

    podRepositoryCache.invalidate(pod.name)

    podRepositoryCache.get(pod.name)!!.withConnection { connection ->
      assertTrue(connection.isEmpty, "the backup row must have been deleted along with the resource")
    }
  }

  @Test
  fun `cache should return null for non-existent pod`() {
    assertNull(podRepositoryCache.get("non-existent-pod"))
  }

  @Test
  fun `a pod nothing was written to recovers empty and stays at a count of zero`() {
    // The ordinary case the old warning could not tell from a disaster: provisioning writes context
    // registry rows, not RDF, so a pod that has only just been created legitimately has no rows at
    // all. Nothing is missing here, and the count is what says so.
    val pod = sempodsTestFactory.newPod()

    podRepositoryCache.get(pod.name)!!.withConnection { connection ->
      assertTrue(connection.isEmpty)
    }

    assertEquals(0L, resourceRowCountOf(pod.name), "a fresh pod is at its baseline, not below it")
  }

  @Test
  fun `a recovery that loses rows behind the cache's back is a shortfall against the count`() {
    // The disaster, staged: the rows go without going through a delete, which is exactly what the
    // write path can never produce and therefore the one thing worth naming. The count is asserted
    // rather than the log line, because it is the same fact and this tree captures no log output.
    val pod = sempodsTestFactory.newPod()
    val context = URI("https://contexts.example.org/${pod.name}/${randomId()}")
    val repo = podRepositoryCache.get(pod.name)!!
    repeat(2) { i ->
      val resourceUri = URI("https://example.org/resource-${randomId()}")
      assertTrue(repo.putResource(resourceUri, model(resourceUri, context, "Resource $i")))
    }
    assertEquals(2L, resourceRowCountOf(pod.name), "the write path recorded both rows")

    val podId = assertNotNull(podDao.fetchByName(pod.name)).id!!
    assertEquals(2L, backupDao.deleteByPod(podId), "the rows go without a delete ever reaching the sink")

    podRepositoryCache.invalidate(pod.name)
    podRepositoryCache.get(pod.name)!!.withConnection { connection ->
      assertTrue(connection.isEmpty, "the store comes back as empty as the collection is")
    }

    // Re-baselined to what the recovery actually loaded: the shortfall is reported once, and a pod
    // whose rows are restored through the write path afterwards does not go on reporting it.
    assertEquals(0L, resourceRowCountOf(pod.name))

    val restored = URI("https://example.org/resource-${randomId()}")
    assertTrue(podRepositoryCache.get(pod.name)!!.putResource(restored, model(restored, context, "Restored")))
    podRepositoryCache.invalidate(pod.name)
    podRepositoryCache.get(pod.name)
    assertEquals(1L, resourceRowCountOf(pod.name), "count and collection agree again after the repair")
  }

  @Test
  fun `a recovery leaves a count that is behind exactly where it is`() {
    // A recovery cannot lift the count without risking a double count: the sink writes its rows
    // before it moves the count, so a recovery landing in that gap sees a row whose delta is still
    // in flight, and writing what it loaded would count that row twice once the delta lands —
    // leaving the count above the collection, which is the one shape that raises a false alarm.
    // Behind is the harmless direction, so behind is where it stays.
    val pod = sempodsTestFactory.newPod()
    val context = URI("https://contexts.example.org/${pod.name}/${randomId()}")
    val resourceUri = URI("https://example.org/resource-${randomId()}")
    assertTrue(podRepositoryCache.get(pod.name)!!.putResource(resourceUri, model(resourceUri, context, "Counted")))
    assertEquals(1L, resourceRowCountOf(pod.name))

    // Stand in for a delta that never landed, or one a recovery raced.
    val podId = assertNotNull(podDao.fetchByName(pod.name)).id!!
    assertTrue(podDao.setResourceRowCount(podId, expected = 1L, count = 0L))

    podRepositoryCache.invalidate(pod.name)
    podRepositoryCache.get(pod.name)!!.withConnection { connection ->
      assertFalse(connection.isEmpty, "the row is there and is loaded — nothing is missing")
    }

    assertEquals(0L, resourceRowCountOf(pod.name), "the recovery states the gap and writes nothing")

    // And the count is not blind while it is behind: a loss beyond the drift still reports, which
    // is what makes leaving it alone affordable.
    assertTrue(podDao.setResourceRowCount(podId, expected = 0L, count = 5L))
    assertEquals(1L, backupDao.deleteByPod(podId))
    podRepositoryCache.invalidate(pod.name)
    podRepositoryCache.get(pod.name)
    assertEquals(0L, resourceRowCountOf(pod.name), "a confirmed shortfall still lowers it")
  }

  private fun resourceRowCountOf(pod: String): Long? =
    assertNotNull(podDao.fetchByName(pod)).resourceRowCount
}
