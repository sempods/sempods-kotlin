package org.sempods.rdf

import org.sempods.commons.json.JsonMappers
import org.sempods.commons.json.JsonUtil
import org.sempods.ontologies.Ontologies
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.impl.SimpleNamespace
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RdfWriterUtilTest {

  private val jsonUtil = JsonUtil(JsonMappers.default())

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
    val expected = jsonUtil.read(
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

    val expected = jsonUtil.read(
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
      JsonUtil.dynamicTypeListRef,
    )

    assertEquals(expected, jsonLdGraphs)
  }

  @Test
  fun `the multi-subject grouping keeps a blank node a blank node, across graphs and as a subject`() {
    // sempods-spec examples/find/responses.yaml, "split": the link in A, the observation's details in B.
    val hit = Values.iri("https://pod.example/articles/42")
    val contextA = Values.iri("https://pod.example/_system/contexts/A")
    val contextB = Values.iri("https://pod.example/_system/contexts/B")
    val findResult = Values.iri("https://schema.sempods.org/findResult")
    val text = Values.iri("https://schema.org/text")
    val observation = Values.bnode("m")
    val model = LinkedHashModel()
    model.add(hit, findResult, observation, contextA)
    model.add(observation, text, Values.literal("Harbour lantern"), contextB)

    val written = JsonMappers.default().writeValueAsString(RdfWriterUtil.toJsonLdNamedGraphs(model))

    assertEquals(
      """[{"@id":"${contextA.stringValue()}","@graph":[{"@id":"${hit.stringValue()}","${findResult.stringValue()}":[{"@id":"_:m"}]}]},""" +
        """{"@id":"${contextB.stringValue()}","@graph":[{"@id":"_:m","${text.stringValue()}":[{"@value":"Harbour lantern"}]}]}]""",
      written,
    )
    val read = Rio.parse(StringReader(written), RDFFormat.JSONLD)
    assertTrue(Models.isomorphic(model, read), "read back: $read")
    val link = read.filter(hit, findResult, null).single()
    val node = assertIs<BNode>(link.`object`)
    assertEquals(contextB, read.filter(node, text, null).single().context)
  }

  @Test
  fun `a model without blank nodes renders as it did, IRI subjects sorted within each graph`() {
    val contextA = Values.iri("https://example/contexts/a")
    val name = Values.iri("https://schema.org/name")
    val model = LinkedHashModel()
    model.add(Values.iri("https://example/b"), name, Values.literal("B"), contextA)
    model.add(Values.iri("https://example/a"), RDF.TYPE, Values.iri("https://schema.org/Thing"), contextA)
    model.add(Values.iri("https://example/a"), Values.iri("https://schema.org/knows"), Values.iri("https://example/b"), contextA)

    assertEquals(
      """[{"@id":"https://example/contexts/a","@graph":[""" +
        """{"@id":"https://example/a","@type":"https://schema.org/Thing","https://schema.org/knows":[{"@id":"https://example/b"}]},""" +
        """{"@id":"https://example/b","https://schema.org/name":[{"@value":"B"}]}]}]""",
      JsonMappers.default().writeValueAsString(RdfWriterUtil.toJsonLdNamedGraphs(model)),
    )
  }
}
