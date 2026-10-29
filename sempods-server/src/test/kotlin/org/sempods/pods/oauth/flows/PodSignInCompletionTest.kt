package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.FakeIdServerTransport
import org.sempods.auth.PendingLogin
import org.sempods.auth.PodIdentityProvider
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.BrowserSignIn
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.Secrets
import org.sempods.commons.net.UrlUtil
import org.sempods.pods.oauth.PodTokenIssuer
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The id-server's answer at `oidc/callback`, decided without a route: what a callback is refused
 * for, how a provider's failure reaches the client, and what a completed sign-in resumes.
 */
internal class PodSignInCompletionTest : PodBrowserFlowTest() {

  @Inject
  private lateinit var completion: PodSignInCompletion

  @Inject
  private lateinit var loginStateStore: PodLoginStateStore

  @Inject
  private lateinit var fakeIdServer: FakeIdServerTransport

  @Inject
  private lateinit var podTokenIssuer: PodTokenIssuer

  @Inject
  private lateinit var identityProvider: PodIdentityProvider

  /** A sign-in parked for [owned], as [PodSignIn] parks one: its state, nonce and browser pin. */
  private inner class Parked(owned: Owned, pod: String = owned.pod.name, serviceConsent: Boolean = false) {
    private val started = loginStateStore.signIn.begin(
      identityProvider.relyingParty(pod),
      PendingLogin(
        clientId = clientId,
        redirectUri = redirectUri,
        clientState = "client-state",
        scope = owned.readScope,
        prompt = null,
        codeChallenge = challenge,
        codeChallengeMethod = "S256",
        serviceConsent = serviceConsent,
      ),
      prompt = null,
    )
    val state = started.state
    val nonce = checkNotNull(UrlUtil.queryParams(URI(started.authorizationUrl).rawQuery, decodeParams = true)["nonce"])
    val pin = started.browserPin

    fun callback(
      code: String? = null,
      error: String? = null,
      errorDescription: String? = null,
      pin: String? = this.pin,
      iss: String? = FakeIdServerTransport.ISSUER,
    ) = PodSignInCallback(
      state = state, iss = iss, code = code, error = error, errorDescription = errorDescription, presentedPin = pin,
    )
  }

  @Test
  fun `an unknown state is refused, and so is one that was already used`() {
    val owned = Owned()
    val parked = Parked(owned)

    assertEquals(
      PodSignInResult.Refused(PodSignInRefusal.UNKNOWN_STATE),
      completion.complete(owned.pod, parked.callback(code = "x").copy(state = Secrets.newSecret())),
    )
    assertIs<PodSignInResult.Failed>(completion.complete(owned.pod, parked.callback()))
    assertEquals(PodSignInResult.Refused(PodSignInRefusal.UNKNOWN_STATE), completion.complete(owned.pod, parked.callback()))
  }

  @Test
  fun `a sign-in resumed by many callbacks at once is resumed once`() {
    val owned = Owned()
    val parked = Parked(owned)
    val code = fakeIdServer.expect(webId = owned.webId, nonce = parked.nonce)

    val results = concurrently { completion.complete(owned.pod, parked.callback(code = code)) }

    assertEquals(1, results.count { it is PodSignInResult.SignedIn }, "$results")
    results.filterNot { it is PodSignInResult.SignedIn }.forEach {
      assertEquals(PodSignInResult.Refused(PodSignInRefusal.UNKNOWN_STATE), it)
    }
  }

  @Test
  fun `a sign-in parked for another pod is refused here and spent`() {
    val owned = Owned()
    val parked = Parked(owned, pod = "another-pod")

    assertEquals(PodSignInResult.Refused(PodSignInRefusal.UNKNOWN_STATE), completion.complete(owned.pod, parked.callback(code = "x")))
    assertEquals(
      BrowserSignIn.Completion.UnknownState,
      loginStateStore.signIn.complete(
        identityProvider.relyingParty("another-pod"),
        BrowserSignIn.Callback(parked.state, parked.pin, FakeIdServerTransport.ISSUER, code = "x", error = null),
      ),
    )
  }

