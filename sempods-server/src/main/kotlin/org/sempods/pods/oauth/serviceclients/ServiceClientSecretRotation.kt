package org.sempods.pods.oauth.serviceclients

/** What [PodServiceClientStore.rotateSecret] did. */
internal sealed interface ServiceClientSecretRotation {
  data class Rotated(val registration: ServiceClientRegistration, val secret: String) : ServiceClientSecretRotation
  data object NotFound : ServiceClientSecretRotation
  data object Conflict : ServiceClientSecretRotation

  /** A `did:web` client: it authenticates with its DID document's keys and has no secret to replace. */
  data object HoldsNoSecret : ServiceClientSecretRotation
}
