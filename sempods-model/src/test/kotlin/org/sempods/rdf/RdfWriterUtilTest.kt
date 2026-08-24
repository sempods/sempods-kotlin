package org.sempods.rdf

import org.sempods.ontologies.Ontologies
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleNamespace
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals

class RdfWriterUtilTest {

  @Test
  fun `the context names the default vocabulary and prefixes every foreign term`() {

    // Two vocabularies on one resource, which is the ordinary case rather than an exotic one: a
    // publisher describes what schema.org has terms for and mints its own for the rest. The
    // rendering has to say which is which — a reader that cannot tell a `startDate` from someone's
    // private `startDate` has learned nothing from the `@context`.
    val vendorNs = SimpleNamespace("ex", "https://vocab.example.org/")
    val subject = URI("https://example/event").toIri()
    val context = URI("https://example/context").toIri()

    val model = LinkedHashModel()
    model.setNamespace(Ontologies.SCHEMA_ORG.NS)
    model.setNamespace(vendorNs)
    model.add(subject, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
    model.add(subject, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("name-1"), context)
    model.add(subject, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("name-2"), context)
    model.add(subject, Ontologies.SCHEMA_ORG.Properties.description, Values.literal("description"), context)
    model.add(subject, Values.iri(vendorNs, "htmlDescription"), Values.literal("html"), context)
    model.add(subject, Ontologies.SCHEMA_ORG.Properties.image, URI("https://example.com/image-1").toIri(), context)
    model.add(subject, Ontologies.SCHEMA_ORG.Properties.image, URI("https://example.com/image-2").toIri(), context)

    val jsonLdMap = RdfWriterUtil.toJsonLdEntry(
      model = model,
      resource = subject,
      contextBuilder = JsonLdContextBuilder(Ontologies.SCHEMA_ORG.NS),
    )
    val expected = RdfWriterUtil.jsonUtil.read(
      """
        {
            "@context": {
                "@vocab": "https://schema.org/",
                "ex": "https://vocab.example.org/",
                "htmlDescription": "ex:htmlDescription"
            },
            "@id": "https://example/event",
            "@type": "Event",
            "name": [
                "name-1",
                "name-2"
            ],
            "description": ["description"],
            "htmlDescription": ["html"],
            "image": [
                { "@id": "https://example.com/image-1" },
                { "@id": "https://example.com/image-2" }
            ]
        }
      """
    )

    assertEquals(expected, jsonLdMap)
  }

  @Test
  fun `named graph rendering keeps context provenance`() {
    val subject = URI("https://example/person/bob").toIri()
    val contextA = URI("https://example/contexts/a").toIri()
    val contextB = URI("https://example/contexts/b").toIri()
    val name = Values.iri("https://schema.org/name")
    val child = Values.iri("https://schema.org/children")
    val model = LinkedHashModel()
    model.add(subject, name, Values.literal("Bob"), contextA)
    model.add(subject, child, Values.iri("https://example/person/carol"), contextB)

    val jsonLdGraphs = RdfWriterUtil.toJsonLdNamedGraphs(model = model, resource = subject)

    val expected = RdfWriterUtil.jsonUtil.read(
      """
        [
          {
            "@id": "https://example/contexts/a",
            "@graph": [
              {
                "@id": "https://example/person/bob",
                "https://schema.org/name": [{"@value": "Bob"}]
              }
            ]
          },
          {
            "@id": "https://example/contexts/b",
            "@graph": [
              {
                "@id": "https://example/person/bob",
                "https://schema.org/children": [
                  { "@id": "https://example/person/carol" }
                ]
              }
            ]
          }
        ]
      """,
      RdfWriterUtil.typeRef_graph,
    )

    assertEquals(expected, jsonLdGraphs)
  }
}
