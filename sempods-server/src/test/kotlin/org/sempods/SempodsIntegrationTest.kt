package org.sempods

import com.google.inject.Guice
import com.google.inject.Inject
import com.google.inject.Injector
import com.google.inject.util.Modules
import org.sempods.admin.AdminAuthorizerTestDouble
import org.sempods.auth.ConsentTransactionStore
import org.sempods.api.pod.system.auth.PodTokenIssuer
import org.sempods.api.system.admin.pods.AdminPodsEndpoint
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.commons.net.UrlUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpRequest
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.okhttp.getAll
import org.eclipse.jetty.server.Server
import org.junit.jupiter.api.BeforeAll
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

open class SempodsIntegrationTest : SempodsTest(injector = sempodsInjector) {

  @Inject
  protected lateinit var sempodsUriBuilder: SempodsUriBuilder

  /**
   * Pod coordinates for the suite's own reads — `podAccess.clientFor(pod)` is a client bound to
   * that pod and its seeding credential. Seeding an event goes through
   * [SempodsTestFactory.seedEvent], which is this same client with the model already built.
   */
  @Inject
  protected lateinit var podAccess: SempodsTestPodAccess

  /**
   * The store itself, for the tests whose subject *is* what the store does — see `docs/testing.md`
   * §"Seeding a pod" for where the line runs. Also the way to plant a raw resource model, which
   * the client surface does not carry.
   */
  @Inject
  protected lateinit var podFacade: PodFacade

  @Inject
  private lateinit var podTokenIssuer: PodTokenIssuer

  @Inject
  private lateinit var podDao: PodDao

  @Inject
  private lateinit var podGrantsDao: PodGrantsDao

  @Inject
  private lateinit var http: TestHttpClient

  // test factories

  @Inject
  protected lateinit var sempodsTestFactory: SempodsTestFactory

  @Inject
  private lateinit var fakeIdServer: FakeIdServerTransport

  /**
   * A pod access token for the pod's owner, carrying no grants at all.
   *
   * Ownership is not a grant: it follows from `podDbo.owner`, and the server recognises it from
   * the token's `sub`. An owner therefore needs nothing granted to manage contexts — which is also
   * what lets a pod with no contexts yet get its first one.
   */
  protected fun mintOwnerPodToken(podName: String, ownerWebId: String): String =
    mintScopedToken(podName, scopes = emptyList(), webId = ownerWebId)

  /**
   * Runs this `/authorize` request as someone who is signed in — through the sign-in, not around
   * it.
   *
   * There is no way to hand `/authorize` an identity any more, and that is the point: the request
   * is parked, the browser goes to the id-server, and only the callback carries an identity,
   * established over a back channel. So this drives exactly that — first request, fake id-server
   * armed with the nonce *that* flow minted, then the callback — and answers with what
   * `/authorize` says afterwards. It replaces appending `&token=` to the URL.
   *
   * A request that does not end in a login redirect (an error, or something already answerable)
   * is handed back untouched, so a test can use this without first knowing which it will be.
   *
   * @param signAs `null` means the id-server refuses. Use it where a test needs the login itself
   *   to fail rather than the request that started it.
   */
  protected fun TestHttpRequest.executeSignedInAs(
    signAs: String?,
    alsoKnownAs: List<String> = emptyList(),
    nonce: String? = null,
  ): TestHttpResponse {
    val started = setFollowRedirect(false).execute()
    val authorizationRequest = started.getHeader("Location")?.takeIf { it.startsWith(FakeIdServerTransport.ISSUER) }
      ?: return started
    // The login redirect sets the browser pin; the same browser has to present it back, so the
    // helper carries it the way a browser would.
    val pin = checkNotNull(started.headers.getAll("Set-Cookie").firstOrNull { it.startsWith("sempods_pod_login_") }) {
      "the login redirect must set a browser pin: ${started.headers.getAll("Set-Cookie")}"
    }.substringBefore(';')
    val query = UrlUtil.queryParams(URI(authorizationRequest).rawQuery, decodeParams = true)
    // The callback address comes out of the request the server just built, so this helper needs to
    // know nothing about where the endpoint lives.
    val callback = checkNotNull(query["redirect_uri"]) { "authorization request carries no redirect_uri" }
    fakeIdServer.expect(
      webId = signAs,
      nonce = nonce ?: checkNotNull(query["nonce"]) { "authorization request carries no nonce" },
      alsoKnownAs = alsoKnownAs,
    )
    return http.prepareGet("$callback?state=${enc(checkNotNull(query["state"]))}&code=a-test-authorization-code")
      .addHeader("Cookie", pin)
      .setFollowRedirect(false).execute()
  }

  /** The session cookie a completed sign-in established, as `name=value` for a `Cookie` header. */
  protected fun TestHttpResponse.sessionCookie(): String? =
    headers.getAll("Set-Cookie").firstOrNull { it.startsWith("sempods_pod_session=") }?.substringBefore(';')

