package org.sempods.pods.oauth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.sempods.auth.core.SigningKeyStore
import org.sempods.auth.core.SigningKeys
import org.sempods.spec.PodRef
import java.net.URI
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Docker-free unit test of the verification half of what `SempodsBaseEndpoint.resolveOAuthAccess`
 * used to do in one piece — the table of "what makes a pod bearer unusable", which had no test of
 * its own before the split.
 *
 * The distinction the endpoint keys off is [PodTokenRejection.podMismatch]: a token that verifies
 * but names another issuer is a 401 on the read path and a 403 on the app-token path, and that is
 * decided one frame out, not here.
 */
class PodTokenAuthenticatorTest {

  private val apiBaseUrl = "https://pods.test/"

  /**
   * Per-instance, because the logging cases below key on it: `PodTokenAuthenticator` names the pod
   * in every line it writes, and its logger is shared with the HTTP suites running beside this one.
   * A fixed "mypod" would make a sibling's line indistinguishable from this test's own. See
   * [linesLoggedAt].
   */
  private val podName = "mypod-${UUID.randomUUID()}"
  private val pod = PodRef(
    uri = URI("$apiBaseUrl$podName"),
    name = podName,
    owner = "https://id.test/e/owner",
  )

  private val key: RSAKey = RSAKeyGenerator(2048)
    .keyID(UUID.randomUUID().toString())
    .keyUse(KeyUse.SIGNATURE)
    .algorithm(JWSAlgorithm.RS256)
    .generate()

  private val foreignKey: RSAKey = RSAKeyGenerator(2048)
    .keyID(UUID.randomUUID().toString())
    .keyUse(KeyUse.SIGNATURE)
    .algorithm(JWSAlgorithm.RS256)
    .generate()

  private val authenticator = PodTokenAuthenticator(SigningKeys(FixedSigningKeyStore(key)))

  /** A store that already holds [held], so [SigningKeys] never bootstraps. */
  private class FixedSigningKeyStore(private val held: RSAKey) : SigningKeyStore {
    override fun loadAll(): List<String> = listOf(held.toJSONString())
    override fun createInitial(jwk: String, kid: String, algorithm: String): Boolean =
      error("must not bootstrap: the store is not empty")
  }

