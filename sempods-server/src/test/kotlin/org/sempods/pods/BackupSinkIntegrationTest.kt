package org.sempods.pods

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.ontologies.Ontologies
import org.sempods.pods.changes.BackupSinkPodChangeListener
import org.sempods.pods.changes.Durability
import org.sempods.pods.changes.PodChangeListener
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.RdfResourceBackupDao
import org.sempods.rdf.RdfWriterUtil
import org.sempods.rdf.toIri
import org.bson.types.ObjectId
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup sink against the real wiring: every committed write mirrors the resource into
 * `resources` as N-Quads, and a delete drops the row. Since the legacy decommission
 * this collection is the pod's only durable persistence, which is why the sink
 * holds the single critical slot — asserted here so the wiring cannot silently lose it.
 */
class BackupSinkIntegrationTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var podRepositoryCache: PodRepositoryCache

  @Inject
  private lateinit var podDao: PodDao

  @Inject
  private lateinit var backupDao: RdfResourceBackupDao

  @Inject
  private lateinit var changeListeners: Set<@JvmSuppressWildcards PodChangeListener>

  private val context = Values.iri("https://pods.test/backup/contexts/main")

  private fun podIdOf(name: String): ObjectId =
    podDao.fetchByName(name)!!.id!!

  @Test
  fun `the backup sink is the deployment's one critical listener`() {
    val critical = changeListeners.filter { it.durability == Durability.CRITICAL }
    assertEquals(
      listOf(BackupSinkPodChangeListener::class),
      critical.map { it::class },
      "the only durable persistence must be the sink whose failure fails the write",
    )
  }

  @Test
  fun `a write mirrors the resource into the backup collection and a delete removes it`() {
    val pod = sempodsTestFactory.newPod()
    val podId = podIdOf(pod.name)
    val resourceUri = URI("https://example.org/bk-${System.nanoTime()}")
    val resourceIri = resourceUri.toIri()

    val repo = podRepositoryCache.get(pod.name)!!
    val model = LinkedHashModel().apply {
      add(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("Backup me"), context)
    }
    assertTrue(repo.putResource(resourceUri, model))

    // The best-effort backup sink mirrored the context's statements as N-Quads, per (resource, context).
    val contextUri = URI(context.toString())
    val backup = backupDao.fetch(podId, resourceUri, contextUri)!!
    val restored = RdfWriterUtil.readNQuads(backup.nquads.byteInputStream())
    assertTrue(Models.isomorphic(model, restored), "backup N-Quads round-trip must be isomorphic, got: $restored")
    // Explicit: the named graph survives the round-trip (N-Quads is context-aware, not N-Triples).
    assertEquals(setOf(context), restored.contexts(), "backup must preserve the named graph")
    assertTrue(restored.contains(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context))

    // Deleting the resource drops its (resource, context) backup row.
    assertTrue(repo.deleteResource(resourceUri))
    assertNull(backupDao.fetch(podId, resourceUri, contextUri))
  }
}
