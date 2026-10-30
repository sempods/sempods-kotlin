package org.sempods.controlplane

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

/** The documents the admin routes exchange, read and written without a server in the way. */
class ControlPlaneJsonTest {

  @Test
  fun `a pod is created for the owner the caller names`() {
    assertEquals("""{"ownerEmail":"alice@example.com"}""", ControlPlaneJson.podOwnerEmail("alice@example.com"))
    assertEquals(
      """{"ownerWebId":"https://id.example/e/abc"}""",
      ControlPlaneJson.podOwnerWebId(URI("https://id.example/e/abc")),
    )
  }

  @Test
  fun `a pod is read with its owner`() {
    val pod = ControlPlaneJson.hostedPod("""{"pod":"alice","exists":true,"owner":"https://id.example/e/abc","extra":1}""")

    assertEquals(OwnedPod(pod = "alice", displayName = null, owner = "https://id.example/e/abc"), pod)
  }

  @Test
  fun `a pod answer without its name or owner is refused`() {
    listOf(
      """{"exists":true,"owner":"https://id.example/e/abc"}""" to "pod",
      """{"pod":"alice","exists":true}""" to "owner",
      """{"pod":"alice","exists":true,"owner":1}""" to "owner",
    ).forEach { (body, member) ->
      val refused = assertThrows<IllegalArgumentException>(body) { ControlPlaneJson.hostedPod(body) }
      assertTrue(refused.message!!.contains(member), "for $body: ${refused.message}")
    }
    assertThrows<IllegalArgumentException> { ControlPlaneJson.hostedPod("[]") }
  }

  @Test
  fun `holding nothing travels as an explicit null`() {
    assertEquals("""{"expectedRegistrationId":null,"expectedSecretId":null}""", ControlPlaneJson.provisionRequest(null, null))
    assertEquals("""{"expectedRegistrationId":"r1","expectedSecretId":"s1"}""", ControlPlaneJson.provisionRequest("r1", "s1"))
  }

  @Test
  fun `a minted registration is read with its secret`() {
    val result = ControlPlaneJson.provisioned(
      """
      {
        "result": "provisioned",
        "clientId": "notes-app",
        "registrationId": "r1",
        "secretId": "s1",
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
    assertEquals("s1", result.secretId)
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
    listOf("result", "registrationId", "secretId", "contextRoot", "scopes").forEach { missing ->
      val refused = assertThrows<IllegalArgumentException>("missing '$missing' must not pass silently") {
        ControlPlaneJson.provisioned(complete(without = missing), fallbackClientId = "notes-app")
      }
      assertTrue(refused.message!!.contains(missing), "for missing '$missing': ${refused.message}")
    }
  }

  @Test
  fun `a result the route does not answer is refused rather than read as a fresh provisioning`() {
    listOf("\"provisoned\"", "\"pending\"", "\"\"", "1").forEach { value ->
      val refused = assertThrows<IllegalArgumentException>("result $value must not pass") {
        ControlPlaneJson.provisioned(complete().replace("\"alreadyProvisioned\"", value), fallbackClientId = "notes-app")
      }
      assertTrue(refused.message!!.contains("result"), "for result $value: ${refused.message}")
    }
  }

  @Test
  fun `a fresh provisioning without the secret it issued is refused`() {
    val provisioned = complete(without = "secret").replace("\"alreadyProvisioned\"", "\"provisioned\"")

    val refused = assertThrows<IllegalArgumentException> {
      ControlPlaneJson.provisioned(provisioned, fallbackClientId = "notes-app")
    }

    assertTrue(refused.message!!.contains("secret"), refused.message!!)
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

  @Test
  fun `an owner's pods are read with their label and the owner each is stored under`() {
    val pods = ControlPlaneJson.ownedPods(
      """
      {
        "pods": [
          {"pod": "alice", "displayName": "Alice", "owner": "https://id.example/e/1", "extra": 1},
          {"pod": "notes", "owner": "https://id.example/e/2"}
        ]
      }
      """.trimIndent(),
    )

    assertEquals(
      listOf(
        OwnedPod(pod = "alice", displayName = "Alice", owner = "https://id.example/e/1"),
        OwnedPod(pod = "notes", displayName = null, owner = "https://id.example/e/2"),
      ),
      pods,
    )
    assertEquals(emptyList<OwnedPod>(), ControlPlaneJson.ownedPods("""{"pods":[]}"""))
  }

  @Test
  fun `an owner listing that is not the route's document is refused`() {
    listOf(
      "{}" to "pods",
      """{"pods":{}}""" to "pods",
      """{"pods":["alice"]}""" to "pods",
      """{"pods":[{"owner":"https://id.example/e/1"}]}""" to "pod",
      """{"pods":[{"pod":"alice"}]}""" to "owner",
      "[]" to "JSON object",
    ).forEach { (body, named) ->
      val refused = assertThrows<IllegalArgumentException>(body) { ControlPlaneJson.ownedPods(body) }
      assertTrue(refused.message!!.contains(named), "for $body: ${refused.message}")
    }
  }

  /** The complete answer, minus one member — `null` keeps all of them. */
  private fun complete(without: String? = null): String =
    mapOf(
      "result" to "\"alreadyProvisioned\"",
      "clientId" to "\"notes-app\"",
      "registrationId" to "\"r1\"",
      "secretId" to "\"s1\"",
      "scopes" to """["s"]""",
      "contextRoot" to "\"https://pods.example/a\"",
      "secret" to "\"sc_secret\"",
    ).filterKeys { it != without }
      .entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
}
