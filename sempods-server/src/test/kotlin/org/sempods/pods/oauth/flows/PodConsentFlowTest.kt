package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.oauth.PodSignOut
import org.sempods.pods.oauth.PodTokenIssuer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the consent dialog coming back decides, without a server and without a protocol message
 * type.
 *
 * The form is the authoritative new state, so most of these cases are about what a submission is
 * allowed to *undo*: which tickets may be spent, which page is too old to write grants back, and
 * what the two named ways out actually end. `PodAuthEndpointHttpTest` drives the same paths over
 * HTTP; this says what it delegates to.
 *
 * The stores are the real ones, for the reason `PodTokenExchangeTest` gives.
 */
internal class PodConsentFlowTest : PodBrowserFlowTest() {

  @Inject
  private lateinit var flow: PodConsentFlow

  @Inject
  private lateinit var authorizationCodeStore: AuthorizationCodeStore

  @Inject
  private lateinit var podSignOut: PodSignOut

  private fun form(
    csrf: String?,
    scopes: List<String>? = null,
    client: String? = clientId,
    address: String? = redirectUri,
    state: String? = "state-${randomId()}",
    newContexts: List<String>? = null,
    newContextScopes: List<String>? = null,
    durable: Boolean = true,
    action: String? = null,
  ) = PodConsentForm(
    clientId = client,
    redirectUri = address,
    state = state,
    codeChallenge = challenge,
    codeChallengeMethod = "S256",
    csrf = csrf,
    scopes = scopes,
    newContexts = newContexts,
    newContextScopes = newContextScopes,
    durable = durable,
    action = action,
  )

  private fun redirectedError(result: PodConsentResult): OAuthErrorDelivery.Redirect {
    val error = assertIs<PodConsentResult.Error>(result, "was: $result")
    return assertIs<OAuthErrorDelivery.Redirect>(error.delivery)
  }

  private fun issuedCode(result: PodConsentResult): String =
    assertIs<PodConsentResult.Code>(result, "was: $result").code

  // ── What may be acted on at all ───────────────────────────────────────────

  @Test
  fun `a client_id this pod cannot place is refused as malformed`() {
    val owned = Owned()
    val result = flow.submit(owned.pod, form(csrf = owned.ticket(), client = "https://app.example"), owned.session)
    assertEquals(PodConsentResult.Refused(PodConsentRefusal.MALFORMED_CLIENT_ID), result)
  }

  @Test
  fun `a dyn client whose registration is gone is told that, not that the id is malformed`() {
    val owned = Owned()
    val result = flow.submit(owned.pod, form(csrf = owned.ticket(), client = "dyn:${randomId()}"), owned.session)
    assertEquals(PodConsentResult.Refused(PodConsentRefusal.UNREGISTERED_CLIENT), result)
  }

  @Test
  fun `a submission with no session is refused before its ticket is spent`() {
    val owned = Owned()
    val ticket = owned.ticket()

    assertEquals(
      PodConsentResult.Refused(PodConsentRefusal.SESSION_EXPIRED),
      flow.submit(owned.pod, form(csrf = ticket), session = null),
    )
    // The ticket survives, so the person can sign in again and submit the page in front of them.
    assertNotNull(consentTransactionStore.consume(ticket), "the ticket must not have been spent")
  }

  @Test
  fun `a ticket is spent once`() {
    val owned = Owned()
    val ticket = owned.ticket()
    owned.grant(owned.readScope)

    issuedCode(flow.submit(owned.pod, form(csrf = ticket, scopes = listOf(owned.readScope)), owned.session))
    assertEquals(
      PodConsentResult.Refused(PodConsentRefusal.FORM_EXPIRED),
      flow.submit(owned.pod, form(csrf = ticket, scopes = listOf(owned.readScope)), owned.session),
      "a page posted twice must not write its selection twice",
    )
  }

  @Test
  fun `a ticket issued to somebody else is refused`() {
    // A transaction alone could be lifted out of a page and spent from another browser; the
    // session is what says who is submitting.
    val owned = Owned()
    val stranger = consentTransactionStore.issue(owned.pod.name, "https://id.test/${randomId()}", null)

    assertEquals(
      PodConsentResult.Refused(PodConsentRefusal.FORM_EXPIRED),
      flow.submit(owned.pod, form(csrf = stranger), owned.session),
    )
  }

