package org.sempods.mcp.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.sempods.commons.net.SempodsVocabulary

/**
 * The pod's context catalogue, in the shape the `list_contexts` tool has always had.
 *
 * A pod answers the catalogue as RDF (`SPS-CTX-033`): the collection's own `@id`, its members under
 * `sd:namedGraph`, and the caller's rights as three direct relations. The tool keeps handing the
 * model `{"contexts":[{"context_iri","permissions"}],"writable_contexts":[…]}`, because that shape
 * is what `ToolCatalog`'s description, the tools/list snapshots and every prompt built on them
 * name — a wire migration under the pod is no reason to re-teach the model.
 *
 * A pod that has not migrated still answers the JSON envelope; that body is passed through
 * unchanged, and the branch goes with
 * [#184](https://github.com/sempods/sempods-kotlin/issues/184).
 *
 * Reading is deliberately forgiving: an unexpected shape yields fewer contexts, never an error. The
 * tool's own answer is the model's authority on what it may reach, and failing the call would tell
 * it nothing at all.
 */
object ContextCatalogue {

  private const val NAMED_GRAPH = "http://www.w3.org/ns/sparql-service-description#namedGraph"

  /** The catalogue as the tool reports it. */
  fun toToolPayload(catalogue: JsonNode): JsonNode {
    // The legacy envelope, from a pod that has not migrated yet.
    if (catalogue.path("contexts").isArray) return catalogue

    val members = iris(catalogue, NAMED_GRAPH)
    val readable = iris(catalogue, SempodsVocabulary.READABLE_CONTEXT)
    val writable = iris(catalogue, SempodsVocabulary.WRITABLE_CONTEXT)
    val manageable = iris(catalogue, SempodsVocabulary.MANAGEABLE_CONTEXT)

    val payload = JsonNodeFactory.instance.objectNode()
    val contexts = payload.putArray("contexts")
    members.forEach { iri ->
      val entry: ObjectNode = contexts.addObject()
      entry.put("context_iri", iri)
      val permissions = entry.putArray("permissions")
      if (iri in readable) permissions.add("read")
      if (iri in writable) permissions.add("write")
      if (iri in manageable) permissions.add("manage")
    }
    val writableContexts = payload.putArray("writable_contexts")
    members.filter { it in writable }.forEach { writableContexts.add(it) }
    return payload
  }

  /** The `@id`s a predicate points at, in the order the catalogue listed them. */
  private fun iris(catalogue: JsonNode, predicate: String): List<String> =
    catalogue.path(predicate)
      .mapNotNull { value -> value.path("@id").takeIf { it.isTextual }?.asText() }
}
