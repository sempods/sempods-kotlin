package org.sempods.pods.grants

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.sempods.pods.PodFacade
import org.sempods.pods.oauth.PodAccessToken
import org.sempods.pods.oauth.SERVICE_CLIENT_TYPE
import org.sempods.spec.PodRef
import java.net.URI
import kotlin.test.assertEquals

/**
 * Docker-free unit test of the [PodAuthorizer] seam's one implementation.
 *
 * What it pins is the rule the sandbox rests on and that had no test below the HTTP level before:
 * the effective contexts are the *resolved grants* — never the token's own scope claim — plus the
 * pod's public contexts only when `public-read` is in the token. The end-to-end half (401/403 on
 * the wire, and a revoked grant taking effect on the next request) stays in
 * `McpEndpointHttpTest` and `PodResourceRevocationHttpTest`.
 */
class GrantStorePodAuthorizerTest {

  private val apiBaseUrl = "https://pods.test/"
  private val podId = ObjectId()
  private val pod = PodRef(
    uri = URI("${apiBaseUrl}mypod"),
    name = "mypod",
    owner = "https://id.test/e/owner",
  )
  private val podBaseUrl = "${pod.uri}/"

  private fun ctx(path: String) = "$podBaseUrl$path"

  private val publicContext = URI(ctx("open"))
  private val grantedContext = URI(ctx("tasks"))

  private val podFacade = mockk<PodFacade>()
  private val resolver = mockk<PodContextPermissionResolver>()

  private val authorizer = GrantStorePodAuthorizer(
    podFacade = podFacade,
    podContextPermissionResolver = resolver,
    podScopeValidator = PodScopeValidator(),
  )

  init {
    every { podFacade.getPodId(pod.name) } returns podId
    every { podFacade.getPublicContexts(podName = pod.name) } returns setOf(publicContext)
  }

  private fun userToken(vararg scopes: String) = PodAccessToken(
    clientId = "did:web:app.test",
    sub = "https://id.test/e/person",
    clientType = null,
    scopeValues = scopes.toSet(),
    jti = "jti-1",
    issuedAt = null,
  )

  private fun serviceToken(vararg scopes: String) = PodAccessToken(
    clientId = "notes-app",
    sub = null,
    clientType = SERVICE_CLIENT_TYPE,
    scopeValues = scopes.toSet(),
    jti = null,
    issuedAt = null,
  )

  private fun grants(vararg contexts: String) = ResolvedContextAccess(
    rawContextScopes = contexts.map { "$it#read" }.toSet(),
    effectiveScopes = contexts.map { "$it#read" }.toSet(),
    contexts = contexts.map(::URI).toSet(),
  )

  private val noGrants = ResolvedContextAccess(emptySet(), emptySet(), emptySet())

  // --- the anonymous caller --------------------------------------------------------------------

  @Test
  fun `an anonymous caller sees exactly the pod's public contexts`() {
    val credentials = authorizer.anonymous(pod)

    assertEquals(setOf(publicContext), credentials.restrictedContexts)
    assertEquals(null, credentials.oauthClientId)
    assertEquals(emptySet(), credentials.oauthScopes)
  }

  // --- public-read is additive, not implicit ----------------------------------------------------

  @Test
  fun `public-read unions the public contexts onto the resolved grants`() {
    every { resolver.resolveFromGrants(podId, any(), any(), podBaseUrl) } returns grants(ctx("tasks"))

    val credentials = authorizer.authorize(pod, userToken("public-read"))

    assertEquals(setOf(grantedContext, publicContext), credentials.restrictedContexts)
    assertEquals(true, "public-read" in credentials.oauthRawScopes)
  }

  @Test
  fun `without public-read the bearer sees only its explicit grants`() {
    every { resolver.resolveFromGrants(podId, any(), any(), podBaseUrl) } returns grants(ctx("tasks"))

    val credentials = authorizer.authorize(pod, userToken())

    assertEquals(setOf(grantedContext), credentials.restrictedContexts)
    verify(exactly = 0) { podFacade.getPublicContexts(podName = any()) }
  }