  private fun token(
    signWith: RSAKey = key,
    /**
     * The `kid` the header advertises. Defaults to [signWith]'s own, so a token signed by a key the
     * pod does not hold also *names* one it does not hold — which is "no matching key", not a bad
     * signature. Override it to claim a key the pod has and sign with another: that is the only way
     * to reach the `badSignature` branch of `JwtVerifier.verify`.
     */
    keyId: String = signWith.keyID,
    issuer: String? = "${apiBaseUrl}${pod.name}/",
    clientId: String? = "did:web:app.test",
    subject: String? = "https://id.test/e/person",
    clientType: String? = null,
    scope: String? = null,
    scp: List<String>? = null,
    expiresAt: Instant = Instant.now().plusSeconds(300),
    jwtId: String? = "jti-1",
  ): String {
    val claims = JWTClaimsSet.Builder().apply {
      issuer?.let { issuer(it) }
      clientId?.let { claim("client_id", it) }
      subject?.let { subject(it) }
      clientType?.let { claim("client_type", it) }
      scope?.let { claim("scope", it) }
      scp?.let { claim("scp", it) }
      jwtId?.let { jwtID(it) }
      issueTime(Date.from(Instant.now()))
      expirationTime(Date.from(expiresAt))
    }.build()
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(keyId).build(),
      claims,
    )
    jwt.sign(RSASSASigner(signWith))
    return jwt.serialize()
  }

  private fun verified(raw: String): PodAccessToken {
    val outcome = authenticator.authenticate(raw, pod)
    assertIs<PodTokenAuthentication.Verified>(outcome)
    return outcome.token
  }

  private fun rejection(raw: String?): PodTokenRejection {
    val outcome = authenticator.authenticate(raw, pod)
    assertIs<PodTokenAuthentication.Rejected>(outcome)
    return outcome.reason
  }

  // --- the anonymous caller is not a failure --------------------------------------------------

  @Test
  fun `no bearer is NoToken rather than a rejection`() {
    assertEquals(PodTokenAuthentication.NoToken, authenticator.authenticate(null, pod))
    assertEquals(PodTokenAuthentication.NoToken, authenticator.authenticate("   ", pod))
  }

  // --- rejections -----------------------------------------------------------------------------

  @Test
  fun `an unparseable bearer is refused without throwing`() {
    assertEquals(PodTokenRejection.invalidToken, rejection("not-a-jwt"))
    assertEquals(PodTokenRejection.invalidToken, rejection("a.b.c"))
  }

  @Test
  fun `a token signed by a key this pod does not publish is refused`() {
    assertEquals(PodTokenRejection.invalidToken, rejection(token(signWith = foreignKey)))
  }

  @Test
  fun `an expired token is refused`() {
    assertEquals(
      PodTokenRejection.invalidToken,
      rejection(token(expiresAt = Instant.now().minusSeconds(1))),
    )
  }

  @Test
  fun `a token issued for another pod is podMismatch and not invalidToken`() {
    assertEquals(
      PodTokenRejection.podMismatch,
      rejection(token(issuer = "${apiBaseUrl}otherpod/")),
    )
  }

  @Test
  fun `a pod is identified by its uri, so the same name on another host does not match`() {
    // What `PodRef.uri` is for: a bare pod name names nothing until a host resolves it, and two
    // deployments may each have one. The issuer check keys off the whole URI, so a token another
    // host minted for its own `mypod` is as foreign as one for a different name.
    assertEquals(
      PodTokenRejection.podMismatch,
      rejection(token(issuer = "https://other-host.test/${pod.name}/")),
    )
  }

  @Test
  fun `a token with no issuer at all is podMismatch too`() {
    assertEquals(PodTokenRejection.podMismatch, rejection(token(issuer = null)))
  }

  @Test
  fun `a token without client_id has no app to resolve grants for`() {
    assertEquals(PodTokenRejection.missingClientId, rejection(token(clientId = null)))
  }

  @Test
  fun `a user token without sub is refused`() {
    // Only a service client may have no subject; anything else without one would resolve grants
    // against a WebID that is not there.
    assertEquals(PodTokenRejection.invalidToken, rejection(token(subject = null)))
    assertEquals(PodTokenRejection.invalidToken, rejection(token(subject = "  ")))
  }

  // --- verified -------------------------------------------------------------------------------

  @Test
  fun `a well-formed user token carries its claims through`() {
    val issuedBefore = Instant.now().plusSeconds(1)
    val result = verified(token(scope = "public-read openid"))

    assertEquals("did:web:app.test", result.clientId)
    assertEquals("https://id.test/e/person", result.sub)
    assertEquals("jti-1", result.jti)
    assertEquals(setOf("public-read", "openid"), result.scopeValues)
    assertEquals(false, result.isServiceClient)
    assertEquals(true, result.issuedAt!!.isBefore(issuedBefore))
  }

  @Test
  fun `a service-client token needs no sub`() {
    val result = verified(token(subject = null, clientType = SERVICE_CLIENT_TYPE))

    assertEquals(null, result.sub)
    assertEquals(true, result.isServiceClient)
  }

  @Test
  fun `scope and scp are unioned, because tokens in this repository use both`() {
    val result = verified(token(scope = "public-read", scp = listOf("openid", "public-read")))
    assertEquals(setOf("openid", "public-read"), result.scopeValues)
  }

  @Test
  fun `a missing jti is null rather than blank`() {
    assertEquals(null, verified(token(jwtId = null)).jti)
  }

  // --- what this class logs, and at which level -----------------------------------------------
  //
  // The levels are load-bearing, not cosmetic: the success line is reached by *every*
  // authenticated request, so at INFO it is a per-request access log — the thing
  // `docs/logging.md` moved `JaxRsDebugFilter` off INFO for. Nothing but a test holds that,
  // and a later refactor that "tidies" a `logger.debug` back to `logger.info` is silent.

  /**
   * Runs [block] with an appender on this class's logger, pinned to [level] — the level a
   * deployment would run at, so what comes back is what that deployment would actually write.
   * `gradle/logback-test.xml` runs test JVMs at INFO, which is the production default and
   * therefore the interesting case.
   *
   * **This logger is shared with every class running beside it**, and `PodTokenAuthenticator` is
   * on the path of every authenticated request in the HTTP suites, which run concurrently
   * (`build.gradle.kts`, `parallel.mode.classes.default = concurrent`). Two consequences, and both
   * are handled here rather than by taking the class out of the parallel run — rung 1 of
   * `docs/testing.md` §"When a test is not safe", which is the only rung that costs no
   * parallelism:
   *
   * - **Only this test's own lines come back.** Every line the class logs names its pod, and
   *   [pod] carries a per-instance name, so a sibling's traffic is filtered out rather than
   *   counted. Without this the negative assertions ("nothing was logged") would read a
   *   neighbour's line as their own. Same idea as `ApiExceptionMapperTest`'s `loggedLine(marker)`
   *   in `:commons-jaxrs`.
   * - **The events are collected concurrency-safely.** `ListAppender` keeps a plain `ArrayList`,
   *   which a sibling's request would append to on another thread while this reads it.
   *
   * The level is process-wide for the duration and is put back. Raising it makes concurrent
   * classes log *more* for that moment, which no test asserts on — nothing in this repository
   * reads this logger's output except the cases below.
   */
  private fun linesLoggedAt(level: Level, block: () -> Unit): List<ILoggingEvent> {
    val logger = logbackContext().getLogger(PodTokenAuthenticator::class.java)
    val appender = CapturingAppender()
    val restoreTo = logger.level
    appender.start()
    logger.addAppender(appender)
    logger.level = level
    try {
      block()
    } finally {
      logger.level = restoreTo
      logger.detachAppender(appender)
      appender.stop()
    }
    return appender.events.filter { "pod='${pod.name}'" in it.formattedMessage }
  }

  /** See [linesLoggedAt]: a sibling on another thread may append while a test reads. */
  private class CapturingAppender : AppenderBase<ILoggingEvent>() {
    val events = CopyOnWriteArrayList<ILoggingEvent>()
    override fun append(eventObject: ILoggingEvent) {
      events += eventObject
    }
  }

  /**
   * SLF4J hands a `SubstituteLoggerFactory` to every thread but the one currently binding the
   * provider, and this suite runs its classes in a `ForkJoinPool` — so a plain cast is flaky. The
   * window closes in microseconds. Same reasoning as `ApiExceptionMapperTest` in `:commons-jaxrs`;
   * a third caller is the point at which this belongs in `:commons` test fixtures.
   */
  private fun logbackContext(): LoggerContext {
    repeat(500) {
      val factory = LoggerFactory.getILoggerFactory()
      if (factory is LoggerContext) return factory
      Thread.sleep(10)
    }
    error("logback never became the SLF4J binding of this test JVM")
  }

  @Test
  fun `a verified token writes nothing at INFO, because that would be one line per request`() {
    val atInfo = linesLoggedAt(Level.INFO) { verified(token(scope = "public-read")) }

    assertTrue(
      atInfo.none { "Token verified" in it.formattedMessage },
      "the success line is back in the production log: every authenticated request reaches it, " +
        "so at INFO it is a per-request access log. See `docs/logging.md`. Got: " +
        atInfo.map { "${it.level} ${it.formattedMessage}" },
    )
  }

  @Test
  fun `the rejection paths stay in the production log, because a refusal is an event`() {
    // The split this class is asserted on: traffic is DEBUG, events are INFO/WARN. An expired
    // token is the routine one and stays INFO so a forged signature at WARN is not buried in it.
    val expired = linesLoggedAt(Level.INFO) { rejection(token(expiresAt = Instant.now().minusSeconds(1))) }
    assertEquals(
      Level.INFO,
      expired.single { "expired" in it.formattedMessage }.level,
    )

    // A forgery has to claim a `kid` this pod holds to reach the WARN branch at all — signed with
    // another key, checked against the one it named. Signed *and* named by a foreign key it is
    // "no matching key", which `JwtVerifier` reports as Inconclusive and this class logs not at
    // all; the case below pins that silence, since it is the one an operator cannot see.
    val forged = linesLoggedAt(Level.INFO) {
      rejection(token(signWith = foreignKey, keyId = key.keyID))
    }
    assertEquals(
      Level.WARN,
      forged.single { "signature verification failed" in it.formattedMessage }.level,
    )

    val unknownKey = linesLoggedAt(Level.DEBUG) { rejection(token(signWith = foreignKey)) }
    assertTrue(
      unknownKey.isEmpty(),
      "a token naming a key this pod does not hold is refused silently by design; if that is to " +
        "change, change it deliberately. Got: ${unknownKey.map { it.formattedMessage }}",
    )

    val otherPod = linesLoggedAt(Level.INFO) { rejection(token(issuer = "${apiBaseUrl}otherpod/")) }
    assertEquals(
      Level.INFO,
      otherPod.single { "Issuer mismatch" in it.formattedMessage }.level,
    )
  }

  @Test
  fun `a concurrent pod's traffic stays out of these assertions`() {
    // The isolation the three cases above rest on, made explicit: this logger is shared with the
    // HTTP suites, which run concurrently and authenticate constantly. A second pod on the same
    // authenticator is what that looks like from in here. Without the filter in `linesLoggedAt`
    // the negative assertions would count a neighbour's line as their own — and would do it
    // intermittently, which is the worst way to find out.
    val neighbour = PodRef(
      uri = URI("${apiBaseUrl}neighbour"),
      name = "neighbour",
      owner = pod.owner,
    )

    val mine = linesLoggedAt(Level.DEBUG) {
      authenticator.authenticate(token(issuer = "${apiBaseUrl}neighbour/"), neighbour)
      verified(token())
    }

    assertEquals(
      1,
      mine.size,
      "only this test's own line may come back, got: ${mine.map { it.formattedMessage }}",
    )
    assertTrue("pod='${pod.name}'" in mine.single().formattedMessage)
  }

  @Test
  fun `at DEBUG the success line names the flow it observed rather than a blank`() {
    // `client_type` is absent on an authorization-code token and only written by
    // `PodTokenIssuer.issueServiceToken`, so its absence *is* the statement that this was 3-leg.
    // Rendering it as `(unset)` read like a value someone forgot to set, and was misread as one.
    val threeLeg = linesLoggedAt(Level.DEBUG) { verified(token()) }
      .single { "Token verified" in it.formattedMessage }

    assertEquals(Level.DEBUG, threeLeg.level)
    assertTrue(
      "clientType='(authorization_code)'" in threeLeg.formattedMessage,
      "an absent client_type must name the flow: ${threeLeg.formattedMessage}",
    )

    val service = linesLoggedAt(Level.DEBUG) {
      verified(token(subject = null, clientType = SERVICE_CLIENT_TYPE))
    }.single { "Token verified" in it.formattedMessage }

    assertTrue(
      "clientType='service'" in service.formattedMessage,
      "a service token must still report its own client_type: ${service.formattedMessage}",
    )
  }
}
