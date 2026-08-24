package org.sempods.pods

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.ontologies.Ontologies
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.RdfResourceBackupDao
import org.sempods.pods.mongo.persist.reconstructModel
import org.sempods.rdf.toIri
import org.bson.types.ObjectId
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Write-through contract (`pods/write-through.md`): writes go against the MemoryStore first
 * (source of truth), and the backup sink mirrors the committed change into `resources` —
 * without invalidating the pod cache.
 */
class WriteThroughPodRepositoryTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var podRepositoryCache: PodRepositoryCache

  @Inject
  private lateinit var podDao: PodDao

  @Inject
  private lateinit var backupDao: RdfResourceBackupDao

  private val context = Values.iri("https://pods.test/write-through/contexts/main")

  private fun podIdOf(name: String): ObjectId =
    podDao.fetchByName(name)!!.id!!

  /** What the backup collection holds for a resource — the state a restart would recover. */
  private fun backedUpModel(podId: ObjectId, resourceUri: URI): Model =
    reconstructModel(backupDao.fetchByPodAndResource(podId, resourceUri))

  @Test
  fun `putResource updates the store and the backup without invalidating the cache`() {
    val pod = sempodsTestFactory.newPod()
    val podId = podIdOf(pod.name)
    val resourceUri = URI("https://example.org/wt-${System.nanoTime()}")
    val resourceIri = resourceUri.toIri()

    val repo = podRepositoryCache.get(pod.name)!!
    val model = LinkedHashModel().apply {
      add(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("First"), context)
    }

    assertTrue(repo.putResource(resourceUri, model))

    // Same repository instance is still cached — no invalidation/rebuild happened.
    assertSame(repo, podRepositoryCache.get(pod.name))

    // The store (SPARQL surface) reflects the write directly.
    repo.withConnection { conn ->
      assertTrue(conn.hasStatement(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, false, context))
    }

    // The backup sink persisted the post-image.
    val backedUp = backedUpModel(podId, resourceUri)
    assertEquals(2, backedUp.size)
    assertEquals(
      "First",
      backedUp.getStatements(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, null).first().`object`.stringValue(),
    )

    // An identical re-write is a no-op — no change, so no pod-timestamp bump.
    assertFalse(repo.putResource(resourceUri, model))
  }

  @Test
  fun `deleteResource drops the resource from the store and the backup`() {
    val pod = sempodsTestFactory.newPod()
    val podId = podIdOf(pod.name)
    val resourceUri = URI("https://example.org/wt-del-${System.nanoTime()}")
    val resourceIri = resourceUri.toIri()

    val repo = podRepositoryCache.get(pod.name)!!
    val model = LinkedHashModel().apply {
      add(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
    }
    assertTrue(repo.putResource(resourceUri, model))

    assertTrue(repo.deleteResource(resourceUri))

    repo.withConnection { conn ->
      assertFalse(conn.hasStatement(resourceIri, null, null, false))
    }
    assertTrue(backupDao.fetchByPodAndResource(podId, resourceUri).isEmpty())

    // Deleting an absent resource is a no-op.
    assertFalse(repo.deleteResource(resourceUri))
  }

  @Test
  fun `removeContext strips the context from every affected resource`() {
    val pod = sempodsTestFactory.newPod()
    val podId = podIdOf(pod.name)
    val otherContext = Values.iri("https://pods.test/write-through/contexts/other")

    val resourceUri = URI("https://example.org/wt-ctx-${System.nanoTime()}")
    val resourceIri = resourceUri.toIri()

    val repo = podRepositoryCache.get(pod.name)!!
    val model = LinkedHashModel().apply {
      add(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("kept"), otherContext)
    }
    assertTrue(repo.putResource(resourceUri, model))

    assertTrue(repo.removeContext(URI(context.toString())))

    val backedUp = backedUpModel(podId, resourceUri)
    assertEquals(1, backedUp.size, "got: $backedUp")
    assertEquals(
      1,
      backedUp.getStatements(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, null, otherContext).count(),
    )

    // Removing a context nobody uses is a no-op.
    assertFalse(repo.removeContext(URI(context.toString())))
  }
}
