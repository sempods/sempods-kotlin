package org.sempods.pods.grants

import com.google.inject.Inject
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.grants.persist.PodWebIdGrantsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.commons.okhttp.TestHttpClient
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the owner-level revocation cascade: a narrowed or removed WebID grant must reach an app
 * that already consented, because the request path resolves permissions from the app level alone.
 *
 * The end-to-end HTTP proof (token issued, resource readable, revoke, resource refused) lives in
 * `org.sempods.api.pod.resources.PodResourceRevocationHttpTest`; this class pins the cascade's
 * own semantics.
 */
class PodGrantsFacadeTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podGrantsFacade: PodGrantsFacade

  @Inject
  private lateinit var podGrantsDao: PodGrantsDao

  @Inject
  private lateinit var podWebIdGrantsDao: PodWebIdGrantsDao

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Inject
  private lateinit var refreshTokenStore: PodRefreshTokenStore

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  private val testClientId = "did:web:localhost%3A5173"
  private val testRedirectUri = "http://localhost:5173/callback"

  /** The identity issuer this deployment is configured with — env-dependent, so read it back. */
  private val idBaseUrl: String by lazy {
    webIdUriDeriver.deriveFromEmail("probe@example.com").substringBeforeLast("/e/")
  }

  @Test
  fun `revoking a manage-root WebID grant sweeps the derived descendant app grants`() {
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()

    val root = contextUri(pod.name, "projects")
    val child = contextUri(pod.name, "projects/alpha")
    // Shares a string prefix but is NOT a slash-delimited descendant — must survive.
    val sibling = contextUri(pod.name, "projects-private")
    listOf("projects", "projects/alpha", "projects-private").forEach { createContext(pod, it) }

    // One single owner-level grant, on the manage root.
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), webId, listOf("$root#manage"), grantedBy = null)
    // Plus an unrelated one on the sibling, to prove isolation.
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), webId, listOf("$sibling#read"), grantedBy = null)

    // The descendant is offered at consent even though no grant names it — expandManageCascade.
    assertEquals(303, consent(pod, webId, listOf("$child#write", "$sibling#read")).statusCode)
    assertEquals(
      setOf("$child#write", "$sibling#read"),
      appGrants(pod, webId),
    )

    val result = podGrantsFacade.revokeWebIdGrants(pod, webId, listOf("$root#manage"))

    // The derived descendant goes even though its text never equalled the revoked grant — this is
    // the case a plain string match would miss.
    assertEquals(setOf("$sibling#read"), appGrants(pod, webId))
    assertEquals(1L, result.deletedAppGrants)
    assertEquals(setOf(testClientId), result.affectedApps)
  }

  @Test
  fun `deleting a manage root sweeps app grants derived for its surviving descendants`() {
    // Context deletion does not cascade into sub-contexts, so `<root>/child` outlives `<root>`.
    // But deleting `<root>` removes the `<root>#manage` grant the person's authority rested on,
    // and the derived app grant names the *descendant* — no string match reaches it. Without the
    // recompute the app would keep writing to a context that still exists.
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()

    val root = contextUri(pod.name, "projects")
    val child = contextUri(pod.name, "projects/alpha")
    listOf("projects", "projects/alpha").forEach { createContext(pod, it) }

    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), webId, listOf("$root#manage"), grantedBy = null)
    assertEquals(303, consent(pod, webId, listOf("$child#write")).statusCode)
    assertEquals(setOf("$child#write"), appGrants(pod, webId))

    podFacade.removeContext(pod.name, URI(root))

    // The descendant context itself is untouched — that is what makes the stale grant dangerous.
    assertTrue(
      podContextsDao.fetchByPod(checkNotNull(pod.id)).any { it.contextUri == child },
      "the descendant context must survive its root's deletion",
    )
    assertEquals(emptySet(), appGrants(pod, webId))
  }

  @Test
  fun `the context sweep repairs a run that died after the owner-level rows were deleted`() {
    // Simulates the partial-failure window: the owner-level grants are already gone but the
    // derived app grants were never swept. The repair pass must find the work from
    // `(pod, contextUri)` alone — there is no record left of whose authority changed.
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()

    val root = contextUri(pod.name, "projects")
    val child = contextUri(pod.name, "projects/alpha")
    listOf("projects", "projects/alpha").forEach { createContext(pod, it) }

    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), webId, listOf("$root#manage"), grantedBy = null)
    assertEquals(303, consent(pod, webId, listOf("$child#write")).statusCode)

    // The state a crashed attempt would leave behind: owner-level authority gone, app grant stale.
    podWebIdGrantsDao.deleteByContext(checkNotNull(pod.id), root)
    assertEquals(setOf("$child#write"), appGrants(pod, webId))

    // Re-running the whole operation must converge.
    val result = podGrantsFacade.revokeContextGrants(pod, root)

    assertEquals(emptySet(), appGrants(pod, webId))
    assertEquals(1L, result.deletedAppGrants)

    // And once more: idempotent, nothing further to do.
    assertEquals(0L, podGrantsFacade.revokeContextGrants(pod, root).deletedAppGrants)
  }

  @Test
  fun `the context sweep leaves grants outside the deleted subtree alone`() {
    // The sweep is restricted to the deleted root's subtree, so an unrelated context's grants are
    // never collateral — important because it runs pod-wide to stay retryable.
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()

    val root = contextUri(pod.name, "projects")
    val child = contextUri(pod.name, "projects/alpha")
    val unrelated = contextUri(pod.name, "notes")
    listOf("projects", "projects/alpha", "notes").forEach { createContext(pod, it) }

    podWebIdGrantsDao.addGrants(
      checkNotNull(pod.id), webId, listOf("$root#manage", "$unrelated#read"), grantedBy = null,
    )
    assertEquals(303, consent(pod, webId, listOf("$child#write", "$unrelated#read")).statusCode)

    podFacade.removeContext(pod.name, URI(root))

    assertEquals(setOf("$unrelated#read"), appGrants(pod, webId))
  }

  @Test
  fun `deleting a manage root leaves the owner's delegations on surviving descendants intact`() {
    // The owner's authority is implicit over every *registered* context, and the descendant stays
    // registered — so the same deletion must not strip their delegation.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val root = contextUri(pod.name, "projects")
    val child = contextUri(pod.name, "projects/alpha")
    listOf("projects", "projects/alpha").forEach { createContext(pod, it) }

    assertEquals(303, consent(pod, ownerWebId, listOf("$root#write", "$child#write")).statusCode)

    podFacade.removeContext(pod.name, URI(root))

    // The grant on the deleted context goes (exact-string sweep), the descendant's stays.
    assertEquals(setOf("$child#write"), appGrants(pod, ownerWebId))
  }

  @Test
  fun `narrowing a WebID grant from write to read removes only the write app grant`() {
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")

    podWebIdGrantsDao.addGrants(
      checkNotNull(pod.id), webId, listOf("$ctx#read", "$ctx#write"), grantedBy = null,
    )
    assertEquals(303, consent(pod, webId, listOf("$ctx#read", "$ctx#write")).statusCode)
    assertEquals(setOf("$ctx#read", "$ctx#write"), appGrants(pod, webId))

    podGrantsFacade.replaceWebIdGrants(pod, webId, listOf("$ctx#read"), grantedBy = null)

    assertEquals(setOf("$ctx#read"), appGrants(pod, webId))
  }

  @Test
  fun `cascade must not delete the public-read scope or kill a live refresh family`() {
    // `public-read` is a genuine OAuth feature scope that also lives in `grants` but never
    // appears in the user-grant set. Sweeping "everything not in the recomputed set" would delete
    // it — and if it were the app's last row, the refresh exchange would read that as a full
    // revocation and force re-consent. A narrowing must not end a session.
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")

    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), webId, listOf("$ctx#read"), grantedBy = null)
    assertEquals(303, consent(pod, webId, listOf("$ctx#read", "public-read")).statusCode)
    assertEquals(setOf("$ctx#read", "public-read"), appGrants(pod, webId))

    val issued = refreshTokenStore.issueNewFamily(
      podId = checkNotNull(pod.id),
      podName = pod.name,
      clientId = testClientId,
      webId = webId,
      scopes = setOf("public-read"),
    )

    podGrantsFacade.revokeWebIdGrants(pod, webId, listOf("$ctx#read"))

    assertEquals(setOf("public-read"), appGrants(pod, webId))
    assertNull(
      refreshTokenStore.findByFamily(issued.token.familyId).single().revokedAt,
      "narrowing one context grant must not revoke the refresh family",
    )
  }

  @Test
  fun `revoking every context grant also revokes the app's refresh family`() {
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")

    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), webId, listOf("$ctx#read"), grantedBy = null)
    assertEquals(303, consent(pod, webId, listOf("$ctx#read")).statusCode)

    val issued = refreshTokenStore.issueNewFamily(
      podId = checkNotNull(pod.id),
      podName = pod.name,
      clientId = testClientId,
      webId = webId,
      scopes = emptySet(),
    )

    val result = podGrantsFacade.revokeAllWebIdGrants(pod, webId)

    assertEquals(emptySet(), appGrants(pod, webId))
    assertTrue(result.revokedRefreshTokens > 0, "full revocation should revoke the family")
    assertNotNull(refreshTokenStore.findByFamily(issued.token.familyId).single().revokedAt)
  }

  @Test
  fun `revoking a WebID grant for the pod owner leaves the owner's app grants intact`() {
    // The owner's authority is implicit (every registered context), not stored in webIdGrants, so
    // the recompute must take the owner branch and sweep nothing.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")

    assertEquals(303, consent(pod, ownerWebId, listOf("$ctx#read", "$ctx#write")).statusCode)
    assertEquals(setOf("$ctx#read", "$ctx#write"), appGrants(pod, ownerWebId))

    // A redundant owner-level row, then revoke everything the owner holds at that level.
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), ownerWebId, listOf("$ctx#read"), grantedBy = null)
    val result = podGrantsFacade.revokeAllWebIdGrants(pod, ownerWebId)

    assertEquals(setOf("$ctx#read", "$ctx#write"), appGrants(pod, ownerWebId))
    assertEquals(0L, result.deletedAppGrants)
  }

  @Test
  fun `cascade reaches app grants consented under a different identity URI`() {
    // The owner writes the grant under an alias URI; the person consents under their primary one,
    // so the app row is keyed by the primary WebID. `subjectUris`, recorded at consent, is what
    // connects the two — `also_known_as` itself lives in sempods-auth and is not resolvable here.
    val pod = sempodsTestFactory.newPod()
    val user = sempodsTestFactory.newOwner()
    val primaryWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(user.email))
    val aliasWebId = "https://acme.example/people/${ObjectId()}"

    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), aliasWebId, listOf("$ctx#read"), grantedBy = null)

    assertEquals(303, consent(pod, primaryWebId, listOf("$ctx#read"), alsoKnownAs = listOf(aliasWebId)).statusCode)
    assertEquals(setOf("$ctx#read"), appGrants(pod, primaryWebId))

    podGrantsFacade.revokeWebIdGrants(pod, aliasWebId, listOf("$ctx#read"))

    assertEquals(emptySet(), appGrants(pod, primaryWebId))
  }

  @Test
  fun `cascade reaches the urn twin of an email-based WebID`() {
    // `{idBaseUrl}/e/<hash>` and `urn:sempods:e:<hash>` are built from one SHA-256, so revoking
    // under either form must find grants stored under the other — no profile lookup involved.
    val pod = sempodsTestFactory.newPod()
    val user = sempodsTestFactory.newOwner()
    val email = checkNotNull(user.email)
    val httpsWebId = webIdUriDeriver.deriveFromEmail(email)
    val urnWebId = WebIdUriDeriver.deriveUrnFromEmail(email)

    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), httpsWebId, listOf("$ctx#read"), grantedBy = null)
    assertEquals(303, consent(pod, httpsWebId, listOf("$ctx#read")).statusCode)

    // Revoke naming the URN twin, not the URI the grant was written under.
    podGrantsFacade.revokeWebIdGrants(pod, urnWebId, listOf("$ctx#read"))

    assertEquals(emptySet(), appGrants(pod, httpsWebId))
    assertEquals(emptySet(), podWebIdGrantsDao.fetchGrantStrings(checkNotNull(pod.id), listOf(httpsWebId)))
  }

  @Test
  fun `cascade reaches the urn twin of an OIDC-based WebID`() {
    // sempods-auth mints `{idBaseUrl}/oidc/<hash>` for logins without an email, with
    // `urn:sempods:oidc:<hash>` as its `also_known_as` twin. A pod owner can only pre-record a
    // grant under the URN form, so revoking by the canonical WebID has to bridge the pair — the
    // same rule as the email namespace, which is easy to implement for `e:` only and forget here.
    val pod = sempodsTestFactory.newPod()
    val hash = WebIdUriDeriver.sha256Hex("https://accounts.example.com:subject-${ObjectId()}")
    val oidcUrn = "${WebIdUriDeriver.URN_OIDC_PREFIX}$hash"
    val oidcWebId = "$idBaseUrl/oidc/$hash"

    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), oidcUrn, listOf("$ctx#read"), grantedBy = null)

    assertEquals(303, consent(pod, oidcWebId, listOf("$ctx#read"), alsoKnownAs = listOf(oidcUrn)).statusCode)
    assertEquals(setOf("$ctx#read"), appGrants(pod, oidcWebId))

    // Revoke naming the canonical WebID, not the URN the grant was written under.
    podGrantsFacade.revokeWebIdGrants(pod, oidcWebId, listOf("$ctx#read"))

    assertEquals(emptySet(), podWebIdGrantsDao.fetchGrantStrings(checkNotNull(pod.id), listOf(oidcUrn)))
    assertEquals(emptySet(), appGrants(pod, oidcWebId))
  }

  @Test
  fun `legacy app grants without subjectUris still cascade via webId`() {
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")

    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), webId, listOf("$ctx#read"), grantedBy = null)
    // Written the way rows looked before `subjectUris` existed.
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = webId,
      grants = listOf("$ctx#read"),
      grantedBy = webId,
    )

    podGrantsFacade.revokeWebIdGrants(pod, webId, listOf("$ctx#read"))

    assertEquals(emptySet(), appGrants(pod, webId))
  }

  @Test
  fun `replaceAppGrants drops a grant whose backing was revoked mid-flight`() {
    // The race the post-write re-check exists for: a caller intersects against the user level,
    // an owner-level revocation completes before the app row is written — so the revocation's own
    // cascade finds nothing to sweep — and the caller then persists its now-stale intersection.
    // This is that state: no owner-level grant, a caller still asking for one.
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")

    val persisted = podGrantsFacade.replaceAppGrants(
      podDbo = pod,
      appId = testClientId,
      webId = webId,
      subjectUris = listOf(webId),
      grants = listOf("$ctx#read"),
      grantedBy = webId,
    )

    assertEquals(emptySet(), persisted, "an unbacked grant must not survive the write")
    assertEquals(emptySet(), appGrants(pod, webId))
  }

  @Test
  fun `replaceAppGrants keeps public-read when the raced context grant is dropped`() {
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")

    val persisted = podGrantsFacade.replaceAppGrants(
      podDbo = pod,
      appId = testClientId,
      webId = webId,
      subjectUris = listOf(webId),
      grants = listOf("$ctx#read", "public-read"),
      grantedBy = webId,
    )

    // `public-read` is an OAuth feature scope, never a sweep candidate — the session survives.
    assertEquals(setOf("public-read"), persisted)
    assertEquals(setOf("public-read"), appGrants(pod, webId))
  }

  @Test
  fun `replaceAppGrants leaves a properly backed delegation untouched`() {
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")
    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), webId, listOf("$ctx#read"), grantedBy = null)

    val persisted = podGrantsFacade.replaceAppGrants(
      podDbo = pod,
      appId = testClientId,
      webId = webId,
      subjectUris = listOf(webId),
      grants = listOf("$ctx#read"),
      grantedBy = webId,
    )

    assertEquals(setOf("$ctx#read"), persisted, "the re-check must be a no-op when nothing raced")
    assertEquals(setOf("$ctx#read"), appGrants(pod, webId))
  }

  @Test
  fun `replaceAppGrants leaves the pod owner's delegation untouched`() {
    // The owner holds no rows in the user-level store at all — the re-check must take the owner
    // branch rather than reading their authority as revoked.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")

    val persisted = podGrantsFacade.replaceAppGrants(
      podDbo = pod,
      appId = testClientId,
      webId = ownerWebId,
      subjectUris = listOf(ownerWebId),
      grants = listOf("$ctx#read", "$ctx#write"),
      grantedBy = ownerWebId,
    )

    assertEquals(setOf("$ctx#read", "$ctx#write"), persisted)
  }

  @Test
  fun `granting additional WebID access does not widen an already consented app`() {
    // Widening is not retroactive: the app level is `requested ∩ user_grants` fixed at consent.
    val pod = sempodsTestFactory.newPod()
    val webId = newPerson()
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")

    podWebIdGrantsDao.addGrants(checkNotNull(pod.id), webId, listOf("$ctx#read"), grantedBy = null)
    assertEquals(303, consent(pod, webId, listOf("$ctx#read")).statusCode)

    podGrantsFacade.addWebIdGrants(pod, webId, listOf("$ctx#write"), grantedBy = null)

    assertEquals(setOf("$ctx#read"), appGrants(pod, webId))
  }

  @Test
  fun `revocation leaves another person's app grants untouched`() {
    val pod = sempodsTestFactory.newPod()
    val webIdA = newPerson()
    val webIdB = newPerson()
    val ctx = contextUri(pod.name, "reports")
    createContext(pod, "reports")

    listOf(webIdA, webIdB).forEach {
      podWebIdGrantsDao.addGrants(checkNotNull(pod.id), it, listOf("$ctx#read"), grantedBy = null)
    }
    assertEquals(303, consent(pod, webIdA, listOf("$ctx#read")).statusCode)
    assertEquals(303, consent(pod, webIdB, listOf("$ctx#read")).statusCode)

    podGrantsFacade.revokeAllWebIdGrants(pod, webIdA)

    assertEquals(emptySet(), appGrants(pod, webIdA))
    assertEquals(setOf("$ctx#read"), appGrants(pod, webIdB))
    assertFalse(podWebIdGrantsDao.fetchGrantStrings(checkNotNull(pod.id), listOf(webIdB)).isEmpty())
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** A fresh non-owner's WebID. */
  private fun newPerson(): String {
    val user = sempodsTestFactory.newOwner()
    return webIdUriDeriver.deriveFromEmail(checkNotNull(user.email))
  }

  private fun contextUri(podName: String, contextPath: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/$contextPath"

  private fun createContext(pod: PodDbo, contextPath: String) {
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = contextUri(pod.name, contextPath),
      label = null,
      description = null,
      createdBy = "test",
    )
  }

  private fun appGrants(pod: PodDbo, webId: String): Set<String> =
    podGrantsDao.fetchGrantStrings(checkNotNull(pod.id), testClientId, listOf(webId))

  /** Drives the real consent submission so the tests exercise the same derivation production does. */
  private fun consent(pod: PodDbo, webId: String, scopes: List<String>, alsoKnownAs: List<String> = emptyList()) =
    signIn(pod.name, webId, alsoKnownAs).let { browser ->
    http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      // The session says who; the CSRF token says the form came from that session.
      .addHeader("Cookie", browser.cookie)
      .setBody(
        "client_id=${URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=cascade" +
          "&csrf=${URLEncoder.encode(browser.csrf, "UTF-8")}" +
          scopes.joinToString("") { "&scope=${URLEncoder.encode(it, "UTF-8")}" }
      )
      .setFollowRedirect(false)
      .execute()
    }
}
