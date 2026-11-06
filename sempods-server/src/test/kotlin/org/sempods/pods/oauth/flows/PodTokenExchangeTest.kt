package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsModule
import org.sempods.auth.core.RefreshTokenStore
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.mockk.every
import io.mockk.spyk
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.logging.CapturedLog
import org.sempods.pods.oauth.DynamicClientStore
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
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.GrantRecipient
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.mongo.persist.toPodId
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.mongo.persist.podId
import org.sempods.pods.mongo.persist.toRef
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodManagementAuthorityStore
import org.sempods.pods.oauth.PrivilegedAuthorityRows
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
  private lateinit var managementAuthorities: PodManagementAuthorityStore

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
      podGrantsFacade.replaceGrants(
        pod = pod.toHostedPod(sempodsUriBuilder),
        recipient = GrantRecipient.Delegation(clientId = clientId, webId = webId, aliases = listOf(webId)),
        selection = grants,
        grantedBy = webId,
      ).granted
    }

    fun answer(durable: Boolean): Long =
      consentDecisionStore.record(podId, clientId, webId, durable).generation

    fun code(
      generation: Long?,
      scopes: Set<String> = emptySet(),
      subjectUris: Set<String> = emptySet(),
      consentText: Int? = null,
    ): String =
      authorizationCodeStore.issue(
        subject = webId,
        realm = pod.name,
        clientId = clientId,
        scopes = scopes,
        redirectUri = redirectUri,
        codeChallenge = null,
        codeChallengeMethod = null,
        consentGeneration = generation,
        subjectUris = subjectUris,
        consentText = consentText,
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

  private fun exchangeUsing(store: PodRefreshTokenStore) = PodTokenExchange(
    authorizationCodeStore = authorizationCodeStore,
    refreshTokenStore = store,
    consentDecisionStore = consentDecisionStore,
    podGrantsFacade = podGrantsFacade,
    dynamicClientStore = injector.getInstance(DynamicClientStore::class.java),
    podTokenIssuer = injector.getInstance(PodTokenIssuer::class.java),
    serviceClients = serviceClients,
    managementAuthorities = managementAuthorities,
    webIdUriDeriver = injector.getInstance(WebIdUriDeriver::class.java),
  )

  private inner class GraceScenario(seconds: Int = 60) : AutoCloseable {
    val authorized = Authorized()
    var now = Instant.now()
    private val collection = "test.refreshGrace.${randomId()}"
    private val db = injector.getInstance(MongoDatabase::class.java)
    val store = PodRefreshTokenStore(
      db = db, collectionName = collection,
      config = SempodsModule.config.copy(refreshReuseGraceSeconds = seconds), clock = { now },
    )
    val flow = exchangeUsing(store)
    fun seed(lifetime: PodRefreshTokenStore.Lifetime = PodRefreshTokenStore.Lifetime.DURABLE) = store.issueNewFamily(
      pod = authorized.podId, podName = authorized.pod.name, clientId = clientId,
      webId = authorized.webId, scopes = emptySet(), lifetime = lifetime,
    )
    fun expireGrace(plaintext: String) {
      val hash = assertNotNull(store.lookup(plaintext).token).tokenHash
      db.getCollection(collection).updateOne(
        com.mongodb.client.model.Filters.eq("tokenHash", hash),
        com.mongodb.client.model.Updates.set("rotatedAt", Instant.now().minusSeconds(120)),
      )
    }
    fun refresh(plaintext: String) = flow.refresh(authorized.podId, authorized.pod.name, plaintext, clientId, null)
    override fun close() {
      db.getCollection(collection).drop()
      db.getCollection("$collection.sequence").drop()
    }
  }

  private fun refused(result: PodTokenResult): PodTokenResult.Refused = assertIs(result)

  private fun issued(result: PodTokenResult): PodTokenResult.Issued = assertIs(result)

  // ── the authorization code ────────────────────────────────────────────────

  @Test
  fun `unticked consent during code exchange reports supersession and keeps existing durable families`() {
    val authorized = Authorized()
    val generation = authorized.answer(durable = true)
    val existing = issued(authorized.redeem(authorized.code(generation)))
    val observedStore = spyk(refreshTokenStore)
    every { observedStore.issueNewFamily(any(), any(), any(), any(), any(), any()) } answers {
      val issued = callOriginal()
      authorized.answer(durable = false)
      issued
    }
    val racingExchange = PodTokenExchange(
      authorizationCodeStore, observedStore, consentDecisionStore, podGrantsFacade,
      injector.getInstance(DynamicClientStore::class.java), injector.getInstance(PodTokenIssuer::class.java),
      serviceClients, managementAuthorities, injector.getInstance(WebIdUriDeriver::class.java),
    )
    lateinit var result: PodTokenResult
    val lines = CapturedLog.linesFrom(PodTokenExchange::class.java) {
      result = racingExchange.redeemCode(authorized.podId, authorized.pod.name, authorized.code(generation), redirectUri, clientId, null)
    }

    assertEquals(OAuthErrorCode.INVALID_GRANT, refused(result).code)
    assertEquals("authorization code superseded by a later consent", refused(result).description)
    assertTrue(lines.none { "durableWithheld" in it })
    val refreshed = issued(authorized.refresh(assertNotNull(existing.refreshToken)))
    val held = assertNotNull(refreshTokenStore.lookup(assertNotNull(refreshed.refreshToken)).token)
    assertEquals(PodRefreshTokenStore.Lifetime.DURABLE, refreshTokenStore.lifetimeOf(held))
  }

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
  fun `two code exchanges leave both families refreshable`() {
    val authorized = Authorized()
    val first = issued(authorized.redeem(authorized.code(authorized.answer(durable = true))))
    val second = issued(authorized.redeem(authorized.code(authorized.answer(durable = true))))

    assertIs<PodTokenResult.Issued>(authorized.refresh(checkNotNull(first.refreshToken)))
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
  fun `rotation permits one lost-answer retry and a second reuse ends the family`() {
    val authorized = Authorized()
    val first = issued(authorized.redeem(authorized.code(authorized.answer(durable = true))))
    val rotated = issued(authorized.refresh(checkNotNull(first.refreshToken)))

    assertNotNull(rotated.refreshToken)
    assertTrue(rotated.refreshToken != first.refreshToken, "a rotation replaces what it spent")

    val retry = issued(authorized.refresh(checkNotNull(first.refreshToken)))
    assertTrue(retry.refreshToken != rotated.refreshToken)
    // A second retry takes the whole family, including both successors.
    assertEquals(
      "refresh token reuse detected",
      refused(authorized.refresh(checkNotNull(first.refreshToken))).description,
    )
    assertEquals(OAuthErrorCode.INVALID_GRANT, refused(authorized.refresh(checkNotNull(rotated.refreshToken))).code)
  }

  @Test
  fun `disabled and elapsed grace retain family-wide reuse detection`() {
    for (seconds in listOf(0, 60)) GraceScenario(seconds).use { scenario ->
      val original = scenario.seed()
      val first = issued(scenario.refresh(original.plaintext))
      if (seconds > 0) scenario.expireGrace(original.plaintext)
      assertEquals("refresh token reuse detected", refused(scenario.refresh(original.plaintext)).description)
      assertEquals(RefreshTokenStore.LookupState.REVOKED, scenario.store.lookup(assertNotNull(first.refreshToken)).state)
    }
  }

  @Test
  fun `two concurrent refreshes leave only the retry successor usable`() {
    GraceScenario().use { scenario ->
      val original = scenario.seed(PodRefreshTokenStore.Lifetime.SESSION)
      val start = CyclicBarrier(2)
      val pool = Executors.newFixedThreadPool(2)
      val results = try {
        pool.invokeAll((1..2).map { Callable {
          start.await(10, TimeUnit.SECONDS)
          scenario.refresh(original.plaintext)
        } }, 15, TimeUnit.SECONDS).map { it.get() }
      } finally {
        pool.shutdownNow()
      }
      val issued = results.filterIsInstance<PodTokenResult.Issued>()
      assertTrue(issued.isNotEmpty())
      val usable = issued.mapNotNull { it.refreshToken }.filter { scenario.store.lookup(it).state == RefreshTokenStore.LookupState.ACTIVE }
      assertEquals(1, usable.size)
      val successor = assertNotNull(scenario.store.lookup(usable.single()).token)
      assertEquals(original.token.familyId, successor.familyId)
      assertEquals(original.token.endsAt, successor.endsAt)
      assertEquals(original.token.kind, successor.kind)
      issued(scenario.refresh(usable.single()))
    }
  }

  @Test
  fun `ineligible replay revokes the family before checking pod client or scopes`() {
    for (seconds in listOf(0, 60)) for (invalid in listOf("pod", "client", "scope")) {
      GraceScenario(seconds).use { scenario ->
        val original = scenario.seed()
        val next = issued(scenario.refresh(original.plaintext))
        if (seconds > 0) scenario.expireGrace(original.plaintext)
        val other = sempodsTestFactory.newPod()
        val result = scenario.flow.refresh(
          if (invalid == "pod") other.podId() else scenario.authorized.podId,
          if (invalid == "pod") other.name else scenario.authorized.pod.name,
          original.plaintext, if (invalid == "client") "did:web:other.example" else clientId,
          if (invalid == "scope") "unknown-scope" else null,
        )
        assertEquals("refresh token reuse detected", refused(result).description)
        assertEquals(RefreshTokenStore.LookupState.REVOKED, scenario.store.lookup(assertNotNull(next.refreshToken)).state)
      }
    }
  }

  @Test
  fun `wrong owners and invalid scopes neither consume grace nor retire the family`() {
    GraceScenario().use { scenario ->
      val original = scenario.seed()
      issued(scenario.refresh(original.plaintext))
      val elsewhere = sempodsTestFactory.newPod()
      assertEquals("refresh token does not belong to this pod", refused(scenario.flow.refresh(
        elsewhere.podId(), elsewhere.name, original.plaintext, clientId, null,
      )).description)
      assertEquals("refresh token does not belong to this client", refused(scenario.flow.refresh(
        scenario.authorized.podId, scenario.authorized.pod.name, original.plaintext, "did:web:other.example", null,
      )).description)
      assertEquals(OAuthErrorCode.INVALID_SCOPE, refused(scenario.flow.refresh(
        scenario.authorized.podId, scenario.authorized.pod.name, original.plaintext, clientId, "unknown-scope",
      )).code)
      issued(scenario.refresh(original.plaintext))
    }
  }

  @Test
  fun `a predecessor from before the current rotation is reuse even within sixty seconds`() {
    GraceScenario().use { scenario ->
      val original = scenario.seed()
      val first = issued(scenario.refresh(original.plaintext))
      val second = issued(scenario.refresh(assertNotNull(first.refreshToken)))
      assertEquals("refresh token reuse detected", refused(scenario.refresh(original.plaintext)).description)
      assertEquals(RefreshTokenStore.LookupState.REVOKED, scenario.store.lookup(assertNotNull(second.refreshToken)).state)
    }
  }

  @Test
  fun `a limit retirement during a grace retry cannot revive the family`() {
    GraceScenario().use { scenario ->
      val original = scenario.seed()
      issued(scenario.refresh(original.plaintext))
      val observed = spyk(scenario.store)
      every { observed.issueInFamily(any(), any(), any()) } answers {
        val successor = callOriginal()
        scenario.store.revokeFamily(original.token.familyId, PodRefreshTokenStore.RevocationReason.LIMIT)
        successor
      }
      val answer = exchangeUsing(observed).refresh(
        scenario.authorized.podId, scenario.authorized.pod.name, original.plaintext, clientId, null,
      )
      assertEquals("refresh token retired: connection limit", refused(answer).description)
      assertTrue(scenario.store.findByFamily(original.token.familyId).all { it.revokedAt != null })
    }
  }

  @Test
  fun `withdrawn grants prevent a grace retry from issuing access`() {
    GraceScenario().use { scenario ->
      val original = scenario.seed()
      issued(scenario.refresh(original.plaintext))
      scenario.authorized.grant(emptySet())
      assertEquals(OAuthErrorCode.INVALID_GRANT, refused(scenario.refresh(original.plaintext)).code)
      assertTrue(scenario.store.findByFamily(original.token.familyId).all { it.revokedAt != null })
    }
  }

  @Test
  fun `a code redeemed by many at once is redeemed once`() {
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = true))

    val results = concurrently { authorized.redeem(code) }

    assertEquals(1, results.count { it is PodTokenResult.Issued }, "$results")
    results.filterIsInstance<PodTokenResult.Refused>().forEach {
      assertEquals(PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "invalid or expired authorization code"), it)
    }
  }

  @Test
  fun `more than two simultaneous refreshes issue at most two successors and end the family`() {
    // The first call rotates, one retry may use grace, and further calls revoke the family.
    val authorized = Authorized()
    val token = checkNotNull(issued(authorized.redeem(authorized.code(authorized.answer(durable = true)))).refreshToken)

    val results = concurrently { authorized.refresh(token) }

    val successors = results.filterIsInstance<PodTokenResult.Issued>().map { checkNotNull(it.refreshToken) }
    assertTrue(successors.size <= 2, "$results")
    results.filterIsInstance<PodTokenResult.Refused>().forEach { assertEquals(OAuthErrorCode.INVALID_GRANT, it.code, "$it") }
    for (survivor in successors + token) {
      assertEquals(OAuthErrorCode.INVALID_GRANT, refused(authorized.refresh(survivor)).code, "the family outlived the race")
    }
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
  fun `a later shorter choice preserves each existing family lifetime`() {
    for (durable in listOf(true, false)) {
      val authorized = Authorized()
      val token = checkNotNull(issued(authorized.redeem(authorized.code(authorized.answer(durable)))).refreshToken)
      val original = checkNotNull(refreshTokenStore.lookup(token).token)
      authorized.answer(durable = false)

      val result = issued(authorized.refresh(token))
      val successor = checkNotNull(refreshTokenStore.lookup(checkNotNull(result.refreshToken)).token)
      assertEquals(original.kind, successor.kind)
      assertEquals(original.endsAt, successor.endsAt)
      assertEquals(original.familyId, successor.familyId)
      val terms = refreshTokenStore.termsOf(refreshTokenStore.lifetimeOf(original))
      assertEquals(minOf(successor.issuedAt.plus(terms.idle), checkNotNull(original.endsAt)), successor.expiresAt)
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
    val registration = assertNotNull(serviceClients.find(pod.id, "notes-app"), "the registration stands")
    assertNotNull(registration.lastUsedAt, "and says when it last minted, for the owner's list")
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

  // ── a privileged authority ────────────────────────────────────────────

  @Test
  fun `a management code is answered without a refresh token`() {
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = false), scopes = setOf(SERVICE_CLIENTS_MANAGE_SCOPE))

    val result = issued(authorized.redeem(code))

    assertEquals(setOf(SERVICE_CLIENTS_MANAGE_SCOPE), result.scopes)
    assertNull(result.refreshToken, "an authority that could be renewed would not be one-shot")
    assertEquals(
      emptySet(),
      refreshTokenStore.liveFamilies(authorized.podId, clientId, listOf(authorized.webId)),
      "and no family was seeded behind it either",
    )
  }

  @Test
  fun `a management authority whose consent answered durable still gets no family`() {
    // The lifetime control is off the management screen, and this is the other end of that rule:
    // an answer recorded by some earlier ordinary authorization of the same app cannot reach in and
    // make this authority renewable.
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = true), scopes = setOf(SERVICE_CLIENTS_MANAGE_SCOPE))

    val result = issued(authorized.redeem(code))

    assertNull(result.refreshToken)
    assertEquals(
      emptySet(),
      refreshTokenStore.liveFamilies(authorized.podId, clientId, listOf(authorized.webId)),
    )
  }

  @Test
  fun `a code carrying a management authority beside another scope is refused`() {
    val authorized = Authorized()
    val code = authorized.code(
      authorized.answer(durable = false),
      scopes = setOf(SERVICE_CLIENTS_MANAGE_SCOPE, PUBLIC_READ_SCOPE),
    )

    val refusal = refused(authorized.redeem(code))

    assertEquals(OAuthErrorCode.INVALID_GRANT, refusal.code)
    assertEquals("a privileged authority cannot be combined with another scope", refusal.description)
  }

  @Test
  fun `a code from before the URI set existed stands for its subject alone`() {
    // The rolling-deploy case: an old node issued the code, so the field is absent. The authority
    // must still name somebody, because each call compares the pod's owner against this set.
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = false), scopes = setOf(SERVICE_CLIENTS_MANAGE_SCOPE))

    val jti = jtiOf(issued(authorized.redeem(code)).accessToken)

    val authority = assertNotNull(managementAuthorities.standing(authorized.podId, jti))
    assertEquals(setOf(authorized.webId), authority.subjectUris)
  }

  @Test
  fun `the authority records the consent text the code carries, and the first where it carries none`() {
    // A dialog an older node rendered carries no text version, and neither does its code: the
    // person saw the first text, whichever node redeems it.
    val authorized = Authorized()
    fun consentOf(code: String) =
      assertNotNull(managementAuthorities.standing(authorized.podId, jtiOf(issued(authorized.redeem(code)).accessToken))).consent

    assertEquals(
      PrivilegedAuthorityRows.FIRST_CONSENT,
      consentOf(authorized.code(authorized.answer(durable = false), scopes = setOf(SERVICE_CLIENTS_MANAGE_SCOPE))),
    )
    assertEquals(
      PrivilegedAuthorityRows.SERVICE_CLIENTS_CONSENT,
      consentOf(
        authorized.code(
          authorized.answer(durable = false),
          scopes = setOf(SERVICE_CLIENTS_MANAGE_SCOPE),
          consentText = PrivilegedAuthorityRows.SERVICE_CLIENTS_CONSENT,
        ),
      ),
    )
  }

  @Test
  fun `the authority carries the URIs the consent recognised the owner by`() {
    // Each call asks who owns the pod now against exactly this set, and an equivalence
    // cannot be resolved from a token — so a set the exchange dropped here is a gap nothing
    // downstream can close.
    val authorized = Authorized()
    val alias = "https://id.test/e/${randomId()}"
    val code = authorized.code(
      authorized.answer(durable = false),
      scopes = setOf(SERVICE_CLIENTS_MANAGE_SCOPE),
      subjectUris = setOf(authorized.webId, alias),
    )

    val jti = jtiOf(issued(authorized.redeem(code)).accessToken)

    val authority = assertNotNull(managementAuthorities.standing(authorized.podId, jti))
    assertEquals(setOf(authorized.webId, alias), authority.subjectUris)
  }

  @Test
  fun `an ordinary code writes no privileged authority`() {
    val authorized = Authorized()
    val code = authorized.code(authorized.answer(durable = false))

    val jti = jtiOf(issued(authorized.redeem(code)).accessToken)

    assertNull(managementAuthorities.standing(authorized.podId, jti))
  }

  @Test
  fun `a refresh row carrying a management authority loses it when it rotates`() {
    // No path in this server writes such a row — a privileged authority seeds no family. This answers for
    // one written before the rule, and it is the second half of the promise the code exchange makes.
    val authorized = Authorized(grants = setOf(contextScope, PUBLIC_READ_SCOPE, SERVICE_CLIENTS_MANAGE_SCOPE))
    val seeded = refreshTokenStore.issueNewFamily(
      pod = authorized.podId,
      podName = authorized.pod.name,
      clientId = clientId,
      webId = authorized.webId,
      scopes = setOf(PUBLIC_READ_SCOPE, SERVICE_CLIENTS_MANAGE_SCOPE),
      lifetime = PodRefreshTokenStore.Lifetime.SESSION,
    )

    val rotated = issued(authorized.refresh(seeded.plaintext))

    assertEquals(setOf(PUBLIC_READ_SCOPE), rotated.scopes)
  }

  @Test
  fun `a refresh cannot be down-scoped to a management authority`() {
    val authorized = Authorized(grants = setOf(contextScope, PUBLIC_READ_SCOPE, SERVICE_CLIENTS_MANAGE_SCOPE))
    val seeded = refreshTokenStore.issueNewFamily(
      pod = authorized.podId,
      podName = authorized.pod.name,
      clientId = clientId,
      webId = authorized.webId,
      scopes = setOf(PUBLIC_READ_SCOPE, SERVICE_CLIENTS_MANAGE_SCOPE),
      lifetime = PodRefreshTokenStore.Lifetime.SESSION,
    )

    val refusal = refused(authorized.refresh(seeded.plaintext, scope = SERVICE_CLIENTS_MANAGE_SCOPE))

    assertEquals(OAuthErrorCode.INVALID_SCOPE, refusal.code)
  }

  /** The `jti` the access token carries — what a privileged authority is filed under. */
  private fun jtiOf(accessToken: String): String =
    checkNotNull(SignedJWT.parse(accessToken).jwtClaimsSet.jwtid) { "an access token always carries a jti" }


  @Test
  fun `a management authority does not end a durable family the same app holds`() {
    // The two consents share one decision document. This is what the management screen must not
    // be able to do to a connection it never asked about.
    val authorized = Authorized()
    val tokens = issued(authorized.redeem(authorized.code(authorized.answer(durable = true))))
    val refreshToken = assertNotNull(tokens.refreshToken)

    // What `PodConsentFlow.privilegedAuthority` writes when the owner approves one.
    consentDecisionStore.recordWithoutLifetime(authorized.podId, clientId, authorized.webId)

    val rotated = issued(authorized.refresh(refreshToken))
    assertNotNull(rotated.refreshToken, "the durable connection was never withdrawn")
  }


  @Test
  fun `a code from an authorization nobody has answered is refused, generation or not`() {
    // Grants on record and no lifetime answer is a state of its own — a consent that died between
    // its two writes, or one older than the control. `decision == null` used to catch it. Once an
    // privileged consent can give that document a generation without answering it, the document exists
    // and the refusal has to read the answer instead of the row.
    val authorized = Authorized()
    val generation = consentDecisionStore
      .recordWithoutLifetime(authorized.podId, clientId, authorized.webId).generation

    val refusal = refused(authorized.redeem(authorized.code(generation)))

    assertEquals(OAuthErrorCode.INVALID_GRANT, refusal.code)
    assertEquals("this authorization has not been answered; re-authorize", refusal.description)
  }
}
