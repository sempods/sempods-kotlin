package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.admin.AdminAuthorizerTestDouble
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.mongo.persist.toPodId
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.flows.PodOwnerAuthority
import org.sempods.pods.oauth.flows.PodOwnerAuthorityCheck
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.SempodsCollections
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.sempods.pods.oauth.serviceclients.persist.PodServiceAuditLogDao
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A service client holding the lasting authority to manage a pod for its owner: granted in the
 * service consent with `authority=manage` or provisioned with the pod, carried as `contexts:manage`
 * in its `client_credentials` tokens, re-checked against the owner on every call, and ended by
 * removing the registration — by the owner or by the service itself. It reads no data.
 */
class ServiceManagementAuthorityHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var services: ServiceAccessFlow

  @Inject
  private lateinit var delegated: DelegatedAccessFlow

  @Inject
  private lateinit var didWeb: DidWebAccessFlow

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  @Inject
  private lateinit var podDao: PodDao

  @Inject
  private lateinit var auditLog: PodServiceAuditLogDao

  @Inject
  private lateinit var serviceClientStore: PodServiceClientStore

  @Inject
  private lateinit var ownerAuthority: PodOwnerAuthority

  @Inject
  private lateinit var db: MongoDatabase

  // ── Granting ────────────────────────────────────────────────────────────────

  @Test
  fun `the owner approves, and the client gets a token carrying the authority`() {
    val owned = ownedPod()
    val service = didWeb.registered(owned.pod, DidWebServiceFixture.newService())

    val page = openManagement(owned, service.did)
    assertEquals(200, page.statusCode, page.responseBody)
    assertTrue("Let a service manage your pod?" in page.responseBody, page.responseBody)
    assertTrue(services.page(page).offered.isEmpty(), "no contexts to tick")
    val confirmed = delegated.submit(services.page(page), owned.cookie, scopes = emptySet())
    assertEquals(200, confirmed.statusCode, confirmed.responseBody)

    val minted = didWeb.token(owned.pod, service.assertion(owned.pod))
    assertEquals(200, minted.statusCode, minted.responseBody)
    assertEquals("contexts:manage", didWeb.json(minted)["scope"])
    assertEquals("contexts:manage", SignedJWT.parse(didWeb.json(minted)["access_token"] as String).jwtClaimsSet.getStringClaim("scope"))
  }

  @Test
  fun `under that token the client lists, creates and deletes contexts`() {
    val owned = ownedPod()
    val existing = owned.context("existing")
    val service = managing(owned)
    val token = didWeb.accessToken(owned.pod, service)

    val created = putContext(owned, "made-by-console", token)
    assertEquals(201, created.statusCode, created.responseBody)
    val listed = catalogue(owned, token)
    assertEquals(setOf(existing, owned.contextIri("made-by-console")), listed.keys)
    assertTrue(listed.values.all { it == listOf("manage") }, "manage alone, as for contexts:manage: $listed")

    val deleted = deleteContext(owned, "existing", token)
    assertEquals(204, deleted.statusCode, deleted.responseBody)
    assertEquals(setOf(owned.contextIri("made-by-console")), catalogue(owned, token).keys)
  }

  @Test
  fun `a token carrying the authority reads no data`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val note = sempodsTestFactory.seedEvent(pod = owned.pod.name, context = URI(notes))
    val token = didWeb.accessToken(owned.pod, managing(owned))

    val read = http.prepareGet(note.toString()).addHeader("Authorization", "Bearer $token").addHeader("Accept", "application/n-quads").execute()
    assertTrue(read.statusCode == 404 || read.statusCode == 403, "status=${read.statusCode} body=${read.responseBody}")
    val written = http.preparePut("${sempodsTestFactory.eventUri(owned.pod.name)}?context=${enc(notes)}")
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody("<urn:x> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <$notes> .")
      .execute()
    assertEquals(403, written.statusCode, written.responseBody)
  }

  @Test
  fun `the calls land in the service-client audit log`() {
    val owned = ownedPod()
    val service = managing(owned)
    val token = didWeb.accessToken(owned.pod, service)

    assertEquals(201, putContext(owned, "audited", token).statusCode)

    val entries = auditLog.findRecent(checkNotNull(owned.pod.id)).filter { it.clientId == service.did }
    assertTrue(entries.isNotEmpty(), "the context creation is audited under '${service.did}'")
  }

  @Test
  fun `the operator sets the authority up when it creates the pod`() {
    val owner = sempodsTestFactory.newOwner()
    val ownerWebId = webIdUriDeriver.deriveFromEmail(owner.email)
    val podName = "mgmt-${java.util.UUID.randomUUID().toString().take(8)}"
    val service = DidWebServiceFixture.newService()

    val created = http.preparePut("${SempodsModule.config.apiBaseUrl}_system/admin/pods/$podName")
      .addHeader("Authorization", "Bearer ${AdminAuthorizerTestDouble.TEST_ADMIN_SECRET}")
      .addHeader("Content-Type", "application/json")
      .setBody("""{"ownerWebId":"$ownerWebId","managementClient":"${service.did}","managementClientName":"Owner Console"}""")
      .execute()
    assertEquals(201, created.statusCode, created.responseBody)

    val pod = checkNotNull(podDao.fetchByName(podName))
    val owned = Owned(pod, ownerWebId)
    val token = didWeb.accessToken(pod, service)
    assertEquals(201, putContext(owned, "first", token).statusCode, "active at once, no consent needed")
    val page = openManagement(owned, service.did)
    assertTrue("This service manages your pod" in page.responseBody, "the owner sees it holds the authority")
  }

  @Test
  fun `a management client that is not a did-web, or carries a port, is refused, and nothing is created`() {
    val owner = sempodsTestFactory.newOwner()

    for (client in listOf("svc:abc", DidWebServiceFixture.newService(port = 8443).did)) {
      val podName = "mgmt-${java.util.UUID.randomUUID().toString().take(8)}"
      val refused = createPod(podName, webIdUriDeriver.deriveFromEmail(owner.email), client)

      assertEquals(400, refused.statusCode, "$client: ${refused.responseBody}")
      assertNull(podDao.fetchByName(podName), client)
    }
  }

  @Test
  fun `the management client is written before the pod, and a retry leaves a removed one removed`() {
    val owner = sempodsTestFactory.newOwner()
    val ownerWebId = webIdUriDeriver.deriveFromEmail(owner.email)
    val podName = "mgmt-${java.util.UUID.randomUUID().toString().take(8)}"
    val service = DidWebServiceFixture.newService()

    assertEquals(201, createPod(podName, ownerWebId, service.did).statusCode)

    val pod = checkNotNull(podDao.fetchByName(podName))
    val podId = checkNotNull(pod.id).toPodId()
    val registration = checkNotNull(serviceClientStore.find(podId, service.did))
    assertEquals(1L shl 32, registration.grantsVersion, "the pod's first service-client epoch")
    val row = checkNotNull(db.getCollection(SempodsCollections.PODS).find(Filters.eq("_id", pod.id)).first())
    assertEquals(1L, (row["serviceClientEpoch"] as Number).toLong(), "the row is written with the epoch its client took")

    assertTrue(serviceClientStore.remove(podId, service.did, registration.id))
    assertEquals(200, createPod(podName, ownerWebId, service.did).statusCode)
    assertNull(serviceClientStore.find(podId, service.did), "an existing pod is left as it is")
  }

  @Test
  fun `a pod name refused leaves no management client behind`() {
    val owner = sempodsTestFactory.newOwner()
    val service = DidWebServiceFixture.newService()

    val refused = createPod("x", webIdUriDeriver.deriveFromEmail(owner.email), service.did)

    assertEquals(400, refused.statusCode, refused.responseBody)
    assertEquals(0L, db.getCollection(SempodsCollections.OAUTH_SERVICE_CLIENTS).countDocuments(Filters.eq("clientId", service.did)))
  }

  @Test
  fun `an authority the page does not know is a 400 page`() {
    val owned = ownedPod()
    val service = didWeb.registered(owned.pod, DidWebServiceFixture.newService())

    val page = http.prepareGet(services.consentUrl(owned.pod))
      .addQueryParam("client_id", service.did)
      .addQueryParam("authority", "everything")
      .addHeader("Cookie", owned.cookie)
      .execute()

    assertEquals(400, page.statusCode, page.responseBody)
  }

  @Test
  fun `an operator-provisioned client is not offered the authority, so the page cannot remove it`() {
    val owned = ownedPod()
    serviceClientStore.register(owned.pod.hosted(), "backend", scopes = emptySet(), label = "backend")

    val page = openManagement(owned, "backend")

    assertEquals(403, page.statusCode, page.responseBody)
    assertFalse("removeBtn" in page.responseBody || "confirmBtn" in page.responseBody, "no form for it")
    assertNotNull(serviceClientStore.find(checkNotNull(owned.pod.id).toPodId(), "backend"), "the operator's registration stays")
  }

  @Test
  fun `a management consent opened without a session resumes as one after the sign-in`() {
    val owned = ownedPod()
    val service = didWeb.registered(owned.pod, DidWebServiceFixture.newService())

    val parked = http.prepareGet(services.consentUrl(owned.pod))
      .addQueryParam("client_id", service.did).addQueryParam("authority", "manage")
      .setFollowRedirect(false).execute()
    assertEquals(307, parked.statusCode, parked.responseBody)

    val resumed = http.prepareGet(services.consentUrl(owned.pod))
      .addQueryParam("client_id", service.did).addQueryParam("authority", "manage")
      .executeSignedInAs(owned.webId)
    assertEquals(200, resumed.statusCode, resumed.responseBody)
    assertTrue("Let a service manage your pod?" in resumed.responseBody, "the sign-in resumes the management variant")
  }

  // ── Ending it ───────────────────────────────────────────────────────────────

  @Test
  fun `the owner removes the service on the page, and its token stops working`() {
    val owned = ownedPod()
    val service = managing(owned)
    val token = didWeb.accessToken(owned.pod, service)

    val page = openManagement(owned, service.did)
    val removed = delegated.submit(services.page(page), owned.cookie, scopes = emptySet(), action = "remove")
    assertEquals(200, removed.statusCode, removed.responseBody)
    assertTrue("Service removed" in removed.responseBody, removed.responseBody)

    assertEquals(401, putContext(owned, "after", token).statusCode, "the token held is withdrawn on its next call")
    assertEquals("invalid_client", didWeb.json(didWeb.token(owned.pod, service.assertion(owned.pod)))["error"])
  }

  @Test
  fun `the client gives it up itself`() {
    val owned = ownedPod()
    val service = managing(owned)
    val token = didWeb.accessToken(owned.pod, service)

    val given = deregister(owned, service.did, token)
    assertEquals(204, given.statusCode, given.responseBody)

    assertEquals("invalid_client", didWeb.json(didWeb.token(owned.pod, service.assertion(owned.pod)))["error"])
    assertEquals(400, openManagement(owned, service.did).statusCode, "the pod holds no such service any more")
  }

  @Test
  fun `a service removes only its own registration`() {
    val owned = ownedPod()
    val service = managing(owned)
    val other = managing(owned)

    val foreign = deregister(owned, other.did, didWeb.accessToken(owned.pod, service))
    assertEquals(403, foreign.statusCode, foreign.responseBody)
    assertEquals(401, deregister(owned, other.did, bearer = null).statusCode)
    assertEquals(200, didWeb.token(owned.pod, other.assertion(owned.pod)).statusCode, "the other stays")
  }

  @Test
  fun `a token of a registration removed and made again holds nothing of the new one`() {
    val owned = ownedPod()
    val service = managing(owned)
    val earlier = didWeb.accessToken(owned.pod, service)
    val podId = checkNotNull(owned.pod.id).toPodId()
    assertTrue(serviceClientStore.remove(podId, service.did, checkNotNull(serviceClientStore.find(podId, service.did)).id))
    managing(owned, service)

    assertEquals(401, putContext(owned, "with-the-earlier", earlier).statusCode, "the earlier token manages nothing")
    assertEquals(404, deregister(owned, service.did, earlier).statusCode, "nor removes the new registration")
    assertEquals(201, putContext(owned, "with-a-new-one", didWeb.accessToken(owned.pod, service)).statusCode)
  }

  @Test
  fun `a service token's type is what the token says, not whether its subject is the client`() {
    val owned = ownedPod()
    val service = managing(owned)
    val registration = checkNotNull(serviceClientStore.find(checkNotNull(owned.pod.id).toPodId(), service.did))
    val withoutSubject = SempodsCredentials(
      pod = owned.pod.hosted().ref,
      restrictedContexts = emptySet(),
      oauthClientId = service.did,
      oauthScopes = setOf(CONTEXTS_MANAGE_SCOPE),
      serviceClient = true,
      serviceRegistrationId = registration.id.value,
    )

    assertTrue(ownerAuthority.check(owned.pod.hosted(), withoutSubject, CONTEXTS_MANAGE_SCOPE) is PodOwnerAuthorityCheck.Standing)
  }

  @Test
  fun `a management screen is stored under a field a node from before it does not read`() {
    val owned = ownedPod()
    val service = didWeb.registered(owned.pod, DidWebServiceFixture.newService())
    val registration = checkNotNull(serviceClientStore.find(checkNotNull(owned.pod.id).toPodId(), service.did))

    assertEquals(200, openManagement(owned, service.did).statusCode)

    val stored = db.getCollection(SempodsCollections.OAUTH_CONSENT_TRANSACTIONS).find(Filters.eq("clientId", service.did)).toList()
    assertEquals(listOf(registration.id.value), stored.map { it.getString("serviceManagementRegistrationId") })
    assertTrue(stored.none { it.containsKey("serviceRegistrationId") }, "a context consent's field would make an older node replace the grants")
  }

  @Test
  fun `the owner's list shows the authority only while the owner who granted it owns the pod`() {
    val owned = ownedPod()
    val service = managing(owned)
    fun listed(webId: String) = serviceClients(owned, mintServiceClientsManagerToken(owned.pod.name, webId)).single { it["client_id"] == service.did }
    assertNotNull(listed(owned.webId)["management_granted_at"])

    val newOwner = webIdUriDeriver.deriveFromEmail(sempodsTestFactory.newOwner().email)
    podDao.setOwner(checkNotNull(owned.pod.id), newOwner)

    assertNull(listed(newOwner)["management_granted_at"], "an authority that ended is not reported as held")
  }

  @Test
  fun `a change of owner refuses the token even where the service also holds context grants`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val service = managing(owned)
    services.confirm(owned.pod, service.did, owned.cookie, setOf("$notes#read"))

    podDao.setOwner(checkNotNull(owned.pod.id), webIdUriDeriver.deriveFromEmail(sempodsTestFactory.newOwner().email))

    val minted = didWeb.token(owned.pod, service.assertion(owned.pod))
    assertEquals("invalid_scope", didWeb.json(minted)["error"], minted.responseBody)
  }

  @Test
  fun `the new owner is asked afresh, and approving restores the authority`() {
    val owned = ownedPod()
    val service = managing(owned)
    val newOwner = webIdUriDeriver.deriveFromEmail(sempodsTestFactory.newOwner().email)
    podDao.setOwner(checkNotNull(owned.pod.id), newOwner)
    val now = Owned(owned.pod, newOwner)

    val page = openManagement(now, service.did)

    assertTrue("Let a service manage your pod?" in page.responseBody, "an authority a former owner granted is not shown as held")
    assertFalse("removeBtn" in page.responseBody)
    val confirmed = delegated.submit(services.page(page), now.cookie, scopes = emptySet())
    assertEquals(200, confirmed.statusCode, confirmed.responseBody)
    assertEquals(201, putContext(now, "restored", didWeb.accessToken(owned.pod, service)).statusCode)
  }

  @Test
  fun `a change of owner ends the authority`() {
    val owned = ownedPod()
    owned.context("kept")
    val service = managing(owned)
    val token = didWeb.accessToken(owned.pod, service)

    podDao.setOwner(checkNotNull(owned.pod.id), webIdUriDeriver.deriveFromEmail(sempodsTestFactory.newOwner().email))

    assertEquals(403, putContext(owned, "after", token).statusCode, "a token held is re-checked on the call")
    assertEquals(emptyMap(), catalogue(owned, token), "and the catalogue shows it nothing")
    val minted = didWeb.token(owned.pod, service.assertion(owned.pod))
    assertEquals("invalid_scope", didWeb.json(minted)["error"], "a new token carries nothing: ${minted.responseBody}")
  }

  @Test
  fun `the pod advertises the authority in its authorization-server metadata`() {
    val owned = ownedPod()

    val metadata = http.prepareGet("${SempodsModule.config.apiBaseUrl}${owned.pod.name}/.well-known/oauth-authorization-server").execute()

    assertEquals(listOf("contexts:manage"), didWeb.json(metadata)["sempods_service_management_scopes"])
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  /** A did:web service registered on [owned]'s pod, holding the authority its owner approved. */
  private fun managing(owned: Owned, service: DidWebService = DidWebServiceFixture.newService()): DidWebService {
    didWeb.registered(owned.pod, service)
    val confirmed = delegated.submit(services.page(openManagement(owned, service.did)), owned.cookie, scopes = emptySet())
    assertEquals(200, confirmed.statusCode, confirmed.responseBody)
    return service
  }

  private fun openManagement(owned: Owned, clientId: String): TestHttpResponse =
    http.prepareGet(services.consentUrl(owned.pod))
      .addQueryParam("client_id", clientId)
      .addQueryParam("state", "manage-1")
      .addQueryParam("authority", "manage")
      .addHeader("Cookie", owned.cookie)
      .setFollowRedirect(false)
      .execute()

  private fun putContext(owned: Owned, path: String, bearer: String): TestHttpResponse =
    http.preparePut("${podBase(owned)}/_system/contexts/$path")
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $bearer")
      .setBody("""{"label":"$path"}""")
      .execute()

  private fun deleteContext(owned: Owned, path: String, bearer: String): TestHttpResponse =
    http.prepareDelete("${podBase(owned)}/_system/contexts/$path").addHeader("Authorization", "Bearer $bearer").execute()

  @Suppress("UNCHECKED_CAST")
  private fun serviceClients(owned: Owned, bearer: String): List<Map<String, Any?>> {
    val response = http.prepareGet("${podBase(owned)}/_system/auth/service-clients").addHeader("Authorization", "Bearer $bearer").execute()
    assertEquals(200, response.statusCode, response.responseBody)
    return JsonMappers.default().readValue(response.responseBody, Map::class.java)["serviceClients"] as List<Map<String, Any?>>
  }

  private fun deregister(owned: Owned, clientId: String, bearer: String?): TestHttpResponse =
    http.prepareDelete("${podBase(owned)}/_system/auth/register/${enc(clientId)}")
      .apply { bearer?.let { addHeader("Authorization", "Bearer $it") } }
      .execute()

  /** Each context the catalogue shows [bearer], with the permissions it reports. */
  @Suppress("UNCHECKED_CAST")
  private fun catalogue(owned: Owned, bearer: String): Map<String, List<String>> {
    val response = http.prepareGet("${podBase(owned)}/_system/contexts")
      .addHeader("Accept", "application/json")
      .addHeader("Authorization", "Bearer $bearer")
      .execute()
    assertEquals(200, response.statusCode, response.responseBody)
    val contexts = JsonMappers.default().readValue(response.responseBody, Map::class.java)["contexts"] as List<Map<String, Any?>>
    return contexts.associate { it["context_iri"] as String to (it["permissions"] as List<String>) }
  }

  private fun PodDbo.hosted() = toHostedPod(sempodsUriBuilder)

  private fun createPod(podName: String, ownerWebId: String, managementClient: String): TestHttpResponse =
    http.preparePut("${SempodsModule.config.apiBaseUrl}_system/admin/pods/$podName")
      .addHeader("Authorization", "Bearer ${AdminAuthorizerTestDouble.TEST_ADMIN_SECRET}")
      .addHeader("Content-Type", "application/json")
      .setBody("""{"ownerWebId":"$ownerWebId","managementClient":"$managementClient"}""")
      .execute()

  private fun podBase(owned: Owned) = "${SempodsModule.config.apiBaseUrl}${owned.pod.name}"

  private inner class Owned(val pod: PodDbo, val webId: String) {
    val cookie: String get() = signIn(pod.name, webId).cookie

    fun contextIri(path: String): String = sempodsUriBuilder.buildContext(pod.name, path).toString()

    /** Registers a private context at [path] and answers its IRI. */
    fun context(path: String): String {
      val uri = sempodsUriBuilder.buildContext(pod.name, path)
      podFacade.createContext(podName = pod.name, contextUri = uri, public = false, label = path, description = null)
      return uri.toString()
    }
  }

  private fun ownedPod(): Owned {
    val owner = sempodsTestFactory.newOwner()
    return Owned(sempodsTestFactory.newPod(ownerUser = owner, createPublicContext = false), webIdUriDeriver.deriveFromEmail(owner.email))
  }
}
