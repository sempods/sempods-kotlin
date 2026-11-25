package org.sempods.pods.authentication

import org.sempods.pods.authentication.spi.PodApiRequest
import org.sempods.pods.authentication.spi.PodRequestAuthentication
import org.sempods.pods.authentication.spi.PodRequestAuthenticator

/**
 * The [PodRequestAuthenticator] one `withSetup { }` block's requests reach: [real] until a test says
 * otherwise through [answerWith]. [real] stays reachable, so an alternative can start from what this
 * server would have answered.
 */
class PodRequestAuthenticatorTestImpl(val real: PodRequestAuthenticator) : PodRequestAuthenticator {

  @Volatile
  private var answer: PodRequestAuthenticator = real

  /** Lets [authenticator] decide every request from now on. */
  fun answerWith(authenticator: PodRequestAuthenticator) {
    answer = authenticator
  }

  override fun authenticate(pod: String, request: PodApiRequest): PodRequestAuthentication =
    answer.authenticate(pod, request)
}
