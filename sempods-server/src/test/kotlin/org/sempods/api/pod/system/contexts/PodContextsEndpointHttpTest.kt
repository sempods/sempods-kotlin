package org.sempods.api.pod.system.contexts

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.json.JsonUtil
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.utils.UriEncodingUtil
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.SempodsUriBuilder
import org.sempods.api.pod.system.auth.PodTokenIssuer
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  private fun mintServiceToken(podId: ObjectId, podName: String, scopes: Set<String>): String {
    val registered = podServiceClientStore.register(
      podId = podId,
      podBaseUrl = "${SempodsModule.config.apiBaseUrl}${podName}/",
      clientId = "notes-app",
      scopes = scopes,
      label = "notes-app",
    )
    val response = http.preparePost(tokenUrl(podName))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(registered.dbo.clientId, registered.plaintextSecret))
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
      .addHeader("Authorization", "Bearer $oauthToken")
      .execute()

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
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

    val anonymous = http.prepareGet(contextsBaseUrl(pod.name)).execute()
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
  fun `delete of a manage root revokes the anchored service-client registration`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val podId = checkNotNull(pod.id)
    val ownerToken = mintOwnerPodToken(pod.name, webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email)))
    createContextViaDao(podId = podId, podName = pod.name, contextPath = "apps/notes")
    val registered = podServiceClientStore.register(
      podId = podId,
      podBaseUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/",
      clientId = "notes-app",
      scopes = setOf("${contextUri(pod.name, "apps/notes")}#manage"),
      label = "notes-app",
    )

    val deleteResponse = http.prepareDelete(contextManageUrl(pod.name, "apps/notes"))
      .addHeader("Authorization", "Bearer $ownerToken")
      .execute()
    assertEquals(204, deleteResponse.statusCode)

    // the registration is revoked with its anchor — the secret must not mint new tokens
    // for the deleted root (manage surviving descendants, recreate the root)
    assertNull(
      podServiceClientDao.findByClientId(podId, "notes-app"),
      "service-client registration anchored at the deleted root must be revoked",
    )
    val tokenResponse = http.preparePost(tokenUrl(pod.name))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(registered.dbo.clientId, registered.plaintextSecret))
      .setBody("grant_type=client_credentials")
      .execute()
    assertEquals(401, tokenResponse.statusCode, "revoked client must not mint tokens; body=${tokenResponse.responseBody}")
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
      podId = podId,
      podBaseUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/",
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
    val serviceToken = mintServiceToken(podId, pod.name, setOf("$appRoot#manage"))

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
    val serviceToken = mintServiceToken(podId, pod.name, setOf("$appRoot#manage"))

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
    val serviceToken = mintServiceToken(podId, pod.name, setOf("$appRoot#manage"))

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
    val serviceToken = mintServiceToken(podId, pod.name, setOf("$appRoot#manage"))

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
}
