package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.sempods.FakeIdServerTransport
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.auth.core.SigningKeys
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.oauth.PodTokenIssuer
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pod base `P` is the issuer of everything a pod signs (SPS-AUTH-028): what it mints, what it
 * accepts, and what it refuses from the pod next door on the same origin.
 *
 * Tokens and session cookies minted before the switch carry `P/`. They are signed here with the
 * pod's own key, because nothing in the server mints that spelling any more.
 */
class PodIssuerHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var signingKeys: SigningKeys

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  private val apiBaseUrl get() = SempodsModule.config.apiBaseUrl.trimEnd('/')

  private fun podBaseUrl(podName: String) = "$apiBaseUrl/$podName"

  /** A read that accepts an anonymous caller and answers 401 for a bearer it refuses. */
  private fun readWith(podName: String, bearer: String): TestHttpResponse =
    http.prepareGet("${podBaseUrl(podName)}/_system/meta/date-modified")
      .addHeader("Authorization", "Bearer $bearer")
      .execute()

  private fun authorizeWith(podName: String, cookie: String): TestHttpResponse =
    http.prepareGet("${podBaseUrl(podName)}/_system/auth/authorize")
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", "did:web:localhost%3A5173")
      .addQueryParam("redirect_uri", "http://localhost:5173/callback")
      .addQueryParam("state", "issuer")
      .addHeader("Cookie", cookie)
      .setFollowRedirect(false)
      .execute()

  /** Signed with the pod's own key, so only [issuer] can make the pod refuse it. */
  private fun signed(issuer: String, claims: JWTClaimsSet.Builder.() -> Unit): String {
    val key = signingKeys.signingKey()
    val now = Instant.now()
    val set = JWTClaimsSet.Builder()
      .issuer(issuer)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(600)))
      .jwtID(UUID.randomUUID().toString())
      .apply(claims)
      .build()
    return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), set)
      .apply { sign(RSASSASigner(key)) }
      .serialize()
  }

  private fun accessToken(issuer: String): String = signed(issuer) {
    subject("https://id.test/e/issuer-test")
    claim("client_id", "did:web:issuer-test.example")
    claim("scope", "")
  }

  private fun sessionCookie(issuer: String, webId: String): String = "sempods_pod_session=" + signed(issuer) {
    subject(webId)
    claim(PodTokenIssuer.CLAIM_TOKEN_USE, PodTokenIssuer.TOKEN_USE_SESSION)
    claim(PodTokenIssuer.CLAIM_AUTH_TIME, Instant.now().epochSecond)
  }

  private fun issuerOf(jwt: String): String? = SignedJWT.parse(jwt).jwtClaimsSet.issuer

  @Test
  fun `the pod mints its base URL as the issuer`() {
    val pod = sempodsTestFactory.newPod()

    assertEquals(podBaseUrl(pod.name), issuerOf(mintScopedToken(pod.name, emptyList())))
    assertEquals(
      podBaseUrl(pod.name),
      issuerOf(signIn(pod.name, "https://id.test/e/issuer-test").cookie.substringAfter('=')),
    )
  }

  @Test
  fun `a token names its pod in either spelling, and no other pod in any`() {
    val alice = sempodsTestFactory.newPod()
    val bob = sempodsTestFactory.newPod()
    val aliceBase = podBaseUrl(alice.name)
    val bobBase = podBaseUrl(bob.name)

    assertEquals(200, readWith(alice.name, mintScopedToken(alice.name, emptyList())).statusCode)
    assertEquals(200, readWith(alice.name, accessToken(aliceBase)).statusCode)
    assertEquals(200, readWith(alice.name, accessToken("$aliceBase/")).statusCode, "a token minted before 0.2")

    // Two path-scoped pods on one origin share a signing key; the issuer is what keeps them apart.
    assertEquals(401, readWith(bob.name, mintScopedToken(alice.name, emptyList())).statusCode)
    for (issuer in listOf(bobBase, "$bobBase/", "$aliceBase/_system/auth")) {
      assertEquals(401, readWith(alice.name, accessToken(issuer)).statusCode, issuer)
    }
  }

  @Test
  fun `a session cookie minted with the trailing-slash issuer still signs its person in, and is renewed as P`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val response = authorizeWith(pod.name, sessionCookie("${podBaseUrl(pod.name)}/", ownerWebId))

    assertEquals(200, response.statusCode, "a remembered sign-in reaches consent directly: ${response.responseBody}")
    val renewed = checkNotNull(response.sessionCookie()) { "an authorization renews the session it arrived with" }
    assertEquals(podBaseUrl(pod.name), issuerOf(renewed.substringAfter('=')))
  }

  @Test
  fun `a session cookie of the pod next door signs nobody in, in either spelling`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val alice = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val bob = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    for (issuer in listOf(podBaseUrl(alice.name), "${podBaseUrl(alice.name)}/")) {
      val response = authorizeWith(bob.name, sessionCookie(issuer, ownerWebId))

      assertEquals(307, response.statusCode, issuer)
      assertTrue(
        checkNotNull(response.getHeader("Location")).startsWith(FakeIdServerTransport.ISSUER),
        "a session on one pod is not a session on another (SPS-AUTH-054): $issuer",
      )
    }
  }
}
