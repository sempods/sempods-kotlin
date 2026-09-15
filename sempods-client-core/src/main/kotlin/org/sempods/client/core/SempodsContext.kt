package org.sempods.client.core

import java.util.Collections
import java.util.EnumSet

/**
 * One context, as the specification's `Context` schema describes it.
 *
 * Only the schema's members are read. Members a server adds of its own are ignored; the raw body keeps
 * them.
 */
class SempodsContext internal constructor(
  /** The context's IRI, exactly as the pod sent it. It is also where the context is managed (SPS-CTX-005). */
  val contextUri: String,
  /** Null when the pod states none. */
  val label: String?,
  /** Null when the pod states none. */
  val description: String?,
  /** Null when the answer does not say. A context is private unless it was made public (SPS-CTX-030). */
  val public: Boolean?,
  permissions: Set<SempodsContextPermission>,
) {

  /**
   * What the caller holds on this context. Empty when the answer states nothing, which the reference
   * server's create answer does. A permission this client does not know is left out.
   */
  val permissions: Set<SempodsContextPermission> =
    Collections.unmodifiableSet(EnumSet.noneOf(SempodsContextPermission::class.java).apply { addAll(permissions) })

  override fun equals(other: Any?): Boolean =
    other is SempodsContext && other.contextUri == contextUri && other.label == label &&
      other.description == description && other.public == public && other.permissions == permissions

  override fun hashCode(): Int = listOf(contextUri, label, description, public, permissions).hashCode()

  override fun toString(): String =
    "SempodsContext(contextUri=$contextUri, label=$label, description=$description, public=$public, permissions=$permissions)"
}
