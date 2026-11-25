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

  /** Lets [authenticate] decide every request from now on; challenge and headers stay [real]'s. */
  fun answerWith(authenticate: (pod: String, request: PodApiRequest) -> PodRequestAuthentication) {
    answerWith(object : PodRequestAuthenticator by real {
      override fun authenticate(pod: String, request: PodApiRequest) = authenticate(pod, request)
    })
  }

  override fun authenticate(pod: String, request: PodApiRequest): PodRequestAuthentication =
    answer.authenticate(pod, request)

  override fun challenge(pod: String): String = answer.challenge(pod)

  override val credentialHeaders: Set<String> get() = answer.credentialHeaders
}
