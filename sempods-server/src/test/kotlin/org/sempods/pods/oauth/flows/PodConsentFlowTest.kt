package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.auth.ConsentBinding
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.commons.logging.CapturedLog
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.grants.GrantRecipient
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.oauth.PodSignOut
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.sempods.pods.oauth.SessionPrincipal

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

  @Inject
  private lateinit var consentSelection: ConsentSelection

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
  fun `a ticket submitted many times at once is spent once`() {
    val owned = Owned()
    val ticket = owned.ticket()
    owned.grant(owned.readScope)

    val results = concurrently { flow.submit(owned.pod, form(csrf = ticket, scopes = listOf(owned.readScope)), owned.session) }

    assertEquals(1, results.count { it is PodConsentResult.Code }, "$results")
    results.filterNot { it is PodConsentResult.Code }.forEach {
      assertEquals(PodConsentResult.Refused(PodConsentRefusal.FORM_EXPIRED), it)
    }
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
    assertEquals(false, decision.durable, "a silence would read as an authorization that predates the control")
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

  @Test
  fun `a person who signed out while deciding gets no code, and the grants still land`() {
    // The window `PodAuthorizationCodes` re-checks for: the sign-out landed while the person was
    // choosing, and the generation it moved would otherwise travel on a code that redeems.
    val owned = Owned()
    val ticket = owned.ticket()
    podSignOut.signOut(owned.pod.id, owned.pod.name, listOf(owned.webId))

    val result = flow.submit(
      owned.pod,
      form(csrf = ticket, scopes = listOf(owned.readScope)),
      owned.session,
    )

    val delivery = redirectedError(result)
    assertEquals(OAuthErrorCode.ACCESS_DENIED, delivery.code)
    assertEquals("signed out", delivery.description)
    // The safe half of the write order stands: the selection was persisted before the code was
    // asked for, and nothing about a sign-out undoes a grant.
    assertEquals(setOf(owned.readScope), owned.held())
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
    assertEquals(false, decision.durable, "the ordinary dialog asked, and this is the answer")
  }

  @Test
  fun `public-read alone on a pod with no public context is refused`() {
    val bare = sempodsTestFactory.newPod(createPublicContext = false)
    val pod = bare.toHostedPod(sempodsUriBuilder)
    val ticket = consentTransactionStore.issue(pod.name, bare.owner, null)
    val session = SessionPrincipal(bare.owner, emptyList(), Instant.now().minusSeconds(60))

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
  fun `a context path cannot forge a log line`() {
    // `docs/logging.md` §"Three rules": a published module escapes caller-supplied text and keeps
    // one test at the call site. `ContextPathRules.normalize` only trims the ends, so a break in
    // the middle of a typed path reaches the line that reports the rejection.
    val owned = Owned()
    val marker = "forged-${randomId()}"
    val forged = "../x\n2026-01-01 21:00:00,000 WARN  [jetty] $marker"

    val lines = CapturedLog.linesFrom(ConsentSelection::class.java) {
      redirectedError(
        flow.submit(
          owned.pod,
          form(csrf = owned.ticket(), scopes = listOf(owned.readScope), newContexts = listOf(forged)),
          owned.session,
        ),
      )
    }

    val line = lines.single { marker in it }
    assertFalse('\n' in line, "was: $line")
    assertTrue("\\u000a" in line, line)
  }

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
  fun `a context path the rules refuse refuses the whole submission, and nothing is written`() {
    // Skipping it would drop a row the person ticked without a word, and the replace would take the
    // grant with it.
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

    assertEquals(OAuthErrorCode.INVALID_REQUEST, redirectedError(result).code)
    assertEquals(emptySet(), owned.held(), "not even the ticked row is granted")
  }

  @Test
  fun `a permission the grammar does not name refuses the submission, and creates no context`() {
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

    assertEquals(OAuthErrorCode.INVALID_REQUEST, redirectedError(result).code)
    assertEquals(emptySet(), owned.held())
    assertFalse(podFacade.getContexts(owned.pod.name).any { it.toString().endsWith("/$path") }, "no context was created")
  }

  @Test
  fun `a bound screen that offered creation to its owner creates nothing once the person no longer owns the pod`() {
    // The offer was the owner's when the page was rendered; creating a context is asked again.
    val owned = Owned()
    val former = "https://id.example/former-${randomId()}"
    val session = SessionPrincipal(former, emptyList(), Instant.now().minusSeconds(60))
    val path = "notes-${randomId()}"
    val ticket = consentTransactionStore.issue(
      owned.pod.name, former, null, emptySet(), 0L,
      ConsentBinding(
        clientId = clientId, redirectUri = redirectUri, state = null, codeChallenge = challenge,
        codeChallengeMethod = "S256", offeredContexts = emptySet(), publicReadOffered = false,
        contextCreationOffered = true,
      ),
    )

    val result = flow.submit(
      owned.pod,
      form(csrf = ticket, state = null, newContexts = listOf(path), newContextScopes = listOf("$path#read")),
      session,
    )

    assertEquals(OAuthErrorCode.INVALID_REQUEST, redirectedError(result).code)
    assertFalse(podFacade.getContexts(owned.pod.name).any { it.toString().endsWith("/$path") }, "no context was created")
  }

  @Test
  fun `a pending context somebody else created meanwhile is not granted`() {
    // Between the checks and the creation: the context exists, and it is not the one the person
    // added to the list.
    val owned = Owned()
    val path = "notes-${randomId()}"
    val parsed = consentSelection.parse(
      owned.pod,
      ConsentSelection.Submission(emptySet(), listOf(path), listOf("$path#write")),
      ConsentSelection.Offer(contexts = emptySet(), publicRead = false, contextCreation = true),
    )
    owned.context(path)

    val applied = consentSelection.apply(
      owned.pod,
      assertIs<ConsentSelection.Parsed.Selection>(parsed),
      GrantRecipient.Delegation(clientId = clientId, webId = owned.webId, aliases = listOf(owned.webId)),
      approverUris = listOf(owned.webId),
      approver = owned.webId,
    )

    assertNull(applied.replacement, "nothing was left to grant")
    assertEquals(emptyList(), applied.created)
    assertEquals(emptySet(), owned.held())
  }

  // ── The management screen's submission ───────────────────────────────────

  @Test
  fun `an approved management authority mints a code for the feature scope and grants nothing`() {
    val owned = Owned()

    val code = issuedCode(
      flow.submit(
        owned.pod,
        form(
          csrf = owned.ticketOffering(SERVICE_CLIENTS_MANAGE_SCOPE),
          scopes = listOf(SERVICE_CLIENTS_MANAGE_SCOPE),
          durable = false,
        ),
        owned.session,
      ),
    )

    val entry = assertNotNull(authorizationCodeStore.consume(code))
    assertEquals(setOf(SERVICE_CLIENTS_MANAGE_SCOPE), entry.scopes)
    assertEquals(emptySet(), owned.held(), "a manager holds none of the rights it administers")
    assertNotNull(entry.consentGeneration, "a code with no generation is refused at the exchange")
  }

  @Test
  fun `a management authority the person left unticked declines it and ends nothing`() {
    // The shape this closes: ticking nothing on an ordinary screen disconnects the app. On this
    // screen it means "do not install", and an app's standing access is not what was being asked
    // about.
    val owned = Owned()
    owned.grant(owned.readScope)

    val delivery = redirectedError(
      flow.submit(
        owned.pod,
        form(csrf = owned.ticketOffering(SERVICE_CLIENTS_MANAGE_SCOPE), scopes = null, durable = false),
        owned.session,
      ),
    )

    assertEquals(OAuthErrorCode.ACCESS_DENIED, delivery.code)
    assertEquals("'service-clients:manage' declined", delivery.description)
    assertEquals(setOf(owned.readScope), owned.held(), "the app keeps what it held")
  }

  @Test
  fun `a management authority submitted beside a context scope is refused`() {
    val owned = Owned()
    owned.grant(owned.readScope)

    val delivery = redirectedError(
      flow.submit(
        owned.pod,
        form(
          csrf = owned.ticketOffering(SERVICE_CLIENTS_MANAGE_SCOPE),
          scopes = listOf(SERVICE_CLIENTS_MANAGE_SCOPE, owned.readScope),
          durable = false,
        ),
        owned.session,
      ),
    )

    assertEquals(OAuthErrorCode.INVALID_SCOPE, delivery.code)
    assertEquals(setOf(owned.readScope), owned.held(), "and nothing was rewritten on the way out")
  }

  @Test
  fun `a management authority asking to create a context is refused`() {
    val owned = Owned()
    val path = "manager-${randomId()}"

    val delivery = redirectedError(
      flow.submit(
        owned.pod,
        form(
          csrf = owned.ticketOffering(SERVICE_CLIENTS_MANAGE_SCOPE),
          scopes = listOf(SERVICE_CLIENTS_MANAGE_SCOPE),
          newContexts = listOf(path),
          newContextScopes = listOf("$path#write"),
          durable = false,
        ),
        owned.session,
      ),
    )

    assertEquals(OAuthErrorCode.INVALID_SCOPE, delivery.code)
  }

  @Test
  fun `a management authority claiming the lifetime control is refused`() {
    // The control is off the screen. A hand-built post is what is left, and the rule answers it.
    val owned = Owned()

    val delivery = redirectedError(
      flow.submit(
        owned.pod,
        form(
          csrf = owned.ticketOffering(SERVICE_CLIENTS_MANAGE_SCOPE),
          scopes = listOf(SERVICE_CLIENTS_MANAGE_SCOPE),
          durable = true,
        ),
        owned.session,
      ),
    )

    assertEquals(OAuthErrorCode.INVALID_SCOPE, delivery.code)
    assertTrue(delivery.description.contains("does not renew"), delivery.description)
  }

  @Test
  fun `the management scope on a screen that never offered it is refused`() {
    val owned = Owned()
    owned.grant(owned.readScope)

    val delivery = redirectedError(
      flow.submit(
        owned.pod,
        form(csrf = owned.ticket(), scopes = listOf(SERVICE_CLIENTS_MANAGE_SCOPE), durable = false),
        owned.session,
      ),
    )

    assertEquals(OAuthErrorCode.INVALID_SCOPE, delivery.code)
    assertEquals(setOf(owned.readScope), owned.held())
  }

  @Test
  fun `a management authority approved by someone who does not own the pod is refused`() {
    val owned = Owned()
    val stranger = SessionPrincipal(
      "https://id.test/${randomId()}", emptyList(), Instant.now().minusSeconds(60),
    )
    val ticket = consentTransactionStore.issue(
      owned.pod.name, stranger.webId, null, setOf(SERVICE_CLIENTS_MANAGE_SCOPE),
    )

    val delivery = redirectedError(
      flow.submit(
        owned.pod,
        form(csrf = ticket, scopes = listOf(SERVICE_CLIENTS_MANAGE_SCOPE), durable = false),
        stranger,
      ),
    )

    assertEquals(OAuthErrorCode.INVALID_SCOPE, delivery.code)
    assertTrue(delivery.description.contains("owner"), delivery.description)
  }


  @Test
  fun `a management authority leaves the lifetime answer this app already carries`() {
    // What this closes: the management screen shares a consent document with the ordinary one.
    // Writing `durable = false` into it for a question nobody was asked is a withdrawal, and
    // `PodTokenExchange.endsOnRefusal` reads it as one — the app's durable family dies at its next
    // refresh because its owner approved a management authority.
    val owned = Owned()
    owned.grant(owned.readScope)
    owned.answered(durable = true)

    issuedCode(
      flow.submit(
        owned.pod,
        form(
          csrf = owned.ticketOffering(SERVICE_CLIENTS_MANAGE_SCOPE),
          scopes = listOf(SERVICE_CLIENTS_MANAGE_SCOPE),
          durable = false,
        ),
        owned.session,
      ),
    )

    val standing = assertNotNull(consentDecisionStore.find(owned.pod.id, clientId, listOf(owned.webId)))
    assertEquals(true, standing.durable, "the management screen asked nothing about the connection")
  }

  @Test
  fun `a management authority on an authorization nobody has answered still answers nothing`() {
    // The other half, and the one a preserved-if-present fix would miss: no answer on record is a
    // state of its own, and a family grandfathered onto the long terms is left alone only while it
    // stays that way.
    val owned = Owned()

    issuedCode(
      flow.submit(
        owned.pod,
        form(
          csrf = owned.ticketOffering(SERVICE_CLIENTS_MANAGE_SCOPE),
          scopes = listOf(SERVICE_CLIENTS_MANAGE_SCOPE),
          durable = false,
        ),
        owned.session,
      ),
    )

    val standing = assertNotNull(consentDecisionStore.find(owned.pod.id, clientId, listOf(owned.webId)))
    assertNull(standing.durable, "a refusal nobody gave is not the answer to a question nobody asked")
    assertTrue(standing.generation > 0, "and the code still has a generation to be bound to")
  }


  @Test
  fun `a management authority does not expire an ordinary page opened beside it`() {
    // The generation is shared, the grants are not. A management authority moves the first and clears
    // nothing, so the page somebody had open for the same app has lost nothing and must still
    // submit — screens coexist on purpose.
    val owned = Owned()
    val pageOpenedFirst = owned.ticket()

    issuedCode(
      flow.submit(
        owned.pod,
        form(
          csrf = owned.ticketOffering(SERVICE_CLIENTS_MANAGE_SCOPE),
          scopes = listOf(SERVICE_CLIENTS_MANAGE_SCOPE),
          durable = false,
        ),
        owned.session,
      ),
    )

    issuedCode(
      flow.submit(
        owned.pod,
        form(csrf = pageOpenedFirst, scopes = listOf(owned.readScope)),
        owned.session,
      ),
    )
    assertEquals(setOf(owned.readScope), owned.held(), "the page wrote the selection it carried")
  }



  @Test
  fun `a page from before the app held anything cannot write grants back after a disconnect`() {
    // The narrowing that let a management authority through must not let this through with it: this page
    // was rendered when there was nothing to lose, but by the time it submits the person has
    // granted access in one tab and ended it in another. What it would write is what they removed.
    val owned = Owned()
    val pageOpenedFirst = owned.ticket()

    issuedCode(
      flow.submit(owned.pod, form(csrf = owned.ticket(), scopes = listOf(owned.readScope)), owned.session),
    )
    redirectedError(flow.submit(owned.pod, form(csrf = owned.ticket(), action = "disconnect"), owned.session))
    assertTrue(owned.held().isEmpty(), "the disconnect landed")

    assertEquals(
      PodConsentResult.Refused(PodConsentRefusal.FORM_EXPIRED),
      flow.submit(owned.pod, form(csrf = pageOpenedFirst, scopes = listOf(owned.readScope)), owned.session),
    )
    assertTrue(owned.held().isEmpty(), "and nothing came back")
  }


  @Test
  fun `a management screen cannot be posted as a disconnect`() {
    // The screen renders no way out, and every other field it could carry across from another
    // dialog is refused. This is the destructive one, so it is refused too.
    val owned = Owned()
    owned.grant(owned.readScope)

    val delivery = redirectedError(
      flow.submit(
        owned.pod,
        form(csrf = owned.ticketOffering(SERVICE_CLIENTS_MANAGE_SCOPE), action = "disconnect"),
        owned.session,
      ),
    )

    assertEquals(OAuthErrorCode.INVALID_REQUEST, delivery.code)
    assertEquals(setOf(owned.readScope), owned.held(), "the app keeps what it held")
  }
}
