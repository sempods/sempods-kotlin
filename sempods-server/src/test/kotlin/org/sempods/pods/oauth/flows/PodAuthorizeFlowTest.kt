package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_SCOPE
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.oauth.PodSignOut
import org.sempods.pods.oauth.PodTokenIssuer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `/authorize` decides, without a server and without a protocol message type.
 *
 * The decisions are the subject: whether a client may be answered at all, whether a standing
 * authorization is enough to skip the dialog, what the dialog arrives pre-ticked with, and where a
 * request goes when nobody is signed in. `PodAuthEndpointHttpTest` drives the same paths over HTTP
 * and is what says the endpoint delegates; this says what it delegates to.
 *
 * The stores are the real ones, for the reason `PodTokenExchangeTest` gives.
 */
internal class PodAuthorizeFlowTest : PodBrowserFlowTest() {

  @Inject
  private lateinit var flow: PodAuthorizeFlow

  @Inject
  private lateinit var authorizationCodeStore: AuthorizationCodeStore

  @Inject
  private lateinit var loginStateStore: PodLoginStateStore

  @Inject
  private lateinit var podSignOut: PodSignOut

  private fun request(
    responseType: String? = "code",
    client: String? = clientId,
    address: String? = redirectUri,
    state: String? = "state-${randomId()}",
    codeChallenge: String? = challenge,
    codeChallengeMethod: String? = "S256",
    prompt: String? = null,
    scope: String? = null,
  ) = PodAuthorizeRequest(
    responseType = responseType,
    clientId = client,
    redirectUri = address,
    state = state,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    prompt = prompt,
    scope = scope,
  )

  private fun redirectedError(result: PodAuthorizeResult): OAuthErrorDelivery.Redirect {
    val error = assertIs<PodAuthorizeResult.Error>(result, "was: $result")
    return assertIs<OAuthErrorDelivery.Redirect>(error.delivery)
  }

  // ── The four refusals that may not travel by redirect ──────────────────────

  @Test
  fun `a client_id that is neither did-web nor dyn is refused, and not by redirect`() {
    val owned = Owned()
    val result = flow.authorize(owned.pod, request(client = "https://app.example"), owned.session)
    assertEquals(PodAuthorizeResult.Refused(PodAuthorizeRefusal.MALFORMED_CLIENT_ID), result)
  }

  @Test
  fun `a dyn client this pod holds no registration for is told so, and not called malformed`() {
    // The two failures are statements about different things — see `PodClientDirectory.identify`.
    val owned = Owned()
    val result = flow.authorize(owned.pod, request(client = "dyn:${randomId()}"), owned.session)
    assertEquals(PodAuthorizeResult.Refused(PodAuthorizeRefusal.UNREGISTERED_CLIENT), result)
  }

  @Test
  fun `the address is read before the client, so a request with neither names the address`() {
    // Order is load-bearing: until the address is known to belong to the client that named it,
    // nothing may travel there. A request missing both must therefore fail on the address.
    val owned = Owned()
    val result = flow.authorize(owned.pod, request(client = "nonsense", address = "   "), owned.session)
    assertEquals(PodAuthorizeResult.Refused(PodAuthorizeRefusal.MISSING_REDIRECT_URI), result)
  }

  @Test
  fun `an address that does not belong to the client is refused without being answered at`() {
    val owned = Owned()
    val result = flow.authorize(owned.pod, request(address = "https://elsewhere.example/cb"), owned.session)
    assertEquals(PodAuthorizeResult.Refused(PodAuthorizeRefusal.REDIRECT_URI_NOT_ALLOWED), result)
  }

  // ── Errors that may ────────────────────────────────────────────────────────

  @Test
  fun `a response_type this server does not implement is an error at the client's own address`() {
    // The implicit grant is advertised nowhere and implemented nowhere.
    val owned = Owned()
    val state = "state-${randomId()}"
    val delivery = redirectedError(
      flow.authorize(owned.pod, request(responseType = "token", state = state), owned.session),
    )
    assertEquals(OAuthErrorCode.UNSUPPORTED_RESPONSE_TYPE, delivery.code)
    assertEquals(redirectUri, delivery.target.uri)
    assertEquals(state, delivery.state)
  }

