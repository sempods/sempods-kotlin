package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.SempodsStoreTest
import org.sempods.SempodsTestFactory
import org.sempods.SempodsUriBuilder
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.Pkce
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.PodId
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.mongo.persist.toPodId
import org.sempods.pods.mongo.persist.toRef
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two exchanges, exercised without a server and without a protocol message type.
 *
 * That is the point of the class rather than a convenience: a code is refused or redeemed, a family
 * rotates or dies, on decisions this layer makes on its own. `PodAuthEndpointHttpTest` still drives
 * the same paths over HTTP and is what says the endpoint delegates; this says what it delegates to.
 *
 * The stores are the real ones. What these exchanges decide is inseparable from what a store does
 * atomically — one-time code consumption, a rotation that has to lose a race — so a fake store
 * would be testing the fake.
 */
class PodTokenExchangeTest : SempodsStoreTest() {

  @Inject
  private lateinit var exchange: PodTokenExchange

  @Inject
  private lateinit var authorizationCodeStore: AuthorizationCodeStore

  @Inject
  private lateinit var consentDecisionStore: PodConsentDecisionStore

  @Inject
  private lateinit var refreshTokenStore: PodRefreshTokenStore

  @Inject
  private lateinit var podGrantsFacade: PodGrantsFacade

  @Inject
  private lateinit var sempodsTestFactory: SempodsTestFactory

  @Inject
  private lateinit var sempodsUriBuilder: SempodsUriBuilder

  private val clientId = "did:web:app.example"
  private val redirectUri = "https://app.example/cb"

  /** A pod, a person, and a grant for the app — the ordinary state a code is minted from. */
  private inner class Authorized {
    val pod = sempodsTestFactory.newPod()
    val podId: PodId = checkNotNull(pod.id).toPodId()
    val webId = "https://id.test/person-${randomId()}"
    val context = "${pod.name}-notes"

    init {
      grant(setOf(contextScope))
    }

    val contextScope: String get() = "urn:sempods:test:$context#read"

    fun grant(grants: Set<String>) {
      podGrantsFacade.replaceAppGrants(
        pod = pod.toRef(sempodsUriBuilder),
        podId = podId,
        appId = clientId,
        webId = webId,
        subjectUris = listOf(webId),
        grants = grants,
        grantedBy = webId,
      )
    }

    fun answer(durable: Boolean): Long =
      consentDecisionStore.record(podId, clientId, webId, durable).generation

    fun code(generation: Long?, scopes: Set<String> = emptySet()): String =
      authorizationCodeStore.issue(
        subject = webId,
        realm = pod.name,
        clientId = clientId,
        scopes = scopes,
        redirectUri = redirectUri,
        codeChallenge = null,
        codeChallengeMethod = null,
        consentGeneration = generation,
      )

    fun redeem(code: String) = exchange.redeemCode(
      pod = podId,
      podName = pod.name,
      code = code,
      redirectUri = redirectUri,
      clientId = clientId,
      codeVerifier = null,
    )

    fun refresh(token: String, scope: String? = null) = exchange.refresh(
      pod = podId,
      podName = pod.name,
      refreshToken = token,
      clientId = clientId,
      requestedScope = scope,
    )
  }

  private fun refused(result: PodTokenResult): PodTokenResult.Refused = assertIs(result)

  private fun issued(result: PodTokenResult): PodTokenResult.Issued = assertIs(result)

  // ── the authorization code ────────────────────────────────────────────────

