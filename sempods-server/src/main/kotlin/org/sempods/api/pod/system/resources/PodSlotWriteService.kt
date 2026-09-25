package org.sempods.api.pod.system.resources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.api.pod.resources.PodContextWriteAuthorizer
import org.sempods.api.pod.resources.RepresentationTags
import org.sempods.api.pod.resources.WriteConditions
import org.sempods.pods.PodFacade
import org.sempods.pods.contexts.ContextPathRules
import org.sempods.pods.mongo.persist.PodDbo
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.EntityTag
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.XSD
import java.net.URI

/**
 * Shared write path for the LOD-CRUD **System layer** slot operations, used by
 * [PodSystemResourcesEndpoint].
 *
 * Layered on top of [PodFacade]'s slot methods; this service handles transport-level
 * concerns: parse JSON-LD value objects into RDF [Value]s, validate the `?context=`
 * parameter, run the `<ctx>#write` / `<root>#manage` authorization check, then evaluate the
 * write's [WriteConditions] against the slot in the write context.
 *
 * [replaceSlot] and [addSlotValue] first refuse a subject under the pod's context namespace with
 * `400` ([ContextPathRules.reservedSubjectReason]), before authorization. The removals do not.
 */
class PodSlotWriteService @Inject constructor(
  private val podFacade: PodFacade,
  private val podContextWriteAuthorizer: PodContextWriteAuthorizer,
) {

  /** @property tag the slot's tag after this write, taken before any other write could run. */
  data class SlotAddResult(
    val outcome: PodFacade.SlotAddOutcome,
    val addedValue: Value,
    val tag: EntityTag,
  )

  /** @property cleared whether the slot held anything. @property tag as on [SlotAddResult]. */
  data class SlotClearResult(val cleared: Boolean, val tag: EntityTag)

  /** @property removed whether the edge was there. @property tag as on [SlotAddResult]. */
  data class EdgeRemoveResult(val removed: Boolean, val tag: EntityTag)

  fun resolveWriteContextOrThrow(pod: String, rawContext: String?): URI =
    podContextWriteAuthorizer.resolveWriteContextOrThrow(pod, rawContext)

  /**
   * Load all `(subject, predicate, *)` statements visible to [credentials], optionally
   * downscoped to [requestedContexts] (System-layer GET filter). Caller-side filtering
   * by readable contexts already happened (`credentials.restrictedContexts`).
   *
   * Returns empty model when the resource does not exist or no matching statements are
   * visible — the endpoint translates an empty result to `404 Not Found`.
   */
  fun getSlot(
    pod: String,
    subjectUri: URI,
    predicateUri: URI,
    requestedContexts: Collection<URI>?,
    credentials: SempodsCredentials,
  ): Model {
    val readable = credentials.restrictedContexts
    val effectiveContexts: Collection<URI>? = when {
      requestedContexts == null -> readable
      readable == null -> requestedContexts
      else -> requestedContexts.intersect(readable)
    }
    if (effectiveContexts != null && effectiveContexts.isEmpty()) {
      return org.eclipse.rdf4j.model.impl.LinkedHashModel()
    }
    return podFacade.getSlot(
      podName = pod,
      subjectUri = subjectUri,
      predicateUri = predicateUri,
      contexts = effectiveContexts,
    )
  }

  /** The slot's statements in [contextUri] alone — what a write there replaces, and what its tag describes. */
  private fun slotInContext(pod: String, subjectUri: URI, predicateUri: URI, contextUri: URI): Model =
    podFacade.getSlot(podName = pod, subjectUri = subjectUri, predicateUri = predicateUri, contexts = listOf(contextUri))

  /** The slot's tag in [contextUri] as it stands now — what a slot write echoes (`SPS-CRUD-052`). */
  private fun slotTag(pod: String, subjectUri: URI, predicateUri: URI, contextUri: URI): EntityTag =
    RepresentationTags.slot(slotInContext(pod, subjectUri, predicateUri, contextUri), subjectUri, predicateUri, contextUri, false)

  fun replaceSlot(
    pod: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
    body: String,
    credentials: SempodsCredentials,
    conditions: WriteConditions,
  ): EntityTag {
    rejectReservedSubject(credentials, subjectUri)
    podContextWriteAuthorizer.authorizeWriteOrThrow(credentials, contextUri)
    return podFacade.exclusively(pod) {
      requireConditions(conditions, pod, subjectUri, predicateUri, contextUri)
      podFacade.replaceSlot(
        podName = pod,
        subjectUri = subjectUri,
        predicateUri = predicateUri,
        contextUri = contextUri,
        newSlotStatements = parseSlotBodyAsArrayOrThrow(body),
      )
      slotTag(pod, subjectUri, predicateUri, contextUri)
    }
  }

  fun addSlotValue(
    pod: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
    body: String,
    credentials: SempodsCredentials,
    conditions: WriteConditions,
  ): SlotAddResult {
    rejectReservedSubject(credentials, subjectUri)
    podContextWriteAuthorizer.authorizeWriteOrThrow(credentials, contextUri)
    return podFacade.exclusively(pod) {
      requireConditions(conditions, pod, subjectUri, predicateUri, contextUri)
      val value = parseSingleValueOrThrow(body)
      val outcome = podFacade.addSlotValue(
        podName = pod,
        subjectUri = subjectUri,
        predicateUri = predicateUri,
        contextUri = contextUri,
        value = value,
      )
      SlotAddResult(outcome = outcome, addedValue = value, tag = slotTag(pod, subjectUri, predicateUri, contextUri))
    }
  }

  /**
   * Remove the single edge `(subject, predicate, target)` in [contextUri]. The route is
   * `SPS-CRUD-042`; that this operation is **idempotent**, and the `removed` / `already_absent`
   * words it answers with, are `SPS-CRUD-044` — a missing edge yields the same outcome as
   * removing a present one. [EdgeRemoveResult.removed] tells the two apart for the audit log and
   * the outcome body; it is not a success/failure signal.
   *
   * An edge has no tag of its own, so [conditions] are evaluated against its slot in [contextUri]
   * (`SPS-CRUD-059`), under the same lock as the removal (`SPS-CRUD-060`). An absent edge is
   * `already_absent` when they hold and `412` when they do not.
   */
  fun removeSlotEdge(
    pod: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
    targetIri: IRI,
    credentials: SempodsCredentials,
    conditions: WriteConditions,
  ): EdgeRemoveResult {
    podContextWriteAuthorizer.authorizeWriteOrThrow(credentials, contextUri)
    return podFacade.exclusively(pod) {
      requireConditions(conditions, pod, subjectUri, predicateUri, contextUri)
      val removed = podFacade.removeSlotEdge(
        podName = pod,
        subjectUri = subjectUri,
        predicateUri = predicateUri,
        contextUri = contextUri,
        targetIri = targetIri,
      )
      EdgeRemoveResult(removed = removed, tag = slotTag(pod, subjectUri, predicateUri, contextUri))
    }
  }

  /**
   * Empty the slot `(subject, predicate)` in [contextUri]. The verb is `SPS-CRUD-041`; that
   * whole-slot DELETE is **idempotent**, and the `cleared` / `already_empty` words it answers
   * with, are `SPS-CRUD-044` — a slot that was already empty yields the same outcome as one that
   * held triples. Returned boolean carries the secondary "did anything actually change" signal
   * for audit / MCP outcome reporting, not success/failure.
   */
  fun clearSlot(
    pod: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
    credentials: SempodsCredentials,
    conditions: WriteConditions,
  ): SlotClearResult {
    podContextWriteAuthorizer.authorizeWriteOrThrow(credentials, contextUri)
    return podFacade.exclusively(pod) {
      requireConditions(conditions, pod, subjectUri, predicateUri, contextUri)
      val cleared = podFacade.clearSlot(
        podName = pod,
        subjectUri = subjectUri,
        predicateUri = predicateUri,
        contextUri = contextUri,
      )
      SlotClearResult(cleared = cleared, tag = slotTag(pod, subjectUri, predicateUri, contextUri))
    }
  }

  /**
   * `If-None-Match: *` holds while the slot is empty in [contextUri] (`SPS-CRUD-053`); a tag holds
   * while it describes the slot there. Statements of other predicates and other contexts do not
   * enter either. Called inside [PodFacade.exclusively] with the write it guards.
   */
  private fun requireConditions(
    conditions: WriteConditions,
    pod: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
  ) {
    val current = slotInContext(pod, subjectUri, predicateUri, contextUri)
    conditions.requireHold(
      current = RepresentationTags.slotWriteTarget(current, subjectUri, predicateUri, contextUri),
      exists = current.isNotEmpty(),
    )
  }

  // -- body parsing --

  /**
   * Parse a `PUT` body — must be a JSON array of value objects (`{"@id": "..."}` or
   * `{"@value": "...", ...}`). An empty array is valid and clears the slot.
   */
  private fun parseSlotBodyAsArrayOrThrow(body: String): List<Value> {
    val root = parseJsonOrThrow(body)
    val array = when {
      root.isArray -> root as ArrayNode
      else -> throw badRequest("slot body must be a JSON array of value objects")
    }
    return array.map { jsonNodeToValueOrThrow(it) }
  }

  /**
   * Parse a `POST` body — either a single value object `{"@id": "..."}` /
   * `{"@value": "..."}` (Spec example) OR a single-element array.
   */
  internal fun parseSingleValueOrThrow(body: String): Value {
    val root = parseJsonOrThrow(body)
    val node = when {
      root.isObject -> root
      root.isArray && root.size() == 1 -> root.get(0)
      root.isArray -> throw badRequest("POST body must contain exactly one value object")
      else -> throw badRequest("POST body must be a JSON value object")
    }
    return jsonNodeToValueOrThrow(node)
  }

  private fun parseJsonOrThrow(body: String): JsonNode {
    if (body.isBlank()) throw badRequest("missing request body")
    return try {
      objectMapper.readTree(body) ?: throw badRequest("invalid JSON body")
    } catch (e: WebApplicationException) {
      throw e
    } catch (_: Exception) {
      throw badRequest("invalid JSON body")
    }
  }

  private fun jsonNodeToValueOrThrow(node: JsonNode): Value {
    if (!node.isObject) {
      throw badRequest("each value must be a JSON-LD value object (\"@id\" or \"@value\")")
    }
    val obj = node as ObjectNode
    val idNode = obj.get("@id")
    val valueNode = obj.get("@value")

    if (idNode != null && valueNode != null) {
      throw badRequest("value object must not carry both \"@id\" and \"@value\"")
    }
    if (idNode != null) {
      if (!idNode.isTextual || idNode.asText().isBlank()) {
        throw badRequest("\"@id\" must be a non-empty IRI string")
      }
      return try {
        Values.iri(idNode.asText())
      } catch (e: Exception) {
        throw badRequest("invalid \"@id\" IRI: ${e.message}")
      }
    }
    if (valueNode != null) {
      val language = obj.get("@language")?.takeIf { !it.isNull }?.asText()?.takeIf(String::isNotBlank)
      val datatype = obj.get("@type")?.takeIf { !it.isNull }?.asText()?.takeIf(String::isNotBlank)
      if (language != null && datatype != null) {
        throw badRequest("literal value must not carry both \"@language\" and \"@type\"")
      }
      val literalString = when {
        valueNode.isTextual -> valueNode.asText()
        valueNode.isNumber || valueNode.isBoolean -> valueNode.asText()
        else -> throw badRequest("\"@value\" must be a string, number, or boolean")
      }
      return when {
        language != null -> Values.literal(literalString, language)
        datatype != null -> try {
          Values.literal(literalString, Values.iri(datatype))
        } catch (e: Exception) {
          throw badRequest("invalid \"@type\" IRI: ${e.message}")
        }
        valueNode.isBoolean -> Values.literal(literalString, XSD.BOOLEAN)
        valueNode.isIntegralNumber -> Values.literal(literalString, XSD.INTEGER)
        valueNode.isFloatingPointNumber -> Values.literal(literalString, XSD.DECIMAL)
        else -> Values.literal(literalString)
      }
    }
    throw badRequest("value object must declare \"@id\" or \"@value\"")
  }

  private fun badRequest(message: String): WebApplicationException =
    WebApplicationException(
      Response.status(400).entity(message).type(MediaType.TEXT_PLAIN).build()
    )

  private fun rejectReservedSubject(credentials: SempodsCredentials, subjectUri: URI) {
    ContextPathRules.reservedSubjectReason("${credentials.pod.uri}/", subjectUri.toString())?.let { throw badRequest(it) }
  }

  companion object {
    private val objectMapper = JsonMappers.default()
  }
}
