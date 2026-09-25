package org.sempods.pods.oauth

import com.google.inject.Inject
import org.bson.types.ObjectId
import org.sempods.SempodsStoreTest
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.PodId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a management authority still stands for a person, asked without its `jti` — the question
 * the consent dialog's disconnect asks. What the bearer may do with a standing one is
 * `PodContextsEndpointHttpTest`'s and `PodServiceClientsEndpointHttpTest`'s.
 */
internal class PodManagementAuthorityStoreTest : SempodsStoreTest() {

  @Inject
  private lateinit var authorities: PodManagementAuthorityStore

  @Inject
  private lateinit var consentDecisions: PodConsentDecisionStore

  private val pod = PodId(ObjectId().toHexString())
  private val clientId = "dyn:${randomId()}"
  private val webId = "https://id.test/${randomId()}"
  private val alias = "https://id.test/e/${randomId()}"

  private fun record(signedInAs: String = webId) {
    val disconnects = consentDecisions.recordWithoutLifetime(pod = pod, appId = clientId, webId = signedInAs).disconnects
    authorities.record(
      pod = pod,
      jti = randomId(),
      clientId = clientId,
      webId = signedInAs,
      disconnects = disconnects,
      subjectUris = setOf(webId, alias),
    )
  }

  @Test
  fun `an authority stands for the person who approved it, on its pod and for its app`() {
    record()

    assertTrue(authorities.standsFor(pod, clientId, listOf(webId)))
    assertFalse(authorities.standsFor(PodId(ObjectId().toHexString()), clientId, listOf(webId)), "another pod")
    assertFalse(authorities.standsFor(pod, "dyn:${randomId()}", listOf(webId)), "another app")
    assertFalse(authorities.standsFor(pod, clientId, listOf("https://id.test/${randomId()}")), "another person")
  }

  @Test
  fun `a disconnect under the URI it was approved under withdraws it`() {
    record()

    consentDecisions.recordDisconnect(pod, clientId, webId)

    assertFalse(authorities.standsFor(pod, clientId, listOf(webId)))
  }

  @Test
  fun `an authority approved under an alias is found only by a person who is known by that alias`() {
    // A disconnect moves the count only under the URIs it is made under. One made without the alias
    // leaves this authority standing, so offering it would promise what it does not do.
    record(signedInAs = alias)

    assertFalse(authorities.standsFor(pod, clientId, listOf(webId)))
    assertTrue(authorities.standsFor(pod, clientId, listOf(webId, alias)))
  }
}