  @Test
  fun `an answer that does not name the id-server is refused before anything in it is used, and the sign-in is spent`() {
    val owned = Owned()
    for (iss in listOf("https://attacker.example", "${FakeIdServerTransport.ISSUER}/", null)) {
      for (answer in listOf("code", "error")) {
        val parked = Parked(owned)
        val code = fakeIdServer.expect(webId = owned.webId, nonce = parked.nonce)
        val callback = if (answer == "code") parked.callback(code = code) else parked.callback(error = "access_denied")

        assertEquals(
          PodSignInResult.Refused(PodSignInRefusal.WRONG_ISSUER),
          completion.complete(owned.pod, callback.copy(iss = iss)),
          "iss $iss, $answer",
        )
        assertEquals(PodSignInResult.Refused(PodSignInRefusal.UNKNOWN_STATE), completion.complete(owned.pod, callback), "iss $iss, $answer")
      }
    }
  }

  @Test
  fun `a callback in another browser is refused before the code is exchanged, and the sign-in is spent`() {
    val owned = Owned()
    for (presented in listOf(null, Secrets.newSecret())) {
      val parked = Parked(owned)
      val code = fakeIdServer.expect(webId = owned.webId, nonce = parked.nonce)

      assertEquals(
        PodSignInResult.Refused(PodSignInRefusal.OTHER_BROWSER),
        completion.complete(owned.pod, parked.callback(code = code, pin = presented)),
      )
      assertEquals(PodSignInResult.Refused(PodSignInRefusal.UNKNOWN_STATE), completion.complete(owned.pod, parked.callback(code = code)))
    }
  }

  @Test
  fun `a provider's refusal reaches the client as one, and everything else as a fault of this pod`() {
    val owned = Owned()
    val cases = listOf(
      Triple("access_denied", null, OAuthErrorCode.ACCESS_DENIED to "access_denied"),
      Triple("user_cancelled_authorize", "", OAuthErrorCode.ACCESS_DENIED to "user_cancelled_authorize"),
      Triple("temporarily_unavailable", "busy", OAuthErrorCode.TEMPORARILY_UNAVAILABLE to "temporarily_unavailable: busy"),
      Triple("invalid_scope", "invalid_scope", OAuthErrorCode.SERVER_ERROR to "invalid_scope"),
      Triple("something_new", "what", OAuthErrorCode.SERVER_ERROR to "something_new: what"),
    )

    for ((error, description, expected) in cases) {
      val parked = Parked(owned)
      val failed = assertIs<PodSignInResult.Failed>(
        completion.complete(owned.pod, parked.callback(error = error, errorDescription = description)),
      )
      assertEquals(expected, failed.error to failed.description, error)
      assertEquals("client-state", failed.pending.clientState)
    }
  }

  @Test
  fun `a callback with neither a code nor an error, or a code the id-server refuses, fails as this pod's fault`() {
    val owned = Owned()

    val withoutCode = assertIs<PodSignInResult.Failed>(completion.complete(owned.pod, Parked(owned).callback(code = " ")))
    assertEquals(OAuthErrorCode.SERVER_ERROR to "no authorization code", withoutCode.error to withoutCode.description)

    val parked = Parked(owned)
    val refusedCode = fakeIdServer.expect(webId = null, nonce = parked.nonce)
    val refused = assertIs<PodSignInResult.Failed>(completion.complete(owned.pod, parked.callback(code = refusedCode)))
    assertEquals(OAuthErrorCode.SERVER_ERROR to "login failed", refused.error to refused.description)
  }

  @Test
  fun `a completed sign-in is a session for the person and resumes the request it parked`() {
    val owned = Owned()

    for (serviceConsent in listOf(false, true)) {
      val parked = Parked(owned, serviceConsent = serviceConsent)
      val code = fakeIdServer.expect(webId = owned.webId, nonce = parked.nonce)

      val signedIn = assertIs<PodSignInResult.SignedIn>(completion.complete(owned.pod, parked.callback(code = code)))

      assertEquals(owned.webId, podTokenIssuer.readSession(owned.pod.name, signedIn.sessionToken)?.webId)
      if (serviceConsent) assertIs<PodSignInResumed.ServiceConsent>(signedIn.resumed)
      else assertIs<PodSignInResumed.Authorize>(signedIn.resumed)
    }
  }
}
