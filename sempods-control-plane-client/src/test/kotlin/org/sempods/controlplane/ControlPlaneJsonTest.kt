package org.sempods.controlplane

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

/** The three documents the admin routes exchange, read and written without a server in the way. */
class ControlPlaneJsonTest {

  @Test
  fun `a pod is created for the owner the caller names`() {
    assertEquals("""{"ownerEmail":"alice@example.com"}""", ControlPlaneJson.podOwner("alice@example.com"))
  }

  @Test
  fun `holding nothing travels as an explicit null`() {
    assertEquals("""{"expectedRegistrationId":null}""", ControlPlaneJson.provisionRequest(null))
    assertEquals("""{"expectedRegistrationId":"r1"}""", ControlPlaneJson.provisionRequest("r1"))
  }

  @Test
  fun `a minted registration is read with its secret`() {
    val result = ControlPlaneJson.provisioned(
      """
      {
        "result": "provisioned",
        "clientId": "notes-app",
        "registrationId": "r1",
        "scopes": ["https://pods.example/alice/_system/contexts/apps/notes#manage"],
        "contextRoot": "https://pods.example/alice/_system/contexts/apps/notes",
        "secret": "sc_secret",
        "extra": 1
      }
      """.trimIndent(),
      fallbackClientId = "notes-app",
    )

    assertFalse(result.alreadyProvisioned)
    assertEquals("r1", result.registrationId)
    assertEquals("sc_secret", result.secret)
    assertEquals(URI("https://pods.example/alice/_system/contexts/apps/notes"), result.contextRoot)
    assertEquals(setOf("https://pods.example/alice/_system/contexts/apps/notes#manage"), result.scopes)
  }

  @Test
  fun `an already provisioned registration carries no secret`() {
    val result = ControlPlaneJson.provisioned(complete(without = "secret"), fallbackClientId = "notes-app")

    assertTrue(result.alreadyProvisioned)
    assertNull(result.secret)
  }

  @Test
  fun `a clientId the answer leaves out is the one the caller asked for`() {
    assertEquals(
      "notes-app",
      ControlPlaneJson.provisioned(complete(without = "clientId"), fallbackClientId = "notes-app").clientId,
    )
  }

  @Test
  fun `each missing contract field is named rather than passing silently`() {
    listOf("result", "registrationId", "contextRoot", "scopes").forEach { missing ->
      val refused = assertThrows<IllegalArgumentException>("missing '$missing' must not pass silently") {
        ControlPlaneJson.provisioned(complete(without = missing), fallbackClientId = "notes-app")
      }
      assertTrue(refused.message!!.contains(missing), "for missing '$missing': ${refused.message}")
    }
  }

  @Test
  fun `an answer that is not the route's document is refused`() {
    listOf(
      """["notes-app"]""",
      "not json at all",
      complete().replace("\"r1\"", "1"),
      complete().replace("""["s"]""", "\"s\""),
      complete().replace(""""contextRoot":"https://pods.example/a"""", """"contextRoot":":"""),
    ).forEach { body ->
      assertThrows<IllegalArgumentException>(body) {
        ControlPlaneJson.provisioned(body, fallbackClientId = "notes-app")
      }
    }
  }

  /** The complete answer, minus one member — `null` keeps all of them. */
  private fun complete(without: String? = null): String =
    mapOf(
      "result" to "\"alreadyProvisioned\"",
      "clientId" to "\"notes-app\"",
      "registrationId" to "\"r1\"",
      "scopes" to """["s"]""",
      "contextRoot" to "\"https://pods.example/a\"",
      "secret" to "\"sc_secret\"",
    ).filterKeys { it != without }
      .entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
}
