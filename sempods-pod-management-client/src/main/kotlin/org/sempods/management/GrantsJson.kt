package org.sempods.management

import org.sempods.client.SempodsPermission
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The answers of `{pod}/_system/auth/grants`, read into this module's types.
 *
 * A body that is not the document the route promises fails here with the member it lacks, and the
 * failure carries no part of the body: [org.sempods.client.SempodsResponse.map] turns what this
 * throws into a decoding failure with the answer's status and headers.
 */
internal object GrantsJson {

  private val mapper = JsonMapper.builder().build()

  /** The answer of `GET {pod}/_system/auth/grants`. */
  fun counts(json: String): List<SempodsContextHolderCounts> = objects(objectOf(json), "contexts").map { entry ->
    SempodsContextHolderCounts.of(
      contextIri = text(entry, "context_iri"),
      people = count(entry, "people"),
      apps = count(entry, "apps"),
      services = count(entry, "services"),
    )
  }

  /** The answer of `GET {pod}/_system/auth/grants/{context}`. */
  fun holders(json: String): SempodsContextHolders {
    val root = objectOf(json)
    return SempodsContextHolders.of(
      contextIri = text(root, "context_iri"),
      people = objects(root, "people").map { person ->
        SempodsGrantPerson.of(
          webId = text(person, "web_id"),
          permissions = permissions(person),
          grants = grants(person),
          delegations = objects(person, "delegations").map(::app),
        )
      },
      apps = objects(root, "apps").map(::app),
      services = objects(root, "services").map { service ->
        SempodsGrantService.of(
          clientId = text(service, "client_id"),
          clientName = optionalText(service, "client_name"),
          permissions = permissions(service),
          grants = grants(service),
          lastUsedAt = instant(service, "last_used_at"),
          grantsVersion = service["grants_version"]?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
            ?: throw IllegalArgumentException("the answer has no integer member 'grants_version'"),
        )
      },
    )
  }

  private fun app(document: ObjectNode): SempodsGrantApp = SempodsGrantApp.of(
    clientId = text(document, "client_id"),
    clientName = optionalText(document, "client_name"),
    permissions = permissions(document),
    grants = grants(document),
    lastUsedAt = instant(document, "last_used_at"),
  )

  private fun grants(document: ObjectNode): List<SempodsReachingGrant> = objects(document, "grants").map {
    SempodsReachingGrant.of(permission = permission(text(it, "permission")), inheritedFrom = optionalText(it, "inherited_from"))
  }

  private fun permissions(document: ObjectNode): Set<SempodsPermission> =
    strings(document, "permissions").mapTo(mutableSetOf(), ::permission)

  /**
   * A permission this client knows. An unknown one fails the whole answer rather than being dropped:
   * a holder shown with less than it can do would mislead the owner, and the routes are the
   * reference implementation's, which ships in step with this module.
   */
  private fun permission(value: String): SempodsPermission =
    SempodsPermission.of(value) ?: throw IllegalArgumentException("the answer names a permission this client does not know")

  private fun count(document: ObjectNode, member: String): Int =
    document[member]?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()?.takeIf { it >= 0 }
      ?: throw IllegalArgumentException("the answer has no count member '$member'")

  /** An RFC 3339 time; absent or null where the pod records none. */
  private fun instant(document: ObjectNode, member: String): Instant? = optionalText(document, member)?.let {
    try {
      Instant.parse(it)
    } catch (_: DateTimeParseException) {
      throw IllegalArgumentException("the answer's '$member' is not an RFC 3339 time")
    }
  }

  private fun objectOf(json: String): ObjectNode {
    val root = try {
      mapper.readTree(json)
    } catch (_: JacksonException) {
      throw IllegalArgumentException("the answer is not one JSON object")
    }
    return root as? ObjectNode ?: throw IllegalArgumentException("the answer is not one JSON object")
  }

  private fun objects(document: ObjectNode, member: String): List<ObjectNode> {
    val values = document[member]
    require(values != null && values.isArray) { "the answer has no array member '$member'" }
    return (0 until values.size()).map {
      values.get(it) as? ObjectNode ?: throw IllegalArgumentException("the answer's '$member' holds a value that is not an object")
    }
  }

  /** A member the route guarantees. A missing one is a broken contract rather than an empty value. */
  private fun text(document: ObjectNode, member: String): String =
    optionalText(document, member) ?: throw IllegalArgumentException("the answer has no string member '$member'")

  private fun optionalText(document: ObjectNode, member: String): String? {
    val value: JsonNode = document[member]?.takeUnless { it.isNull } ?: return null
    require(value.isString) { "the answer's '$member' is not a string" }
    return value.stringValue()
  }

  private fun strings(document: ObjectNode, member: String): List<String> {
    val values = document[member]
    require(values != null && values.isArray) { "the answer has no array member '$member'" }
    return (0 until values.size()).map {
      val value = values.get(it)
      require(value != null && value.isString) { "the answer's '$member' holds a value that is not a string" }
      value.stringValue()
    }
  }
}