  @Test
  fun `a page rendered before the app was disconnected cannot write its grants back`() {
    // Screens are allowed to coexist. This is the one case that is not: an older page would
    // submit its own selection as the new state and hand back everything the person just removed.
    val owned = Owned()
    owned.grant(owned.readScope)
    owned.answered()
    val olderPage = owned.ticket()

    // The disconnect the person made in the other tab.
    val disconnected = flow.submit(owned.pod, form(csrf = owned.ticket(), action = "disconnect"), owned.session)
    assertEquals("app disconnected", redirectedError(disconnected).description)

    assertEquals(
      PodConsentResult.Refused(PodConsentRefusal.FORM_EXPIRED),
      flow.submit(owned.pod, form(csrf = olderPage, scopes = listOf(owned.readScope)), owned.session),
    )
    assertTrue(owned.held().isEmpty(), "the disconnect must stand")
  }

  @Test
  fun `a submission carrying no address is refused`() {
    val owned = Owned()
    assertEquals(
      PodConsentResult.Refused(PodConsentRefusal.MISSING_REDIRECT_URI),
      flow.submit(owned.pod, form(csrf = owned.ticket(), address = "   "), owned.session),
    )
  }

  @Test
  fun `an address that does not belong to the client is refused without being answered at`() {
    val owned = Owned()
    assertEquals(
      PodConsentResult.Refused(PodConsentRefusal.REDIRECT_URI_NOT_ALLOWED),
      flow.submit(
        owned.pod,
        form(csrf = owned.ticket(), address = "https://elsewhere.example/cb"),
        owned.session,
      ),
    )
  }

  // ── The two named ways out ────────────────────────────────────────────────

  @Test
  fun `signing out ends the session and tells the client the request was denied`() {
    val owned = Owned()
    owned.grant(owned.readScope)

    val result = flow.submit(owned.pod, form(csrf = owned.ticket(), action = "signout"), owned.session)

    val signedOut = assertIs<PodConsentResult.SignedOut>(result, "was: $result")
    val delivery = assertIs<OAuthErrorDelivery.Redirect>(signedOut.delivery)
    assertEquals(OAuthErrorCode.ACCESS_DENIED, delivery.code)
    assertEquals("signed out", delivery.description)
    assertTrue(
      !podSignOut.sessionStands(owned.pod.id, owned.session),
      "the sign-out has to end the session it was submitted under",
    )
    // A sign-out writes no grants: signing in again finds them where they were.
    assertEquals(setOf(owned.readScope), owned.held())
  }

  @Test
  fun `disconnecting takes the grants, the durability and the refresh families`() {
    val owned = Owned()
    owned.grant(owned.readScope)
    val before = owned.answered(durable = true)

    val result = flow.submit(owned.pod, form(csrf = owned.ticket(), action = "disconnect"), owned.session)

    val delivery = redirectedError(result)
    assertEquals(OAuthErrorCode.ACCESS_DENIED, delivery.code)
    assertEquals("app disconnected", delivery.description)
    assertTrue(owned.held().isEmpty(), "the grants go")
    val decision = assertNotNull(consentDecisionStore.find(owned.pod.id, clientId, listOf(owned.webId)))
    assertTrue(!decision.durable, "a silence would read as an authorization that predates the control")
    assertTrue(decision.generation > before, "the generation moves, so a code minted before it cannot redeem")
  }

  @Test
  fun `a submission that ticks nothing ends the authorization the same way`() {
    val owned = Owned()
    owned.grant(owned.readScope)

    val result = flow.submit(owned.pod, form(csrf = owned.ticket(), scopes = emptyList()), owned.session)

    assertEquals("app disconnected", redirectedError(result).description)
    assertTrue(owned.held().isEmpty())
  }

  @Test
  fun `ending an authorization that holds nothing stays the plain denial`() {
    // Reporting a disconnect of nothing is the same lie as reporting nothing when something ended.
    val owned = Owned()

    val result = flow.submit(owned.pod, form(csrf = owned.ticket(), action = "disconnect"), owned.session)

    val delivery = redirectedError(result)
    assertEquals(OAuthErrorCode.ACCESS_DENIED, delivery.code)
    assertEquals("no scopes selected", delivery.description)
  }

  // ── The ordinary save ─────────────────────────────────────────────────────

  @Test
  fun `a saved selection is persisted and the code carries the answer it was saved under`() {
    val owned = Owned()

    val result = flow.submit(
      owned.pod,
      form(csrf = owned.ticket(), scopes = listOf(owned.readScope, PUBLIC_READ_SCOPE)),
      owned.session,
    )

    val code = issuedCode(result)
    assertEquals(setOf(owned.readScope, PUBLIC_READ_SCOPE), owned.held())

    val entry = assertNotNull(authorizationCodeStore.consume(code))
    assertEquals(owned.webId, entry.subject)
    assertEquals(owned.standing(), entry.consentGeneration)
    // Only feature scopes travel in a token; the context permission stays in the grant store.
    assertEquals(setOf(PUBLIC_READ_SCOPE), entry.scopes)
  }

