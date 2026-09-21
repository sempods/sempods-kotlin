package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.oauth.DynamicClientStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The gate both browser routes pass through on their way to a code.
 *
 * What each route decides before it gets here is its own test's; this is what the step refuses on
 * its own, whichever of them asked.
 */
internal class PodAuthorizationCodesTest : PodBrowserFlowTest() {

  @Inject
  private lateinit var codes: PodAuthorizationCodes

  @Inject
  private lateinit var dynamicClientStore: DynamicClientStore

  @Inject
  private lateinit var authorizationCodeStore: AuthorizationCodeStore

  private fun target(owned: Owned) = assertNotNull(
    OAuthErrors.redirectTargetFor(
      PodClientDirectory.of(owned.pod.id, dynamicClientStore),
      clientId,
      redirectUri,
    ),
  )

  @Test
  fun `a dynamic client cannot be handed a code without PKCE, whatever route reached here`() {
    // Defence in depth: `/authorize` refuses this at the entrance, and this is the second gate —
    // the consent submission arrives with values off a form.
    val owned = Owned()

    val result = codes.issue(
      pod = owned.pod,
      clientId = "dyn:${randomId()}",
      webId = owned.webId,
      scopes = emptySet(),
      target = target(owned),
      state = null,
      codeChallenge = null,
      codeChallengeMethod = null,
      via = PodCodeIssuance.CONSENT,
      session = owned.session,
    )

    val refused = assertIs<PodCodeResult.Refused>(result, "was: $result")
    val delivery = assertIs<OAuthErrorDelivery.Redirect>(refused.delivery)
    assertEquals(OAuthErrorCode.INVALID_REQUEST, delivery.code)
    assertEquals("PKCE (S256) is required for dynamic clients", delivery.description)
  }

  @Test
  fun `a code carries the address and the state it will travel with`() {
    val owned = Owned()
    val state = "state-${randomId()}"

    val result = codes.issue(
      pod = owned.pod,
      clientId = clientId,
      webId = owned.webId,
      scopes = emptySet(),
      target = target(owned),
      state = state,
      codeChallenge = challenge,
      codeChallengeMethod = "S256",
      via = PodCodeIssuance.AUTO_GRANT,
      session = owned.session,
    )

    // Assembling the address is the adapter's, so what this answers has to carry both parts.
    val minted = assertIs<PodCodeResult.Minted>(result, "was: $result")
    assertEquals(redirectUri, minted.target.uri)
    assertEquals(state, minted.state)

    // And the code is one the exchange will take: minting that left nothing redeemable would
    // satisfy every assertion above.
    val entry = assertNotNull(authorizationCodeStore.consume(minted.code))
    assertEquals(owned.webId, entry.subject)
    assertEquals(redirectUri, entry.redirectUri)
    assertEquals(challenge, entry.codeChallenge)
  }
}