  @Test
  fun `a token with public-read and no grants at all still sees the public contexts`() {
    every { resolver.resolveFromGrants(podId, any(), any(), podBaseUrl) } returns noGrants

    assertEquals(setOf(publicContext), authorizer.authorize(pod, userToken("public-read")).restrictedContexts)
  }

  // --- which store a token is resolved against --------------------------------------------------

  @Test
  fun `a user token resolves against the grant store, keyed by its subject`() {
    every {
      resolver.resolveFromGrants(podId, "did:web:app.test", "https://id.test/e/person", podBaseUrl)
    } returns grants(ctx("tasks"))

    assertEquals(setOf(grantedContext), authorizer.authorize(pod, userToken()).restrictedContexts)

    verify(exactly = 1) {
      resolver.resolveFromGrants(podId, "did:web:app.test", "https://id.test/e/person", podBaseUrl)
    }
  }

  @Test
  fun `a service-client token resolves against its registration instead`() {
    every { resolver.resolveFromServiceClient(podId, "notes-app", podBaseUrl) } returns grants(ctx("tasks"))

    assertEquals(setOf(grantedContext), authorizer.authorize(pod, serviceToken()).restrictedContexts)

    verify(exactly = 1) { resolver.resolveFromServiceClient(podId, "notes-app", podBaseUrl) }
    verify(exactly = 0) { resolver.resolveFromGrants(any(), any(), any(), any()) }
  }

  // --- the token's own scope claim ---------------------------------------------------------------

  @Test
  fun `context scopes carried in the token are ignored, not trusted`() {
    // The whole point of resolving server-side: a signed `<ctx>#write` in the token buys nothing
    // once the grant store says otherwise.
    every { resolver.resolveFromGrants(podId, any(), any(), podBaseUrl) } returns noGrants

    val credentials = authorizer.authorize(pod, userToken("${ctx("tasks")}#write"))

    assertEquals(emptySet(), credentials.restrictedContexts)
    assertEquals(emptySet(), credentials.oauthScopes)
  }

  @Test
  fun `malformed scopes are dropped rather than carried through`() {
    every { resolver.resolveFromGrants(podId, any(), any(), podBaseUrl) } returns noGrants

    val credentials = authorizer.authorize(pod, userToken("public-read", "not a scope", "https://elsewhere.test/x#read"))

    assertEquals(setOf("public-read"), credentials.oauthScopes)
    assertEquals(setOf("public-read"), credentials.oauthRawScopes)
  }

  @Test
  fun `the manage cascade shows up in oauthScopes but not in oauthRawScopes`() {
    // `oauthRawScopes` is what a surface reports as "what the client was granted", so it must not
    // grow as the pod owner registers descendants; `oauthScopes` is what authorization keys off.
    every { resolver.resolveFromGrants(podId, any(), any(), podBaseUrl) } returns ResolvedContextAccess(
      rawContextScopes = setOf("${ctx("tasks")}#manage"),
      effectiveScopes = setOf("${ctx("tasks")}#manage", "${ctx("tasks/today")}#read"),
      contexts = setOf(URI(ctx("tasks")), URI(ctx("tasks/today"))),
    )

    val credentials = authorizer.authorize(pod, userToken("public-read"))

    assertEquals(
      setOf("${ctx("tasks")}#manage", "${ctx("tasks/today")}#read", "public-read"),
      credentials.oauthScopes,
    )
    assertEquals(setOf("${ctx("tasks")}#manage", "public-read"), credentials.oauthRawScopes)
  }

  // --- a pod that went away mid-request ----------------------------------------------------------

  @Test
  fun `a pod whose row is gone resolves to no contexts rather than throwing`() {
    every { podFacade.getPodId(pod.name) } returns null
    every { podFacade.getPublicContexts(podName = pod.name) } returns emptySet()

    val credentials = authorizer.authorize(pod, userToken("public-read"))

    assertEquals(emptySet(), credentials.restrictedContexts)
    verify(exactly = 0) { resolver.resolveFromGrants(any(), any(), any(), any()) }
  }
}
