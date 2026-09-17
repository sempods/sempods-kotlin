package org.sempods.api.pod.resources

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * The `If-Match` and `If-None-Match` a write arrived with, evaluated against the current
 * representations of its target (RFC 9110 §13.1.1, §13.1.2).
 *
 * The write services evaluate it after the write is authorized and its target found, and before
 * the body is read (RFC 9110 §13.2.1). So a caller that may not write gets `403` with or without a
 * condition, and a PATCH or DELETE of a resource that has nothing in the context stays `404`.
 *
 * A field that is not `*` or a list of entity tags is `400`. Ignoring it would run the write
 * without the condition the caller asked for.
 *
 * @param ifMatch the field as received, repeated header lines joined with a comma; null when absent.
 * @param ifNoneMatch the same for `If-None-Match`.
 */
class WriteConditions(private val ifMatch: String?, private val ifNoneMatch: String?) {

  /**
   * Throws `412` unless both conditions hold for [current], the strong tags of the target's current
   * representations. `*` asks whether the target [exists]; a tag asks whether it is one of [current].
   * The two differ only for an empty slot, which has a tag to chain on (`SPS-CRUD-052`) and no
   * representation for `*` (`SPS-CRUD-053`).
   *
   * `If-Match` compares strongly, so a weak tag never matches. `If-None-Match` compares weakly.
   */
  internal fun requireHold(current: Collection<EntityTag>, exists: Boolean = current.isNotEmpty()) {
    val match = ifMatch?.let { parse("If-Match", it) }
    val noneMatch = ifNoneMatch?.let { parse("If-None-Match", it) }
    val currentValues = current.map { it.value }.toSet()

    val matchHolds = when (match) {
      null -> true
      Field.Any -> exists
      is Field.Tags -> match.tags.any { !it.isWeak && it.value in currentValues }
    }
    val noneMatchHolds = when (noneMatch) {
      null -> true
      Field.Any -> !exists
      is Field.Tags -> noneMatch.tags.none { it.value in currentValues }
    }
    if (!matchHolds || !noneMatchHolds) {
      throw WebApplicationException(Response.status(Response.Status.PRECONDITION_FAILED).build())
    }
  }

  private sealed interface Field {
    data object Any : Field
    data class Tags(val tags: List<EntityTag>) : Field
  }

  private fun parse(name: String, field: String): Field {
    if (field.trim() == "*") return Field.Any
    if (!LIST.matches(field)) {
      throw WebApplicationException(
        Response.status(400)
          .entity("$name must be \"*\" or a list of entity tags")
          .type(MediaType.TEXT_PLAIN)
          .build()
      )
    }
    return Field.Tags(TAG.findAll(field).map { EntityTag(it.groupValues[2], it.groupValues[1].isNotEmpty()) }.toList())
  }

  private companion object {
    /** `entity-tag` from RFC 9110 §8.8.3, with `obs-text`. */
    const val ENTITY_TAG = """(W/)?"([\x21\x23-\x7E\x80-\xFF]*)""""

    val TAG = Regex(ENTITY_TAG)

    /** `#entity-tag`: comma-separated, possibly empty, empty elements allowed (RFC 9110 §5.6.1). */
    val LIST = Regex("""^[ \t,]*(?:$ENTITY_TAG(?:[ \t]*,[ \t,]*$ENTITY_TAG)*)?[ \t,]*$""")
  }
}
