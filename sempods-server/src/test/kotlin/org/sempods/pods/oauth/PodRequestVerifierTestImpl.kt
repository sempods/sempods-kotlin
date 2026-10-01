package org.sempods.pods.oauth

import org.sempods.pods.oauth.spi.PodRequestVerifier
import org.sempods.pods.oauth.spi.PodResourceRequest
import org.sempods.pods.oauth.spi.PodTokenAuthentication
import org.sempods.spec.PodRef

/**
 * The [PodRequestVerifier] one `withSetup { }` block's requests reach: [real] until a test says
 * otherwise through [answerWith].
 */
class PodRequestVerifierTestImpl(private val real: PodRequestVerifier) : PodRequestVerifier {

  @Volatile
  private var answer: PodRequestVerifier = real

  /** Lets [verifier] decide every request from now on. */
  fun answerWith(verifier: PodRequestVerifier) {
    answer = verifier
  }

  override fun verify(request: PodResourceRequest, pod: PodRef): PodTokenAuthentication =
    answer.verify(request, pod)
}
