package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import com.nimbusds.jwt.SignedJWT
import org.sempods.SempodsStoreTest
import org.sempods.SempodsTestFactory
import org.sempods.SempodsUriBuilder
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.Pkce
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.PodId
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.mongo.persist.toPodId
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.mongo.persist.podId
import org.sempods.pods.mongo.persist.toRef
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodInstallationAuthorityStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.PodTokenIssuer
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
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
  private lateinit var installationAuthorities: PodInstallationAuthorityStore

  @Inject
  private lateinit var podGrantsFacade: PodGrantsFacade

  @Inject
  private lateinit var sempodsTestFactory: SempodsTestFactory

  @Inject
  private lateinit var sempodsUriBuilder: SempodsUriBuilder

  @Inject
  private lateinit var serviceClients: PodServiceClientStore

  private val clientId = "did:web:app.example"
  private val redirectUri = "https://app.example/cb"

  /** One context grant, so an authorization has something to stand on. */
  private val contextScope = "urn:sempods:test:notes#read"

  /** A pod, a person, and a grant for the app — the ordinary state a code is minted from. */
  private inner class Authorized(grants: Set<String> = setOf(contextScope)) {
    val pod = sempodsTestFactory.newPod(createPublicContext = false)
    val podId: PodId = pod.podId()
    val webId = "https://id.test/person-${randomId()}"

    init {
      grant(grants)
    }

    fun grant(grants: Set<String>) {
      podGrantsFacade.replaceAppGrants(
        pod = pod.toHostedPod(sempodsUriBuilder),
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

    fun redeem(
      code: String?,
      redirectUri: String = this@PodTokenExchangeTest.redirectUri,
      clientId: String = this@PodTokenExchangeTest.clientId,
      codeVerifier: String? = null,
    ) = exchange.redeemCode(podId, pod.name, code, redirectUri, clientId, codeVerifier)

    fun refresh(
      token: String,
      scope: String? = null,
      clientId: String = this@PodTokenExchangeTest.clientId,
    ) = exchange.refresh(podId, pod.name, token, clientId, scope)
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

    val missing = authorized.redeem(code = null)
    assertEquals(OAuthErrorCode.INVALID_REQUEST, refused(missing).code)
    assertIs<PodTokenResult.Issued>(authorized.redeem(code))
  }

  @Test
  fun `a code redeemed against another address or another client is refused`() {
    val authorized = Authorized()

    val elsewhere = authorized.redeem(
      authorized.code(authorized.answer(durable = false)),
      redirectUri = "https://app.example/other",
    )
    assertEquals("redirect_uri mismatch", refused(elsewhere).description)

    val somebodyElse = authorized.redeem(
      authorized.code(authorized.answer(durable = false)),
      clientId = "did:web:other.example",
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

    val wrong = authorized.redeem(code, codeVerifier = "b".repeat(64))
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
      exchange.redeemCode(pod.podId(), pod.name, code, redirectUri, clientId, null),
    )
    assertEquals(setOf(PUBLIC_READ_SCOPE), result.scopes)
    assertNull(result.refreshToken, "nobody granted it, so there is nobody to grant a way back to")
  }

  @Test
  fun `a bearer carries feature scopes only`() {
    val authorized = Authorized()
    authorized.grant(setOf(contextScope, PUBLIC_READ_SCOPE))
    val code = authorized.code(
      authorized.answer(durable = false),
      scopes = setOf(PUBLIC_READ_SCOPE, contextScope),
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

    val wrongPod = exchange.refresh(elsewhere.podId(), elsewhere.name, token, clientId, null)
    assertEquals("refresh token does not belong to this pod", refused(wrongPod).description)

    val wrongClient = authorized.refresh(token, clientId = "did:web:other.example")
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
    authorized.grant(setOf(contextScope, PUBLIC_READ_SCOPE))
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

  // ─── client_credentials ───────────────────────────────────────────────────

  @Test
  fun `a service client's own credentials mint a short-lived token and mark it used`() {
    val pod = sempodsTestFactory.newPod(createPublicContext = false).toHostedPod(sempodsUriBuilder)
    val minted = serviceClient(pod)

    val result = issued(
      exchange.exchangeServiceClient(pod.id, pod.name, "notes-app", minted.secret, requestedScope = null),
    )

    assertEquals(PodTokenIssuer.SERVICE_TOKEN_TTL_SECONDS, result.expiresInSeconds)
    assertNull(result.refreshToken, "a service has no person to come back as")
    assertTrue(result.statesEmptyScope, "the service answer names `scope` even when it is empty")
    assertNotNull(serviceClients.find(pod.id, "notes-app"), "the registration stands")
  }

  @Test
  fun `a secret that does not verify is a challenge, not an error document`() {
    // RFC 6749 §5.2: an invalid client authentication is a 401 the caller can answer, which is a
    // different answer from a request this server understood and refused.
    val pod = sempodsTestFactory.newPod(createPublicContext = false).toHostedPod(sempodsUriBuilder)
    serviceClient(pod)

    for ((client, secret) in listOf("notes-app" to "sc_wrong", "no-such-app" to "sc_wrong")) {
      val result = exchange.exchangeServiceClient(pod.id, pod.name, client, secret, requestedScope = null)
      assertIs<PodTokenResult.ClientAuthenticationRequired>(result, "clientId='$client'")
    }
  }

  @Test
  fun `asking for a narrower scope is refused rather than quietly ignored`() {
    // The token grants the client's full registered set, so honouring a subset would need
    // per-token state that does not exist — and ignoring the parameter would grant more than was
    // asked for without saying so.
    val pod = sempodsTestFactory.newPod(createPublicContext = false).toHostedPod(sempodsUriBuilder)
    val minted = serviceClient(pod)

    val result = refused(
      exchange.exchangeServiceClient(pod.id, pod.name, "notes-app", minted.secret, requestedScope = "public-read"),
    )

    assertEquals(OAuthErrorCode.INVALID_SCOPE, result.code)
  }

  /** A registered service client on [pod], with the secret it was handed once. */
  private fun serviceClient(pod: HostedPod): PodServiceClientStore.Registered =
    serviceClients.register(
      pod = pod,
      clientId = "notes-app",
      scopes = setOf("${sempodsUriBuilder.buildContext(pod.name, "apps/notes")}#manage"),
      label = "notes-app",
    )

  // ── the installation authority ────────────────────────────────────────────

  @Test
  fun `an installation code is answered without a refresh token`() {
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = false), scopes = setOf(SERVICE_CLIENTS_SCOPE))

    val result = issued(authorized.redeem(code))

    assertEquals(setOf(SERVICE_CLIENTS_SCOPE), result.scopes)
    assertNull(result.refreshToken, "an authority that could be renewed would not be one-shot")
    assertEquals(
      emptySet(),
      refreshTokenStore.liveFamilies(authorized.podId, clientId, listOf(authorized.webId)),
      "and no family was seeded behind it either",
    )
  }

  @Test
  fun `an installation whose consent answered durable still gets no family`() {
    // The lifetime control is off the installation screen, and this is the other end of that rule:
    // an answer recorded by some earlier ordinary authorization of the same app cannot reach in and
    // make this authority renewable.
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = true), scopes = setOf(SERVICE_CLIENTS_SCOPE))

    val result = issued(authorized.redeem(code))

    assertNull(result.refreshToken)
    assertEquals(
      emptySet(),
      refreshTokenStore.liveFamilies(authorized.podId, clientId, listOf(authorized.webId)),
    )
  }

  @Test
  fun `a code carrying an installation authority beside another scope is refused`() {
    val authorized = Authorized()
    val code = authorized.code(
      authorized.answer(durable = false),
      scopes = setOf(SERVICE_CLIENTS_SCOPE, PUBLIC_READ_SCOPE),
    )

    val refusal = refused(authorized.redeem(code))

    assertEquals(OAuthErrorCode.INVALID_GRANT, refusal.code)
    assertEquals("an installation authority cannot be combined with another scope", refusal.description)
  }

  @Test
  fun `the authority an installation token carries is spent once`() {
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = false), scopes = setOf(SERVICE_CLIENTS_SCOPE))

    val jti = jtiOf(issued(authorized.redeem(code)).accessToken)

    assertNotNull(
      installationAuthorities.consume(authorized.podId, jti),
      "whoever registers first holds the authority",
    )
    assertNull(
      installationAuthorities.consume(authorized.podId, jti),
      "and the bearer is worth nothing afterwards",
    )
  }

  @Test
  fun `an ordinary code writes no installation authority`() {
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = false))

    val jti = jtiOf(issued(authorized.redeem(code)).accessToken)

    assertNull(installationAuthorities.consume(authorized.podId, jti))
  }

  @Test
  fun `a refresh row carrying an installation authority loses it when it rotates`() {
    // No path in this server writes such a row — an installation seeds no family. This answers for
    // one written before the rule, and it is the second half of the promise the code exchange makes.
    val authorized = Authorized(grants = setOf(contextScope, PUBLIC_READ_SCOPE, SERVICE_CLIENTS_SCOPE))
    val seeded = refreshTokenStore.issueNewFamily(
      pod = authorized.podId,
      podName = authorized.pod.name,
      clientId = clientId,
      webId = authorized.webId,
      scopes = setOf(PUBLIC_READ_SCOPE, SERVICE_CLIENTS_SCOPE),
      lifetime = PodRefreshTokenStore.Lifetime.SESSION,
    )

    val rotated = issued(authorized.refresh(seeded.plaintext))

    assertEquals(setOf(PUBLIC_READ_SCOPE), rotated.scopes)
  }

  @Test
  fun `a refresh cannot be down-scoped to an installation authority`() {
    val authorized = Authorized(grants = setOf(contextScope, PUBLIC_READ_SCOPE, SERVICE_CLIENTS_SCOPE))
    val seeded = refreshTokenStore.issueNewFamily(
      pod = authorized.podId,
      podName = authorized.pod.name,
      clientId = clientId,
      webId = authorized.webId,
      scopes = setOf(PUBLIC_READ_SCOPE, SERVICE_CLIENTS_SCOPE),
      lifetime = PodRefreshTokenStore.Lifetime.SESSION,
    )

    val refusal = refused(authorized.refresh(seeded.plaintext, scope = SERVICE_CLIENTS_SCOPE))

    assertEquals(OAuthErrorCode.INVALID_SCOPE, refusal.code)
  }

  /** The `jti` the access token carries — what an installation authority is filed under. */
  private fun jtiOf(accessToken: String): String =
    checkNotNull(SignedJWT.parse(accessToken).jwtClaimsSet.jwtid) { "an access token always carries a jti" }


  @Test
  fun `an installation does not end a durable family the same app holds`() {
    // The two consents share one decision document. This is what the installation screen must not
    // be able to do to a connection it never asked about.
    val authorized = Authorized()
    val tokens = issued(authorized.redeem(authorized.code(authorized.answer(durable = true))))
    val refreshToken = assertNotNull(tokens.refreshToken)

    // What `PodConsentFlow.installation` writes when the owner approves an installation.
    consentDecisionStore.recordWithoutLifetime(authorized.podId, clientId, authorized.webId)

    val rotated = issued(authorized.refresh(refreshToken))
    assertNotNull(rotated.refreshToken, "the durable connection was never withdrawn")
  }
}
