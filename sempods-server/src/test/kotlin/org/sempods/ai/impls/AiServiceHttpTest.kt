package org.sempods.ai.impls

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.sempods.ai.AiChatMessage
import org.sempods.ai.AiChatRole
import org.sempods.ai.AiServiceException
import org.sempods.ai.AiStructuredOutputRequest
import org.sempods.ai.impls.ollama.OllamaAiService
import org.sempods.ai.impls.openai.OpenAiService
import org.sempods.commons.json.JsonMappers
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What the two provider clients put on the wire, and what they make of what comes back.
 *
 * One class for both because the assertions are the same shape and the providers differ only in
 * the envelope: a happy answer parsed, a refusal turned into an [AiServiceException] that still
 * says what the provider said, and assistant content that is not the JSON the schema asked for.
 *
 * Against a loopback `com.sun.net.httpserver.HttpServer` — in the JDK, so no dependency, and local,
 * so no test here reaches a model provider. Both services are constructed directly rather than
 * through the injector: `SempodsTestModule` binds `AiService` to a test double, which is the right
 * seam for everything *above* these two classes and no help at all for the HTTP inside them.
 */
class AiServiceHttpTest {

  private lateinit var server: HttpServer
  private var status = 200
  private var responseBody = ""
  private var receivedBody: String? = null
  private var receivedAuthorization: String? = null

  private val schema = JsonMappers.default().readTree("""{"type":"object"}""")

  private val request = AiStructuredOutputRequest(
    messages = listOf(AiChatMessage(AiChatRole.user, "  what is a pod?  ")),
    jsonSchema = schema,
  )

  @BeforeEach
  fun startServer() {
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/") { exchange ->
      receivedBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
      receivedAuthorization = exchange.requestHeaders.getFirst("Authorization")
      val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
      exchange.close()
    }
    server.start()
  }

  @AfterEach
  fun stopServer() = server.stop(0)

  // ─── openai ────────────────────────────────────────────────────────────────

  @Test
  fun `openai sends the model, the trimmed message and the bearer`() {
    responseBody = openAiAnswer("""{\"answer\":\"a place for data\"}""")

    val answer = openAi().generateStructuredOutput(request)

    val sent = JsonMappers.default().readTree(checkNotNull(receivedBody))
    assertEquals("gpt-test", sent.path("model").asText())
    assertEquals("what is a pod?", sent.path("messages").first().path("content").asText())
    assertEquals("Bearer a-test-key", receivedAuthorization)
    assertEquals("a place for data", answer.json.path("answer").asText())
    assertEquals("stop", answer.doneReason)
  }

  @Test
  fun `openai reports the status and what the provider said`() {
    status = 429
    responseBody = """{"error": {"message": "rate limit reached"}}"""

    val failure = assertFailsWith<AiServiceException> { openAi().generateStructuredOutput(request) }

    assertContains(failure.message.orEmpty(), "(429)")
    assertContains(failure.message.orEmpty(), "rate limit reached", message = "the provider's own reason travels with the failure")
  }

  @Test
  fun `openai refuses assistant content that is not json`() {
    responseBody = openAiAnswer("not json at all")

    assertContains(
      assertFailsWith<AiServiceException> { openAi().generateStructuredOutput(request) }.message.orEmpty(),
      "not valid json",
    )
  }

  // ─── ollama ────────────────────────────────────────────────────────────────

  @Test
  fun `ollama sends the schema as the format and asks for a single answer`() {
    responseBody = """{"model":"llama-test","message":{"role":"assistant","content":"{\"answer\":\"yes\"}"}}"""

    val answer = ollama().generateStructuredOutput(request)

    val sent = JsonMappers.default().readTree(checkNotNull(receivedBody))
    assertEquals("llama-test", sent.path("model").asText())
    assertEquals(schema, sent.path("format"))
    assertEquals(false, sent.path("stream").asBoolean())
    assertEquals("yes", answer.json.path("answer").asText())
  }

  @Test
  fun `ollama reports the status of a refusal`() {
    status = 500
    responseBody = "the model is not loaded"

    assertContains(
      assertFailsWith<AiServiceException> { ollama().generateStructuredOutput(request) }.message.orEmpty(),
      "(500)",
    )
  }

  private fun openAi() = OpenAiService(OkHttpClient(), url(), "a-test-key", "gpt-test")

  private fun ollama() = OllamaAiService(OkHttpClient(), url(), "llama-test")

  private fun url() = "http://${server.address.hostString}:${server.address.port}"

  private fun openAiAnswer(content: String) =
    """{"model":"gpt-test","choices":[{"finish_reason":"stop","message":{"content":"$content"}}]}"""
}
