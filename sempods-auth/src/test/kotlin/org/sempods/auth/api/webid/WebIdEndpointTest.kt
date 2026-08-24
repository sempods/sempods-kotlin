package org.sempods.auth.api.webid

import org.sempods.auth.SempodsAuthIntegrationTest
import org.sempods.auth.persist.WebIdNamespace
import org.sempods.auth.persist.WebIdProfile
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.test.assertContains
import kotlin.test.assertEquals

class WebIdEndpointTest : SempodsAuthIntegrationTest() {

  // --- EMAIL namespace ---

  @Test
  fun `email-namespace profile is served as Turtle`() = withApp {
    val hash = uniqueHash()
    webIdProfileDao.insert(emailProfile(hash, displayName = "Alice"))

    val response = client.get("/e/$hash") { header("Accept", "text/turtle") }

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertContains(body, "foaf:Person")
    assertContains(body, "${testConfig.idBaseUrl}/e/$hash")
    assertContains(body, "Alice")
  }

  @Test
  fun `email-namespace profile is served as JSON-LD`() = withApp {
    val hash = uniqueHash()
    webIdProfileDao.insert(emailProfile(hash, displayName = "Alice"))

    val response = client.get("/e/$hash") { header("Accept", "application/ld+json") }

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertContains(body, "@id")
    assertContains(body, "foaf:Person")
    assertContains(body, "Alice")
  }

  @Test
  fun `email-namespace profile is served as HTML by default`() = withApp {
    val hash = uniqueHash()
    webIdProfileDao.insert(emailProfile(hash, displayName = "Alice"))

    val response = client.get("/e/$hash")

    assertEquals(HttpStatusCode.OK, response.status)
    assertContains(response.bodyAsText(), "Alice")
  }

  @Test
  fun `email-namespace profile with linkedIdentities includes authLookupKey in Turtle`() = withApp {
    val hash = uniqueHash()
    val linked = "${testConfig.idBaseUrl}/oidc/${uniqueHash()}"
    webIdProfileDao.insert(emailProfile(hash, displayName = "Alice", linkedIdentities = listOf(linked)))

    val response = client.get("/e/$hash") { header("Accept", "text/turtle") }

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertContains(body, "sempods:authLookupKey")
    assertContains(body, linked)
  }

  @Test
  fun `email-namespace unknown hash returns 404`() = withApp {
    val response = client.get("/e/${uniqueHash()}")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  // --- OIDC namespace ---

  @Test
  fun `oidc-namespace profile is served as Turtle`() = withApp {
    val hash = uniqueHash()
    webIdProfileDao.insert(oidcProfile(hash, displayName = "Bob"))

    val response = client.get("/oidc/$hash") { header("Accept", "text/turtle") }

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertContains(body, "foaf:Person")
    assertContains(body, "${testConfig.idBaseUrl}/oidc/$hash")
    assertContains(body, "Bob")
  }

  @Test
  fun `oidc-namespace unknown hash returns 404`() = withApp {
    val response = client.get("/oidc/${uniqueHash()}")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  // --- email address must not appear in WebID document ---

  @Test
  fun `email address is not exposed in Turtle output`() = withApp {
    val hash = uniqueHash()
    webIdProfileDao.insert(emailProfile(hash, displayName = "Alice"))

    val response = client.get("/e/$hash") { header("Accept", "text/turtle") }

    val body = response.bodyAsText()
    assertContains(body, "foaf:Person")
    assertEquals(false, body.contains(Regex("""\w@\w""")), "email address must not appear in Turtle output")
  }

  // --- helpers ---

  private fun emailProfile(
    hash: String,
    displayName: String,
    linkedIdentities: List<String> = emptyList(),
  ) = WebIdProfile(
    id = "${testConfig.idBaseUrl}/e/$hash",
    namespace = WebIdNamespace.EMAIL,
    displayName = displayName,
    createdAt = Date(),
    linkedIdentities = linkedIdentities,
  )

  private fun oidcProfile(
    hash: String,
    displayName: String,
  ) = WebIdProfile(
    id = "${testConfig.idBaseUrl}/oidc/$hash",
    namespace = WebIdNamespace.OIDC,
    displayName = displayName,
    createdAt = Date(),
  )
}
