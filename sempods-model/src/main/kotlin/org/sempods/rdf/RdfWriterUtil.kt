package org.sempods.rdf

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.sempods.commons.json.JsonUtil
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Namespace
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.ModelBuilder
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.XSD
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.io.StringWriter

object RdfWriterUtil {

  fun readNQuads(inputStream: InputStream): Model {
    return Rio.parse(inputStream, RDFFormat.NQUADS)
  }

  fun streamNQuads(outputStream: OutputStream, model: Model) {
    val writer = Rio.createWriter(RDFFormat.NQUADS, outputStream)
    outputStream.use {
      writer.startRDF()
      for (st in model) {
        writer.handleStatement(st)
      }
      writer.endRDF()
    }
  }

  fun writeNQuads(model: Model): String {
    val sw = StringWriter()
    val writer = Rio.createWriter(RDFFormat.NQUADS, sw)
    sw.use {
      writer.startRDF()
      for (st in model) {
        writer.handleStatement(st)
      }
      writer.endRDF()
    }
    return sw.toString()
  }

  fun writeJsonLd(model: Model): String {
    val sw = StringWriter()
    val writer = Rio.createWriter(RDFFormat.JSONLD, sw)
    sw.use {
      writer.startRDF()
      for (st in model) {
        writer.handleStatement(st)
      }
      writer.endRDF()
    }
    return sw.toString()
  }

  fun readJsonLd(jsonLd: String, targetContext: Resource? = null): Model {
    return if (targetContext == null) {
      Rio.parse(StringReader(jsonLd), RDFFormat.JSONLD)
    } else {
      Rio.parse(StringReader(jsonLd), RDFFormat.JSONLD, targetContext)
    }
  }

  fun readJsonLd(jsonLdMap: List<Map<String, Any?>>): Model {
    return readJsonLd(jsonUtil.write(jsonLdMap))
  }

  fun toJsonLdEntry(
    model: Model,
    resource: Resource,
    contextBuilder: JsonLdContextBuilder = JsonLdContextBuilder(),
    includeContext: Boolean = true,
  ): Map<String, Any> {

    model.namespaces.forEach { ns -> contextBuilder.putNs(ns) }

    val entry = mutableMapOf<String, Any>()

    if (includeContext) {
      entry["@context"] = emptyMap<String, Any>()
    }

    entry["@id"] = resource.toString()
    model.getStatements(resource, RDF.TYPE, null).firstOrNull()?.let { stmt ->
      val objIri = stmt.`object` as? IRI ?: return@let
      entry["@type"] = contextBuilder.shorten(objIri)
    }

    // remove context information
    val modelBuilder = ModelBuilder()
    model.forEach { stmt -> modelBuilder.add(stmt.subject, stmt.predicate, stmt.`object`) }

    modelBuilder.build().getStatements(resource, null, null)
      .forEach { stmt ->

        if (stmt.predicate == RDF.TYPE) {
          // already handled
          return@forEach
        }

        val pred = contextBuilder.shorten(stmt.predicate)
        val obj = if (stmt.`object`.isResource) {
          mapOf("@id" to stmt.`object`.stringValue())
        } else {
          stmt.`object`.stringValue()
        }

        val existing = entry[pred]
        if (existing == null) {
          entry[pred] = mutableListOf(obj)
        } else {
          if (existing is MutableList<*>) {
            (existing as MutableList<Any>).add(obj)
          } else {
            entry[pred] = mutableListOf(existing, obj)
          }
        }
      }

    if (includeContext) {
      entry["@context"] = contextBuilder.build()
    }

    return entry
  }

  fun toJsonLdGraph(model: Model): List<Map<String, Any?>> {
    return jsonUtil.read(writeJsonLd(model), typeRef_graph)
  }