  @Test
  fun `a code is spent once`() {
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = false))

    assertIs<PodTokenResult.Issued>(authorized.redeem(code))
    assertEquals(OAuthErrorCode.INVALID_GRANT, refused(authorized.redeem(code)).code)
  }

  @Test
  fun `a request missing what it has to name is refused before the code is touched`() {
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = false))

    val missing = exchange.redeemCode(authorized.podId, authorized.pod.name, null, redirectUri, clientId, null)
    assertEquals(OAuthErrorCode.INVALID_REQUEST, refused(missing).code)
    assertIs<PodTokenResult.Issued>(authorized.redeem(code))
  }

  @Test
  fun `a code redeemed against another address or another client is refused`() {
    val authorized = Authorized()

    val elsewhere = exchange.redeemCode(
      authorized.podId, authorized.pod.name,
      authorized.code(authorized.answer(durable = false)), "https://app.example/other", clientId, null,
    )
    assertEquals("redirect_uri mismatch", refused(elsewhere).description)

    val somebodyElse = exchange.redeemCode(
      authorized.podId, authorized.pod.name,
      authorized.code(authorized.answer(durable = false)), redirectUri, "did:web:other.example", null,
    )
    assertEquals("client_id mismatch", refused(somebodyElse).description)
  }

  @Test
  fun `a code carrying no consent generation is refused outright`() {
    // Every code minted for a person comes from an authorization that has been answered, so one
    // without a generation is the debris of a half-written consent.
    val authorized = Authorized()
    authorized.answer(durable = false)

    assertEquals(
      "authorization code superseded by a later consent",
      refused(authorized.redeem(authorized.code(generation = null))).description,
    )
  }

  @Test
  fun `a code cannot pick up a consent given after it`() {
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = false))
    authorized.answer(durable = true)

    assertEquals(OAuthErrorCode.INVALID_GRANT, refused(authorized.redeem(code)).code)
  }

  @Test
  fun `PKCE is verified against the challenge the code carries`() {
    val authorized = Authorized()
    val verifier = "a".repeat(64)
    val code = authorizationCodeStore.issue(
      subject = authorized.webId,
      realm = authorized.pod.name,
      clientId = clientId,
      scopes = emptySet(),
      redirectUri = redirectUri,
      codeChallenge = Pkce.challengeFor(verifier),
      codeChallengeMethod = Pkce.METHOD_S256,
      consentGeneration = authorized.answer(durable = false),
    )

    val wrong = exchange.redeemCode(authorized.podId, authorized.pod.name, code, redirectUri, clientId, "b".repeat(64))
    assertEquals("PKCE verification failed", refused(wrong).description)
  }

  @Test
  fun `the answer on record decides how long the family lives, not the code`() {
    // The code carries what was asked for; the stored consent carries the authority.
    for (durable in listOf(false, true)) {
      val authorized = Authorized()
      val issued = issued(authorized.redeem(authorized.code(authorized.answer(durable))))
      val family = checkNotNull(refreshTokenStore.lookup(checkNotNull(issued.refreshToken)).token)

      val expected = if (durable) PodRefreshTokenStore.Lifetime.DURABLE else PodRefreshTokenStore.Lifetime.SESSION
      assertEquals(expected, refreshTokenStore.lifetimeOf(family), "durable=$durable")
    }
  }

  @Test
  fun `a reconnect retires the family it supersedes`() {
    val authorized = Authorized()
    val first = issued(authorized.redeem(authorized.code(authorized.answer(durable = true))))
    val second = issued(authorized.redeem(authorized.code(authorized.answer(durable = true))))

    assertEquals(OAuthErrorCode.INVALID_GRANT, refused(authorized.refresh(checkNotNull(first.refreshToken))).code)
    assertIs<PodTokenResult.Issued>(authorized.refresh(checkNotNull(second.refreshToken)))
  }

  @Test
  fun `an anonymous public-read code yields a token with no refresh beside it`() {
    val pod = sempodsTestFactory.newPod()
    val anonymous = "urn:sempods:anon:${randomId()}"
    val code = authorizationCodeStore.issue(
      subject = anonymous,
      realm = pod.name,
      clientId = clientId,
      scopes = setOf(PUBLIC_READ_SCOPE),
      redirectUri = redirectUri,
      codeChallenge = null,
      codeChallengeMethod = null,
    )

    val result = issued(
      exchange.redeemCode(checkNotNull(pod.id).toPodId(), pod.name, code, redirectUri, clientId, null),
    )
    assertEquals(setOf(PUBLIC_READ_SCOPE), result.scopes)
    assertNull(result.refreshToken, "nobody granted it, so there is nobody to grant a way back to")
  }

  @Test
  fun `a bearer carries feature scopes only`() {
    val authorized = Authorized()
    authorized.grant(setOf(authorized.contextScope, PUBLIC_READ_SCOPE))
    val code = authorized.code(
      authorized.answer(durable = false),
      scopes = setOf(PUBLIC_READ_SCOPE, authorized.contextScope),
    )

    assertEquals(setOf(PUBLIC_READ_SCOPE), issued(authorized.redeem(code)).scopes)
  }

  // ── the refresh token ─────────────────────────────────────────────────────

  @Test
  fun `a refresh token this pod does not recognise is refused`() {
    val authorized = Authorized()

    assertEquals("refresh token not recognized", refused(authorized.refresh("not-a-token")).description)
  }

  @Test
  fun `rotation returns a new pair and the spent one becomes reuse`() {
    val authorized = Authorized()
    val first = issued(authorized.redeem(authorized.code(authorized.answer(durable = true))))
    val rotated = issued(authorized.refresh(checkNotNull(first.refreshToken)))

    assertNotNull(rotated.refreshToken)
    assertTrue(rotated.refreshToken != first.refreshToken, "a rotation replaces what it spent")

    // Reuse detection takes the whole family, the successor included.
    assertEquals(
      "refresh token reuse detected",
      refused(authorized.refresh(checkNotNull(first.refreshToken))).description,
    )
    assertEquals(OAuthErrorCode.INVALID_GRANT, refused(authorized.refresh(checkNotNull(rotated.refreshToken))).code)
  }

  @Test
  fun `a refresh token does not travel to another pod or another client`() {
    val authorized = Authorized()
    val token = checkNotNull(issued(authorized.redeem(authorized.code(authorized.answer(durable = true)))).refreshToken)
    val elsewhere = sempodsTestFactory.newPod()

    val wrongPod = exchange.refresh(checkNotNull(elsewhere.id).toPodId(), elsewhere.name, token, clientId, null)
    assertEquals("refresh token does not belong to this pod", refused(wrongPod).description)

    val wrongClient = exchange.refresh(authorized.podId, authorized.pod.name, token, "did:web:other.example", null)
    assertEquals("refresh token does not belong to this client", refused(wrongClient).description)
  }

  @Test
  fun `a family whose grants are all gone forces re-consent`() {
    val authorized = Authorized()
    val token = checkNotNull(issued(authorized.redeem(authorized.code(authorized.answer(durable = true)))).refreshToken)

    authorized.grant(emptySet())

    assertEquals(
      "all previously granted scopes have been revoked",
      refused(authorized.refresh(token)).description,
    )
  }

  @Test
  fun `a refresh may narrow its scope but not widen it`() {
    val authorized = Authorized()
    authorized.grant(setOf(authorized.contextScope, PUBLIC_READ_SCOPE))
    val token = checkNotNull(
      issued(
        authorized.redeem(authorized.code(authorized.answer(durable = true), setOf(PUBLIC_READ_SCOPE))),
      ).refreshToken,
    )

    assertEquals(
      "requested scopes not covered by this refresh token",
      refused(authorized.refresh(token, scope = "something-else")).description,
    )
    // `offline_access` is taken out of the comparison first: clients echo back the scope list they
    // were handed, and refusing the echo would break the ones that behaved correctly.
    assertIs<PodTokenResult.Issued>(authorized.refresh(token, scope = "$PUBLIC_READ_SCOPE offline_access"))
  }

  @Test
  fun `a withdrawal ends a durable family and leaves a session one alone`() {
    // A session family exists because the answer was "no", so a refusal on record cannot be what
    // ends it — that would make the control do nothing at all.
    for (durable in listOf(true, false)) {
      val authorized = Authorized()
      val token = checkNotNull(issued(authorized.redeem(authorized.code(authorized.answer(durable)))).refreshToken)
      authorized.answer(durable = false)

      val result = authorized.refresh(token)
      if (durable) {
        assertEquals("the durable connection was withdrawn", refused(result).description)
      } else {
        assertIs<PodTokenResult.Issued>(result)
      }
    }
  }
}
