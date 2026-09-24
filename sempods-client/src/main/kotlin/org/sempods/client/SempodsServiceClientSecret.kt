package org.sempods.client

/**
 * A service client's new secret, from a rotation. The old one stopped working when the pod answered.
 * Like [SempodsServiceClientRegistration.clientSecret] it is answered once; [toString] leaves it out.
 */
class SempodsServiceClientSecret private constructor(
  val clientId: String,
  val clientSecret: String,
) {

  override fun toString(): String = "SempodsServiceClientSecret(clientId=$clientId)"

  internal companion object {

    @JvmSynthetic
    internal fun of(clientId: String, clientSecret: String) = SempodsServiceClientSecret(clientId, clientSecret)
  }
}
