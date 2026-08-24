package org.sempods.api.pod.system.ai.semweb

import com.fasterxml.jackson.databind.JsonNode
import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.ai.AiServiceTestObserver
import org.sempods.commons.tests.TestUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PodAiSemWebEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var aiServiceTestObserver: AiServiceTestObserver

  private val httpClient by lazy { http.followingRedirects }

  private fun text2modelUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}$podName/_system/ai/semweb/text2model"

  private fun model2modelUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}$podName/_system/ai/semweb/model2model"

  @Test
  fun `POST text2model should return 401 without bearer token`() {
    val pod = sempodsTestFactory.newPod()
    val response = httpClient.preparePost(text2modelUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(buildText2ModelPayload("Morgen Auto zur Reparatur bringen"))
      .execute()

    assertEquals(401, response.statusCode)
    assertTrue(response.responseBody.contains("missing or invalid app bearer token"))
  }

  @Test
  @Disabled("TODO: Rewrite for WebID/JWT authorize flow")
  fun `POST text2model should return 403 for token issued for different pod`() {
    // TODO: Rewrite for WebID/JWT authorize flow
  }

  @Test
  @Disabled("TODO: Rewrite for WebID/JWT authorize flow")
  fun `POST text2model should call ai service and return JSON-LD envelope for valid app token`() {
    // TODO: Rewrite for WebID/JWT authorize flow
  }

  @Test
  fun `POST model2model should return 401 without bearer token`() {
    val pod = sempodsTestFactory.newPod()
    val currentModel = JsonMappers.default().readTree(
      """
        {
          "@context": {"schema":"https://schema.org/"},
          "@graph": [
            {
              "@id":"https://sempods.org/${pod.name}/tasks/1",
              "@type":"schema:Action",
              "schema:name":"Auto zur Reparatur bringen",
              "schema:actionStatus":"schema:PotentialActionStatus"
            }
          ]
        }
      """.trimIndent()
    )
    val response = httpClient.preparePost(model2modelUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(buildModel2ModelPayload("erst in 3 Tagen", currentModel))
      .execute()

    assertEquals(401, response.statusCode)
    assertTrue(response.responseBody.contains("missing or invalid app bearer token"))
  }

  @Test
  @Disabled("TODO: Rewrite for WebID/JWT authorize flow")
  fun `POST model2model should call ai service and return updated model for valid app token`() {
    // TODO: Rewrite for WebID/JWT authorize flow
  }

  @Test
  @Disabled("TODO: Rewrite for WebID/JWT authorize flow")
  fun `POST text2model should derive SHACL guidance when client guidance is omitted`() {
    // TODO: Rewrite for WebID/JWT authorize flow
  }

  @Test
  @Disabled("TODO: Rewrite for WebID/JWT authorize flow")
  fun `POST model2model should return no_change when model remains unchanged and allowNoChange true`() {
    // TODO: Rewrite for WebID/JWT authorize flow
  }

  @Test
  @Disabled("TODO: Rewrite for WebID/JWT authorize flow")
  fun `POST model2model should return 422 when model remains unchanged and allowNoChange false`() {
    // TODO: Rewrite for WebID/JWT authorize flow
  }

  private fun buildText2ModelPayload(
    text: String,
    includeClientCurrentTimestampTerm: Boolean = false,
    includeGuidance: Boolean = true,
  ): String {
    val shaclData = buildActionShaclData()
    val guidanceTerms = mutableMapOf<String, Any>(
      "https://schema.org/startTime" to mapOf(
        "description" to "Planned start time as ISO-8601 date-time value",
      ),
      "https://schema.org/description" to mapOf(
        "description" to "A description of the item.",
      )
    )
    if (includeClientCurrentTimestampTerm) {
      guidanceTerms["currentTimestamp"] = "2024-01-01T00:00:00Z"
    }

    val payload = mutableMapOf<String, Any>(
      "content" to mapOf(
        "text" to text,
        "language" to "de",
      ),
      "target" to mapOf(
        "shacl" to mapOf(
          "syntax" to "text/turtle",
          "data" to shaclData,
        )
      ),
      "options" to mapOf(
        "strict" to true,
        "allowEmpty" to true,
      ),
    )
    if (includeGuidance) {
      payload["guidance"] = mapOf(
        "instructions" to "Extract one planned action when present. Include schema:startTime as ISO-8601 when inferable. Include schema:description when useful details are present.",
        "terms" to guidanceTerms,
      )
    }
    return JsonMappers.default().writeValueAsString(payload)
  }

  private fun buildModel2ModelPayload(
    instructionText: String,
    currentModel: JsonNode,
    allowNoChange: Boolean = true,
    strict: Boolean = true,
  ): String {
    val payload = mapOf(
      "instruction" to mapOf(
        "text" to instructionText,
        "language" to "de",
      ),
      "currentModel" to currentModel,
      "target" to mapOf(
        "shacl" to mapOf(
          "syntax" to "text/turtle",
          "data" to buildActionShaclData(),
        )
      ),
      "guidance" to mapOf(
        "instructions" to "Apply the instruction to the current task model. Keep untouched facts stable.",
        "terms" to mapOf(
          "https://schema.org/startTime" to mapOf(
            "description" to "Planned start time as ISO-8601 date-time value",
          ),
        ),
      ),
      "options" to mapOf(
        "strict" to strict,
        "allowNoChange" to allowNoChange,
      ),
    )
    return JsonMappers.default().writeValueAsString(payload)
  }

  private fun buildActionShaclData(): String {
    return """
      @prefix sh: <http://www.w3.org/ns/shacl#> .
      @prefix schema: <https://schema.org/> .
      @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
      <urn:shape:Action> a sh:NodeShape ;
        sh:targetClass schema:Action ;
        sh:description "Extract one planned action from short task text."@en ;
        sh:property [
          sh:path schema:name ;
          sh:datatype xsd:string ;
          sh:description "Short title of the task."@en ;
        ] ;
        sh:property [
          sh:path schema:description ;
          sh:datatype xsd:string ;
          sh:description "A description of the item."@en ;
        ] ;
        sh:property [
          sh:path schema:actionStatus ;
          sh:description "Action lifecycle status."@en ;
        ] ;
        sh:property [
          sh:path schema:startTime ;
          sh:datatype xsd:dateTime ;
          sh:description "Planned start time as ISO-8601 date-time value."@en ;
        ] .
    """.trimIndent()
  }
}
