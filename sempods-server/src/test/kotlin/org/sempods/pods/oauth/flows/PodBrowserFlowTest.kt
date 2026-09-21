package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.SempodsStoreTest
import org.sempods.SempodsTestFactory
import org.sempods.SempodsUriBuilder
import org.sempods.auth.ConsentTransactionStore
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodTokenIssuer
import java.time.Instant

/**
 * The pod and the person the two browser routes are exercised against.
 *
 * One fixture rather than one per test class: `/authorize` and the consent submission are the same
 * authorization in two requests, so they need the same starting state — a pod, its owner, one
 * context the owner can delegate, and the app that is asking. The two drifted apart the first time
 * they were written separately, which is what this exists to stop.
 */
internal open class PodBrowserFlowTest : SempodsStoreTest() {

  @Inject
  protected lateinit var podGrantsFacade: PodGrantsFacade

  @Inject
  protected lateinit var consentDecisionStore: PodConsentDecisionStore

  @Inject
  protected lateinit var consentTransactionStore: ConsentTransactionStore

  @Inject
  protected lateinit var sempodsTestFactory: SempodsTestFactory

  @Inject
  protected lateinit var sempodsUriBuilder: SempodsUriBuilder

  protected val clientId = "did:web:app.example"
  protected val redirectUri = "https://app.example/cb"

  /** Any well-formed pair is enough here; the verifier is only checked at `/token`. */
  protected val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

  /** A pod, its owner, and the one public context `SempodsTestFactory` gives every pod. */
  protected inner class Owned {
    val row = sempodsTestFactory.newPod()
    val pod: HostedPod = row.toHostedPod(sempodsUriBuilder)
    val webId: String = row.owner
    val contextUri: String = sempodsTestFactory.publicContextUri(row.name).toString()
    val readScope = "$contextUri#read"

    /**
     * Signed in a minute ago, so a sign-out written during a case is unambiguously later than the
     * session it has to end.
     */
    val session = PodTokenIssuer.SessionPrincipal(webId, emptyList(), Instant.now().minusSeconds(60))

    fun grant(vararg scopes: String) {
      podGrantsFacade.replaceAppGrants(
        pod = pod,
        appId = clientId,
        webId = webId,
        subjectUris = listOf(webId),
        grants = scopes.toSet(),
        grantedBy = webId,
      )
    }

    /** Answer the durability question, and hand back the generation that answer stands under. */
    fun answered(durable: Boolean = true): Long =
      consentDecisionStore.record(pod.id, clientId, webId, durable).generation

    fun standing(): Long? = consentDecisionStore.find(pod.id, clientId, listOf(webId))?.generation

    /** What this app holds for this person right now. */
    fun held(): Set<String> = podGrantsFacade.appGrants(pod.id, clientId, listOf(webId))

    /** A ticket for the screen this person is looking at now. */
    fun ticket(): String = consentTransactionStore.issue(pod.name, webId, standing())
  }
}
