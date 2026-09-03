package org.sempods

import com.google.inject.Inject
import org.sempods.api.pod.system.auth.DynamicClientRegistrationDao
import org.sempods.auth.core.RefreshTokenStore
import org.sempods.ontologies.Ontologies
import org.sempods.pods.PodRepositoryCache
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.media.persist.MediaAssignment
import org.sempods.pods.media.persist.PodMedia
import org.sempods.pods.media.persist.PodMediaDao
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.mongo.persist.RdfResourceBackupDao
import org.sempods.rdf.Rdf4JUtil
import org.sempods.rdf.toIri
import org.sempods.commons.tests.TestUtil.randomId
import org.bson.types.ObjectId
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

class SempodsFacadeTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var podDao: PodDao

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Inject
  private lateinit var podGrantsDao: PodGrantsDao

  @Inject
  private lateinit var refreshTokenStore: PodRefreshTokenStore

  @Inject
  private lateinit var dynamicClientRegistrationDao: DynamicClientRegistrationDao

  @Inject
  private lateinit var podRepositoryCache: PodRepositoryCache

  @Inject
  private lateinit var backupDao: RdfResourceBackupDao

  @Inject
  private lateinit var podMediaDao: PodMediaDao

  @Test
  fun `pod last modified should work after deleting a resource`() {

    val pod = sempodsTestFactory.newPod()

    val event1DateModified = Instant.now().minusSeconds(60)
    val event1Uri = URI.create("https://events/event1-${randomId()}")
    val event1ContextUri = URI.create("https://contexts/event-1${randomId()}")
    putEventResource(
      podName = pod.name,
      resourceUri = event1Uri,
      contextUri = event1ContextUri,
      name = "Event 1",
      dateModified = event1DateModified,
    )

    putEventResource(
      podName = pod.name,
      resourceUri = URI.create("https://events/event2-${randomId()}"),
      contextUri = URI.create("https://contexts/event-2${randomId()}"),
      name = "Event 2",
      dateModified = event1DateModified.plusSeconds(100),
    )

    val podLastModified = podDao.fetchLastModifiedAt(pod.name)

    Thread.sleep(10)
    podFacade.deleteFromContext(pod.name, event1Uri, event1ContextUri)

    assertNotEquals(
      podLastModified,
      podDao.fetchLastModifiedAt(pod.name),
    )
  }

  @Test
  fun `deletePod should cascade across all pod-scoped persistence and the in-memory repository cache`() {
    val pod1 = sempodsTestFactory.newPod(createPublicContext = false)
    val pod2 = sempodsTestFactory.newPod(createPublicContext = false)
    val pod1Id = checkNotNull(pod1.id)
    val pod2Id = checkNotNull(pod2.id)

    seedAllPodScopedRecords(pod1.name, pod1Id)
    seedAllPodScopedRecords(pod2.name, pod2Id)

    // Force the in-memory RDF cache to populate for both pods.
    assertNotNull(podRepositoryCache.get(pod1.name))
    assertNotNull(podRepositoryCache.get(pod2.name))

    // The backup sink mirrored each seeded resource — both pods have a backup row to clean up.
    assertEquals(1, backupDao.fetchAllByPod(pod1Id).size, "backup row must exist before deletion")

    deletePodViaAdminApi(pod1.name)

    // pod1: every pod-scoped row gone, pod doc gone, cache invalidated.
    assertNull(podDao.fetchByName(pod1.name), "pod doc must be gone")
    assertEquals(0, backupDao.fetchAllByPod(pod1Id).size, "RDF backups must be gone")
    assertEquals(0, podContextsDao.fetchByPod(pod1Id).size, "PodContextDbo rows must be gone")
    assertFalse(podGrantsDao.anyForPod(pod1Id), "PodGrantDbo rows must be gone")
    assertEquals(
      RefreshTokenStore.LookupState.NOT_FOUND,
      refreshTokenStore.lookup(seededToken(pod1.name)).state,
      "refresh tokens must be gone",
    )
    assertNull(
      dynamicClientRegistrationDao.findByClientId(pod1Id, clientIdFor(pod1.name)),
      "DCR rows must be gone",
    )
    // PodRepositoryCache.get() reloads from DB; with the pod removed it returns null.
    assertNull(podRepositoryCache.get(pod1.name), "cache must not resurrect a deleted pod")

    // pod2: untouched.
    assertNotNull(podDao.fetchByName(pod2.name), "neighbour pod doc must survive")
    assertEquals(1, backupDao.fetchAllByPod(pod2Id).size, "neighbour pod backups must survive")
    assertEquals(1, podContextsDao.fetchByPod(pod2Id).size)
    assertTrue(podGrantsDao.anyForPod(pod2Id))
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, refreshTokenStore.lookup(seededToken(pod2.name)).state)
    assertNotNull(dynamicClientRegistrationDao.findByClientId(pod2Id, clientIdFor(pod2.name)))
  }

  private fun seedAllPodScopedRecords(podName: String, podId: ObjectId) {
    // RdfResource: one event in a context inside the pod's namespace.
    val contextUri = URI("https://contexts.example.org/$podName/${randomId()}")
    putEventResource(
      podName = podName,
      resourceUri = URI("https://events.example.org/$podName/${randomId()}"),
      contextUri = contextUri,
      name = "cascade-test-${randomId()}",
    )

    // PodContextDbo: explicit row.
    podContextsDao.create(
      podId = podId,
      contextUri = contextUri.toString(),
      label = null,
      description = null,
      createdBy = "cascade-test",
    )

    // PodGrantDbo: scope grant via the WebID-based path.
    podGrantsDao.addGrants(
      podId = podId,
      appId = "did:web:cascade-test.example",
      webId = "https://id.example.org/cascade-test",
      grants = listOf("$contextUri#read"),
      grantedBy = "cascade-test",
    )

    // A refresh token for the pod, remembered by its plaintext so the cascade can be asserted on it.
    seededTokens[podName] = refreshTokenStore.issueNewFamily(
      podId = podId,
      podName = podName,
      clientId = "dyn:cascade-${randomId()}",
      webId = "https://id.example.org/cascade-test",
      scopes = setOf("$contextUri#read"),
    ).plaintext

    // DynamicClientRegistrationDbo: pod-scoped row, deterministic clientId.
    dynamicClientRegistrationDao.create(
      clientId = clientIdFor(podName),
      registeredForPodId = podId,
      registeredForPodName = podName,
      redirectUris = setOf("http://127.0.0.1:0/callback"),
      clientName = "cascade-test",
      clientUri = null,
      logoUri = null,
      softwareId = null,
      softwareVersion = null,
      contacts = emptyList(),
      tosUri = null,
      policyUri = null,
      rawRequest = emptyMap(),
    )
  }

  /** Plaintexts of the tokens seeded per pod — the only handle a caller of the store ever holds. */
  private val seededTokens = mutableMapOf<String, String>()

  private fun seededToken(podName: String) = checkNotNull(seededTokens[podName])

  private fun clientIdFor(podName: String) = "dyn:cascade-client-$podName"

  @Test
  fun `removeContext should cascade across grants, resources, and the context registry`() {
    // Refresh tokens are not in this cascade: a family carries feature scopes, so it holds no
    // authority over a context to lose, and what a deleted context takes away is the grant rows
    // the request path resolves from. Where that leaves an app with nothing at all, the family
    // does go — `PodGrantsFacadeTest` owns both halves of that rule.
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)

    val targetContext = URI("https://contexts.example.org/${pod.name}/target-${randomId()}")
    val siblingContext = URI("https://contexts.example.org/${pod.name}/sibling-${randomId()}")

    seedContext(pod.name, podId, targetContext)
    seedContext(pod.name, podId, siblingContext)

    podFacade.removeContext(pod.name, targetContext)

    // Target context: gone everywhere.
    assertFalse(podContextsDao.exists(podId, targetContext.toString()), "registry row gone")
    val targetGrants =
      podGrantsDao.fetchGrantStrings(podId, "did:web:cascade-test.example", listOf(webIdFor(pod.name)))
    assertFalse(
      targetGrants.any { it.startsWith("$targetContext#") },
      "grants on target context gone, was: $targetGrants"
    )

    // Sibling context: untouched.
    assertTrue(podContextsDao.exists(podId, siblingContext.toString()))
    val siblingGrants =
      podGrantsDao.fetchGrantStrings(podId, "did:web:cascade-test.example", listOf(webIdFor(pod.name)))
    assertTrue(siblingGrants.any { it == "$siblingContext#read" })
    assertTrue(siblingGrants.any { it == "$siblingContext#write" })
  }

  @Test
  fun `removeContext on a manage root drops the root manage grant but leaves sub-contexts intact`() {
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)

    val rootContext = URI("https://contexts.example.org/${pod.name}/root-${randomId()}")
    val subContext = URI("$rootContext/sub")

    podContextsDao.create(podId, rootContext.toString(), null, null, "test")
    podContextsDao.create(podId, subContext.toString(), null, null, "test")
    podGrantsDao.addGrants(
      podId = podId,
      appId = "did:web:cascade-test.example",
      webId = webIdFor(pod.name),
      grants = listOf("$rootContext#manage"),
      grantedBy = "test",
    )

    podFacade.removeContext(pod.name, rootContext)

    assertFalse(podContextsDao.exists(podId, rootContext.toString()), "root context gone")
    assertTrue(podContextsDao.exists(podId, subContext.toString()), "sub-context untouched")
    val grants = podGrantsDao.fetchGrantStrings(podId, "did:web:cascade-test.example", listOf(webIdFor(pod.name)))
    assertFalse(grants.any { it == "$rootContext#manage" }, "root manage grant gone")
  }

  @Test
  fun `removeContext on a sub-context leaves the manage root and its grant intact`() {
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)

    val rootContext = URI("https://contexts.example.org/${pod.name}/root-${randomId()}")
    val subContext = URI("$rootContext/sub")

    podContextsDao.create(podId, rootContext.toString(), null, null, "test")
    podContextsDao.create(podId, subContext.toString(), null, null, "test")
    podGrantsDao.addGrants(
      podId = podId,
      appId = "did:web:cascade-test.example",
      webId = webIdFor(pod.name),
      grants = listOf("$rootContext#manage", "$subContext#read"),
      grantedBy = "test",
    )

    podFacade.removeContext(pod.name, subContext)

    assertTrue(podContextsDao.exists(podId, rootContext.toString()), "root context untouched")
    assertFalse(podContextsDao.exists(podId, subContext.toString()), "sub-context gone")
    val grants = podGrantsDao.fetchGrantStrings(podId, "did:web:cascade-test.example", listOf(webIdFor(pod.name)))
    assertTrue(grants.any { it == "$rootContext#manage" }, "root manage grant must survive")
    assertFalse(grants.any { it == "$subContext#read" }, "sub-context grant must be gone")
  }

  /** Returns the plaintext of the refresh token it seeded for [contextUri]. */
  private fun seedContext(
    podName: String,
    podId: ObjectId,
    contextUri: URI,
  ) {
    podContextsDao.create(podId, contextUri.toString(), null, null, "test")
    putEventResource(
      podName = podName,
      resourceUri = URI("https://events.example.org/$podName/${randomId()}"),
      contextUri = contextUri,
      name = "ctx-cascade-${randomId()}",
    )
    podGrantsDao.addGrants(
      podId = podId,
      appId = "did:web:cascade-test.example",
      webId = webIdFor(podName),
      grants = listOf("$contextUri#read", "$contextUri#write"),
      grantedBy = "test",
    )
  }

  private fun webIdFor(podName: String) = "https://id.example.org/$podName-cascade-user"

  // -- media, the other half of both cascades --
  //
  // Seeded straight through [PodMediaDao] and asserted there, with no store anywhere in sight. That
  // is the assertion, not a shortcut: both cascades are pure registry work and have to keep running
  // on a deployment configured with `SEMPODS_MEDIA_BACKEND=none`, where `PodMediaFacade` is not
  // bound at all. Injecting the facade here would test something these two lines must not need.

  private fun seedMedia(podId: ObjectId, vararg contexts: URI): String {
    val mediaId = randomId()
    val candidate = PodMedia(podId = podId, mediaId = mediaId, size = 42, assignments = emptySet(), unreferencedSince = null)
    contexts.forEach { context ->
      podMediaDao.upsertAndAssign(
        candidate,
        MediaAssignment(
          context = context.toString(),
          contentType = "image/jpeg",
          filename = "photo.jpg",
          createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
          createdBy = "cascade-test",
        ),
      )
    }
    return mediaId
  }

  @Test
  fun `deletePod stamps the pod's media unreferenced instead of deleting them`() {
    val pod = sempodsTestFactory.newPod(createPublicContext = false)
    val podId = checkNotNull(pod.id)
    val context = URI("https://contexts.example.org/${pod.name}/${randomId()}")
    val mediaId = seedMedia(podId, context)

    deletePodViaAdminApi(pod.name)

    assertNull(podDao.fetchByName(pod.name), "the pod itself is gone")
    // Not deleted, and that is the point: the grace period cushions an accidental pod deletion the
    // way it cushions an accidental unassign. The row outlives its pod, still carrying podId and
    // mediaId — which is what the host-level sweep addresses the bytes by.
    val stamped = assertNotNull(podMediaDao.find(podId, mediaId), "the media row must survive the pod")
    assertNotNull(stamped.unreferencedSince, "and it must be a sweep candidate")
    assertEquals(emptySet(), stamped.contexts, "its contexts went with the pod")
  }

  @Test
  fun `removeContext pulls the context out of the media assignments`() {
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)
    val targetContext = URI("https://contexts.example.org/${pod.name}/target-${randomId()}")
    val siblingContext = URI("https://contexts.example.org/${pod.name}/sibling-${randomId()}")
    podContextsDao.create(podId, targetContext.toString(), null, null, "test")
    podContextsDao.create(podId, siblingContext.toString(), null, null, "test")

    val onlyThere = seedMedia(podId, targetContext)
    val alsoElsewhere = seedMedia(podId, targetContext, siblingContext)

    podFacade.removeContext(pod.name, targetContext)

    val orphaned = assertNotNull(podMediaDao.find(podId, onlyThere))
    assertEquals(emptySet(), orphaned.contexts, "the media lost its only assignment")
    assertNotNull(orphaned.unreferencedSince, "so it becomes a sweep candidate")

    val surviving = assertNotNull(podMediaDao.find(podId, alsoElsewhere))
    assertEquals(setOf(siblingContext.toString()), surviving.contexts)
    assertNull(surviving.unreferencedSince, "a media still assigned elsewhere is not a candidate")
  }
  /**
   * One `schema:Event` in one context, written straight to the store — the smallest seed that
   * produces an RDF resource, a backup row and a pod-level `lastModifiedAt` bump.
   */
  private fun putEventResource(
    podName: String,
    resourceUri: URI,
    contextUri: URI,
    name: String,
    dateModified: Instant? = null,
  ) {
    val subject = resourceUri.toIri()
    val context = contextUri.toIri()
    val model = LinkedHashModel()
    model.add(subject, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
    model.add(subject, Ontologies.SCHEMA_ORG.Properties.name, Values.literal(name), context)
    if (dateModified != null) {
      model.add(
        subject,
        Ontologies.SCHEMA_ORG.Properties.dateModified,
        Rdf4JUtil.literal(dateModified),
        context,
      )
    }
    podFacade.putResourceModel(podName = podName, resourceUri = resourceUri, model = model)
  }
}
