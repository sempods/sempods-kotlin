package org.sempods.controlplane

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.URISyntaxException

/**
 * The admin surface's JSON: the two documents this client sends, and the one it reads.
 *
 * A body that is not the document the route promises fails here, and the failure carries no part of
 * it: [org.sempods.client.SempodsResponse.map] turns what this throws into a decoding failure
 * with the answer's status and headers. The member name is in the message because a member the route
 * guarantees is a broken contract rather than an empty value — and it is this object's own test that
 * reads that message, since `map` deliberately keeps a decoder's message out of what it reports.
 */
internal object ControlPlaneJson {

  private val mapper = JsonMapper.builder().build()

  /** The body of `PUT {server}/_system/admin/pods/{pod}`. */
  fun podOwner(ownerEmail: String): String =
    mapper.writeValueAsString(mapper.createObjectNode().put("ownerEmail", ownerEmail))

  /**
   * The body of `POST …/service-clients/{clientId}`.
   *
   * A held nothing travels as an explicit `null` rather than as an absent member: the route reads the
   * assertion the caller makes, and an absent member and a `null` would have to mean the same thing
   * for that to be safe.
   */
  fun provisionRequest(expectedRegistrationId: String?, expectedSecretId: String?): String =
    mapper.writeValueAsString(
      mapper.createObjectNode()
        .put("expectedRegistrationId", expectedRegistrationId)
        .put("expectedSecretId", expectedSecretId),
    )

  /**
   * The answer of `POST …/service-clients/{clientId}`.
   *
   * [fallbackClientId] is what the caller asked for, and stands in for a `clientId` the answer leaves
   * out — the route echoes what was registered, and nothing else could have been.
   * Refuses the answers [SempodsControlPlaneClient.provisionServiceClient] does not accept.
   */
  fun provisioned(json: String, fallbackClientId: String): ProvisionServiceClientResult {
    val root = try {
      mapper.readTree(json)
    } catch (_: JacksonException) {
      throw IllegalArgumentException("the answer is not one JSON object")
    }
    if (root !is ObjectNode) throw IllegalArgumentException("the answer is not one JSON object")

    val result = text(root, "result")
    require(result == PROVISIONED || result == ALREADY_PROVISIONED) {
      "the answer's 'result' is neither '$PROVISIONED' nor '$ALREADY_PROVISIONED'"
    }
    val secret = optionalText(root, "secret")
    require(result == ALREADY_PROVISIONED || secret != null) { "a '$PROVISIONED' answer has no string member 'secret'" }

    return ProvisionServiceClientResult(
      alreadyProvisioned = result == ALREADY_PROVISIONED,
      clientId = optionalText(root, "clientId") ?: fallbackClientId,
      registrationId = text(root, "registrationId"),
      secretId = text(root, "secretId"),
      scopes = strings(root, "scopes"),
      contextRoot = uri(text(root, "contextRoot"), "contextRoot"),
      // absent on `alreadyProvisioned` (@JsonInclude NON_NULL) — the caller keeps what it holds
      secret = secret,
    )
  }

  private const val PROVISIONED = "provisioned"

  private const val ALREADY_PROVISIONED = "alreadyProvisioned"

  /** A member the route guarantees. A missing one is a broken contract rather than an empty value. */
  private fun text(root: ObjectNode, member: String): String =
    optionalText(root, member) ?: throw IllegalArgumentException("the answer has no string member '$member'")

  private fun optionalText(root: ObjectNode, member: String): String? =
    root[member]?.takeIf { it.isString }?.stringValue()

  private fun strings(root: ObjectNode, member: String): Set<String> {
    val values = root[member]
    require(values != null && values.isArray) { "the answer has no array member '$member'" }
    return (0 until values.size()).map { element(values.get(it), member) }.toSet()
  }

  private fun element(value: JsonNode?, member: String): String {
    require(value != null && value.isString) { "the answer's '$member' holds a value that is not a string" }
    return value.stringValue()
  }

  private fun uri(value: String, member: String): URI =
    try {
      URI(value)
    } catch (_: URISyntaxException) {
      throw IllegalArgumentException("the answer's '$member' is not a URI")
    }
}