  @Test
  fun `prompt=none with nobody signed in is login_required, not a dialog`() {
    val owned = Owned()
    val delivery = redirectedError(
      flow.authorize(owned.pod, request(prompt = "none"), session = null),
    )
    assertEquals(OAuthErrorCode.LOGIN_REQUIRED, delivery.code)
  }

  @Test
  fun `prompt=none combined with login is a request error`() {
    // OIDC Core 1.0 §3.1.2.1 — `none` is exclusive.
    val owned = Owned()
    val delivery = redirectedError(
      flow.authorize(owned.pod, request(prompt = "none login"), owned.session),
    )
    assertEquals(OAuthErrorCode.INVALID_REQUEST, delivery.code)
  }

  // ── Where an unauthenticated request goes ──────────────────────────────────

  @Test
  fun `nobody signed in parks the whole request and sends the browser to the id-server`() {
    val owned = Owned()
    val state = "state-${randomId()}"
    val result = flow.authorize(owned.pod, request(state = state), session = null)

    val login = assertIs<PodAuthorizeResult.Login>(result, "was: $result")
    assertTrue(login.authorizationUrl.startsWith("http"), login.authorizationUrl)
    assertTrue(login.browserPin.isNotBlank(), "the callback is tied to this browser by this")

    // The request survives the round trip here, not in a parameter the browser carries.
    val parked = assertNotNull(loginStateStore.consume(login.state))
    assertEquals(owned.pod.name, parked.pod)
    assertEquals(clientId, parked.clientId)
    assertEquals(redirectUri, parked.redirectUri)
    assertEquals(state, parked.clientState)
    assertEquals(challenge, parked.codeChallenge)
    assertEquals(login.browserPin, parked.browserPin)
    assertNull(loginStateStore.consume(login.state), "the state is spent once")
  }

  @Test
  fun `a login the person asked for is not carried back into the parked request`() {
    // `prompt=login` is satisfied by the login now beginning; carrying it back would send the
    // person straight into another one.
    val owned = Owned()
    val result = flow.authorize(owned.pod, request(prompt = "login consent"), session = null)

    val login = assertIs<PodAuthorizeResult.Login>(result, "was: $result")
    assertEquals("consent", assertNotNull(loginStateStore.consume(login.state)).prompt)
  }

  @Test
  fun `a session is not enough for prompt=login`() {
    // The person asked to prove themselves again, and a cookie is what they are asking to bypass.
    val owned = Owned()
    owned.grant(owned.readScope)
    owned.answered()

    val result = flow.authorize(owned.pod, request(prompt = "login"), owned.session)
    assertIs<PodAuthorizeResult.Login>(result, "was: $result")
  }

  // ── The dialog, and when it is skipped ─────────────────────────────────────

  @Test
  fun `standing grants the person has answered for are re-issued without a dialog`() {
    val owned = Owned()
    owned.grant(owned.readScope)
    val generation = owned.answered()

    val result = flow.authorize(owned.pod, request(), owned.session)

    val issued = assertIs<PodAuthorizeResult.Code>(result, "was: $result")
    assertEquals(redirectUri, issued.target.uri)
    val entry = assertNotNull(authorizationCodeStore.consume(issued.code))
    assertEquals(owned.webId, entry.subject)
    assertEquals(clientId, entry.clientId)
    assertEquals(owned.pod.name, entry.realm)
    assertEquals(challenge, entry.codeChallenge)
    // The generation standing when the code was minted — a code must not pick up a consent given
    // after it, which is what the exchange compares this against.
    assertEquals(generation, entry.consentGeneration)
    // Context permissions are resolved per request from the grant store and never travel in a token.
    assertEquals(emptySet(), entry.scopes)
  }

