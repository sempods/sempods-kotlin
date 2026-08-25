package org.sempods.pods

/**
 * Which pod something belongs to — the tenant key the per-pod seams partition by.
 *
 * **A partition key, not a name and not a location.** Nothing above a store resolves it,
 * dereferences it or shows it to anyone; it separates one pod's data from another's and that is
 * its whole job. The addressable identity of a pod is [org.sempods.spec.PodRef], which carries the
 * URI, the owner and the label — a different question with a different type.
 *
 * **The value promises nothing about its own form.** It is not a path segment, not an object key,
 * not a column; an implementation that needs a physical location derives one and owns that mapping,
 * exactly as [org.sempods.pods.media.PodMediaStore] says it owns its layout. Reading the value as
 * though it were already a location is the coupling this type exists to remove — a key whose form
 * is part of the contract has picked one storage shape for every implementation. The two shipped
 * media stores use the token verbatim as a directory name and a key prefix, and each says so in its
 * own KDoc; that is their statement, not this type's.
 *
 * A deployment mints these and is the only side that can recognise its own.
 * `PodMediaFacade.reconcile` is where that recognition happens.
 */
@JvmInline
value class PodId(val value: String) {

  init {
    // Identity, not form: an empty token names nothing. Everything else a backend might object to
    // is the backend's own business, and its mapping is where that belongs.
    require(value.isNotEmpty()) { "a pod id cannot be empty" }
  }

  /** The token itself, so a log line reads as the id rather than as a wrapper. */
  override fun toString(): String = value
}
