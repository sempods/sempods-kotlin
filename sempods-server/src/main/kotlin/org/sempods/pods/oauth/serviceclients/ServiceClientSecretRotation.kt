package org.sempods.pods.oauth.serviceclients

/** What [PodServiceClientStore.rotateSecret] did. */
internal sealed interface ServiceClientSecretRotation {
  data class Rotated(val registration: ServiceClientRegistration, val secret: String) : ServiceClientSecretRotation
  data object NotFound : ServiceClientSecretRotation
  data object Conflict : ServiceClientSecretRotation
}
