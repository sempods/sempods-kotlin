package org.sempods.client.core

import java.util.Collections

/**
 * The contexts a caller can see, as the specification's `ContextList` schema describes them
 * (SPS-CTX-021).
 *
 * Only the schema's members are read. A list the answer leaves out reads as empty; a single value it
 * leaves out reads as null.
 */
class SempodsContextList internal constructor(
  /** The pod's base URL as the pod states it; null when it does not. */
  val podBaseUrl: String?,
  /** Whether the pod took the request as authenticated; null when it does not say. */
  val authenticated: Boolean?,
  contexts: List<SempodsContext>,
  writableContexts: List<String>,
) {

  /** The visible contexts, in the order the pod sent them. */
  val contexts: List<SempodsContext> = Collections.unmodifiableList(ArrayList(contexts))

  /** The IRIs of the contexts the caller may write to, in the order the pod sent them. */
  val writableContexts: List<String> = Collections.unmodifiableList(ArrayList(writableContexts))

  override fun equals(other: Any?): Boolean =
    other is SempodsContextList && other.podBaseUrl == podBaseUrl && other.authenticated == authenticated &&
      other.contexts == contexts && other.writableContexts == writableContexts

  override fun hashCode(): Int = listOf(podBaseUrl, authenticated, contexts, writableContexts).hashCode()

  override fun toString(): String =
    "SempodsContextList(podBaseUrl=$podBaseUrl, authenticated=$authenticated, contexts=$contexts, " +
      "writableContexts=$writableContexts)"
}