  @Test
  fun `grants nobody has answered for fall through to the dialog rather than issuing in silence`() {
    // An authorization predating the durability control has grants and no decision. It renders
    // nothing today, so it could never acquire one — once, it takes the dialog instead.
    val owned = Owned()
    owned.grant(owned.readScope)

    val result = flow.authorize(owned.pod, request(), owned.session)
    assertIs<PodAuthorizeResult.Consent>(result, "was: $result")
  }

  @Test
  fun `prompt=consent asks again even where an answered grant stands`() {
    val owned = Owned()
    owned.grant(owned.readScope)
    owned.answered()

    val result = flow.authorize(owned.pod, request(prompt = "consent"), owned.session)
    assertIs<PodAuthorizeResult.Consent>(result, "was: $result")
  }

  @Test
  fun `the dialog arrives ticked with what the person granted last time`() {
    val owned = Owned()
    owned.grant(owned.readScope)

    val screen = assertIs<PodAuthorizeResult.Consent>(
      flow.authorize(owned.pod, request(), owned.session),
    ).screen

    assertEquals(owned.webId, screen.webId)
    assertEquals(clientId, screen.clientId)
    // A `did:web:` client registers nothing, so there is no name to show but its identifier.
    assertEquals(clientId, screen.clientName)
    assertEquals(redirectUri, screen.redirectUri)
    assertEquals(challenge, screen.codeChallenge)
    assertTrue(screen.isOwner, "the pod's owner is asking")
    assertTrue(screen.disconnectAvailable, "this app holds something, so the way out is on offer")
    assertTrue(screen.csrfToken.isNotBlank(), "one screen, once")

    val context = assertNotNull(
      screen.contexts.singleOrNull { it.uri == owned.readScope.substringBefore('#') },
      "was: ${screen.contexts}",
    )
    assertTrue(context.readGranted, "read was granted")
    assertTrue(!context.writeGranted && !context.manageGranted, "and nothing else was")
  }

  @Test
  fun `a first authorization offers everything and pre-ticks nothing`() {
    // The rows come from what the person *can* delegate, the ticks from what they already have —
    // so an owner sees their contexts on a first consent, all empty.
    val owned = Owned()

    val screen = assertIs<PodAuthorizeResult.Consent>(
      flow.authorize(owned.pod, request(), owned.session),
    ).screen

    assertTrue(!screen.disconnectAvailable, "nothing is held, so nothing can be disconnected")
    val context = assertNotNull(
      screen.contexts.singleOrNull { it.uri == owned.readScope.substringBefore('#') },
      "was: ${screen.contexts}",
    )
    assertTrue(
      !context.readGranted && !context.writeGranted && !context.manageGranted,
      "nothing has been delegated to this app yet",
    )
  }

  @Test
  fun `the dialog states the terms the store will actually enforce`() {
    val owned = Owned()

    val screen = assertIs<PodAuthorizeResult.Consent>(
      flow.authorize(owned.pod, request(), owned.session),
    ).screen

    // Read from the store, not restated here: the failure worth catching is a dialog naming a
    // term the deployment does not keep.
    assertTrue(screen.durableTerms.absolute > screen.sessionTerms.absolute, "durable outlives session")
    assertTrue(!screen.durablePreselected, "nothing was recorded and nothing asked for offline_access")
  }

  @Test
  fun `offline_access pre-ticks durability, and a recorded refusal outranks it`() {
    val owned = Owned()

    val asked = assertIs<PodAuthorizeResult.Consent>(
      flow.authorize(owned.pod, request(scope = "offline_access"), owned.session),
    ).screen
    assertTrue(asked.durablePreselected, "the request asked for it and nothing was on record")

    owned.answered(durable = false)
    val afterRefusal = assertIs<PodAuthorizeResult.Consent>(
      flow.authorize(owned.pod, request(scope = "offline_access"), owned.session),
    ).screen
    assertTrue(
      !afterRefusal.durablePreselected,
      "a request must not quietly re-tick a box the person cleared",
    )
  }

  // ── The anonymous shortcut ─────────────────────────────────────────────────

