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
 * **The context is the caller's to name.** With one, the write sends exactly one `context` parameter;
 * without one, it sends none, and the pod decides. Today's specification asks every write for one
 * (SPS-CRUD-007), and the reference server answers a write without it with `400`.
 *
 * **Tags are sent exactly as given.** Which tag a pod accepts as a write's validator is #149's to
 * settle; this type only carries it.
 */
class SempodsWriteOptions private constructor(
  /** The IRI of the context the write targets, as the pod gave it; null sends no `context` parameter. */
  val contextUri: String?,
  /** An entity tag sent as `If-Match`; null sends none. */
  val ifMatch: String?,
  /** An entity tag, or `*`, sent as `If-None-Match`; null sends none. */
  val ifNoneMatch: String?,
) {

  /** @throws IllegalArgumentException for a blank IRI; null removes the context. */
  fun withContext(contextUri: String?): SempodsWriteOptions {
    require(contextUri == null || contextUri.isNotBlank()) { "A context IRI must not be blank; pass null to send none." }
    return SempodsWriteOptions(contextUri, ifMatch, ifNoneMatch)
  }

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
  internal val isConditional: Boolean get() = ifMatch != null || ifNoneMatch != null

  override fun equals(other: Any?): Boolean =
    other is SempodsWriteOptions && other.contextUri == contextUri && other.ifMatch == ifMatch &&
      other.ifNoneMatch == ifNoneMatch

  override fun hashCode(): Int = listOf(contextUri, ifMatch, ifNoneMatch).hashCode()

  override fun toString(): String = "SempodsWriteOptions(contextUri=$contextUri, ifMatch=$ifMatch, ifNoneMatch=$ifNoneMatch)"

  companion object {

    private val DEFAULTS = SempodsWriteOptions(contextUri = null, ifMatch = null, ifNoneMatch = null)

    /** No context, unconditional. */
    @JvmStatic
    fun defaults(): SempodsWriteOptions = DEFAULTS

    /** [contextUri] as the target, unconditional. */
    @JvmStatic
    fun inContext(contextUri: String): SempodsWriteOptions = DEFAULTS.withContext(contextUri)
  }
}
