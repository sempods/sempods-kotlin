package org.sempods

import org.sempods.pods.authentication.PodRequestAuthenticatorTestImpl
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsRequestAuth
import org.sempods.client.media.SempodsPodMedia
import org.sempods.controlplane.SempodsControlPlaneClient
import org.sempods.admin.AdminAuthorizerTestDouble
import org.sempods.auth.core.FakeOidcProvider
import org.sempods.client.rdf4j.SempodsRdf4jPod
import org.sempods.pods.oauth.PodRequestVerifierTestImpl
import org.sempods.retrieval.FindAdapterTestImpl
import org.sempods.query.PodConnectionsTestImpl

/**
 * The scenario a `withSetup { }` block in [SempodsIntegrationTest] runs in: the test implementations
 * standing in for the server's seams, for the requests this block makes and for nobody else's, and
 * the published client to make them with.
 */
class SempodsTestSetup internal constructor(
  /**
   * Turns a request into the caller's credentials; this server's bearer pipeline until a test calls
   * `answerWith`. Everything below it, [requestVerifier] included, is reached only through it.
   */
  val requestAuthenticator: PodRequestAuthenticatorTestImpl,
  /** Decides who is calling; the real verifier until a test calls `answerWith`. */
  val requestVerifier: PodRequestVerifierTestImpl,
  /**
   * The id-server this block's token exchanges reach. It answers like the suite's until a test
   * changes it, and sign-ins armed on either complete on both.
   */
  val idServer: FakeOidcProvider,
  /** A `find` engine beside the SPARQL adapter; it answers nothing until a test calls `answerWith`. */
  val findAdapter: FindAdapterTestImpl,
  /** Where SPARQL reads reach a pod's store; this server's in-memory stores until a test calls `answerWith`. */
  val podConnections: PodConnectionsTestImpl,
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

  /** [podAs] for its media routes. */
  fun mediaAs(name: String, auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()): SempodsPodMedia =
    SempodsPodMedia(podAs(name, auth))

  /** [mediaAs] with a bearer. */
  fun mediaAs(name: String, bearer: String): SempodsPodMedia = SempodsPodMedia(podAs(name, bearer))

  /** This server's host admin surface, by default under the suite's admin credential. */
  fun adminAs(
    auth: SempodsRequestAuth = SempodsRequestAuth.bearer(AdminAuthorizerTestDouble.TEST_ADMIN_SECRET),
  ): SempodsControlPlaneClient = podAccess.hostSessionFor(auth)
}
