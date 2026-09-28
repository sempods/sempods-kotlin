package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.api.pod.system.auth.DelegatedAccessFlow.ConsentPage
import org.sempods.api.pod.system.auth.DelegatedAccessFlow.Tokens
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.net.SempodsVocabulary
import org.sempods.commons.net.UrlUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.PodTokenIssuer
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Delegated access end to end: an app acting for a person through Authorization Code + PKCE, from
 * registration to the pod's MCP endpoint.
 *
 * One test per case, each on a pod of its own. Every token comes from the dialog, never from
 * `mintScopedToken`, and every consent is the rendered form posted back. The finer points of each
 * step — store rows, error texts, edge cases — are pinned in `PodAuthEndpointHttpTest`; these tests
 * pin that the steps still add up to working access.
 */
class DelegatedAccessHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var flow: DelegatedAccessFlow

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  private val noteName = "Written through a delegated token"

  @Test
  fun `an app registers, is shown the dialog, and redeems its code with the PKCE verifier`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val app = flow.register(owned.pod)
    assertTrue(app.clientId.startsWith("dyn:"), app.clientId)

    val page = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie, state = "connect"))

    assertEquals(app.clientId, page.hidden["client_id"])
    assertEquals(app.redirectUri, page.hidden["redirect_uri"])
    assertEquals("connect", page.hidden["state"])
    assertEquals(DelegatedAccessFlow.CODE_CHALLENGE, page.hidden["code_challenge"])
    assertEquals("S256", page.hidden["code_challenge_method"])
    assertTrue(page.hidden["csrf"].orEmpty().isNotBlank(), "the form carries its token: ${page.hidden}")
    val public = sempodsTestFactory.publicContextUri(owned.pod.name).toString()
    assertEquals(setOf(PUBLIC_READ_SCOPE) + rows(notes) + rows(diary) + rows(public), page.offered)
    assertEquals(setOf(PUBLIC_READ_SCOPE), page.ticked, "a first dialog pre-ticks public-read alone")
    assertTrue(page.has("durableToggle") && !page.durableTicked, "the lifetime is offered and left unticked")
    assertTrue(page.has("newContextInput"), "the owner may create a context here")
    assertFalse(page.has("disconnectBtn"), "there is nothing to remove yet")

    val submitted = flow.submit(page, owned.cookie, scopes = setOf("$notes#read", "$notes#write"))
    assertEquals(303, submitted.statusCode, submitted.responseBody)
    val location = checkNotNull(submitted.getHeader("Location"))
    assertTrue(location.startsWith(app.redirectUri), location)
    assertEquals("connect", query(submitted)["state"])

    val exchanged = flow.exchangeCode(owned.pod, app, flow.codeFrom(submitted))
    assertEquals(200, exchanged.statusCode, exchanged.responseBody)
    val tokens = Tokens.of(exchanged)
    assertTrue("bearer".equals(tokens.json["token_type"] as String?, ignoreCase = true), tokens.json.toString())
    assertTrue(tokens.accessToken.isNotBlank() && tokens.refreshToken.isNotBlank())

    val again = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))
    assertEquals(setOf("$notes#read", "$notes#write"), again.ticked, "the next dialog pre-ticks what was granted")
    assertTrue(again.has("disconnectBtn"), "and offers to remove it")
  }

  @Test
  fun `a connected app lists its tools, reads and writes where granted, and is refused elsewhere`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val app = flow.register(owned.pod)
    val token = connect(owned, app, setOf("$notes#read", "$notes#write")).accessToken

    val listed = mcp(owned.pod, token, "tools/list", emptyMap())
    assertEquals(200, listed.statusCode, listed.responseBody)
    @Suppress("UNCHECKED_CAST")
    val tools = ((json(listed)["result"] as Map<String, Any?>)["tools"] as List<Map<String, Any?>>).map { it["name"] }
    assertTrue(tools.containsAll(listOf("create_resource", "get_resource")), tools.toString())

    val note = "${podBase(owned.pod)}/notes/${randomId()}"
    assertEquals(201, payload(createResource(owned.pod, token, notes, note))["status"])
    val read = payload(tool(owned.pod, token, "get_resource", mapOf("resource_iri" to note, "context_iri" to listOf(notes))))
    assertTrue(JsonMappers.default().writeValueAsString(read["jsonld"]).contains(noteName), read.toString())

    val refused = createResource(owned.pod, token, diary, "${podBase(owned.pod)}/diary/${randomId()}")
    assertEquals(true, refused["isError"], refused.toString())
    assertTrue("403" in text(refused), text(refused))

    val catalogue = catalogue(owned.pod, token)
    assertEquals(setOf(notes), catalogue.readable)
    assertEquals(setOf(notes), catalogue.writable)
  }

  @Test
  fun `a narrowed selection takes the removed context from an access token already issued`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val entry = sempodsTestFactory.seedEvent(pod = owned.pod.name, context = URI(diary), name = "Diary ${randomId()}")
    val app = flow.register(owned.pod)
    val token = connect(owned, app, setOf("$notes#read", "$notes#write", "$diary#read")).accessToken
    assertTrue(entry.toString() in selectIn(owned.pod, token, diary), "the token reads the diary beforehand")

    val page = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))
    assertEquals(setOf("$notes#read", "$notes#write", "$diary#read"), page.ticked)
    val narrowed = flow.submit(page, owned.cookie, scopes = page.ticked - "$diary#read")
    assertEquals(303, narrowed.statusCode, narrowed.responseBody)

    assertEquals(setOf(notes), catalogue(owned.pod, token).readable)
    assertFalse(entry.toString() in selectIn(owned.pod, token, diary), "the same token no longer reads the diary")
    assertEquals(201, payload(createResource(owned.pod, token, notes, "${podBase(owned.pod)}/notes/${randomId()}"))["status"])
  }

  @Test
  fun `a refresh rotates the token, and replaying the old one ends the family`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val app = flow.register(owned.pod)
    val first = connect(owned, app, setOf("$notes#read"))

    val rotated = flow.refresh(owned.pod, app, first.refreshToken)
    assertEquals(200, rotated.statusCode, rotated.responseBody)
    val second = Tokens.of(rotated)
    assertNotEquals(first.refreshToken, second.refreshToken, "a rotation hands out a new refresh token")
    assertEquals(setOf(notes), catalogue(owned.pod, second.accessToken).readable)

    val replayed = flow.refresh(owned.pod, app, first.refreshToken)
    assertEquals(400, replayed.statusCode, replayed.responseBody)
    assertEquals("invalid_grant", json(replayed)["error"])
    val successor = flow.refresh(owned.pod, app, second.refreshToken)
    assertEquals(400, successor.statusCode, "the replay ended the whole family: ${successor.responseBody}")
    assertEquals("invalid_grant", json(successor)["error"])
  }

  @Test
  fun `an empty selection ends the authorization, and the next refresh fails`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val app = flow.register(owned.pod)
    val tokens = connect(owned, app, setOf("$notes#read"), durable = true)

    val page = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie, state = "empty"))
    assertTrue(page.durableTicked, "the durable connection is on record")
    val ended = flow.submit(page, owned.cookie, scopes = emptySet())

    assertEquals(303, ended.statusCode, ended.responseBody)
    assertEquals("access_denied", query(ended)["error"])
    assertEquals("app disconnected", query(ended)["error_description"])
    assertEquals("empty", query(ended)["state"])
    val refreshed = flow.refresh(owned.pod, app, tokens.refreshToken)
    assertEquals(400, refreshed.statusCode, refreshed.responseBody)
    assertEquals("invalid_grant", json(refreshed)["error"])
    assertEquals(emptySet(), catalogue(owned.pod, tokens.accessToken).readable)
    assertFalse(ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie)).has("disconnectBtn"), "nothing is left to remove")
  }

  @Test
  fun `a did-web app is authorized silently after its consent, and a dyn app is not`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val didWeb = DelegatedAccessFlow.App("did:web:localhost%3A5173", "http://localhost:5173/callback")
    val dyn = flow.register(owned.pod)
    connect(owned, didWeb, setOf("$notes#read"))
    connect(owned, dyn, setOf("$notes#read"))

    val silent = flow.authorize(owned.pod, didWeb, owned.cookie, prompt = "none", state = "silent")
    assertEquals(303, silent.statusCode, silent.responseBody)
    assertEquals("silent", query(silent)["state"])
    val exchanged = flow.exchangeCode(owned.pod, didWeb, flow.codeFrom(silent))
    assertEquals(200, exchanged.statusCode, exchanged.responseBody)
    assertEquals(setOf(notes), catalogue(owned.pod, Tokens.of(exchanged).accessToken).readable)

    val refused = flow.authorize(owned.pod, dyn, owned.cookie, prompt = "none")
    assertEquals(303, refused.statusCode, refused.responseBody)
    assertEquals("consent_required", query(refused)["error"])
    assertNull(query(refused)["code"], "a dyn client is never authorized silently")
  }

  @Test
  fun `a context created in the dialog is private and owner-only, and the app gets only the ticked rows`() {
    val owned = ownedPod()
    val app = flow.register(owned.pod)
    val page = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))

    // The page's script adds a new context with read and write ticked; the person unticks write.
    val submitted = flow.submit(page, owned.cookie, scopes = emptySet(), newContexts = mapOf("projects/plan" to setOf("read")))
    assertEquals(303, submitted.statusCode, submitted.responseBody)
    val exchanged = flow.exchangeCode(owned.pod, app, flow.codeFrom(submitted))
    assertEquals(200, exchanged.statusCode, exchanged.responseBody)
    val token = Tokens.of(exchanged).accessToken
    val plan = sempodsUriBuilder.buildContext(owned.pod.name, "projects/plan").toString()

    val catalogue = catalogue(owned.pod, token)
    assertEquals(setOf(plan), catalogue.readable)
    assertEquals(emptySet(), catalogue.writable)
    assertEquals(emptySet(), catalogue.manageable)
    assertEquals(false, isPublic(owned.pod, token, "projects/plan"), "a context created in the dialog is private")
    val write = createResource(owned.pod, token, plan, "${podBase(owned.pod)}/plan/${randomId()}")
    assertEquals(true, write["isError"], write.toString())
    assertTrue("403" in text(write), text(write))
    val another = http.preparePut("${podBase(owned.pod)}/_system/contexts/projects/other")
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("{}")
      .execute()
    assertEquals(403, another.statusCode, "creating one context grants no management of the pod: ${another.responseBody}")

    val somebodyElse = webIdUriDeriver.deriveFromEmail(sempodsTestFactory.newOwner().email)
    val theirs = ConsentPage.of(flow.authorize(owned.pod, app, signIn(owned.pod.name, somebodyElse).cookie))
    assertTrue(theirs.offered.none { it.startsWith(plan) }, "nobody but the owner holds the new context: ${theirs.offered}")
  }

  @Test
  fun `two open dialogs submitted in turn both complete and the later stands, and a page from before a disconnect is refused`() {
    // Submitted one after the other. Overlapping submissions can leave the union of both selections,
    // because the replacement deletes and then inserts (#338); no test pins which one wins then.
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val app = flow.register(owned.pod)

    val first = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))
    val second = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))
    val earlier = flow.submit(first, owned.cookie, scopes = setOf("$notes#read"))
    val later = flow.submit(second, owned.cookie, scopes = setOf("$diary#read"))
    assertEquals(303, earlier.statusCode, earlier.responseBody)
    assertEquals(303, later.statusCode, later.responseBody)
    assertTrue(query(earlier).containsKey("code"), earlier.getHeader("Location"))
    val exchanged = flow.exchangeCode(owned.pod, app, flow.codeFrom(later))
    assertEquals(200, exchanged.statusCode, exchanged.responseBody)
    assertEquals(setOf(diary), catalogue(owned.pod, Tokens.of(exchanged).accessToken).readable)

    val renderedBefore = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))
    val ending = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))
    assertEquals("access_denied", query(flow.submit(ending, owned.cookie, action = "disconnect"))["error"])

    val stale = flow.submit(renderedBefore, owned.cookie, scopes = setOf("$notes#read"))
    assertEquals(403, stale.statusCode, stale.responseBody)
    val after = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))
    assertTrue(after.ticked.none { it.startsWith(notes) || it.startsWith(diary) }, "nothing was written back: ${after.ticked}")
  }

  @Test
  fun `the privileged dialogs offer their authority alone and unticked, and grant it for an hour`() {
    val owned = ownedPod()
    val app = flow.register(owned.pod)

    val bearers = listOf(CONTEXTS_MANAGE_SCOPE, SERVICE_CLIENTS_MANAGE_SCOPE).associateWith { scope ->
      val page = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie, scope = scope))
      assertEquals(setOf(scope), page.offered, "the dialog asks this one question")
      assertEquals(emptySet(), page.ticked, "and does not answer it: $scope")
      assertFalse(page.has("durableToggle"), "no lifetime: $scope")
      assertFalse(page.has("newContextInput"), "no context creation: $scope")
      assertFalse(page.has("disconnectBtn"), "no disconnect: $scope")

      val submitted = flow.submit(page, owned.cookie, scopes = setOf(scope))
      assertEquals(303, submitted.statusCode, submitted.responseBody)
      val exchanged = flow.exchangeCode(owned.pod, app, flow.codeFrom(submitted))
      assertEquals(200, exchanged.statusCode, exchanged.responseBody)
      val tokens = Tokens.of(exchanged)
      assertEquals(scope, tokens.json["scope"])
      assertEquals(PodTokenIssuer.USER_TOKEN_TTL_SECONDS, (tokens.json["expires_in"] as Number).toLong())
      assertNull(tokens.json["refresh_token"], "the authority is an hour and no more: $scope")
      tokens.accessToken
    }

    val created = http.preparePut("${podBase(owned.pod)}/_system/contexts/projects")
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer ${bearers.getValue(CONTEXTS_MANAGE_SCOPE)}")
      .setBody("{}")
      .execute()
    assertEquals(201, created.statusCode, created.responseBody)
    val listed = http.prepareGet("${podBase(owned.pod)}/_system/auth/service-clients")
      .addHeader("Authorization", "Bearer ${bearers.getValue(SERVICE_CLIENTS_MANAGE_SCOPE)}")
      .execute()
    assertEquals(200, listed.statusCode, listed.responseBody)
  }

  // ── Fixture ─────────────────────────────────────────────────────────────────

  private inner class Owned(val pod: PodDbo, val webId: String) {
    val cookie: String get() = signIn(pod.name, webId).cookie

    /** Registers a private context at [path] and answers its IRI. */
    fun context(path: String): String {
      val uri = sempodsUriBuilder.buildContext(pod.name, path)
      podFacade.createContext(podName = pod.name, contextUri = uri, public = false, label = path, description = null)
      return uri.toString()
    }
  }

  private fun ownedPod(): Owned {
    val owner = sempodsTestFactory.newOwner()
    return Owned(sempodsTestFactory.newPod(ownerUser = owner), webIdUriDeriver.deriveFromEmail(owner.email))
  }

  private fun podBase(pod: PodDbo) = "${SempodsModule.config.apiBaseUrl}${pod.name}"

  private fun rows(context: String) = setOf("$context#read", "$context#write", "$context#manage")

  /** Renders the dialog, ticks [scopes], submits and redeems the code. */
  private fun connect(owned: Owned, app: DelegatedAccessFlow.App, scopes: Set<String>, durable: Boolean = false): Tokens {
    val page = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))
    val submitted = flow.submit(page, owned.cookie, scopes = scopes, durable = durable)
    assertEquals(303, submitted.statusCode, submitted.responseBody)
    val exchanged = flow.exchangeCode(owned.pod, app, flow.codeFrom(submitted))
    assertEquals(200, exchanged.statusCode, exchanged.responseBody)
    return Tokens.of(exchanged)
  }

  private fun mcp(pod: PodDbo, token: String, method: String, params: Map<String, Any>): TestHttpResponse =
    http.preparePost("${podBase(pod)}/_system/mcp")
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(JsonMappers.default().writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params)))
      .execute()

  /** A `tools/call`, answered with its JSON-RPC `result`. */
  @Suppress("UNCHECKED_CAST")
  private fun tool(pod: PodDbo, token: String, name: String, arguments: Map<String, Any>): Map<String, Any?> {
    val response = mcp(pod, token, "tools/call", mapOf("name" to name, "arguments" to arguments))
    assertEquals(200, response.statusCode, response.responseBody)
    return json(response)["result"] as Map<String, Any?>
  }

  private fun createResource(pod: PodDbo, token: String, context: String, iri: String): Map<String, Any?> =
    tool(pod, token, "create_resource", mapOf(
      "context_iri" to context,
      "resource_iri" to iri,
      "jsonld" to mapOf("@id" to iri, "https://schema.org/name" to noteName),
    ))

  /** The subjects a `sparql_select` narrowed to [context] finds, as the tool's text. */
  private fun selectIn(pod: PodDbo, token: String, context: String): String {
    val result = tool(pod, token, "sparql_select", mapOf(
      "query" to "SELECT ?s WHERE { ?s ?p ?o }",
      "context_iri" to listOf(context),
    ))
    assertNotEquals(true, result["isError"], result.toString())
    return text(result)
  }

  private fun text(result: Map<String, Any?>): String = ((result["content"] as List<*>).first() as Map<*, *>)["text"] as String

  /** The JSON a successful tool call answers. */
  @Suppress("UNCHECKED_CAST")
  private fun payload(result: Map<String, Any?>): Map<String, Any?> {
    assertNotEquals(true, result["isError"], result.toString())
    return JsonMappers.default().readValue(text(result), Map::class.java) as Map<String, Any?>
  }

  private class Catalogue(val readable: Set<String>, val writable: Set<String>, val manageable: Set<String>)

  /** `GET {pod}/_system/contexts` as JSON-LD: the contexts [token] may read, write and manage. */
  private fun catalogue(pod: PodDbo, token: String): Catalogue {
    val response = http.prepareGet("${podBase(pod)}/_system/contexts")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, response.statusCode, response.responseBody)
    val body = json(response)
    return Catalogue(
      ids(body[SempodsVocabulary.READABLE_CONTEXT]),
      ids(body[SempodsVocabulary.WRITABLE_CONTEXT]),
      ids(body[SempodsVocabulary.MANAGEABLE_CONTEXT]),
    )
  }

  /** The registry's `public` flag of the context at [path], as [token] reads its description. */
  private fun isPublic(pod: PodDbo, token: String, path: String): Boolean? {
    val response = http.prepareGet("${podBase(pod)}/_system/contexts/$path")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, response.statusCode, response.responseBody)
    return values(json(response)[SempodsVocabulary.PUBLIC]).singleOrNull()?.get("@value") as Boolean?
  }

  private fun ids(value: Any?): Set<String> = values(value).map { it["@id"] as String }.toSet()

  private fun values(value: Any?): List<Map<*, *>> = when (value) {
    null -> emptyList()
    is List<*> -> value.map { it as Map<*, *> }
    else -> listOf(value as Map<*, *>)
  }

  @Suppress("UNCHECKED_CAST")
  private fun json(response: TestHttpResponse): Map<String, Any?> =
    JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>

  /** The query of a redirect's `Location`, decoded. */
  private fun query(response: TestHttpResponse): Map<String, String> {
    val location = checkNotNull(response.getHeader("Location")) { "no redirect: ${response.statusCode} ${response.responseBody}" }
    return UrlUtil.queryParams(URI(location).rawQuery)
  }
}
