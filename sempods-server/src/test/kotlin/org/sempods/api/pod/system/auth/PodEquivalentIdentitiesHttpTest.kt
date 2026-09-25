package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.sempods.FakeIdServerTransport
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.auth.core.EquivalentIdentities
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.tests.TestUtil
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.grants.persist.PodWebIdGrantsDao
import org.sempods.pods.mongo.persist.PodDbo
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The equivalent-identity claim at the pod's login callback, through the real sign-in: the
 * id-server's token is signed and validated, and what it asserts decides grants and ownership at
 * the consent that follows (`SPS-OIDC-016`, `SPS-OIDC-017`, `SPS-OIDC-018`).
 */
class PodEquivalentIdentitiesHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Inject
  private lateinit var podGrantsDao: PodGrantsDao

  @Inject
  private lateinit var podWebIdGrantsDao: PodWebIdGrantsDao

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  private val clientId = "did:web:localhost%3A5173"
  private val redirectUri = "http://localhost:5173/callback"

  @Test
  fun `an equivalent identity the id-server asserts makes its person the owner`() {
    val (pod, ownerWebId) = ownedPod()
    val ctx = context(pod, "reports")
    val signedInAs = oidcWebId()

    val submitted = consent(pod, signedInAs, listOf("$ctx#read"), claims = claim(listOf(ownerWebId)))

    assertEquals(303, submitted.statusCode)
    assertEquals(setOf("$ctx#read"), appGrants(pod, signedInAs), "the owner may delegate any context")
    val recorded = podGrantsDao.fetchGrantsForSubject(checkNotNull(pod.id), listOf(signedInAs)).single().subjectUris.orEmpty()
    assertTrue(ownerWebId in recorded, "the consent records the URIs it recognised: $recorded")
  }

  @Test
  fun `order, duplicates and sub itself change nothing`() {
    val (pod, ownerWebId) = ownedPod()
    val ctx = context(pod, "reports")
    val signedInAs = oidcWebId()

    val submitted = consent(pod, signedInAs, listOf("$ctx#read"), claims = claim(listOf(signedInAs, ownerWebId, ownerWebId)))

    assertEquals(303, submitted.statusCode)
    assertEquals(setOf("$ctx#read"), appGrants(pod, signedInAs))
  }

  @Test
  fun `a grant under the URN twin of an equivalent identity resolves`() {
    // The claim carries WebIDs only; the pod derives each one's URN twin, so an invitation recorded
    // as `urn:sempods:e:<hash>` before the person first signed in still reaches them.
    val pod = sempodsTestFactory.newPod()
    val ctx = context(pod, "reports")
    val email = "${TestUtil.randomId()}@example.org"
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), WebIdUriDeriver.deriveUrnFromEmail(email), listOf("$ctx#read"), grantedBy = null)
    val signedInAs = oidcWebId()

    val submitted = consent(pod, signedInAs, listOf("$ctx#read"), claims = claim(listOf(webIdUriDeriver.deriveFromEmail(email))))

    assertEquals(303, submitted.statusCode)
    assertEquals(setOf("$ctx#read"), appGrants(pod, signedInAs))
  }

  @Test
  fun `a grant under the URN twin of the WebID resolves with no claim at all`() {
    // Grant-before-login, the case the id-server used to carry the URN in its token for.
    val pod = sempodsTestFactory.newPod()
    val ctx = context(pod, "reports")
    val email = "${TestUtil.randomId()}@example.org"
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), WebIdUriDeriver.deriveUrnFromEmail(email), listOf("$ctx#read"), grantedBy = null)
    val signedInAs = webIdUriDeriver.deriveFromEmail(email)

    val submitted = consent(pod, signedInAs, listOf("$ctx#read"))

    assertEquals(303, submitted.statusCode)
    assertEquals(setOf("$ctx#read"), appGrants(pod, signedInAs))
  }

  @Test
  fun `the registered also_known_as claim makes no one the owner`() {
    val (pod, ownerWebId) = ownedPod()
    val ctx = context(pod, "reports")
    val signedInAs = oidcWebId()

    consent(pod, signedInAs, listOf("$ctx#read"), claims = mapOf("also_known_as" to listOf(ownerWebId)))

    assertEquals(emptySet(), appGrants(pod, signedInAs), "a human pseudonym is no identity")
  }

  @Test
  fun `a malformed claim refuses the sign-in and leaves no session`() {
    val pod = sempodsTestFactory.newPod()
    val signedInAs = oidcWebId()
    val valid = "${FakeIdServerTransport.ISSUER}/e/${TestUtil.randomId()}"

    for (value in listOf(null, valid, 42L, mapOf("id" to valid), listOf(valid, "urn:example:alice"), listOf("/alice"), listOf(""))) {
      val response = authorize(pod).executeSignedInAs(signedInAs, claims = claim(value))

      // The identity service answered and its answer was unusable: `server_error`, as for any token
      // that fails validation, and the client gets it at its redirect address.
      assertEquals(303, response.statusCode, "value $value")
      val location = checkNotNull(response.getHeader("Location"))
      assertTrue(location.startsWith(redirectUri) && "error=server_error" in location, "value $value: $location")
      assertNull(response.sessionCookie(), "value $value: a refused sign-in establishes no session")
    }
  }

  @Test
  fun `a sign-in without the claim revokes nothing an earlier one established`() {
    // `SPS-OIDC-017`: omission asserts nothing about earlier equivalences. The person consented while
    // the id-server named their alias; the next sign-in does not name it, and what the consent
    // wrote stands.
    val pod = sempodsTestFactory.newPod()
    val ctx = context(pod, "reports")
    val alias = "https://acme.example/people/${ObjectId()}"
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), alias, listOf("$ctx#read"), grantedBy = null)
    val signedInAs = oidcWebId()
    assertEquals(303, consent(pod, signedInAs, listOf("$ctx#read"), claims = claim(listOf(alias))).statusCode)
    assertEquals(setOf("$ctx#read"), appGrants(pod, signedInAs))

    val again = authorize(pod).executeSignedInAs(signedInAs)

    assertTrue(again.statusCode in setOf(200, 303), "the sign-in itself succeeds: ${again.statusCode}")
    assertEquals(setOf("$ctx#read"), appGrants(pod, signedInAs), "the app keeps what the alias backed")
    assertEquals(setOf("$ctx#read"), podWebIdGrantsDao.fetchGrantStrings(checkNotNull(pod.id), listOf(alias)))
  }

  @Test
  fun `an alias a later sign-in names first is recorded, so omitting it afterwards revokes nothing`() {
    // The consent recorded alias A. The owner-level grant moves to alias B, which the next sign-in
    // names beside A: the grant is still backed, so nothing is narrowed, and B has to be recorded
    // anyway. A sign-in after that names neither, and B still backs the grant (`SPS-OIDC-017`).
    val pod = sempodsTestFactory.newPod()
    val ctx = context(pod, "reports")
    val first = "https://acme.example/people/${ObjectId()}"
    val second = "https://acme.example/people/${ObjectId()}"
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), first, listOf("$ctx#read"), grantedBy = null)
    val signedInAs = oidcWebId()
    assertEquals(303, consent(pod, signedInAs, listOf("$ctx#read"), claims = claim(listOf(first))).statusCode)
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), second, listOf("$ctx#read"), grantedBy = null)
    podWebIdGrantsDao.deleteGrants(checkNotNull(pod.id), first, listOf("$ctx#read"))

    authorize(pod).executeSignedInAs(signedInAs, claims = claim(listOf(first, second)))
    assertEquals(setOf("$ctx#read"), appGrants(pod, signedInAs), "the grant is backed by the alias named now")

    authorize(pod).executeSignedInAs(signedInAs)
    assertEquals(setOf("$ctx#read"), appGrants(pod, signedInAs), "the recorded alias still backs it")
  }

  @Test
  fun `a grant its alias no longer backs is still repaired away`() {
    // The other side of the rule above: what the consent recorded keeps the equivalence alive, and
    // the grant behind it still has to exist. An owner-level revocation whose cascade never landed
    // is still caught at the next sign-in.
    val pod = sempodsTestFactory.newPod()
    val ctx = context(pod, "reports")
    val alias = "https://acme.example/people/${ObjectId()}"
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), alias, listOf("$ctx#read"), grantedBy = null)
    val signedInAs = oidcWebId()
    assertEquals(303, consent(pod, signedInAs, listOf("$ctx#read"), claims = claim(listOf(alias))).statusCode)
    podWebIdGrantsDao.deleteGrants(checkNotNull(pod.id), alias, listOf("$ctx#read"))

    authorize(pod).executeSignedInAs(signedInAs, claims = claim(listOf(alias)))

    assertEquals(emptySet(), appGrants(pod, signedInAs))
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private fun claim(value: Any?): Map<String, Any?> = mapOf(EquivalentIdentities.CLAIM to value)

  private fun oidcWebId(): String =
    "${FakeIdServerTransport.ISSUER}/oidc/${WebIdUriDeriver.sha256Hex(TestUtil.randomId())}"

  private fun ownedPod(): Pair<PodDbo, String> {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    return pod to webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
  }

  private fun context(pod: PodDbo, path: String): String {
    val contextUri = "${SempodsModule.config.apiBaseUrl}${pod.name}/$path"
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = contextUri,
      label = null,
      description = null,
      createdBy = "test",
    )
    return contextUri
  }

  private fun authorize(pod: PodDbo) =
    http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize")
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", clientId)
      .addQueryParam("redirect_uri", redirectUri)
      .addQueryParam("state", "equivalent-identities")

  /** Signs in through the real callback, then submits the consent screen that sign-in rendered. */
  private fun consent(
    pod: PodDbo,
    signAs: String,
    scopes: List<String>,
    claims: Map<String, Any?> = emptyMap(),
  ): TestHttpResponse {
    val page = authorize(pod).addQueryParam("prompt", "consent").executeSignedInAs(signAs, claims = claims)
    assertEquals(200, page.statusCode, "the sign-in renders the consent screen: ${page.getHeader("Location")}")
    val csrf = checkNotNull(Regex("""name="csrf" value="([^"]+)"""").find(page.responseBody)?.groupValues?.get(1)) {
      "the consent form carries no CSRF token"
    }
    return http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", checkNotNull(page.sessionCookie()))
      .setBody(
        "client_id=${enc(clientId)}&redirect_uri=${enc(redirectUri)}&state=equivalent-identities" +
          "&csrf=${enc(csrf)}" + scopes.joinToString("") { "&scope=${enc(it)}" },
      )
      .setFollowRedirect(false)
      .execute()
  }

  private fun appGrants(pod: PodDbo, webId: String): Set<String> =
    podGrantsDao.fetchGrantStrings(checkNotNull(pod.id), clientId, listOf(webId))
}
