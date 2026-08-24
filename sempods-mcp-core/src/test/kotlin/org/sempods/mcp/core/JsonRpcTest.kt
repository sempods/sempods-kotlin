package org.sempods.mcp.core

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonRpcTest {

  @Test
  fun `a missing id is a notification, in both surfaces' representations`() {
    // The pod deserializes into JsonRpcRequest, where a JSON null becomes a Kotlin null...
    assertTrue(isNotification(null))
    // ...the hosted service reads the raw tree, where it stays a NullNode.
    assertTrue(isNotification(JsonNodeFactory.instance.nullNode()))
  }

  @Test
  fun `an id makes it a request, whatever its JSON type`() {
    assertFalse(isNotification(1))
    assertFalse(isNotification("abc"))
    assertFalse(isNotification(JsonNodeFactory.instance.numberNode(1)))
    assertFalse(isNotification(JsonNodeFactory.instance.textNode("abc")))
  }

  @Test
  fun `negotiate answers a supported version with itself`() {
    McpProtocol.SUPPORTED.forEach { assertEquals(it, McpProtocol.negotiate(it)) }
  }

  @Test
  fun `negotiate falls back to the latest version for absent or unknown ones`() {
    assertEquals(McpProtocol.LATEST, McpProtocol.negotiate(null))
    assertEquals(McpProtocol.LATEST, McpProtocol.negotiate(""))
    assertEquals(McpProtocol.LATEST, McpProtocol.negotiate("1999-01-01"))
  }

  @Test
  fun `the latest supported version is the first entry`() {
    // Both surfaces relied on `SUPPORTED.first()` and on a separate LATEST constant respectively,
    // and they agreed. This pins that they still do.
    assertEquals("2025-11-25", McpProtocol.LATEST)
    assertEquals(McpProtocol.SUPPORTED.first(), McpProtocol.LATEST)
  }

  @Test
  fun `the error codes keep the numbers both surfaces already answer with`() {
    assertEquals(-32600, JsonRpcErrorCodes.INVALID_REQUEST)
    assertEquals(-32601, JsonRpcErrorCodes.METHOD_NOT_FOUND)
    assertEquals(-32602, JsonRpcErrorCodes.INVALID_PARAMS)
    assertEquals(-32603, JsonRpcErrorCodes.INTERNAL_ERROR)
    assertEquals(-32001, JsonRpcErrorCodes.UNAUTHORIZED)
    assertEquals(-32002, JsonRpcErrorCodes.RESOURCE_NOT_FOUND)
    assertEquals(-32000, JsonRpcErrorCodes.SERVER_ERROR)
  }
}

class JsonRpcSerializationTest {

  private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

  @Test
  fun `a Kotlin null omits the id, a NullNode emits it`() {
    // Both forms are in use and neither is a mistake: the pod answers a rejected notification POST
    // with no `id` at all (MCP transport), the hosted service answers an unreadable request with
    // `"id": null` (JSON-RPC 2.0 5). The value carries the difference, so neither surface can lose
    // its form to an inclusion annotation on the other's behalf.
    assertEquals(
      """{"jsonrpc":"2.0","error":{"code":-32002,"message":"Unknown pod"}}""",
      mapper.writeValueAsString(
        JsonRpcErrorResponse("2.0", null, JsonRpcError(JsonRpcErrorCodes.RESOURCE_NOT_FOUND, "Unknown pod")),
      ),
    )
    assertEquals(
      """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Invalid Request"}}""",
      mapper.writeValueAsString(
        JsonRpcErrorResponse(
          "2.0",
          com.fasterxml.jackson.databind.node.NullNode.getInstance(),
          JsonRpcError(JsonRpcErrorCodes.INVALID_REQUEST, "Invalid Request"),
        ),
      ),
    )
  }

  @Test
  fun `an absent error data field is omitted`() {
    assertEquals(
      """{"code":-32602,"message":"nope"}""",
      mapper.writeValueAsString(JsonRpcError(JsonRpcErrorCodes.INVALID_PARAMS, "nope")),
    )
  }

  @Test
  fun `an empty tools capability serializes as an empty object`() {
    // The hosted service announces `{"tools":{}}`; the pod announces `{"tools":{"listChanged":true}}`.
    // Same type, and the difference is one argument — which is why it is pinned on both sides.
    assertEquals(
      """{"tools":{}}""",
      mapper.writeValueAsString(Capabilities(ToolsCapability())),
    )
    assertEquals(
      """{"tools":{"listChanged":true}}""",
      mapper.writeValueAsString(Capabilities(ToolsCapability(listChanged = true))),
    )
  }
}