  @Test
  fun `an anonymous public-read request is answered with a code for a subject nobody is`() {
    val owned = Owned()

    val result = flow.authorize(
      owned.pod,
      request(prompt = "none", scope = PUBLIC_READ_SCOPE),
      session = null,
    )

    val issued = assertIs<PodAuthorizeResult.Code>(result, "was: $result")
    val entry = assertNotNull(authorizationCodeStore.consume(issued.code))
    assertTrue(entry.subject.startsWith("urn:sempods:anon:"), entry.subject)
    assertEquals(setOf(PUBLIC_READ_SCOPE), entry.scopes)
    // Nothing to persist consent against, so nothing is recorded for it either.
    assertNull(entry.consentGeneration)
  }

  @Test
  fun `public-read on a pod with no public context is refused rather than issued empty`() {
    val bare = sempodsTestFactory.newPod(createPublicContext = false).toHostedPod(sempodsUriBuilder)

    val delivery = redirectedError(
      flow.authorize(bare, request(prompt = "none", scope = PUBLIC_READ_SCOPE), session = null),
    )
    assertEquals(OAuthErrorCode.CONSENT_REQUIRED, delivery.code)
    assertEquals("pod has no public-read contexts", delivery.description)
  }

  // ── What issuing a code re-checks on its own ───────────────────────────────

  @Test
  fun `a person who signed out while deciding gets no code`() {
    // The window this closes: a sign-out landing between the session read and the consent
    // generation read would leave the code carrying the moved generation, and it would redeem.
    val owned = Owned()
    owned.grant(owned.readScope)
    owned.answered()
    podSignOut.signOut(owned.pod.id, owned.pod.name, listOf(owned.webId))

    val result = flow.authorize(owned.pod, request(), owned.session)

    val delivery = redirectedError(result)
    assertEquals(OAuthErrorCode.ACCESS_DENIED, delivery.code)
    assertEquals("signed out", delivery.description)
  }

  // ── The installer scope ────────────────────────────────────────────────────

  @Test
  fun `an installation is put to the owner as its own screen, unticked`() {
    val owned = Owned()

    val screen = assertIs<PodAuthorizeResult.Consent>(
      flow.authorize(owned.pod, request(scope = SERVICE_CLIENTS_SCOPE), owned.session),
    ).screen

    assertEquals(listOf(SERVICE_CLIENTS_SCOPE), screen.privilegedFeatures)
    assertTrue(!screen.lifetimeAvailable, "a one-shot authority has no lifetime to choose")
    assertTrue(screen.contexts.isEmpty(), "an installer selects no data")
    assertTrue(screen.publicContexts.isEmpty(), "and is offered none")
    assertTrue(!screen.publicReadPreselected, "least of all pre-ticked")
  }

  @Test
  fun `an ordinary authorization offers no installation and keeps its lifetime control`() {
    val owned = Owned()

    val screen = assertIs<PodAuthorizeResult.Consent>(
      flow.authorize(owned.pod, request(), owned.session),
    ).screen

    assertEquals(emptyList(), screen.privilegedFeatures, "nothing asked for it")
    assertTrue(screen.lifetimeAvailable, "and the ordinary question is still asked")
  }

  @Test
  fun `the installation screen offers no way out, because it is not about what the app holds`() {
    val owned = Owned()
    owned.grant(owned.readScope)

    val screen = assertIs<PodAuthorizeResult.Consent>(
      flow.authorize(owned.pod, request(scope = SERVICE_CLIENTS_SCOPE), owned.session),
    ).screen

    assertTrue(
      !screen.disconnectAvailable,
      "this app holds something, and ending it is not a decision this screen asks for",
    )
  }

  @Test
  fun `the installer scope cannot be asked for beside a context scope`() {
    val owned = Owned()

    val delivery = redirectedError(
      flow.authorize(owned.pod, request(scope = "$SERVICE_CLIENTS_SCOPE ${owned.readScope}"), owned.session),
    )
    assertEquals(OAuthErrorCode.INVALID_SCOPE, delivery.code)
    assertTrue(delivery.description.contains(SERVICE_CLIENTS_SCOPE), delivery.description)
  }

