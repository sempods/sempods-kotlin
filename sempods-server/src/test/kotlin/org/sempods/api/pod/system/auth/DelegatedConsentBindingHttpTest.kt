package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.api.pod.system.auth.DelegatedAccessFlow.ConsentPage
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.net.UrlUtil
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The delegated consent answers the screen that was rendered: its request and the rows it offered
 * are bound to its transaction, and a submission the dialog could not have produced is refused with
 * nothing written.
 *
 * `DelegatedAccessHttpTest` pins that delegated access still works end to end; this pins what the
 * binding refuses, and how the three ways out of the dialog stay apart.
 */
class DelegatedConsentBindingHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var flow: DelegatedAccessFlow

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  @Inject
  private lateinit var podGrantsDao: PodGrantsDao

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Test
  fun `a form rendered for one app and posted as another is refused, and writes nothing`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val first = flow.register(owned.pod)
    val second = flow.register(owned.pod, redirectUri = "http://localhost:5174/callback")
    val page = ConsentPage.of(flow.authorize(owned.pod, first, owned.cookie))

    val posted = flow.submit(
      page, owned.cookie, scopes = setOf("$notes#read"),
      extra = listOf("client_id" to second.clientId, "redirect_uri" to second.redirectUri),
    )

    assertEquals(400, posted.statusCode, posted.responseBody)
    assertNull(posted.getHeader("Location"), "neither app is the one this answer is owed to")
    assertEquals(emptySet(), grants(owned, first))
    assertEquals(emptySet(), grants(owned, second))
    assertEquals(403, flow.submit(page, owned.cookie, scopes = setOf("$notes#read")).statusCode, "the token is spent")
  }

  @Test
  fun `a form that changes its state or PKCE challenge is refused, and one that repeats them is not`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val app = flow.register(owned.pod)

    for (changed in listOf("state" to "another", "code_challenge" to "A".repeat(43), "code_challenge_method" to "plain")) {
      val page = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie, state = "rendered"))
      val posted = flow.submit(page, owned.cookie, scopes = setOf("$notes#read"), extra = listOf(changed))
      assertEquals(400, posted.statusCode, "$changed: ${posted.responseBody}")
      assertEquals(emptySet(), grants(owned, app), "$changed")
    }

    val page = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie, state = "rendered"))
    val repeated = flow.submit(
      page, owned.cookie, scopes = setOf("$notes#read"),
      extra = listOf("state" to "rendered", "code_challenge" to DelegatedAccessFlow.CODE_CHALLENGE),
    )
    assertEquals("rendered", query(repeated)["state"], repeated.getHeader("Location"))
    val exchanged = flow.exchangeCode(owned.pod, app, flow.codeFrom(repeated))
    assertEquals(200, exchanged.statusCode, "the code carries the rendered challenge: ${exchanged.responseBody}")
  }

  @Test
  fun `a context the dialog did not offer refuses the submission, and the grants stay as they were`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val app = flow.register(owned.pod)
    connect(owned, app, setOf("$notes#read"))

    val page = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))
    // Created after the page was rendered: the person never saw it, so they never ticked it.
    val late = owned.context("late")
    val posted = flow.submit(page, owned.cookie, scopes = setOf("$notes#write"), extra = listOf("scope" to "$late#read"))

    assertEquals("invalid_scope", query(posted)["error"], posted.getHeader("Location"))
    assertEquals(setOf("$notes#read"), grants(owned, app), "the grants stay as they were")
  }

  @Test
  fun `an invalid new context refuses the submission, and nothing is created or granted`() {
    val owned = ownedPod()
    val app = flow.register(owned.pod)
    val page = ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))

    val posted = flow.submit(
      page, owned.cookie, scopes = emptySet(),
      newContexts = mapOf("fine/one" to setOf("read"), "apps/claimed" to setOf("read")),
    )

    assertEquals("invalid_request", query(posted)["error"], posted.getHeader("Location"))
    val contexts = podContextsDao.fetchByPod(checkNotNull(owned.pod.id)).map { it.contextUri }
    assertTrue(contexts.none { it.endsWith("/fine/one") || it.endsWith("/apps/claimed") }, "$contexts")
    assertEquals(emptySet(), grants(owned, app))
  }

  @Test
  fun `cancelling, confirming nothing and a refused submission give three different answers`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val app = flow.register(owned.pod)
    connect(owned, app, setOf("$notes#read"))

    val cancelled = flow.submit(ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie, state = "c")), owned.cookie, action = "cancel")
    assertEquals(mapOf("error" to "access_denied", "error_description" to "cancelled", "state" to "c"), query(cancelled))
    assertEquals(setOf("$notes#read"), grants(owned, app), "cancelling changes nothing")

    val refused = flow.submit(
      ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie, state = "r")), owned.cookie,
      extra = listOf("scope" to "${owned.context("unseen")}#read"),
    )
    assertEquals("invalid_scope", query(refused)["error"])
    assertEquals(setOf("$notes#read"), grants(owned, app), "a refused submission changes nothing")

    val empty = flow.submit(ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie, state = "e")), owned.cookie, scopes = emptySet())
    assertEquals(mapOf("error" to "access_denied", "error_description" to "app disconnected", "state" to "e"), query(empty))
    assertEquals(emptySet(), grants(owned, app), "confirming nothing ends the access")
  }

  @Test
  fun `an unbound form from a node on the old code is still accepted`() {
    // The rollout rule: old nodes write transactions without a binding while a deploy runs, and the
    // page they rendered carries the request itself. Accepted for the rest of 0.2.x.
    val owned = ownedPod()
    val app = flow.register(owned.pod)

    val posted = flow.consent(owned.pod, owned.webId, app, owned.cookie, state = "old-node")

    assertEquals("old-node", query(posted)["state"], posted.getHeader("Location"))
    assertEquals(200, flow.exchangeCode(owned.pod, app, flow.codeFrom(posted)).statusCode)
  }

  @Test
  fun `a row below a context the app manages says so, and keeps boxes of its own`() {
    val owned = ownedPod()
    val projects = owned.context("projects")
    val plan = owned.context("projects/plan")
    val app = flow.register(owned.pod)
    connect(owned, app, setOf("$projects#manage"))

    val response = flow.authorize(owned.pod, app, owned.cookie)
    val page = ConsentPage.of(response)

    assertTrue(page.offered.containsAll(setOf("$plan#read", "$plan#write", "$plan#manage")), "${page.offered}")
    assertTrue(page.ticked.none { it.startsWith("$plan#") }, "the note is not a grant: ${page.ticked}")
    // The shell includes each shared fragment once; an unclosed one swallows the next.
    for (id in listOf("newContextInput", "pendingContextTemplate")) {
      assertEquals(1, Regex("""id="$id"""").findAll(response.responseBody).count(), id)
    }
    val note = Regex("""Also reached through .*?</div>""", RegexOption.DOT_MATCHES_ALL).findAll(response.responseBody).toList()
    assertEquals(1, note.size, "one row lies below the root")
    // Named as the root's own row names it.
    assertTrue("/projects</span>" in note.single().value, note.single().value)
  }

  // ── Fixture ─────────────────────────────────────────────────────────────────

  private inner class Owned(val pod: PodDbo, val webId: String) {
    val cookie: String get() = signIn(pod.name, webId).cookie

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

  private fun connect(owned: Owned, app: DelegatedAccessFlow.App, scopes: Set<String>) {
    val submitted = flow.submit(ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie)), owned.cookie, scopes = scopes)
    assertEquals(200, flow.exchangeCode(owned.pod, app, flow.codeFrom(submitted)).statusCode)
  }

  /** The app-level grants [app] holds for the owner, `public-read` left out. */
  private fun grants(owned: Owned, app: DelegatedAccessFlow.App): Set<String> =
    podGrantsDao.fetchGrantStrings(checkNotNull(owned.pod.id), app.clientId, listOf(owned.webId))
      .filterTo(mutableSetOf()) { '#' in it }

  /** The query of a redirect's `Location`, decoded. */
  private fun query(response: TestHttpResponse): Map<String, String> {
    val location = checkNotNull(response.getHeader("Location")) { "no redirect: ${response.statusCode} ${response.responseBody}" }
    return UrlUtil.queryParams(URI(location).rawQuery)
  }
}
