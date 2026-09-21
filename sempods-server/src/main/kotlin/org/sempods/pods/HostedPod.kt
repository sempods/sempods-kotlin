package org.sempods.pods

import org.sempods.spec.PodRef

/**
 * A pod as this deployment holds it: the pod it *is*, and the key it is stored under.
 *
 * The two are separate types on purpose — [PodRef] is a pod's addressable identity anywhere,
 * [PodId] is this host's tenant key and promises nothing about its own form
 * (`docs/concepts/modularity.md` §"The pattern"). A caller that needs both needs them **of the same
 * pod**, and passing them as two parameters is a pair that can disagree: a facade validating one
 * pod's scopes against another pod's rows finds every scope outside the namespace and deletes it.
 * One value cannot be transposed.
 *
 * Minted from the row a request already read — `PodDbo.toHostedPod` — and not resolved by name,
 * which would go through the process-local name-to-id cache. `PodSignOut` says what that costs.
 *
 * Not a seam type: a seam takes [PodRef] or [PodId], whichever question it asks. This is the
 * reference implementation's own pairing, which is where the mapping belongs.
 */
internal data class HostedPod(val ref: PodRef, val id: PodId) {

  /**
   * The pod's grant namespace — what `PodScopeValidator` measures context URIs against.
   *
   * [PodRef.uri] carries no trailing slash and every context hangs off it as `<pod-uri>/<segment>`,
   * so this is that string with the separator: the value
   * `SempodsUriBuilder.buildResourceUri(name, "")` builds, without the parse.
   */
  val baseUrl: String get() = "${ref.uri}/"

  val name: String get() = ref.name

  val owner: String get() = ref.owner
}
