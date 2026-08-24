package org.sempods.api.pod.system.mcp

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Pins the exact `tools/list` payload this pod answers with.
 *
 * The tool descriptions are the behavioural specification for the model on the other end, and the
 * argument schemas are what a strict MCP client validates against — so a diff here is a change in
 * behaviour, not in formatting. Nothing else in the suite covers this: `McpEndpointHttpTest` checks
 * that tool *names* appear and never looks at `inputSchema`, `required` or `additionalProperties`.
 *
 * Regenerate deliberately, never mechanically. On a mismatch the test writes what it saw to
 * `build/mcp/pod-tools-list.actual.json` and names it in the failure — read that diff before you
 * copy it over the golden. There is no update flag, because a flag would make exactly the step
 * that should be hard easy.
 *
 * Anonymous on purpose: `authorize` is the one tool whose description depends on the caller's auth
 * state, and the anonymous rendering is the stable one. Everything else is a compile-time constant.
 */
class McpToolsListSnapshotTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  private val httpClient by lazy { http.followingRedirects }
  private val objectMapper = JsonMappers.default()

  @Test
  fun `tools list payload matches the golden file`() {
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 1,
      "method" to "tools/list",
      "params" to mapOf<String, Any>(),
    )

    val response = httpClient.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/mcp")
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)

    val tools = objectMapper.readTree(response.responseBody).get("result").get("tools")
    val actual = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tools) + "\n"
    val expected = javaClass.getResource(GOLDEN)?.readText()

    if (expected != actual) {
      val dump = File("build/mcp/pod-tools-list.actual.json")
      dump.parentFile.mkdirs()
      dump.writeText(actual)
      assertEquals(
        expected, actual,
        "tools/list changed. What the pod answers now is in ${dump.absolutePath}; " +
          "the golden is src/test/resources$GOLDEN. Read the diff, then copy it over deliberately.",
      )
    }
  }

  private companion object {
    const val GOLDEN = "/mcp/pod-tools-list.json"
  }
}
