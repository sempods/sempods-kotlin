package org.sempods.api.pod.system.contexts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.json.JsonUtil
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.net.SempodsVocabulary
import org.sempods.commons.utils.UriEncodingUtil
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.SempodsUriBuilder
import org.sempods.pods.oauth.PodTokenIssuer
import org.sempods.client.SempodsContextCreate
import org.sempods.client.SempodsGraphFormat
import org.sempods.client.SempodsOkHttp
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsPodContexts
import org.sempods.client.SempodsRequestAuth
import org.sempods.client.SempodsSession
import org.sempods.client.rdf4j.SempodsRdf4jContexts
import org.sempods.client.rdf4j.SempodsRdf4jPod
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import okhttp3.OkHttpClient
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodContextsEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  @Inject
  private lateinit var podServiceClientDao: PodServiceClientDao

  @Inject
  private lateinit var podServiceClientStore: PodServiceClientStore

  @Inject
  private lateinit var podTokenIssuer: PodTokenIssuer

  private val jsonUtil = JsonUtil(JsonMappers.default())

  private val objectMapper = ObjectMapper()

  private fun tokenUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/_system/auth/token"

  /**
   * RFC 6749 §2.3.1 `client_secret_basic`: form-urlencode `client_id` and
   * `client_secret`, join with `:`, base64-encode.
   */
  private fun basicHeader(clientId: String, secret: String): String {
    val encId = java.net.URLEncoder.encode(clientId, Charsets.UTF_8)
    val encSecret = java.net.URLEncoder.encode(secret, Charsets.UTF_8)
    return "Basic " + Base64.getEncoder().encodeToString("$encId:$encSecret".toByteArray(Charsets.UTF_8))
  }

  /**
   * Register a service client with the given scopes and mint a `client_credentials`
   * access token via `{pod}/_system/auth/token` — the real client_credentials token path.
   */
  private fun mintServiceToken(pod: PodDbo, scopes: Set<String>): String {
    val registered = podServiceClientStore.register(
      pod = pod.hosted,
      clientId = "notes-app",
      scopes = scopes,
      label = "notes-app",
    )
    val response = http.preparePost(tokenUrl(pod.name))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(registered.registration.clientId, registered.secret))
      .setBody("grant_type=client_credentials")
      .execute()
    assertEquals(200, response.statusCode, "token mint failed; body=${response.responseBody}")
    val body = objectMapper.readValue(response.responseBody, Map::class.java).mapKeys { it.key.toString() }
    return checkNotNull(body["access_token"] as? String) { "access_token missing in $body" }
  }

  private fun contextsBaseUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/_system/contexts"

  private fun contextManageUrl(podName: String, contextPath: String): String =
    "${contextsBaseUrl(podName)}/${contextPath.trimStart('/')}"

  // Identity and management route are the same string since contexts moved into the reserved
  // area — this mirrors `contextManageUrl` on purpose rather than deriving a second shape.
  private fun contextUri(podName: String, contextPath: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/${SempodsUriBuilder.CONTEXT_PATH_PREFIX}${contextPath.trimStart('/')}"

  private fun createContextViaDao(podId: ObjectId, podName: String, contextPath: String) {
    podContextsDao.create(
      podId = podId,
      contextUri = contextUri(podName, contextPath),
      label = null,
      description = null,
      createdBy = "test",
    )
  }

  /**
   * Mint a properly-signed user token whose `sub` is blank. `PodTokenAuthenticator` treats a
   * blank subject as missing (`getStringClaim("sub")?.takeIf { it.isNotBlank() }`), so this
   * exercises the same missing-subject rejection (401 + WWW-Authenticate) as a token with no
   * `sub` claim — via the normal issuer API, without reflection or hand-rolled signing.
   */
  private fun mintUserTokenMissingSub(podName: String): String =
    podTokenIssuer.issue(
      pod = podName,
      webId = "",
      clientId = "did:web:test.example",
      scopes = emptySet(),
    )

  @Test
  fun `put context should return 401 without owner token`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.preparePut(contextManageUrl(pod.name, "apps/example/tasks"))
      .addHeader("Content-Type", "application/json")
      .setBody("{}")
      .execute()

    assertEquals(401, response.statusCode)
  }

  @Test
  fun `get returns the context at its own IRI, and only the registry's view of it`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerToken = mintOwnerPodToken(pod.name, webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email)))
    http.preparePut(contextManageUrl(pod.name, "apps/example/tasks"))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $ownerToken")
      .setBody("""{"label":"Tasks"}""")
      .execute()

    // Someone attaches a triple to the context's IRI — a claim, living in that same context.
    val contextIri = contextUri(pod.name, "apps/example/tasks")
    val oauthToken = mintScopedToken(pod.name, listOf("$contextIri#read", "$contextIri#write"))
    val b64 = UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(contextIri))
    http.preparePut("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/resources/$b64?context=$contextIri")
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $oauthToken")
      .setBody("""{"@id":"$contextIri","https://schema.org/name":[{"@value":"someone's claim"}]}""")
      .execute()

    val response = http.prepareGet(contextManageUrl(pod.name, "apps/example/tasks"))
      .addHeader("Accept", "application/json")
      .addHeader("Authorization", "Bearer $oauthToken")
      .execute()

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
    assertEquals("true", response.headers.get("Deprecation"), "the envelope is the transitional shape")
    assertTrue(response.responseBody.contains(contextIri), response.responseBody)
    assertTrue(response.responseBody.contains("\"label\":\"Tasks\""), response.responseBody)
    // The registry answers for what the context *is*; third-party statements about the IRI are
    // read through `_system/resources/{b64}` and must not leak in here.
    assertFalse(response.responseBody.contains("someone's claim"), response.responseBody)
  }

  @Test
  fun `get returns 404 for an unknown context`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/example/tasks")
    val token = mintScopedToken(pod.name, listOf("${contextUri(pod.name, "apps/example/tasks")}#read"))

    val response = http.prepareGet(contextManageUrl(pod.name, "apps/example/nope"))
      .addHeader("Authorization", "Bearer $token")
      .execute()

    assertEquals(404, response.statusCode, "body=${response.responseBody}")
  }

  @Test
  fun `put allows free names but reserves type names and the operation segment`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerToken = mintOwnerPodToken(pod.name, webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email)))

    fun put(path: String) = http.preparePut(contextManageUrl(pod.name, path))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $ownerToken")
      .setBody("{}")
      .execute()

    // The pod's own working areas carry no type — nothing is delegated, the pod is already the
    // owner's. Free naming is the normal case, not an exception.
    assertEquals(201, put("privat").statusCode)
    assertEquals(201, put("projects/alpha").statusCode)

    // Type names belong to the control plane: a root is created by provisioning, not here.
    assertEquals(400, put("apps/example").statusCode)
    // Below a root is the app's own business, which its `#manage` scope covers anyway.
    assertEquals(201, put("apps/example/tasks").statusCode)
    // `users` is reserved for the guest case and has no producer yet.
    val reserved = put("users/alice/notes")
    assertEquals(400, reserved.statusCode)
    assertTrue(reserved.responseBody.contains("reserved for a future"), reserved.responseBody)
    // `_system` stays free as the separator for future per-context operations.
    assertEquals(400, put("apps/example/_system/shape").statusCode)
  }

  @Test
  fun `put context should create context under the reserved contexts namespace and return 201`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val ownerToken = mintOwnerPodToken(pod.name, ownerWebId)

    val response = http.preparePut(contextManageUrl(pod.name, "apps/example/tasks"))
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "application/json")
      .addHeader("Authorization", "Bearer $ownerToken")
      .setBody("""{"label":"Tasks"}""")
      .execute()

    assertEquals(201, response.statusCode)
    assertTrue(response.responseBody.contains("\"label\":\"Tasks\""))
    assertTrue(response.responseBody.contains("${pod.name}/_system/contexts/apps/example/tasks"))
  }

  @Test
  fun `put context should be idempotent on duplicates and return 200`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val ownerToken = mintOwnerPodToken(pod.name, ownerWebId)
    val url = contextManageUrl(pod.name, "apps/example/tasks")

    val first = http.preparePut(url)
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $ownerToken")
      .setBody("{}")
      .execute()
    assertEquals(201, first.statusCode)

    val second = http.preparePut(url)
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $ownerToken")
      .setBody("{}")
      .execute()
    assertEquals(200, second.statusCode)
  }

  /**
   * `SPS-CTX-027` / `SPS-CTX-030`: a context is private unless somebody chose otherwise, and the
   * body carrying that choice is optional — so both quiet paths, an empty JSON object and no body
   * at all, have to land private.
   *
   * Asserted through the anonymous listing and not only on the `public` field, because a server
   * reporting `false` while storing something else would satisfy the field check. An anonymous
   * caller resolves to `public-read` and nothing further, so what that listing returns *is* the set
   * of contexts the flag opened up. The explicit `public: true` is the contrast: without a context
   * that did ask for it, the two assertions above would hold just as well on a server that ignored
   * the flag entirely.
   */
  @Test
  fun `put creates a private context wherever the public flag is absent`() {
    val ownerUser = sempodsTestFactory.newOwner()
    // No fixture context, so the anonymous listing below reports what this test created and
    // nothing else.
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser, createPublicContext = false)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val ownerToken = mintOwnerPodToken(pod.name, ownerWebId)

    // `null` is the request that carries no body at all — `TestHttpRequest` then sends
    // `Content-Length: 0`, which is the case `SPS-CTX-027` names explicitly.
    fun put(contextPath: String, body: String?): TestHttpResponse {
      val request = http.preparePut(contextManageUrl(pod.name, contextPath))
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", "application/json")
        .addHeader("Authorization", "Bearer $ownerToken")
      body?.let { request.setBody(it) }
      return request.execute()
    }

    fun assertCreated(response: TestHttpResponse, public: Boolean, what: String) {
      assertEquals(201, response.statusCode, "$what; body=${response.responseBody}")
      assertEquals(
        public,
        jsonUtil.read(response.responseBody, PutPodContextResponse::class.java).public,
        "$what; body=${response.responseBody}",
      )
    }

    assertCreated(put("defaults/empty-body", "{}"), public = false, what = "empty JSON body")
    assertCreated(put("defaults/no-body", null), public = false, what = "no body at all")
    assertCreated(put("defaults/asked-for", """{"public":true}"""), public = true, what = "explicit public:true")

    val anonymous = http.prepareGet(contextsBaseUrl(pod.name)).addHeader("Accept", "application/json").execute()
    assertEquals(200, anonymous.statusCode, "body=${anonymous.responseBody}")
    assertEquals(
      listOf(contextUri(pod.name, "defaults/asked-for")),
      jsonUtil.read(anonymous.responseBody, PodContextsListResponse::class.java).contexts.map { it.contextIri },
      "only the context whose creation asked for `public` may be anonymously visible",
    )
  }

  @Test
  fun `delete context should return 204 and remove the registry row`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val ownerToken = mintOwnerPodToken(pod.name, ownerWebId)
    val url = contextManageUrl(pod.name, "apps/example/tasks")

    http.preparePut(url)
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $ownerToken")
      .setBody("{}")
      .execute()

    val response = http.prepareDelete(url)
      .addHeader("Authorization", "Bearer $ownerToken")
      .execute()
    assertEquals(204, response.statusCode)
  }

  @Test
  fun `delete of a manage root revokes what the service client held on it`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val ownerToken = mintOwnerPodToken(pod.name, webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email)))
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/notes")
    val registered = podServiceClientStore.register(
      pod = pod.hosted,
      clientId = "notes-app",
      scopes = setOf("${contextUri(pod.name, "apps/notes")}#manage"),
      label = "notes-app",
    )

    val deleteResponse = http.prepareDelete(contextManageUrl(pod.name, "apps/notes"))
      .addHeader("Authorization", "Bearer $ownerToken")
      .execute()
    assertEquals(204, deleteResponse.statusCode)

    // The authority is revoked with its anchor — the secret must not mint new tokens for the
    // deleted root (manage surviving descendants, recreate the root). The registration itself
    // stays, holding a credential and nothing else.
    assertEquals(
      emptySet(),
      assertNotNull(
        podServiceClientDao.findByClientId(podId, "notes-app"),
        "the registration outlives the context it was anchored at",
      ).scopes,
    )
    val tokenResponse = http.preparePost(tokenUrl(pod.name))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(registered.registration.clientId, registered.secret))
      .setBody("grant_type=client_credentials")
      .execute()
    assertEquals(400, tokenResponse.statusCode, "a client with no scopes mints nothing; body=${tokenResponse.responseBody}")
    assertTrue("invalid_scope" in tokenResponse.responseBody, tokenResponse.responseBody)
  }

  @Test
  fun `delete of one anchor only strips that scope from a multi-scope service client`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val ownerToken = mintOwnerPodToken(pod.name, webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email)))
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/notes")
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/other")
    val survivingScope = "${contextUri(pod.name, "apps/other")}#read"
    podServiceClientStore.register(
      pod = pod.hosted,
      clientId = "notes-app",
      scopes = setOf("${contextUri(pod.name, "apps/notes")}#manage", survivingScope),
      label = "notes-app",
    )

    val deleteResponse = http.prepareDelete(contextManageUrl(pod.name, "apps/notes"))
      .addHeader("Authorization", "Bearer $ownerToken")
      .execute()
    assertEquals(204, deleteResponse.statusCode)

    val survivor = assertNotNull(
      podServiceClientDao.findByClientId(podId, "notes-app"),
      "registration with scopes on other anchors must survive",
    )
    assertEquals(setOf(survivingScope), survivor.scopes, "only the deleted anchor's scope must be stripped")
  }

  @Test
  fun `delete context should return 404 when context does not exist`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val ownerToken = mintOwnerPodToken(pod.name, ownerWebId)

    val response = http.prepareDelete(contextManageUrl(pod.name, "apps/missing/tasks"))
      .addHeader("Authorization", "Bearer $ownerToken")
      .execute()
    assertEquals(404, response.statusCode)
  }

  @Test
  fun `put context with service-client manage scope creates descendant and returns 201`() {
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)
    val appRoot = contextUri(pod.name, "apps/notes")
    // Root registered; service client creates a slash-delimited descendant under it.
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/notes")
    val serviceToken = mintServiceToken(pod, setOf("$appRoot#manage"))

    val response = http.preparePut(contextManageUrl(pod.name, "apps/notes/events"))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $serviceToken")
      .setBody("""{"label":"Events"}""")
      .execute()

    assertEquals(201, response.statusCode, "unexpected status; body=${response.responseBody}")
    assertTrue(response.responseBody.contains(contextUri(pod.name, "apps/notes/events")))
  }

  @Test
  fun `put context outside the manage root returns 403 for a service client`() {
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)
    val appRoot = contextUri(pod.name, "apps/notes")
    val serviceToken = mintServiceToken(pod, setOf("$appRoot#manage"))

    val response = http.preparePut(contextManageUrl(pod.name, "apps/other/tasks"))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $serviceToken")
      .setBody("{}")
      .execute()

    assertEquals(403, response.statusCode, "sibling-outside-root must be forbidden; body=${response.responseBody}")
  }

  @Test
  fun `delete context with service-client manage scope returns 204 and removes the row`() {
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)
    val appRoot = contextUri(pod.name, "apps/notes")
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/notes")
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/notes/events")
    val serviceToken = mintServiceToken(pod, setOf("$appRoot#manage"))

    val response = http.prepareDelete(contextManageUrl(pod.name, "apps/notes/events"))
      .addHeader("Authorization", "Bearer $serviceToken")
      .execute()

    assertEquals(204, response.statusCode)
    assertFalse(
      podContextsDao.exists(podId = podId, contextUri = contextUri(pod.name, "apps/notes/events")),
      "registry row must be gone after delete",
    )
  }

  @Test
  fun `delete context outside the manage root returns 403 without leaking existence`() {
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)
    val appRoot = contextUri(pod.name, "apps/notes")
    val serviceToken = mintServiceToken(pod, setOf("$appRoot#manage"))

    // Context does not exist; an out-of-sandbox caller must see 403, not 404.
    val response = http.prepareDelete(contextManageUrl(pod.name, "apps/other/tasks"))
      .addHeader("Authorization", "Bearer $serviceToken")
      .execute()

    assertEquals(403, response.statusCode)
  }

  @Test
  fun `owner can still create a context outside any service-client sandbox`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val ownerToken = mintOwnerPodToken(pod.name, ownerWebId)

    val response = http.preparePut(contextManageUrl(pod.name, "apps/other/owner-only"))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $ownerToken")
      .setBody("{}")
      .execute()

    assertEquals(201, response.statusCode, "owner is the catch-all allow; body=${response.responseBody}")
  }

  @Test
  fun `the owner is recognised from the token subject, with nothing granted and no contexts yet`() {
    // Ownership is not a grant and not a scope — it follows from `podDbo.owner`, and the server
    // reads which person is asking from the token's `sub`. So an owner with an empty grant set, on
    // a pod that has no contexts at all, can still make the first one. That is the bootstrap the
    // old flow covered with an identity JWT presented as a bearer.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser, createPublicContext = false)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val response = http.preparePut(contextManageUrl(pod.name, "first"))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer ${mintOwnerPodToken(pod.name, ownerWebId)}")
      .setBody("{}")
      .execute()

    assertEquals(201, response.statusCode, "body=${response.responseBody}")
  }

  @Test
  fun `a token for somebody who is not the owner gets no owner authority`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val strangerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(sempodsTestFactory.newOwner().email))

    // A valid pod token, correct pod, just a different person. Nothing is granted to them either.
    val response = http.preparePut(contextManageUrl(pod.name, "apps/other/not-yours"))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer ${mintOwnerPodToken(pod.name, strangerWebId)}")
      .setBody("{}")
      .execute()

    assertEquals(403, response.statusCode, "body=${response.responseBody}")
  }

  @Test
  fun `list contexts should return contexts accessible by oauth token`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val publicContext = "public/tasks"
    val appContext = "apps/example/notes"

    createContextViaDao(podId = podId, podName = pod.name, contextPath = publicContext)
    createContextViaDao(podId = podId, podName = pod.name, contextPath = appContext)

    // OAuth token with read access to the public context only
    val readScope = "${contextUri(pod.name, publicContext)}#read"
    val oauthToken = mintScopedToken(pod.name, listOf(readScope))

    val listResponse = http.prepareGet(contextsBaseUrl(pod.name))
      .addHeader("Accept", "application/json")
      .addHeader("Authorization", "Bearer $oauthToken")
      .execute()

    assertEquals(200, listResponse.statusCode)
    val payload = jsonUtil.read(listResponse.responseBody, PodContextsListResponse::class.java)
    // Only the context matching the token scopes is visible
    assertTrue(payload.contexts.any { it.contextIri.endsWith(publicContext) })
    assertTrue(payload.contexts.none { it.contextIri.endsWith(appContext) })
  }

  @Test
  fun `list contexts should report grants from oauth token scopes`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val contextPath = "test/scope-debug"
    createContextViaDao(podId = podId, podName = pod.name, contextPath = contextPath)

    val readScope = "${contextUri(pod.name, contextPath)}#read"
    val writeScope = "${contextUri(pod.name, contextPath)}#write"
    val oauthToken = mintScopedToken(pod.name, listOf(readScope, writeScope))

    val response = http.prepareGet(contextsBaseUrl(pod.name))
      .addHeader("Accept", "application/json")
      .addHeader("Authorization", "Bearer $oauthToken")
      .execute()

    assertEquals(200, response.statusCode)
    val payload = jsonUtil.read(response.responseBody, PodContextsListResponse::class.java)
    val item = payload.contexts.firstOrNull { it.contextIri.endsWith(contextPath) }
    assertNotNull(item, "Expected context '$contextPath' in response, got: ${payload.contexts}")
    assertEquals(listOf("read", "write"), item.permissions, "permissions must mirror token scopes")
    assertEquals("grant", item.source, "direct read/write grant should be sourced as 'grant'")
    assertTrue(payload.writableContexts.any { it.endsWith(contextPath) }, "writable_contexts must include the write-granted context")
  }

  @Test
  fun `list contexts with manage root token should include slash-delimited descendants with grants`() {
    // A service token carrying only `<R>#manage` must surface every registered
    // descendant `<R>/...` in the listing, with read/write/manage permissions —
    // matching the slash-delimited authorization rule (sempods-spec `spec/core/grants.md` §"manage
    // semantics"). Sibling-prefix contexts (`<R>-private`) must stay out.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val rootPath = "apps/notes"
    val childPath = "apps/notes/events"
    val grandchildPath = "apps/notes/views"
    val siblingPath = "apps/notes-private"

    createContextViaDao(podId = podId, podName = pod.name, contextPath = rootPath)
    createContextViaDao(podId = podId, podName = pod.name, contextPath = childPath)
    createContextViaDao(podId = podId, podName = pod.name, contextPath = grandchildPath)
    createContextViaDao(podId = podId, podName = pod.name, contextPath = siblingPath)

    val rootContextUri = contextUri(pod.name, rootPath)
    val manageScope = "${rootContextUri}#manage"
    val oauthToken = mintScopedToken(pod.name, listOf(manageScope))

    val response = http.prepareGet(contextsBaseUrl(pod.name))
      .addHeader("Accept", "application/json")
      .addHeader("Authorization", "Bearer $oauthToken")
      .execute()

    assertEquals(200, response.statusCode)
    val payload = jsonUtil.read(response.responseBody, PodContextsListResponse::class.java)
    val byContext = payload.contexts.associateBy { it.contextIri }

    val rootItem = assertNotNull(byContext[contextUri(pod.name, rootPath)], "root context must be listed")
    assertEquals(listOf("manage", "read", "write"), rootItem.permissions.sorted())
    assertEquals("manage", rootItem.source, "manage-covered context should be sourced as 'manage'")

    val childItem = assertNotNull(byContext[contextUri(pod.name, childPath)], "slash-delimited child must be listed")
    assertEquals(listOf("manage", "read", "write"), childItem.permissions.sorted())
    assertEquals("manage", childItem.source)

    val grandchildItem = assertNotNull(byContext[contextUri(pod.name, grandchildPath)], "grandchild must be listed")
    assertEquals(listOf("manage", "read", "write"), grandchildItem.permissions.sorted())

    assertTrue(
      byContext[contextUri(pod.name, siblingPath)] == null,
      "sibling-prefix context must NOT be visible to a `${rootPath}#manage` token (got ${payload.contexts.map { it.contextIri }})"
    )
  }

  @Test
  fun `list contexts without auth should return only public contexts`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(contextsBaseUrl(pod.name))
      .execute()

    // Anonymous caller → implicit public-read only (may be 0 items if pod has no public contexts).
    assertEquals(200, response.statusCode)
  }

  @Test
  fun `list contexts with invalid bearer should return 401 with WWW-Authenticate`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(contextsBaseUrl(pod.name))
      .addHeader("Authorization", "Bearer not-a-real-jwt")
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 response must include WWW-Authenticate header")
    assertTrue(authHeader.contains("/.well-known/oauth-protected-resource"))
  }

  @Test
  fun `list contexts with non-service bearer missing sub should return 401 with WWW-Authenticate`() {
    val pod = sempodsTestFactory.newPod()
    val malformedToken = mintUserTokenMissingSub(pod.name)

    val response = http.prepareGet(contextsBaseUrl(pod.name))
      .addHeader("Authorization", "Bearer " + malformedToken)
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 response must include WWW-Authenticate header")
    assertTrue(authHeader.contains("/.well-known/oauth-protected-resource"))
  }

  // ── The registry as RDF (`SPS-CTX-031` … `SPS-CTX-037`) ─────────────────────────

  /** The client core against the served routes, so neither the routes nor the answers it takes can drift from these. */
  private fun <T> withContexts(podName: String, auth: SempodsRequestAuth, block: (SempodsPodContexts) -> T): T {
    val client = SempodsOkHttp.install(OkHttpClient.Builder()).build()
    val base = SempodsPodBase.of("${SempodsModule.config.apiBaseUrl}$podName")
    try {
      return block(SempodsPod(SempodsSession(base, auth), client).contexts())
    } finally {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
  }

  @Test
  fun `the client core creates a context and reads the description this endpoint answers with`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerToken = mintOwnerPodToken(pod.name, webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email)))
    val tasks = contextUri(pod.name, "apps/example/tasks")
    val label = "T\u00e2ches \"\u00f6ffentlich\" \\ \u2713"

    withContexts(pod.name, SempodsRequestAuth.bearer(ownerToken)) { contexts ->
      val created = contexts.create(tasks, SempodsContextCreate.fields().withLabel(label).withPublic(true))
      assertEquals(201, created.status)
      val description = objectMapper.readTree(created.body)
      assertEquals(tasks, description.path("@id").asText())
      assertEquals(listOf("${SD_NS}NamedGraph"), description.path("@type").map { it.asText() })
      assertEquals(label, description.path("http://www.w3.org/2000/01/rdf-schema#label").single().path("@value").asText())
      assertTrue(description.path(SempodsVocabulary.PUBLIC).single().path("@value").asBoolean())

      // `PUT` is idempotent, and the second answer is the unchanged context (SPS-CTX-016, SPS-CTX-037).
      val again = contexts.create(tasks, SempodsContextCreate.fields().withPublic(false), SempodsGraphFormat.N_QUADS)
      assertEquals(200, again.status)
      val quads = String(checkNotNull(again.body))
      assertTrue(quads.contains("<$tasks> <${SempodsVocabulary.PUBLIC}> \"true\""), quads)
    }

    // Read back through a grant, not through ownership: an owner holds none, so the registry shows
    // them nothing (#185).
    val reader = mintScopedToken(pod.name, listOf("$tasks#read"))
    withContexts(pod.name, SempodsRequestAuth.bearer(reader)) { contexts ->
      val read = contexts.getText(tasks)
      assertEquals(200, read.status)
      assertEquals(tasks, objectMapper.readTree(read.body).path("@id").asText())
      val tag = checkNotNull(read.headers["ETag"])
      assertEquals(304, contexts.getText(tasks, SempodsGraphFormat.JSON_LD, tag).status)
      // The tag belongs to the representation it was read from (SPS-CTX-035).
      assertEquals(200, contexts.getBytes(tasks, SempodsGraphFormat.N_QUADS, tag).status)
    }

    withContexts(pod.name, SempodsRequestAuth.bearer(ownerToken)) { contexts ->
      assertEquals(204, contexts.delete(tasks).status)
      assertEquals(404, contexts.delete(tasks).status)
    }
    assertNull(podContextsDao.fetchByContextUri(podId = checkNotNull(pod.id), contextUri = tasks))
  }

  @Test
  fun `a context named in a caller's own language is created, read and removed through the core`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerToken = mintOwnerPodToken(pod.name, webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email)))
    // `SPS-CTX-009`: a freely chosen name that breaks no structural rule. The path carries it
    // percent-encoded and the endpoint takes it back decoded, so the IRI is the one the pod stored.
    val iri = contextUri(pod.name, "grüße/例")

    withContexts(pod.name, SempodsRequestAuth.bearer(ownerToken)) { contexts ->
      assertEquals(201, contexts.create(iri).status)
    }
    assertNotNull(podContextsDao.fetchByContextUri(podId = checkNotNull(pod.id), contextUri = iri))

    val reader = mintScopedToken(pod.name, listOf("$iri#read"))
    withContexts(pod.name, SempodsRequestAuth.bearer(reader)) { contexts ->
      val read = contexts.getText(iri)
      assertEquals(200, read.status, read.body)
      assertEquals(iri, objectMapper.readTree(read.body).path("@id").asText())
      assertTrue(ids(objectMapper.readTree(contexts.listText().body), "${SD_NS}namedGraph").contains(iri))
    }

    withContexts(pod.name, SempodsRequestAuth.bearer(ownerToken)) { contexts ->
      assertEquals(204, contexts.delete(iri).status)
    }
    assertNull(podContextsDao.fetchByContextUri(podId = checkNotNull(pod.id), contextUri = iri))
  }

  @Test
  fun `the client core reads the catalogue its session sees, and nothing beyond it`() {
    val pod = sempodsTestFactory.newPod()
    val path = "test/core-listing"
    createContextViaDao(podId = checkNotNull(pod.id), podName = pod.name, contextPath = path)
    val context = contextUri(pod.name, path)
    val token = mintScopedToken(pod.name, listOf("$context#read", "$context#write"))

    withContexts(pod.name, SempodsRequestAuth.bearer(token)) { contexts ->
      val catalogue = objectMapper.readTree(contexts.listText().body)
      assertEquals(contextsBaseUrl(pod.name), catalogue.path("@id").asText())
      assertTrue(ids(catalogue, "${SD_NS}namedGraph").contains(context), contexts.listText().body)
      assertTrue(ids(catalogue, SempodsVocabulary.WRITABLE_CONTEXT).contains(context))
    }

    withContexts(pod.name, SempodsRequestAuth.anonymous()) { contexts ->
      val public = sempodsTestFactory.publicContextUri(pod.name).toString()
      assertEquals(listOf(public), ids(objectMapper.readTree(contexts.listText().body), "${SD_NS}namedGraph"))
      // A context this session cannot see answers as one that was never registered (SPS-CTX-036).
      val hidden = contexts.getText(context)
      assertEquals(404, hidden.status)
      assertNull(hidden.body)
      assertNull(hidden.headers["ETag"])
      assertEquals(404, contexts.getText(contextUri(pod.name, "never/registered")).status)
    }
  }

  private fun registryGet(url: String, token: String?, accept: String): TestHttpResponse {
    val request = http.prepareGet(url).addHeader("Accept", accept)
    token?.let { request.addHeader("Authorization", "Bearer $it") }
    return request.execute()
  }

  private fun ids(body: JsonNode, predicate: String): List<String> = body.path(predicate).map { it.path("@id").asText() }

  @Test
  fun `the catalogue and a description answer canonical JSON-LD, and the same RDF as N-Quads`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val path = "apps/example/tasks"
    createContextViaDao(podId = podId, podName = pod.name, contextPath = path)
    val iri = contextUri(pod.name, path)
    val token = mintScopedToken(pod.name, listOf("$iri#read", "$iri#write"))

    val catalogue = registryGet(contextsBaseUrl(pod.name), token, "application/ld+json")
    assertEquals(200, catalogue.statusCode, catalogue.responseBody)
    assertTrue(catalogue.contentType.orEmpty().startsWith("application/ld+json"), catalogue.contentType)
    val listing = objectMapper.readTree(catalogue.responseBody)
    assertEquals(contextsBaseUrl(pod.name), listing.path("@id").asText())
    assertEquals(listOf("${SD_NS}GraphCollection"), listing.path("@type").map { it.asText() })
    assertEquals(listOf(iri), ids(listing, "${SD_NS}namedGraph"))
    assertEquals(listOf(iri), ids(listing, SempodsVocabulary.READABLE_CONTEXT))
    assertEquals(listOf(iri), ids(listing, SempodsVocabulary.WRITABLE_CONTEXT))
    assertTrue(listing.path(SempodsVocabulary.MANAGEABLE_CONTEXT).isMissingNode, catalogue.responseBody)

    val description = registryGet(contextManageUrl(pod.name, path), token, "application/ld+json")
    assertEquals(200, description.statusCode, description.responseBody)
    val context = objectMapper.readTree(description.responseBody)
    assertEquals(iri, context.path("@id").asText())
    assertEquals(listOf("${SD_NS}NamedGraph"), context.path("@type").map { it.asText() })
    assertEquals(listOf(iri), ids(context, "${SD_NS}name"))
    val public = context.path(SempodsVocabulary.PUBLIC).single().path("@value")
    assertTrue(public.isBoolean, "the registry's flag is a JSON boolean: ${description.responseBody}")
    assertFalse(public.asBoolean())
    assertEquals(
      "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/resources/" +
        UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(iri)),
      ids(context, "http://www.w3.org/2000/01/rdf-schema#seeAlso").single(),
    )
    assertTrue(context.path(SempodsVocabulary.READABLE_CONTEXT).isMissingNode, "a description states no caller right")

    val quads = registryGet(contextManageUrl(pod.name, path), token, "application/n-quads")
    assertEquals(200, quads.statusCode, quads.responseBody)
    assertTrue(quads.contentType.orEmpty().startsWith("application/n-quads"), quads.contentType)
    val lines = quads.responseBody.lines().filter { it.isNotBlank() }
    assertTrue(lines.any { it.contains("${SD_NS}NamedGraph") }, quads.responseBody)
    lines.forEach { line ->
      assertTrue(Regex("<[^>]*>").findAll(line).count() <= 3, "the registry's RDF is the default graph: $line")
    }
  }

  @Test
  fun `a request that names no type gets JSON-LD, and application slash json still gets the deprecated envelope`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/example/tasks")
    val token = mintScopedToken(pod.name, listOf("${contextUri(pod.name, "apps/example/tasks")}#read"))

    val bare = http.prepareGet(contextsBaseUrl(pod.name)).addHeader("Authorization", "Bearer $token").execute()
    assertEquals(200, bare.statusCode, bare.responseBody)
    assertTrue(bare.contentType.orEmpty().startsWith("application/ld+json"), bare.contentType)
    assertNull(bare.headers.get("Deprecation"))
    assertEquals("Accept, Authorization", bare.headers.get("Vary"))
    assertEquals("no-store", bare.headers.get("Cache-Control"))

    val legacy = registryGet(contextsBaseUrl(pod.name), token, "application/json")
    assertEquals(200, legacy.statusCode, legacy.responseBody)
    assertEquals("true", legacy.headers.get("Deprecation"))
    assertTrue(legacy.headers.get("Link").orEmpty().contains("issues/184"), legacy.headers.get("Link"))
    assertTrue(objectMapper.readTree(legacy.responseBody).path("contexts").isArray, legacy.responseBody)
  }

  @Test
  fun `an unsatisfiable Accept is refused, and a create refused for one leaves no context behind`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val ownerToken = mintOwnerPodToken(pod.name, ownerWebId)
    val path = "apps/example/turtle"

    val refused = http.preparePut(contextManageUrl(pod.name, path))
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "text/turtle")
      .addHeader("Authorization", "Bearer $ownerToken")
      .setBody("{}")
      .execute()

    assertEquals(406, refused.statusCode, refused.responseBody)
    assertNull(
      podContextsDao.fetchByContextUri(podId = podId, contextUri = contextUri(pod.name, path)),
      "a create the pod could not answer must not have written",
    )
    assertEquals(406, registryGet(contextsBaseUrl(pod.name), ownerToken, "text/turtle").statusCode)
  }

  @Test
  fun `a registry read carries a strong validator per representation, and never answers 304 without the authorization behind it`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val path = "apps/example/tasks"
    createContextViaDao(podId = podId, podName = pod.name, contextPath = path)
    val iri = contextUri(pod.name, path)
    val token = mintScopedToken(pod.name, listOf("$iri#read"))

    val first = registryGet(contextManageUrl(pod.name, path), token, "application/ld+json")
    val tag = assertNotNull(first.headers.get("ETag"), "a registry read carries its validator")
    assertFalse(tag.startsWith("W/"), tag)
    assertNotEquals(
      tag,
      registryGet(contextManageUrl(pod.name, path), token, "application/n-quads").headers.get("ETag"),
      "two representations never share a tag",
    )

    val unchanged = http.prepareGet(contextManageUrl(pod.name, path))
      .addHeader("Accept", "application/ld+json")
      .addHeader("If-None-Match", tag)
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(304, unchanged.statusCode, unchanged.responseBody)
    assertEquals("no-store", unchanged.headers.get("Cache-Control"))

    // The same context and the same tag, held by a caller who may not read it: the pod establishes
    // its authorization before it evaluates the condition, so the answer is the absence.
    val stranger = mintScopedToken(pod.name, listOf("${contextUri(pod.name, "apps/example/other")}#read"))
    val revoked = http.prepareGet(contextManageUrl(pod.name, path))
      .addHeader("Accept", "application/ld+json")
      .addHeader("If-None-Match", tag)
      .addHeader("Authorization", "Bearer $stranger")
      .execute()
    assertEquals(404, revoked.statusCode, revoked.responseBody)
    assertNull(revoked.headers.get("ETag"))
  }

  @Test
  fun `a context the caller cannot see answers exactly as one that was never registered`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/example/private")
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/example/mine")
    val token = mintScopedToken(pod.name, listOf("${contextUri(pod.name, "apps/example/mine")}#read"))

    val hidden = registryGet(contextManageUrl(pod.name, "apps/example/private"), token, "application/ld+json")
    val absent = registryGet(contextManageUrl(pod.name, "apps/example/never"), token, "application/ld+json")

    listOf(hidden, absent).forEach { response ->
      assertEquals(404, response.statusCode, response.responseBody)
      assertEquals("unknown context", response.responseBody)
      assertNull(response.headers.get("ETag"))
      assertEquals("no-store", response.headers.get("Cache-Control"))
    }
    assertEquals(hidden.contentType, absent.contentType)
  }

  @Test
  fun `a caller who sees no context still gets the collection`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    createContextViaDao(podId = checkNotNull(pod.id), podName = pod.name, contextPath = "apps/example/private")
    // A token whose only scope names a context that was never registered: nothing is visible to it,
    // where an anonymous caller would still see the pod's public context.
    val stranger = mintScopedToken(pod.name, listOf("${contextUri(pod.name, "apps/example/never")}#read"))

    val response = registryGet(contextsBaseUrl(pod.name), stranger, "application/ld+json")

    assertEquals(200, response.statusCode, response.responseBody)
    val body = objectMapper.readTree(response.responseBody)
    assertEquals(contextsBaseUrl(pod.name), body.path("@id").asText())
    assertEquals(listOf("${SD_NS}GraphCollection"), body.path("@type").map { it.asText() })
    assertEquals(2, body.size(), "an empty catalogue is its identity and its type: ${response.responseBody}")
  }

  @Test
  fun `a create answers the registry description, and a repeat answers the unchanged one`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val ownerToken = mintOwnerPodToken(pod.name, ownerWebId)
    val path = "apps/example/tasks"
    fun put(label: String) = http.preparePut(contextManageUrl(pod.name, path))
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $ownerToken")
      .setBody("""{"label":"$label"}""")
      .execute()

    val created = put("Tasks")
    assertEquals(201, created.statusCode, created.responseBody)
    val body = objectMapper.readTree(created.responseBody)
    assertEquals(contextUri(pod.name, path), body.path("@id").asText())
    assertEquals("Tasks", body.path(RDFS_LABEL).single().path("@value").asText())
    assertNull(created.headers.get("ETag"), "a write carries no validator of its own")

    val again = put("Renamed")
    assertEquals(200, again.statusCode, again.responseBody)
    assertEquals(
      "Tasks",
      objectMapper.readTree(again.responseBody).path(RDFS_LABEL).single().path("@value").asText(),
      "a repeated create implies no metadata update",
    )

    // `SPS-CTX-037` asks the create to answer the description a read would give, down to
    // `dcterms:created` — which a row handed back before it was stored would get wrong.
    val reader = mintScopedToken(pod.name, listOf("${contextUri(pod.name, path)}#read"))
    val read = registryGet(contextManageUrl(pod.name, path), reader, "application/ld+json")
    assertEquals(200, read.statusCode, read.responseBody)
    assertEquals(created.responseBody, read.responseBody)
  }

  @Test
  fun `ordinary statements about a context IRI change neither the registry's answer nor its validator`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val path = "apps/example/tasks"
    createContextViaDao(podId = podId, podName = pod.name, contextPath = path)
    val iri = contextUri(pod.name, path)
    val token = mintScopedToken(pod.name, listOf("$iri#read", "$iri#write"))
    val before = registryGet(contextManageUrl(pod.name, path), token, "application/ld+json")

    val b64 = UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(iri))
    val claim = http.preparePut("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/resources/$b64?context=$iri")
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("""{"@id":"$iri","${SempodsVocabulary.PUBLIC}":[{"@value":true}]}""")
      .execute()
    assertTrue(claim.statusCode in 200..201, "the claim is an ordinary write; body=${claim.responseBody}")

    val after = registryGet(contextManageUrl(pod.name, path), token, "application/ld+json")
    assertEquals(before.responseBody, after.responseBody, "the registry answers for what it holds")
    assertEquals(before.headers.get("ETag"), after.headers.get("ETag"))
  }

  @Test
  fun `the transitional envelope carries no validator, so a condition on it changes nothing`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val path = "apps/example/tasks"
    createContextViaDao(podId = podId, podName = pod.name, contextPath = path)
    val token = mintScopedToken(pod.name, listOf("${contextUri(pod.name, path)}#read"))

    val legacy = registryGet(contextManageUrl(pod.name, path), token, "application/json")
    assertEquals(200, legacy.statusCode, legacy.responseBody)
    // The envelope states the caller's own permissions, which the registry's RDF does not: a tag
    // hashed from that model would stay put while this body moved.
    assertNull(legacy.headers.get("ETag"))

    val conditional = http.prepareGet(contextManageUrl(pod.name, path))
      .addHeader("Accept", "application/json")
      .addHeader("If-None-Match", "\"any-tag-a-caller-kept\"")
      .addHeader("Authorization", "Bearer $token")
      .execute()

    assertEquals(200, conditional.statusCode, "the transitional shape offers no conditional read")
  }

  @Test
  fun `negotiation follows the quality values, and a representation excluded at zero is not sent`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val path = "apps/example/tasks"
    createContextViaDao(podId = podId, podName = pod.name, contextPath = path)
    val token = mintScopedToken(pod.name, listOf("${contextUri(pod.name, path)}#read"))

    val preferred = registryGet(contextsBaseUrl(pod.name), token, "application/n-quads;q=0.5, application/ld+json")
    assertEquals(200, preferred.statusCode, preferred.responseBody)
    assertTrue(preferred.contentType.orEmpty().startsWith("application/ld+json"), preferred.contentType)

    // A wildcard beside an exclusion: the wildcard would match JSON-LD, and `q=0` says it is
    // unacceptable.
    val excluded = registryGet(contextsBaseUrl(pod.name), token, "*/*, application/ld+json;q=0")
    assertEquals(200, excluded.statusCode, excluded.responseBody)
    assertFalse(excluded.contentType.orEmpty().startsWith("application/ld+json"), excluded.contentType)

    // A profile this route does not produce names something else, so what is left excludes
    // everything and the honest answer is a refusal.
    val profiled = registryGet(
      contextsBaseUrl(pod.name),
      token,
      "application/ld+json;profile=\"https://example.org/profile\", application/ld+json;q=0, application/n-quads;q=0, application/json;q=0",
    )
    assertEquals(406, profiled.statusCode, profiled.responseBody)

    // A parameter written after the weight is an accept extension, and names no representation.
    val extended = registryGet(contextsBaseUrl(pod.name), token, "application/ld+json;q=1;foo=bar")
    assertEquals(200, extended.statusCode, extended.responseBody)
    assertTrue(extended.contentType.orEmpty().startsWith("application/ld+json"), extended.contentType)

    // A parameter the representations do carry keeps matching.
    val charset = registryGet(contextsBaseUrl(pod.name), token, "application/json;charset=utf-8")
    assertEquals(200, charset.statusCode, charset.responseBody)
    assertEquals("true", charset.headers.get("Deprecation"))
  }

  @Test
  fun `every refusal of the registry carries its cache isolation`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)

    val unknownPod = registryGet(
      "${SempodsModule.config.apiBaseUrl}pod-that-never-existed/_system/contexts",
      null,
      "application/ld+json",
    )
    val unsatisfiable = registryGet(contextsBaseUrl(pod.name), null, "text/turtle")
    val refusedBearer = http.prepareGet(contextsBaseUrl(pod.name))
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer not-a-token")
      .execute()

    assertEquals(406, unsatisfiable.statusCode, unsatisfiable.responseBody)
    assertEquals(401, refusedBearer.statusCode, refusedBearer.responseBody)
    listOf(unknownPod, unsatisfiable, refusedBearer).forEach { response ->
      assertTrue(response.statusCode >= 400, "a refusal, not a ${response.statusCode}")
      // `SPS-CTX-036` covers errors, and these three are built where no registry method runs.
      assertEquals("no-store", response.headers.get("Cache-Control"), "status ${response.statusCode}")
      assertEquals("Accept, Authorization", response.headers.get("Vary"), "status ${response.statusCode}")
    }
  }

  // ── The RDF4J adapter against these routes ───────────────────────────────────

  @Test
  fun `the RDF4J adapter creates a context and reads the registry as models`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerToken = mintOwnerPodToken(pod.name, webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email)))
    val tasks = contextUri(pod.name, "apps/example/tasks")
    val tasksIri = Values.iri(tasks)

    withRdf4jContexts(pod.name, SempodsRequestAuth.bearer(ownerToken)) { contexts ->
      val created = assertNotNull(contexts.create(tasks, SempodsContextCreate.fields().withLabel("Tasks")).body)
      assertTrue(created.contains(tasksIri, RDF.TYPE, Values.iri("${SD_NS}NamedGraph")), "created: $created")
      assertTrue(created.contains(tasksIri, Values.iri(RDFS_LABEL), Values.literal("Tasks")), "created: $created")
    }

    val reader = mintScopedToken(pod.name, listOf("$tasks#read"))
    withRdf4jContexts(pod.name, SempodsRequestAuth.bearer(reader)) { contexts ->
      val read = contexts.getModel(tasks)
      assertTrue(assertNotNull(read.body).contains(tasksIri, Values.iri(RDFS_LABEL), Values.literal("Tasks")))
      assertEquals(304, contexts.getModel(tasks, checkNotNull(read.headers["ETag"])).status)
      assertTrue(assertNotNull(contexts.listModel().body).contains(null, null, tasksIri), "the catalogue names the context")
    }
  }

  private fun <T> withRdf4jContexts(podName: String, auth: SempodsRequestAuth, block: (SempodsRdf4jContexts) -> T): T {
    val client = SempodsOkHttp.install(OkHttpClient.Builder()).build()
    try {
      return block(SempodsRdf4jPod(SempodsPod(SempodsSession(SempodsPodBase.of("${SempodsModule.config.apiBaseUrl}$podName"), auth), client)).contexts())
    } finally {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
  }

  private companion object {

    const val SD_NS = "http://www.w3.org/ns/sparql-service-description#"

    const val RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
  }
}