  /** Arms the in-process id-server for a callback a test drives itself. */
  protected fun fakeIdServerExpect(webId: String?, nonce: String, alsoKnownAs: List<String> = emptyList()) =
    fakeIdServer.expect(webId = webId, nonce = nonce, alsoKnownAs = alsoKnownAs)

  /**
   * Signs [webId] in on [pod] and hands back the session cookie plus its CSRF token — what a
   * browser holds after a completed sign-in.
   *
   * The consent form is protected by the pair, so a test posting it needs both.
   * `PodAuthEndpointHttpTest` also walks the whole flow once and scrapes the token out of the
   * rendered page, which is what keeps this shortcut honest.
   */
  protected fun signIn(pod: String, webId: String, alsoKnownAs: List<String> = emptyList()): SignedInBrowser =
    browsers.getOrPut(pod to webId) {
      SignedInBrowser(
        cookie = "sempods_pod_session=${podTokenIssuer.issueSession(pod, webId, alsoKnownAs)}",
        webId = webId,
        pod = pod,
      )
    }

  /**
   * One session per person per pod, for the length of a test.
   *
   * Remembered rather than minted per call so that `signIn(...).cookie` and `signIn(...).csrf` at
   * two places in the same request describe the *same* browser — which is the whole point of the
   * pair. Minting twice would produce a cookie and a token from different sessions, and the
   * request would be refused for a reason that has nothing to do with what the test is about.
   */
  private val browsers = mutableMapOf<Pair<String, String>, SignedInBrowser>()

  @Inject
  private lateinit var consentTransactionStore: ConsentTransactionStore

  /**
   * @property csrf a **fresh** consent token each time it is read — one screen, one token. A test
   *   that reads it twice is describing two screens, which is what the endpoint will see.
   */
  protected inner class SignedInBrowser(val cookie: String, private val webId: String, private val pod: String) {
    val csrf: String get() = consentTransactionStore.issue(pod, webId)
  }

  private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

  /**
   * Deletes a pod the way anything deletes a pod: `DELETE /_system/admin/pods/{pod}` with the host
   * admin credential.
   *
   * Not merely a convenience over `SempodsFacade.deletePod`. Deletion is two steps — drop the
   * in-memory repository, *then* the rows — and only [AdminPodsEndpoint] performs both, in that
   * order. A test calling the facade alone would leave the cache holding a repository for a pod
   * that no longer exists, and a test performing both itself would be asserting on its own call
   * order rather than on the server's.
   */
  protected fun deletePodViaAdminApi(pod: String) {
    val response = http.prepareDelete("${SempodsModule.config.apiBaseUrl}_system/admin/pods/$pod")
      .addHeader("Authorization", "Bearer ${AdminAuthorizerTestDouble.TEST_ADMIN_SECRET}")
      .execute()
    assertEquals(204, response.statusCode, "pod deletion failed: ${response.responseBody}")
  }

  /**
   * Mints a pod-scoped OAuth access token for the given [podName] and [scopes] AND persists
   * the matching grant rows, since context permissions are now resolved server-side from
   * `PodGrantsDao` (token-slimming). The token itself still carries [scopes] for test
   * convenience; `GrantStorePodAuthorizer` ignores token-carried context scopes and reads the
   * persisted grants — so a minted token only grants what the grant rows authorize.
   */
  protected fun mintScopedToken(podName: String, scopes: List<String>, webId: String = "https://id.test/user"): String {
    val clientId = "did:web:test.example"
    val podId = checkNotNull(podDao.fetchByName(podName)?.id) { "no pod named '$podName'" }
    // Mirrors real consent (replace = authoritative selection) for this (client, webId).
    // Context permissions are resolved per (client, webId), so a test that mints several
    // tokens with DIFFERENT scope sets for the same pod must give each a distinct [webId];
    // otherwise the later mint replaces the earlier grant. Union access = one mint listing all
    // scopes.
    podGrantsDao.replaceGrants(
      podId = podId,
      appId = clientId,
      webId = webId,
      grants = scopes.toSet(),
      subjectUris = listOf(webId),
      grantedBy = webId,
    )
    return podTokenIssuer.issue(
      pod = podName,
      webId = webId,
      clientId = clientId,
      scopes = scopes.toSet(),
    )
  }

  companion object {

    @JvmStatic
    protected val sempodsInjector: Injector by lazy {
      Guice.createInjector(
        Modules.override(SempodsModule())
          .with(SempodsTestModule())
      )
    }

    @BeforeAll
    @JvmStatic
    fun beforeAll() {
      // Ensure that the server is started
      assertNotNull(sempodsInjector.getInstance(Server::class.java))
    }
  }
}
