package org.sempods.client.core

/**
 * Where a write lands and what it may assume: the target context, and the validators it is conditional
 * on.
 *
 * ```java
 * resources.put(iri, SempodsGraphFormat.JSON_LD, content, SempodsWriteOptions.inContext(tasks));
 * resources.patch(iri, patch, SempodsWriteOptions.inContext(tasks).withIfMatch(etag));
 * ```
 *
 * **Every write names its target context**, sent as exactly one `context` parameter (SPS-CRUD-007).
 *
 * **Tags are sent exactly as given.** A tag identifies the representation it came with, and a write
 * replaces what one context holds, so the tag to send is one from a read selected to that context —
 * `SempodsReadOptions.of(SempodsContextSelection.of(tasks))`. A read of every readable context is a
 * different representation, and its tag does not validate the write.
 */
class SempodsWriteOptions private constructor(
  /** The IRI of the context the write targets, as the pod gave it. */
  val contextUri: String,
  /** An entity tag sent as `If-Match`; null sends none. */
  val ifMatch: String?,
  /** An entity tag, or `*`, sent as `If-None-Match`; null sends none. */
  val ifNoneMatch: String?,
) {

  init {
    require(contextUri.isNotBlank()) { "A context IRI must not be blank: every write names its target context." }
  }

  /** The same conditions, for a write into [contextUri]. @throws IllegalArgumentException for a blank IRI. */
  fun withContext(contextUri: String): SempodsWriteOptions = SempodsWriteOptions(contextUri, ifMatch, ifNoneMatch)

  /** @throws IllegalArgumentException for a blank tag; null removes the condition. */
  fun withIfMatch(entityTag: String?): SempodsWriteOptions {
    require(entityTag == null || entityTag.isNotBlank()) { "An entity tag must not be blank; pass null to send none." }
    return SempodsWriteOptions(contextUri, entityTag, ifNoneMatch)
  }

  /** @throws IllegalArgumentException for a blank tag; null removes the condition. */
  fun withIfNoneMatch(entityTag: String?): SempodsWriteOptions {
    require(entityTag == null || entityTag.isNotBlank()) { "An entity tag must not be blank; pass null to send none." }
    return SempodsWriteOptions(contextUri, ifMatch, entityTag)
  }

  /** Whether the write is conditional, and so may be answered `412`. */
  @get:JvmSynthetic
  internal val isConditional: Boolean get() = ifMatch != null || ifNoneMatch != null

  override fun equals(other: Any?): Boolean =
    other is SempodsWriteOptions && other.contextUri == contextUri && other.ifMatch == ifMatch &&
      other.ifNoneMatch == ifNoneMatch

  override fun hashCode(): Int = listOf(contextUri, ifMatch, ifNoneMatch).hashCode()

  override fun toString(): String = "SempodsWriteOptions(contextUri=$contextUri, ifMatch=$ifMatch, ifNoneMatch=$ifNoneMatch)"

  companion object {

    /** A write into [contextUri], unconditional. @throws IllegalArgumentException for a blank IRI. */
    @JvmStatic
    fun inContext(contextUri: String): SempodsWriteOptions = SempodsWriteOptions(contextUri, ifMatch = null, ifNoneMatch = null)
  }
}
