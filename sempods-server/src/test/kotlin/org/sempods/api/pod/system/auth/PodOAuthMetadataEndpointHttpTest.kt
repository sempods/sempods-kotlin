package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.commons.tests.TestUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodOAuthMetadataEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podDao: PodDao

  private val httpClient by lazy { http.followingRedirects }
  private val objectMapper = JsonMappers.default()

  private val apiBaseUrl get() = SempodsModule.config.apiBaseUrl.trimEnd('/')

  /** `P`: the pod's resource identifier and its issuer. */
  private fun podBaseUrl(podName: String) = "$apiBaseUrl/$podName"

  /** Where the authorization, token, registration and JWKS endpoints live — below the issuer. */
  private fun authEndpoints(podName: String) = "${podBaseUrl(podName)}/_system/auth"

  private fun resourceMetadataUrlAppend(podName: String) =
    "${podBaseUrl(podName)}/.well-known/oauth-protected-resource"

  private fun authServerMetadataUrlAppend(podName: String) =
    "${podBaseUrl(podName)}/.well-known/oauth-authorization-server"

  private fun authServerMetadataUrlStrict(podName: String) =
    "$apiBaseUrl/.well-known/oauth-authorization-server/$podName"

  private fun resourceMetadataUrlStrictPodResource(podName: String) =
    "$apiBaseUrl/.well-known/oauth-protected-resource/$podName"

  private fun resourceMetadataUrlStrictMcp(podName: String) =
    "$apiBaseUrl/.well-known/oauth-protected-resource/$podName/_system/mcp"

  private fun authServerMetadataUrlStrictMcp(podName: String) =
    "$apiBaseUrl/.well-known/oauth-authorization-server/$podName/_system/mcp"

  @Test
  fun `protected-resource metadata should return RFC 9728 body`() {
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.prepareGet(resourceMetadataUrlAppend(pod.name))
      .execute()

    assertEquals(200, response.statusCode)
    assertTrue(
      response.contentType.orEmpty().startsWith("application/json"),
      "Content-Type must be application/json, was: ${response.contentType}"
    )

    @Suppress("UNCHECKED_CAST")
    val body = objectMapper.readValue(response.responseBody, Map::class.java) as Map<String, Any?>

    assertEquals(
      podBaseUrl(pod.name),
      body["resource"],
      "resource must be the pod base URL (no trailing slash)"
    )
    assertEquals(
      listOf(podBaseUrl(pod.name)),
      body["authorization_servers"],
      "authorization_servers must name the pod itself: it is its own issuer (SPS-AUTH-065)",
    )
    assertEquals(
      listOf("header"),
      body["bearer_methods_supported"],
      "bearer_methods_supported must declare 'header'"
    )

    // The whole of what a client may put in `scope`. It is short because context permissions are
    // grants rather than scopes, and `offline_access` is on it because a client that reads only
    // this document has no other way to learn the extension exists. `openid` is not: no `id_token`.
    assertEquals(
      listOf("public-read", "service-clients:install", "service-clients:manage", "contexts:manage", "offline_access"),
      body["scopes_supported"],
      "scopes_supported must name the feature scopes and the refresh-token extension",
    )

    // R5: public_contexts is the count of the pod's public contexts, read from the registry.
    // `SempodsTestFactory.newPod` registers one, so the count is > 0 for any pod created here.
    val publicContexts = body["public_contexts"]
    assertTrue(publicContexts is Number, "public_contexts must be a number, was $publicContexts")
    assertTrue((publicContexts as Number).toInt() > 0, "expected at least one public context, was $publicContexts")

    // R5: name is optional — only present if the pod has a configured display name.
    // Test pods don't set one, so the field must be absent here.
    assertNull(body["name"], "name must be absent when no displayName is configured")
  }

  @Test
  fun `protected-resource metadata exposes pod displayName when configured for R5`() {
    // R5: a pod with a non-blank displayName has it surfaced as `name` in the PRM
    // body, so SDK consumers can render `PodConnection.displayName` without
    // falling back to the hostname.
    val pod = sempodsTestFactory.newPod()
    val display = "Alice Test-Pod ${TestUtil.randomId()}"
    podDao.setDisplayName(checkNotNull(pod.id), display)

    val response = httpClient.prepareGet(resourceMetadataUrlAppend(pod.name))
      .execute()

    assertEquals(200, response.statusCode)
    @Suppress("UNCHECKED_CAST")
    val body = objectMapper.readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    assertEquals(display, body["name"], "name must reflect the configured displayName")
  }

  @Test
  fun `protected-resource metadata should return 404 for unknown pod`() {
    val unknownPod = "nonexistent-${TestUtil.randomId()}"

    val response = httpClient.prepareGet(resourceMetadataUrlAppend(unknownPod))
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `authorization-server metadata should return RFC 8414 body`() {
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.prepareGet(authServerMetadataUrlAppend(pod.name))
      .execute()

    assertEquals(200, response.statusCode)
    assertTrue(
      response.contentType.orEmpty().startsWith("application/json"),
      "Content-Type must be application/json, was: ${response.contentType}"
    )

    @Suppress("UNCHECKED_CAST")
    val body = objectMapper.readValue(response.responseBody, Map::class.java) as Map<String, Any?>

    val authBase = authEndpoints(pod.name)

    assertEquals(podBaseUrl(pod.name), body["issuer"], "issuer must be the pod base URL (SPS-AUTH-066)")
    assertEquals("$authBase/authorize", body["authorization_endpoint"])
    assertEquals("$authBase/token", body["token_endpoint"])
    assertEquals("$authBase/register", body["registration_endpoint"])
    assertEquals("$authBase/jwks.json", body["jwks_uri"])
    assertEquals(listOf("code"), body["response_types_supported"])
    assertEquals(
      listOf("authorization_code", "refresh_token", "client_credentials"),
      body["grant_types_supported"],
    )
    assertEquals(listOf("S256"), body["code_challenge_methods_supported"])
    assertEquals(
      listOf("none", "client_secret_basic"),
      body["token_endpoint_auth_methods_supported"],
    )
    assertEquals(
      listOf("public-read", "service-clients:install", "service-clients:manage", "contexts:manage", "offline_access"),
      body["scopes_supported"],
      "the AS metadata must name the same scope set as the protected-resource metadata",
    )
  }

  @Test
  fun `authorization-server metadata should return 404 for unknown pod`() {
    val unknownPod = "nonexistent-${TestUtil.randomId()}"

    val response = httpClient.prepareGet(authServerMetadataUrlAppend(unknownPod))
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `RFC-strict protected-resource path for pod resource should return PRM body`() {
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.prepareGet(resourceMetadataUrlStrictPodResource(pod.name))
      .execute()

    assertEquals(200, response.statusCode)

    @Suppress("UNCHECKED_CAST")
    val body = objectMapper.readValue(response.responseBody, Map::class.java) as Map<String, Any?>

    assertEquals(podBaseUrl(pod.name), body["resource"])
    assertEquals(listOf(podBaseUrl(pod.name)), body["authorization_servers"])
  }

  @Test
  fun `RFC-strict protected-resource path body must equal append-style body`() {
    val pod = sempodsTestFactory.newPod()

    val strictResponse = httpClient.prepareGet(resourceMetadataUrlStrictPodResource(pod.name))
      .execute()
    val appendResponse = httpClient.prepareGet(resourceMetadataUrlAppend(pod.name))
      .execute()

    assertEquals(200, strictResponse.statusCode)
    assertEquals(200, appendResponse.statusCode)

    @Suppress("UNCHECKED_CAST")
    val strictBody = objectMapper.readValue(strictResponse.responseBody, Map::class.java) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val appendBody = objectMapper.readValue(appendResponse.responseBody, Map::class.java) as Map<String, Any?>

    assertEquals(
      appendBody,
      strictBody,
      "RFC-9728-strict host-rooted PRM and append-style PRM must serve identical metadata bodies",
    )
  }

  @Test
  fun `RFC-strict protected-resource path should return 404 for unknown pod`() {
    val unknownPod = "nonexistent-${TestUtil.randomId()}"

    val response = httpClient.prepareGet(resourceMetadataUrlStrictPodResource(unknownPod))
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `RFC-strict authorization-server path for the pod issuer serves the append-style body`() {
    val pod = sempodsTestFactory.newPod()

    val strict = httpClient.prepareGet(authServerMetadataUrlStrict(pod.name)).execute()
    val append = httpClient.prepareGet(authServerMetadataUrlAppend(pod.name)).execute()

    assertEquals(200, strict.statusCode)
    @Suppress("UNCHECKED_CAST")
    val body = objectMapper.readValue(strict.responseBody, Map::class.java) as Map<String, Any?>
    assertEquals(podBaseUrl(pod.name), body["issuer"])
    assertEquals("${authEndpoints(pod.name)}/token", body["token_endpoint"])
    assertEquals(
      objectMapper.readValue(append.responseBody, Map::class.java),
      body,
      "both addresses must return the same pod's metadata (SPS-AUTH-067)",
    )
  }

  @Test
  fun `RFC-strict authorization-server path should return 404 for unknown pod`() {
    val unknownPod = "nonexistent-${TestUtil.randomId()}"

    val response = httpClient.prepareGet(authServerMetadataUrlStrict(unknownPod))
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `no authorization-server metadata is served for the auth routes as an issuer`() {
    // Both addresses RFC 8414 derives from `P/_system/auth`. A document there would name issuer
    // `P`, which RFC 8414 §3.3 makes a client discard; the pod is the issuer (SPS-AUTH-066).
    val pod = sempodsTestFactory.newPod()

    listOf(
      "${authEndpoints(pod.name)}/.well-known/oauth-authorization-server",
      "$apiBaseUrl/.well-known/oauth-authorization-server/${pod.name}/_system/auth",
    ).forEach { url ->
      assertEquals(404, httpClient.prepareGet(url).execute().statusCode, url)
    }
  }

  @Test
  fun `two pods on one origin each describe only themselves`() {
    // Path-scoped pods share the host, so the host-rooted routes are the ones that could mix them
    // up. Every document names its own pod, and nothing in it names the other one.
    val alice = sempodsTestFactory.newPod()
    val bob = sempodsTestFactory.newPod()

    for ((pod, other) in listOf(alice to bob, bob to alice)) {
      val base = podBaseUrl(pod.name)
      val otherBase = podBaseUrl(other.name)
      val resourceMetadata = listOf(
        resourceMetadataUrlAppend(pod.name),
        "$apiBaseUrl/.well-known/oauth-protected-resource/${pod.name}",
      )
      val authMetadata = listOf(authServerMetadataUrlAppend(pod.name), authServerMetadataUrlStrict(pod.name))

      for (url in resourceMetadata + authMetadata) {
        val response = httpClient.prepareGet(url).execute()
        assertEquals(200, response.statusCode, url)
        assertTrue(
          listOf("\"$otherBase\"", "\"$otherBase/").none { it in response.responseBody },
          "$url names the other pod: ${response.responseBody}",
        )
        @Suppress("UNCHECKED_CAST")
        val body = objectMapper.readValue(response.responseBody, Map::class.java) as Map<String, Any?>
        if (url in resourceMetadata) {
          assertEquals(base, body["resource"], url)
          assertEquals(listOf(base), body["authorization_servers"], url)
        } else {
          assertEquals(base, body["issuer"], url)
          listOf("authorization_endpoint", "token_endpoint", "registration_endpoint", "jwks_uri").forEach {
            assertTrue((body[it] as String).startsWith("$base/_system/auth/"), "$url $it: ${body[it]}")
          }
        }
      }
    }
  }

  // MCP 2025-11-25 probes these RFC-strict paths with the MCP URL as the resource identifier.

  @Test
  fun `RFC-strict protected-resource path with MCP identifier should serve the pod-level body`() {
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.prepareGet(resourceMetadataUrlStrictMcp(pod.name))
      .execute()

    assertEquals(200, response.statusCode)

    @Suppress("UNCHECKED_CAST")
    val body = objectMapper.readValue(response.responseBody, Map::class.java) as Map<String, Any?>

    assertEquals(podBaseUrl(pod.name), body["resource"], "resource stays at pod URL")
    assertEquals(
      listOf(podBaseUrl(pod.name)),
      body["authorization_servers"],
      "the MCP URL is another spelling of the pod resource, not a resource with its own issuer",
    )
  }

  @Test
  fun `RFC-strict authorization-server path with MCP identifier should 404`() {
    // The MCP URL is not an issuer identifier, so there is no AS-metadata to serve under it:
    // RFC 8414 §3.3 wants the served `issuer` to match the URL it came from, and the pod's
    // issuer is the pod base. Clients reach it through the PRM.
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.prepareGet(authServerMetadataUrlStrictMcp(pod.name))
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `RFC-strict MCP-identifier path should 404 on a suffix below the MCP URL`() {
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.prepareGet(
      "$apiBaseUrl/.well-known/oauth-protected-resource/${pod.name}/_system/mcp/chatgpt-work",
    )
      .execute()

    assertEquals(404, response.statusCode, "a pod has one MCP surface; nothing routes below it")
  }

  @Test
  fun `authorization-server metadata should advertise the single registration endpoint`() {
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.prepareGet(authServerMetadataUrlAppend(pod.name))
      .execute()

    assertEquals(200, response.statusCode)

    @Suppress("UNCHECKED_CAST")
    val body = objectMapper.readValue(response.responseBody, Map::class.java) as Map<String, Any?>

    val authBase = authEndpoints(pod.name)
    assertEquals(
      "$authBase/register",
      body["registration_endpoint"],
      "a pod has one registration endpoint",
    )
  }
}
