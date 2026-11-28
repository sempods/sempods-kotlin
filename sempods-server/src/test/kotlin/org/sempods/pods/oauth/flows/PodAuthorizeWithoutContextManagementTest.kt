package org.sempods.pods.oauth.flows

import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.auth.ConsentTransactionStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.pods.contexts.ContextManagementSupport
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.PodScopeValidator
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `/authorize` on a pod that serves no context creation and deletion.
 *
 * The suite's server installs the context-management module, so the flow is built here from the
 * suite's own parts with the module absent. The HTTP half of the refusal is the one
 * `PodGrantsEndpointHttpTest` pins for `grants:manage`.
 */
class PodAuthorizeWithoutContextManagementTest : SempodsIntegrationTest() {

  private fun flow(contextManagement: ContextManagementSupport) = PodAuthorizeFlow(
    codes = injector.getInstance(PodAuthorizationCodes::class.java),
    podFacade = podFacade,
    podGrantsFacade = injector.getInstance(PodGrantsFacade::class.java),
    dynamicClientStore = injector.getInstance(DynamicClientStore::class.java),
    consentDecisionStore = injector.getInstance(PodConsentDecisionStore::class.java),
    refreshTokenStore = injector.getInstance(PodRefreshTokenStore::class.java),
    consentTransactionStore = injector.getInstance(ConsentTransactionStore::class.java),
    signIn = injector.getInstance(PodSignIn::class.java),
    podScopeValidator = injector.getInstance(PodScopeValidator::class.java),
    appHoldings = injector.getInstance(PodAppHoldings::class.java),
    consentSelection = injector.getInstance(ConsentSelection::class.java),
    contextManagement = contextManagement,
  )

  private fun request(scope: String) = PodAuthorizeRequest(
    clientId = "did:web:app.example",
    redirectUri = "https://app.example/callback",
    state = "s-1",
    terms = PodAuthorizeTerms.Read(
      responseType = setOf("code"),
      codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
      codeChallengeMethod = "S256",
      prompt = emptySet(),
      scopes = setOf(scope),
    ),
  )

  @Test
  fun `without the module contexts manage is invalid_scope at the client's address`() {
    val pod = sempodsTestFactory.newPod(ownerUser = sempodsTestFactory.newOwner())

    val result = flow(ContextManagementSupport(Optional.empty())).authorize(pod.hosted, request(CONTEXTS_MANAGE_SCOPE), session = null)

    val delivery = assertIs<OAuthErrorDelivery.Redirect>(assertIs<PodAuthorizeResult.Error>(result).delivery)
    assertEquals(OAuthErrorCode.INVALID_SCOPE, delivery.code)
    assertTrue("creates and deletes no contexts" in delivery.description, delivery.description)
  }

  @Test
  fun `with the module the same request goes on to the sign-in`() {
    val pod = sempodsTestFactory.newPod(ownerUser = sempodsTestFactory.newOwner())

    val result = flow(injector.getInstance(ContextManagementSupport::class.java)).authorize(pod.hosted, request(CONTEXTS_MANAGE_SCOPE), session = null)

    assertIs<PodAuthorizeResult.Login>(result)
  }
}
