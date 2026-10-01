package org.sempods

import org.sempods.client.SempodsPod
import org.sempods.client.SempodsRequestAuth
import org.sempods.client.rdf4j.SempodsRdf4jPod
import org.sempods.pods.oauth.PodRequestVerifierTestImpl

/**
 * The scenario a `withSetup { }` block in [SempodsIntegrationTest] runs in: the test implementations
 * standing in for the server's seams, for the requests this block makes and for nobody else's, and
 * the published client to make them with.
 */
class SempodsTestSetup internal constructor(
  /** Decides who is calling; the real verifier until a test calls `answerWith`. */
  val requestVerifier: PodRequestVerifierTestImpl,
  private val podAccess: SempodsTestPodAccess,
) {

  /**
   * The pod named [name] as a caller presenting [auth] sees it, through `sempods-client`. Its
   * requests carry this block's trace, so they reach the seams above.
   */
  fun podAs(name: String, auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()): SempodsPod =
    podAccess.sessionFor(name, auth)

  /** [podAs] with a bearer. */
  fun podAs(name: String, bearer: String): SempodsPod = podAs(name, SempodsRequestAuth.bearer(bearer))

  /** [podAs] reading and writing RDF4J values. */
  fun rdfAs(name: String, auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()): SempodsRdf4jPod =
    SempodsRdf4jPod(podAs(name, auth))

  /** [rdfAs] with a bearer. */
  fun rdfAs(name: String, bearer: String): SempodsRdf4jPod = SempodsRdf4jPod(podAs(name, bearer))
}
