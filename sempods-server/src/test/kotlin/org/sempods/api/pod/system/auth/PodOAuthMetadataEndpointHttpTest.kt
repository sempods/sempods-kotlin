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

  private fun podBaseUrl(podName: String) = "$apiBaseUrl/$podName"
  private fun authIssuer(podName: String) = "${podBaseUrl(podName)}/_system/auth"

  private fun resourceMetadataUrlAppend(podName: String) =
    "${podBaseUrl(podName)}/.well-known/oauth-protected-resource"

  private fun authServerMetadataUrlAppend(podName: String) =
    "${authIssuer(podName)}/.well-known/oauth-authorization-server"

  private fun authServerMetadataUrlStrictPodIssuer(podName: String) =
    "$apiBaseUrl/.well-known/oauth-authorization-server/$podName/_system/auth"

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
      listOf(authIssuer(pod.name)),
      body["authorization_servers"],
      "authorization_servers must point at the pod's own _system/auth issuer"
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
      listOf("public-read", "offline_access"),
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

    val authBase = authIssuer(pod.name)

    assertEquals(authBase, body["issuer"], "issuer must be the pod's own _system/auth URL")
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
      listOf("public-read", "offline_access"),
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
    assertEquals(listOf(authIssuer(pod.name)), body["authorization_servers"])
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
  fun `RFC-strict authorization-server path for pod issuer should return AS body`() {
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.prepareGet(authServerMetadataUrlStrictPodIssuer(pod.name))
      .execute()

    assertEquals(200, response.statusCode)

    @Suppress("UNCHECKED_CAST")
    val body = objectMapper.readValue(response.responseBody, Map::class.java) as Map<String, Any?>

    val authBase = authIssuer(pod.name)
    assertEquals(authBase, body["issuer"])
    assertEquals("$authBase/authorize", body["authorization_endpoint"])
    assertEquals("$authBase/register", body["registration_endpoint"])
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
      listOf(authIssuer(pod.name)),
      body["authorization_servers"],
      "the MCP URL is another spelling of the pod resource, not a resource with its own issuer",
    )
  }

  @Test
  fun `RFC-strict authorization-server path with MCP identifier should 404`() {
    // The MCP URL is not an issuer identifier, so there is no AS-metadata to serve under it:
    // RFC 8414 §3.3 wants the served `issuer` to match the URL it came from, and the pod's
    // issuer is `_system/auth`. Clients reach it through the PRM.
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

    val authBase = authIssuer(pod.name)
    assertEquals(
      "$authBase/register",
      body["registration_endpoint"],
      "a pod has one registration endpoint",
    )
  }
}