  @Test
  fun `the installer scope cannot be asked for beside public-read`() {
    // `public-read` is a feature scope rather than a context one, and it still reaches the owner's
    // data. An installer holds none of it.
    val owned = Owned()

    val delivery = redirectedError(
      flow.authorize(owned.pod, request(scope = "$SERVICE_CLIENTS_SCOPE $PUBLIC_READ_SCOPE"), owned.session),
    )
    assertEquals(OAuthErrorCode.INVALID_SCOPE, delivery.code)
  }

  @Test
  fun `offline_access beside the installer scope changes nothing about the screen`() {
    // It preselects a control this screen does not render, so there is nothing for it to decide —
    // and refusing it would break a client sending back the scope list it habitually holds.
    val owned = Owned()

    val screen = assertIs<PodAuthorizeResult.Consent>(
      flow.authorize(owned.pod, request(scope = "$SERVICE_CLIENTS_SCOPE offline_access"), owned.session),
    ).screen

    assertEquals(listOf(SERVICE_CLIENTS_SCOPE), screen.privilegedFeatures)
    assertTrue(!screen.lifetimeAvailable, "the control is still off the screen")
  }

  @Test
  fun `a person who does not own the pod is refused the installer scope`() {
    val owned = Owned()
    val stranger = PodTokenIssuer.SessionPrincipal(
      "https://id.test/${randomId()}", emptyList(), Instant.now().minusSeconds(60),
    )

    val delivery = redirectedError(
      flow.authorize(owned.pod, request(scope = SERVICE_CLIENTS_SCOPE), stranger),
    )
    assertEquals(OAuthErrorCode.INVALID_SCOPE, delivery.code)
    assertTrue(delivery.description.contains("owner"), delivery.description)
  }

  @Test
  fun `an owner signed in under an alias is offered the installation`() {
    val owned = Owned()
    val alias = PodTokenIssuer.SessionPrincipal(
      webId = "https://id.test/${randomId()}",
      alsoKnownAs = listOf(owned.webId),
      authTime = Instant.now().minusSeconds(60),
    )

    val screen = assertIs<PodAuthorizeResult.Consent>(
      flow.authorize(owned.pod, request(scope = SERVICE_CLIENTS_SCOPE), alias),
    ).screen
    assertEquals(listOf(SERVICE_CLIENTS_SCOPE), screen.privilegedFeatures)
  }

  @Test
  fun `prompt=none cannot obtain an installation`() {
    val owned = Owned()
    owned.grant(owned.readScope)
    owned.answered()

    val delivery = redirectedError(
      flow.authorize(owned.pod, request(prompt = "none", scope = SERVICE_CLIENTS_SCOPE), owned.session),
    )
    assertEquals(OAuthErrorCode.CONSENT_REQUIRED, delivery.code)
  }

  @Test
  fun `an answered grant does not auto-grant an installation`() {
    // The branch that skips the dialog is the one a second installation would come out of. An
    // installer request never reaches it.
    val owned = Owned()
    owned.grant(owned.readScope)
    owned.answered()

    val result = flow.authorize(owned.pod, request(scope = SERVICE_CLIENTS_SCOPE), owned.session)
    assertIs<PodAuthorizeResult.Consent>(result, "was: $result")
  }

  @Test
  fun `a stored grant carrying the installer scope hands nothing back, and is swept`() {
    // The shape this closes: a row that named the installer scope would otherwise be re-issued by
    // auto-grant, and an installation would recover itself out of an ordinary reconnect.
    val owned = Owned()
    owned.grant(owned.readScope, SERVICE_CLIENTS_SCOPE)
    owned.answered()

    val result = flow.authorize(owned.pod, request(), owned.session)

    val issued = assertIs<PodAuthorizeResult.Code>(result, "was: $result")
    val entry = assertNotNull(authorizationCodeStore.consume(issued.code))
    assertTrue(SERVICE_CLIENTS_SCOPE !in entry.scopes, "was: ${entry.scopes}")
    assertTrue(SERVICE_CLIENTS_SCOPE !in owned.held(), "and the row is gone: ${owned.held()}")
  }
}
