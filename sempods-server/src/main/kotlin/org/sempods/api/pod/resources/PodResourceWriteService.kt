package org.sempods.api.pod.resources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.inject.Inject
import org.sempods.commons.jaxrs.errors.ApiException
import org.sempods.commons.json.JsonMappers
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.api.pod.errors.PodResourceErrors
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.rdf.RdfWriterUtil
import org.sempods.rdf.toIri
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.rio.RDFFormat
import java.net.URI

/**
 * Shared write path for pod resources, used by [PodResourceEndpoint] (HTTP REST) and
 * [org.sempods.api.pod.system.mcp.McpEndpoint] (MCP write tools).
 *
 * All methods throw [WebApplicationException] or [ApiException] with a meaningful 4xx
 * status when preconditions fail, so callers can translate to their transport's error shape
 * (HTTP or JSON-RPC).
 */
class PodResourceWriteService @Inject constructor(
  private val podFacade: PodFacade,
  private val podContextWriteAuthorizer: PodContextWriteAuthorizer,
) {

  /**
   * Outcome of [putResource] — distinguishes the two cases the HTTP `PUT` needs to translate
   * to `201 Created` vs. `200 OK` per RFC 9110 §9.3.4.
   */
  enum class PutResourceOutcome { CREATED, UPDATED }

  /**
   * Resolve a write-context URI for a pod from a raw input that is either an absolute URI or a
   * pod-relative path. Validates the URI is inside the pod namespace and the context exists.
   */
  fun resolveWriteContextOrThrow(pod: String, rawContext: String?): URI {
    return podContextWriteAuthorizer.resolveWriteContextOrThrow(pod, rawContext)
  }

  /**
   * Replace all statements for [resourceUri] in [contextUri] with the parsed body. Mirrors
   * `PUT /{pod}/{resourcePath}` semantics. Returns [PutResourceOutcome.CREATED] when the
   * target context contained no statements for the resource before this call, otherwise
   * [PutResourceOutcome.UPDATED].
   */
  fun putResource(
    pod: String,
    resourceUri: URI,
    contextUri: URI,
    contentTypeHeader: String?,
    body: String,
    credentials: SempodsCredentials,
  ): PutResourceOutcome {
    podContextWriteAuthorizer.authorizeWriteOrThrow(credentials, contextUri)
    val inputModel = parseRequestModelOrThrow(contentTypeHeader, body)
    validateReplacementModelOrThrow(
      model = inputModel,
      resourceUri = resourceUri,
      targetContextUri = contextUri,
      allowEmptyModel = false,
    )
    val preExisting = podFacade.loadResourceStatementsInContext(
      podName = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
    )
    val existedBefore = preExisting.isNotEmpty()
    podFacade.patchResource(
      podName = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
      replacementModel = inputModel,
    )
    return if (existedBefore) PutResourceOutcome.UPDATED else PutResourceOutcome.CREATED
  }

  /**
   * Apply a strict RFC 7396 JSON merge-patch to the resource's canonical JSON-LD
   * representation in [contextUri]. The patch body MUST be in the canonical JSON-LD shape
   * defined by `docs/lod-crud/lod-layer.md` §"Canonical JSON-LD representation":
   *
   * - top-level `@id` only when it equals the request resource URI
   * - `@type` allowed (string, array of strings, or `null` to remove all `rdf:type` triples)
   * - all other keys must be absolute IRIs — `@context` and compact terms like
   *   `schema:name` are rejected with `400`
   * - JSON-LD keywords other than `@id` / `@type` are rejected with `400`
   *
   * RFC 7396 array-replace semantics apply unchanged: writing a multivalued property
   * replaces every existing value. Use the System-layer property-value tools for additive
   * multivalued operations.
   */
  fun mergePatchResource(
    pod: String,
    resourceUri: URI,
    contextUri: URI,
    body: String,
    credentials: SempodsCredentials,
  ): Boolean {
    podContextWriteAuthorizer.authorizeWriteOrThrow(credentials, contextUri)

    val existingStatements = podFacade.loadResourceStatementsInContext(
      podName = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
    )
    if (existingStatements.isEmpty()) {
      PodResourceErrors.throwResourceNotFoundInContext(
        resourceUri = resourceUri,
        contextUri = contextUri,
        hint = "use create_resource to create it",
      )
    }

    val patch = parseStrictPatchOrThrow(body, resourceUri)
    val currentDocument = objectMapper.valueToTree<JsonNode>(
      RdfWriterUtil.toCanonicalJsonLdEntry(
        model = existingStatements,
        resource = resourceUri.toIri(),
      )
    ) as ObjectNode
    val merged = applyVanillaJsonMergePatch(currentDocument, patch)
    if (!merged.isObject) {
      throw WebApplicationException(
        Response.status(400).entity("merge patch result must be a JSON object").type(MediaType.TEXT_PLAIN).build()
      )
    }
    // `@id` survives the merge naturally (canonical document carries it, patch is required to
    // match if it sets one), but re-pin defensively so a removed `@id` cannot fall through.
    (merged as ObjectNode).put("@id", resourceUri.toString())

    val patchedModel = parseRequestModelOrThrow(
      contentTypeHeader = "application/ld+json",
      body = objectMapper.writeValueAsString(merged),
    )
    validateReplacementModelOrThrow(
      model = patchedModel,
      resourceUri = resourceUri,
      targetContextUri = contextUri,
      allowEmptyModel = true,
    )

    return podFacade.patchResource(
      podName = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
      replacementModel = patchedModel,
    )
  }

  /**
   * Remove the resource from [contextUri]. Returns true if something was deleted. Throws 404 if
   * the resource does not exist in that context.
   */
  fun deleteResource(
    pod: String,
    resourceUri: URI,
    contextUri: URI,
    credentials: SempodsCredentials,
  ): Boolean {
    podContextWriteAuthorizer.authorizeWriteOrThrow(credentials, contextUri)
    val changed = podFacade.deleteFromContext(
      podName = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
    )
    if (!changed) {
      PodResourceErrors.throwResourceNotFoundInContext(
        resourceUri = resourceUri,
        contextUri = contextUri,
      )
    }
    return changed
  }

  private fun parseRequestModelOrThrow(contentTypeHeader: String?, body: String): Model {
    if (body.isBlank()) {
      throw WebApplicationException(
        Response.status(400).entity("missing request body").type(MediaType.TEXT_PLAIN).build()
      )
    }
    val mediaType = contentTypeHeader
      ?.substringBefore(";")
      ?.trim()
      ?.lowercase()
      ?: MediaType.APPLICATION_JSON
    return try {
      when (mediaType) {
        "application/json", "application/ld+json" -> RdfWriterUtil.readJsonLd(body)
        RDFFormat.NQUADS.defaultMIMEType -> RdfWriterUtil.readNQuads(body.byteInputStream())
        else -> throw WebApplicationException(
          Response.status(415).entity("unsupported content type '$mediaType'").type(MediaType.TEXT_PLAIN).build()
        )
      }
    } catch (e: WebApplicationException) {
      throw e
    } catch (_: Exception) {
      throw WebApplicationException(
        Response.status(400).entity("invalid RDF request body").type(MediaType.TEXT_PLAIN).build()
      )
    }
  }

  private fun validateReplacementModelOrThrow(
    model: Model,
    resourceUri: URI,
    targetContextUri: URI,
    allowEmptyModel: Boolean,
  ) {
    val resourceIri = resourceUri.toIri()
    val subjects = model.map { it.subject }.toSet()
    if (!allowEmptyModel && subjects.isEmpty()) {
      // Most common cause for an AI-generated JSON-LD body: bare-key predicates
      // (e.g. `{"@id": "...", "text": "hello"}`) without a `@context` mapping.
      // JSON-LD 1.1 drops those during expansion → zero triples. Surface the root
      // cause so the caller can actually fix it rather than guessing at phantom fields.
      throw WebApplicationException(
        Response.status(400).entity(
          "request body parsed to 0 RDF statements (nothing to store). " +
              "Most common cause: JSON-LD predicates used as bare keys without a \"@context\" " +
              "mapping. Either use absolute IRIs as keys (e.g. \"https://schema.org/text\": \"…\") " +
              "or add a \"@context\" block that maps your shorthand keys to IRIs (e.g. " +
              "{\"@context\": {\"schema\": \"https://schema.org/\"}, \"@id\": \"…\", \"schema:text\": \"…\"}). " +
              "The resource object MUST contain at least one predicate/value pair besides \"@id\" and \"@type\"."
        ).type(MediaType.TEXT_PLAIN).build()
      )
    }
    // Blank nodes are forbidden (a nested JSON-LD object without `@id` expands to one). Reject here
    // with 400 — otherwise a blank-node object passes the subject check and only the
    // ResourceBoundary backstop catches it, as a 500.
    if (model.any { it.subject is BNode || it.`object` is BNode }) {
      throw WebApplicationException(
        Response.status(400)
          .entity(
            "request body contains a blank node — pod data must be fully IRI/literal-valued; " +
              "a nested value must be its own resource with an \"@id\""
          )
          .type(MediaType.TEXT_PLAIN)
          .build()
      )
    }
    if (subjects.any { it != resourceIri }) {
      throw WebApplicationException(
        Response.status(400)
          .entity("request body must only contain statements for '$resourceUri'")
          .type(MediaType.TEXT_PLAIN)
          .build()
      )
    }

    val mismatchingContexts = model.asSequence()
      .mapNotNull { stmt -> stmt.context?.stringValue() }
      .filter { explicitContext -> explicitContext != targetContextUri.toString() }
      .toSet()
    if (mismatchingContexts.isNotEmpty()) {
      throw WebApplicationException(
        Response.status(400)
          .entity("request body contains statements for context(s) other than '$targetContextUri'")
          .type(MediaType.TEXT_PLAIN)
          .build()
      )
    }
  }

  /**
   * Parse a PATCH body in canonical JSON-LD shape. The patch is validated against the
   * canonical-form rules in [mergePatchResource]; everything else is forwarded unchanged to
   * [applyVanillaJsonMergePatch].
   */
  private fun parseStrictPatchOrThrow(body: String, resourceUri: URI): ObjectNode {
    if (body.isBlank()) {
      throw badRequest("missing request body")
    }
    val root = try {
      objectMapper.readTree(body)
    } catch (_: Exception) {
      throw badRequest("invalid merge patch body")
    } ?: throw badRequest("invalid merge patch body")

    if (!root.isObject) {
      throw badRequest("merge patch body must be a JSON object")
    }
    val patch = root as ObjectNode
    val resourceUriString = resourceUri.toString()

    for (key in patch.fieldNames().asSequence().toList()) {
      when {
        key == "@id" -> {
          val node = patch.get(key)
          if (node.isNull) {
            throw badRequest(
              "JSON-LD keyword '@id' is not allowed with null value in canonical patch bodies " +
                "(only '@type' accepts null to remove all rdf:type triples)"
            )
          }
          if (!node.isTextual || node.asText() != resourceUriString) {
            throw badRequest(
              "@id in the merge patch must equal the request resource URI '$resourceUriString'"
            )
          }
        }
        key == "@type" -> {
          val node = patch.get(key)
          val valid = when {
            node.isNull -> true
            node.isTextual -> isCanonicalAbsoluteIri(node.asText())
            node.isArray -> node.all { it.isTextual && isCanonicalAbsoluteIri(it.asText()) }
            else -> false
          }
          if (!valid) {
            throw badRequest(
              "@type must be an absolute IRI string, an array of absolute IRI strings, or null " +
                "(compact terms like 'schema:Event' are not accepted on this endpoint)"
            )
          }
        }
        key.startsWith("@") -> throw badRequest(
          "JSON-LD keyword '$key' is not allowed in canonical patch bodies " +
            "(only '@id' and '@type' are accepted; '@context' and compact terms must be " +
            "expanded to absolute IRIs by the client)"
        )
        else -> {
          if (!isCanonicalAbsoluteIri(key)) {
            throw badRequest(
              "predicate key '$key' is not an absolute IRI — canonical JSON-LD requires " +
                "fully-qualified predicate URIs (e.g. 'https://schema.org/name'); '@context' " +
                "and compact terms like 'schema:name' are not accepted on this endpoint"
            )
          }
        }
      }
    }
    return patch
  }

  /**
   * Strict RFC 7396 merge-patch application: object fields are merged recursively, `null`
   * deletes the field, arrays and other JSON values replace. No JSON-LD-specific logic — the
   * caller pre-validated the patch shape so both target and patch use absolute-IRI keys.
   */
  private fun applyVanillaJsonMergePatch(target: JsonNode?, patch: JsonNode): JsonNode {
    if (!patch.isObject) return patch.deepCopy()
    val targetObject = (target as? ObjectNode)?.deepCopy() ?: objectMapper.createObjectNode()
    val fieldNames = patch.fieldNames()
    while (fieldNames.hasNext()) {
      val field = fieldNames.next()
      val patchValue = patch.get(field) ?: continue
      if (patchValue.isNull) {
        targetObject.remove(field)
      } else {
        val mergedValue = applyVanillaJsonMergePatch(targetObject.get(field), patchValue)
        targetObject.set(field, mergedValue)
      }
    }
    return targetObject
  }

  private fun badRequest(message: String): WebApplicationException =
    WebApplicationException(
      Response.status(400).entity(message).type(MediaType.TEXT_PLAIN).build()
    )

  /**
   * A canonical absolute IRI — strict enough to reject JSON-LD compact terms like
   * `schema:name` while still accepting opaque-form absolutes like `urn:isbn:...` and
   * `did:web:...`.
   *
   * `URI.isAbsolute` alone is too lax: `schema:name` parses as URI with `scheme="schema"` and
   * `isAbsolute=true`, which would silently allow compact patches to slip through.
   *
   * Rule:
   * - hierarchical schemes (anything with `//` right after the colon) → accept; the `//`
   *   marks an authority component that compact terms never carry
   * - opaque schemes → accept only a small allowlist of LOD-relevant schemes that legitimately
   *   appear as bare IRIs (URN, DID, mailto, tag, news, data). Other opaque schemes are
   *   indistinguishable from compact terms at the syntax level and must therefore be rejected
   *   on this endpoint
   */
  private fun isCanonicalAbsoluteIri(key: String): Boolean {
    val parsed = try { URI(key) } catch (_: Exception) { return false }
    if (!parsed.isAbsolute) return false
    val ssp = parsed.rawSchemeSpecificPart ?: return false
    if (ssp.startsWith("//")) return true
    return parsed.scheme.lowercase() in ABSOLUTE_OPAQUE_IRI_SCHEMES
  }

  companion object {
    private val objectMapper = JsonMappers.default()
    private val ABSOLUTE_OPAQUE_IRI_SCHEMES = setOf("urn", "did", "mailto", "tag", "news", "data")
  }
}
