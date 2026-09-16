package org.sempods.api.pod.system.contexts

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bson.types.ObjectId
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.DCTERMS
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDFS
import org.eclipse.rdf4j.model.vocabulary.SD
import org.eclipse.rdf4j.model.vocabulary.XSD
import org.junit.jupiter.api.Test
import org.sempods.commons.net.SempodsVocabulary
import org.sempods.pods.contexts.persist.PodContextDbo
import org.sempods.pods.grants.ContextPermissionEntry
import org.sempods.pods.grants.ContextPermissionSource
import org.sempods.pods.grants.EffectiveContextPermissions

/** What the registry says about a context, and what it says the caller may do with one. */
class PodContextRegistryRdfTest {

  private val podBaseUrl = "https://pods.example/alice/"

  private val tasks = "https://pods.example/alice/_system/contexts/tasks"

  private val notes = "https://pods.example/alice/_system/contexts/notes"

  private fun row(
    contextUri: String,
    label: String? = null,
    description: String? = null,
    isPublic: Boolean = false,
    createdAt: Instant = Instant.parse("2026-09-16T10:15:30Z"),
  ) = PodContextDbo(
    podId = ObjectId(),
    contextUri = contextUri,
    label = label,
    description = description,
    isPublic = isPublic,
    createdAt = createdAt,
    createdBy = "test",
  )

  private fun visible(vararg entries: Pair<String, List<String>>) = EffectiveContextPermissions(
    byContext = entries.associate { (uri, permissions) ->
      uri to ContextPermissionEntry(contextUri = uri, permissions = permissions, source = ContextPermissionSource.GRANT)
    },
    writableContexts = entries.filter { "write" in it.second }.map { it.first },
  )

  @Test
  fun `a description carries the registry's own view and nothing about the caller`() {
    val model = PodContextRegistryRdf.describe(row(tasks, label = "Tasks", description = "What to do"), podBaseUrl)
    val context = Values.iri(tasks)

    assertEquals(setOf(Values.iri(SD.NAMED_GRAPH_CLASS.stringValue())), model.filter(context, RDF.TYPE, null).objects().toSet())
    assertEquals(setOf(context), model.filter(context, SD.NAME, null).objects().toSet())
    assertEquals("Tasks", model.filter(context, RDFS.LABEL, null).objects().single().stringValue())
    assertEquals("What to do", model.filter(context, DCTERMS.DESCRIPTION, null).objects().single().stringValue())
    assertEquals(
      Values.literal("2026-09-16T10:15:30Z", XSD.DATETIME),
      model.filter(context, DCTERMS.CREATED, null).objects().single(),
    )
    assertEquals(
      "https://pods.example/alice/_system/resources/" +
        "aHR0cHM6Ly9wb2RzLmV4YW1wbGUvYWxpY2UvX3N5c3RlbS9jb250ZXh0cy90YXNrcw",
      model.filter(context, RDFS.SEEALSO, null).objects().single().stringValue(),
    )
    assertEquals(
      Values.literal(false),
      model.filter(context, Values.iri(SempodsVocabulary.PUBLIC), null).objects().single(),
    )
    assertTrue(
      model.none { it.predicate.stringValue().startsWith(SempodsVocabulary.NAMESPACE + "readable") },
      "a description states no caller right",
    )
    assertEquals(7, model.size, "no statement beyond the seven the registry holds")
  }

  @Test
  fun `a bare private context leaves label and description out and stays public-flagged`() {
    val model = PodContextRegistryRdf.describe(row(notes), podBaseUrl)
    val context = Values.iri(notes)

    assertTrue(model.filter(context, RDFS.LABEL, null).isEmpty())
    assertTrue(model.filter(context, DCTERMS.DESCRIPTION, null).isEmpty())
    assertEquals(Values.literal(false), model.filter(context, Values.iri(SempodsVocabulary.PUBLIC), null).objects().single())
    assertEquals(
      Values.literal(true),
      PodContextRegistryRdf.describe(row(notes, isPublic = true), podBaseUrl)
        .filter(context, Values.iri(SempodsVocabulary.PUBLIC), null).objects().single(),
    )
  }

  @Test
  fun `the catalogue lists what the caller sees, with the rights they hold`() {
    val model = PodContextRegistryRdf.catalogue(
      podBaseUrl = podBaseUrl,
      rows = listOf(row(tasks), row(notes)),
      effective = visible(tasks to listOf("read", "write"), notes to listOf("read")),
    )
    val catalogue = PodContextRegistryRdf.catalogueIri(podBaseUrl)

    assertEquals("https://pods.example/alice/_system/contexts", catalogue.stringValue())
    assertEquals(setOf(SD.GRAPH_COLLECTION), model.filter(catalogue, RDF.TYPE, null).objects().toSet())
    assertEquals(
      setOf(Values.iri(tasks), Values.iri(notes)),
      model.filter(catalogue, SD.NAMED_GRAPH_PROPERTY, null).objects().toSet(),
    )
    assertEquals(
      setOf(Values.iri(tasks), Values.iri(notes)),
      model.filter(catalogue, Values.iri(SempodsVocabulary.READABLE_CONTEXT), null).objects().toSet(),
    )
    assertEquals(
      setOf(Values.iri(tasks)),
      model.filter(catalogue, Values.iri(SempodsVocabulary.WRITABLE_CONTEXT), null).objects().toSet(),
    )
    assertTrue(model.filter(catalogue, Values.iri(SempodsVocabulary.MANAGEABLE_CONTEXT), null).isEmpty())
  }

  @Test
  fun `a manage right is stated in all three relations`() {
    val model = PodContextRegistryRdf.catalogue(
      podBaseUrl = podBaseUrl,
      rows = listOf(row(tasks)),
      effective = visible(tasks to listOf("read", "write", "manage")),
    )
    val catalogue = PodContextRegistryRdf.catalogueIri(podBaseUrl)

    listOf(
      SempodsVocabulary.READABLE_CONTEXT,
      SempodsVocabulary.WRITABLE_CONTEXT,
      SempodsVocabulary.MANAGEABLE_CONTEXT,
    ).forEach { relation ->
      assertEquals(setOf(Values.iri(tasks)), model.filter(catalogue, Values.iri(relation), null).objects().toSet(), relation)
    }
  }

  @Test
  fun `a right is never stated about a context the catalogue does not list`() {
    val model = PodContextRegistryRdf.catalogue(
      podBaseUrl = podBaseUrl,
      // `notes` is visible to the credential but its registry row is gone; `tasks` is registered
      // but invisible to this caller.
      rows = listOf(row(tasks)),
      effective = visible(notes to listOf("read", "write")),
    )
    val catalogue = PodContextRegistryRdf.catalogueIri(podBaseUrl)

    assertTrue(model.filter(catalogue, SD.NAMED_GRAPH_PROPERTY, null).isEmpty())
    assertTrue(model.filter(catalogue, Values.iri(SempodsVocabulary.READABLE_CONTEXT), null).isEmpty())
    assertTrue(model.filter(catalogue, Values.iri(SempodsVocabulary.WRITABLE_CONTEXT), null).isEmpty())
  }

  @Test
  fun `an empty catalogue is still the collection it is`() {
    val model = PodContextRegistryRdf.catalogue(podBaseUrl, rows = emptyList(), effective = visible())
    val catalogue = PodContextRegistryRdf.catalogueIri(podBaseUrl)

    assertEquals(1, model.size)
    assertEquals(setOf(SD.GRAPH_COLLECTION), model.filter(catalogue, RDF.TYPE, null).objects().toSet())
  }
}