  @Test
  fun `the submission is the new state, so an unticked scope is revoked`() {
    val owned = Owned()
    owned.grant(owned.readScope, "${owned.contextUri}#write")

    issuedCode(flow.submit(owned.pod, form(csrf = owned.ticket(), scopes = listOf(owned.readScope)), owned.session))

    assertEquals(setOf(owned.readScope), owned.held(), "the scope the person unticked has to go")
  }

  @Test
  fun `a scope the person cannot delegate is dropped rather than granted`() {
    val owned = Owned()
    val foreign = "${sempodsTestFactory.publicContextUri("someone-else").toString()}#read"

    val result = flow.submit(
      owned.pod,
      form(csrf = owned.ticket(), scopes = listOf(owned.readScope, foreign)),
      owned.session,
    )

    issuedCode(result)
    assertEquals(setOf(owned.readScope), owned.held())
  }

  @Test
  fun `withholding durability revokes what the authorization already had`() {
    // Withholding is not merely declining to extend: the families would otherwise keep rotating
    // and the person would have changed nothing they can observe.
    val owned = Owned()
    owned.grant(owned.readScope)
    owned.answered(durable = true)

    issuedCode(
      flow.submit(
        owned.pod,
        form(csrf = owned.ticket(), scopes = listOf(owned.readScope), durable = false),
        owned.session,
      ),
    )

    val decision = assertNotNull(consentDecisionStore.find(owned.pod.id, clientId, listOf(owned.webId)))
    assertTrue(!decision.durable)
  }

  @Test
  fun `public-read alone on a pod with no public context is refused`() {
    val bare = sempodsTestFactory.newPod(createPublicContext = false)
    val pod = bare.toHostedPod(sempodsUriBuilder)
    val ticket = consentTransactionStore.issue(pod.name, bare.owner, null)
    val session = PodTokenIssuer.SessionPrincipal(bare.owner, emptyList(), Instant.now().minusSeconds(60))

    val result = flow.submit(pod, form(csrf = ticket, scopes = listOf(PUBLIC_READ_SCOPE)), session)

    val delivery = redirectedError(result)
    assertEquals(OAuthErrorCode.CONSENT_REQUIRED, delivery.code)
    assertEquals(
      "pod has no public-read contexts and no per-context scopes were selected",
      delivery.description,
    )
  }

  // ── Contexts typed into the form ──────────────────────────────────────────

  @Test
  fun `an owner can create a context from the dialog and grant it in the same submission`() {
    val owned = Owned()
    val path = "notes-${randomId()}"

    val result = flow.submit(
      owned.pod,
      form(
        csrf = owned.ticket(),
        scopes = emptyList(),
        newContexts = listOf(path),
        newContextScopes = listOf("$path#write"),
      ),
      owned.session,
    )

    issuedCode(result)
    val granted = owned.held()
    assertEquals(1, granted.size, "was: $granted")
    val scope = granted.single()
    assertTrue(scope.endsWith("#write"), scope)
    assertTrue(path in scope, "the grant has to name the context that was created: $scope")
  }

  @Test
  fun `a context path the rules refuse is skipped, and the rest of the submission stands`() {
    // Losing the whole flow over a mistyped context name would be the worse outcome.
    val owned = Owned()
    val bad = "_system/contexts/nope"

    val result = flow.submit(
      owned.pod,
      form(
        csrf = owned.ticket(),
        scopes = listOf(owned.readScope),
        newContexts = listOf(bad),
        newContextScopes = listOf("$bad#write"),
      ),
      owned.session,
    )

    issuedCode(result)
    assertEquals(setOf(owned.readScope), owned.held(), "the typed context is not granted, the ticked one is")
  }

  @Test
  fun `a permission the grammar does not name is dropped from a pending context`() {
    val owned = Owned()
    val path = "notes-${randomId()}"

    val result = flow.submit(
      owned.pod,
      form(
        csrf = owned.ticket(),
        scopes = listOf(owned.readScope),
        newContexts = listOf(path),
        newContextScopes = listOf("$path#administer"),
      ),
      owned.session,
    )

    issuedCode(result)
    assertEquals(setOf(owned.readScope), owned.held())
  }
}