  /**
   * Render a model as the **canonical** JSON-LD object used by LOD-layer reads and `PATCH`
   * round-trips:
   *
   * - top-level `@id` equals the resource URI
   * - `@type` is the resource's `rdf:type` value(s) as absolute IRI string(s)
   * - predicate properties use absolute IRI keys (no `@context`, no shortened terms)
   * - property values are arrays of JSON-LD value objects:
   *   - IRI objects: `{ "@id": "https://..." }`
   *   - literal objects: `{ "@value": "...", "@language": "..." }` or
   *     `{ "@value": "...", "@type": "<datatype-iri>" }`
   *   - plain string literals: `{ "@value": "..." }`
   *
   * This is the only shape the LOD-layer `PATCH` endpoint accepts as patch document; see
   * `docs/lod-crud/lod-layer.md` §"Canonical JSON-LD representation".
   */
  fun toCanonicalJsonLdEntry(model: Model, resource: Resource): MutableMap<String, Any> {
    val entry = linkedMapOf<String, Any>()
    entry["@id"] = resource.stringValue()

    // Strip context so we only look at (subject, predicate, object) — graph dimension is the
    // caller's concern (they pre-filtered by readable contexts).
    val flattened = LinkedHashModel()
    model.forEach { stmt -> flattened.add(stmt.subject, stmt.predicate, stmt.`object`) }

    val typeIris = flattened.getStatements(resource, RDF.TYPE, null)
      .mapNotNull { (it.`object` as? IRI)?.stringValue() }
    if (typeIris.isNotEmpty()) {
      entry["@type"] = if (typeIris.size == 1) typeIris.single() else typeIris
    }

    flattened.getStatements(resource, null, null).forEach { stmt ->
      if (stmt.predicate == RDF.TYPE) return@forEach
      val predicateKey = stmt.predicate.stringValue()
      val valueObject: Map<String, Any> = when (val obj = stmt.`object`) {
        is Literal -> {
          val map = linkedMapOf<String, Any>("@value" to obj.label)
          val lang = obj.language.orElse(null)
          if (lang != null) {
            map["@language"] = lang
          } else if (obj.datatype != null && obj.datatype.stringValue() != XSD.STRING.stringValue()) {
            map["@type"] = obj.datatype.stringValue()
          }
          map
        }
        is Resource -> mapOf("@id" to obj.stringValue())
        else -> mapOf("@value" to obj.stringValue())
      }
      @Suppress("UNCHECKED_CAST")
      val existing = entry[predicateKey] as? MutableList<Map<String, Any>>
      if (existing == null) {
        entry[predicateKey] = mutableListOf(valueObject)
      } else {
        existing.add(valueObject)
      }
    }

    return entry
  }

  /**
   * Render a model as JSON-LD named graph objects, grouped by RDF4J statement context.
   *
   * This is the graph-aware sibling of [toJsonLdEntry]: `toJsonLdEntry` deliberately
   * collapses named-graph provenance for ergonomic resource reads, while this method keeps
   * the context boundary visible for clients that opt into provenance.
   */
  fun toJsonLdNamedGraphs(model: Model, resource: Resource): List<Map<String, Any>> {
    return model
      .asSequence()
      .groupBy { it.context }
      .entries
      .sortedBy { (context, _) -> context?.stringValue() ?: "" }
      .map { (context, statements) ->
        val contextModel = LinkedHashModel()
        statements.forEach { stmt ->
          contextModel.add(stmt.subject, stmt.predicate, stmt.`object`, stmt.context)
        }

        val graph = linkedMapOf<String, Any>()
        if (context != null) {
          graph["@id"] = context.stringValue()
        }
        graph["@graph"] = listOf(
          toCanonicalJsonLdEntry(
            model = contextModel,
            resource = resource,
          )
        )
        graph
      }
  }

  /**
   * Multi-subject sibling of [toJsonLdNamedGraphs]: render a whole model (potentially many subjects)
   * as JSON-LD named-graph objects, grouped by RDF4J statement context. Within each context every
   * distinct IRI subject becomes one canonical entry. Used by `find` with `include_contexts` to
   * expose which context each matched/expanded statement came from. Statements with a null context
   * are grouped under an entry without `@id`.
   */
  fun toJsonLdNamedGraphs(model: Model): List<Map<String, Any>> {
    return model
      .asSequence()
      .groupBy { it.context }
      .entries
      .sortedBy { (context, _) -> context?.stringValue() ?: "" }
      .map { (context, statements) ->
        val contextModel = LinkedHashModel()
        statements.forEach { stmt ->
          contextModel.add(stmt.subject, stmt.predicate, stmt.`object`, stmt.context)
        }
        val subjects = contextModel.subjects()
          .filterIsInstance<IRI>()
          .sortedBy { it.stringValue() }

        val graph = linkedMapOf<String, Any>()
        if (context != null) {
          graph["@id"] = context.stringValue()
        }
        graph["@graph"] = subjects.map { subject ->
          toCanonicalJsonLdEntry(model = contextModel, resource = subject)
        }
        graph
      }
  }

  /**
   * A context-less copy of [model] — every statement re-added without its named graph. Used to
   * serialize the default (flat) representation deterministically, independent of how a given
   * RDF writer treats statement contexts.
   */
  fun flatten(model: Model): Model {
    val flat = LinkedHashModel()
    model.forEach { stmt -> flat.add(stmt.subject, stmt.predicate, stmt.`object`) }
    return flat
  }

  // `internal`: public, these put `JsonUtil` and a Jackson `TypeReference` in the module's
  // signature while `:commons-json` is `implementation` — visible properties, unreachable types.
  internal val typeRef_graph = object : TypeReference<List<Map<String, Any?>>>() {}

  internal val jsonUtil = JsonUtil(
    ObjectMapper()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
  )
}
