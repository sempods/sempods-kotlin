package org.sempods.client.core

import java.time.Instant

/**
 * When a pod was last written to — deletes included.
 *
 * [dateModified] is null for a pod that exists and was never written to. The server sends the member
 * as `null` then; an answer without the member reads the same way.
 */
class SempodsPodDateModified internal constructor(
  val dateModified: Instant?,
) {

  override fun equals(other: Any?): Boolean = other is SempodsPodDateModified && other.dateModified == dateModified

  override fun hashCode(): Int = dateModified.hashCode()

  override fun toString(): String = "SempodsPodDateModified(dateModified=$dateModified)"
}
