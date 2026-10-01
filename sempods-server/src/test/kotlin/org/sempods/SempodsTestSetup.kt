package org.sempods

import org.sempods.pods.oauth.PodRequestVerifierTestImpl

/**
 * The scenario a `withSetup { }` block in [SempodsIntegrationTest] runs in: the test implementations
 * standing in for the server's seams, for the requests this block makes and for nobody else's.
 */
class SempodsTestSetup internal constructor(
  /** Decides who is calling; the real verifier until a test calls `answerWith`. */
  val requestVerifier: PodRequestVerifierTestImpl,
)
