package org.sempods.client.core

import java.io.IOException

/**
 * The values of one predicate on one subject: a slot (SPS-CRUD-041), read as a JSON-LD array,
 * replaced, added to and cleared, and one IRI value removed through its edge (SPS-CRUD-042). Subject,
 * predicate and target travel as base64url path segments, as on [SempodsPodSubjects], and only a blank
 * one is refused before sending.
 *
 * ```java
 * SempodsPodSlots slots = pod.slots();
 * SempodsWriteOptions inContacts = SempodsWriteOptions.inContext(contacts);
 * slots.add(bob, knows, SempodsContent.of("{\"@id\":\"" + carol + "\"}"), inContacts);
 * String values = slots.getJson(bob, knows).getBody();
 * slots.removeEdge(bob, knows, carol, inContacts);
 * ```
 *
 * **Answers.** Every status an operation lists is a [SempodsResponse] with its headers as the pod sent
 * them; any other is a [SempodsStatusException]. A write's body is returned as the pod sent it, which
 * today names the outcome, `{"outcome":"created"}` and the like (SPS-CRUD-044).
 *
 * | Operation | Answers |
 * |---|---|
 * | [getJson], [getBytes] | `200`; `404` without a body; `304` without a body only with `If-None-Match` |
 * | [put] | `200`, `204`; `412` without a body only when conditional |
 * | [add] | `201`, with `Location` for a new IRI value; `200` for a value already present (SPS-CRUD-047); `204`; `412` only when conditional |
 * | [clear] | `200`, `204`; `412` only when conditional |
 * | [removeEdge] | `200`, `204` |
 *
 * **`204` is listed for [add], [clear] and [removeEdge] ahead of the specification.** The mutation
 * recommendation in sempods-spec's access-control proposal, adopted through sempods/sempods-spec#68,
 * replaces the outcome bodies and the `201`/`200` distinction with it, and this group reads such a pod
 * unchanged.
 *
 * **[removeEdge] takes no condition.** The pod ignores one on an edge (SPS-CRUD-054), so a tag in its
 * options is an [IllegalArgumentException] and nothing is sent.
 *
 * A read's selection, `include_contexts` (SPS-CRUD-057) and [SempodsContextSelection.none] behave as on
 * [SempodsPodResources]. A slot read in exactly one context carries an `ETag`, one spanning several
 * does not (SPS-CRUD-050, SPS-CRUD-051), and [put], [add] and [clear] answer with the slot's new one
 * (SPS-CRUD-052).
 *
 * **After a connection lost before an answer**, a read, [put], [clear] and [removeEdge] are sent once
 * more; [add] is not, and a write with stream content never is. When the first attempt already took
 * effect, a clear then reports `already_empty` and an edge removal `already_absent`.
 */
class SempodsPodSlots internal constructor(
  private val operations: ResourceOperations,
) {

  /** The slot's values as the JSON-LD array the pod sent. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getJson(
    subjectUri: String,
    predicateUri: String,
    options: SempodsReadOptions = SempodsReadOptions.defaults(),
  ): SempodsResponse<String> =
    operations.readAt(ResourceAddress.SystemRoute.slotPath(subjectUri, predicateUri), JSON_LD, options, BodyReading.TEXT)

  /** The slot's values as the bytes the pod sent. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getBytes(
    subjectUri: String,
    predicateUri: String,
    options: SempodsReadOptions = SempodsReadOptions.defaults(),
  ): SempodsResponse<ByteArray> =
    operations.readAt(ResourceAddress.SystemRoute.slotPath(subjectUri, predicateUri), JSON_LD, options, BodyReading.BYTES)

  /** Replaces the slot's values in the target context with [values], a JSON-LD array; `[]` empties it. */
  @Throws(IOException::class)
  fun put(
    subjectUri: String,
    predicateUri: String,
    values: SempodsContent,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> =
    operations.writeAt("PUT", ResourceAddress.SystemRoute.slotPath(subjectUri, predicateUri), values, JSON_LD, options, PUT_ANSWERS)

  /** Adds [values], a JSON-LD value object or an array of them (SPS-CRUD-049), to the slot in the target context. */
  @Throws(IOException::class)
  fun add(
    subjectUri: String,
    predicateUri: String,
    values: SempodsContent,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> =
    operations.writeAt("POST", ResourceAddress.SystemRoute.slotPath(subjectUri, predicateUri), values, JSON_LD, options, ADD_ANSWERS)

  /** Removes every value of the slot in the target context. */
  @Throws(IOException::class)
  fun clear(
    subjectUri: String,
    predicateUri: String,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> =
    operations.writeAt(
      "DELETE",
      ResourceAddress.SystemRoute.slotPath(subjectUri, predicateUri),
      content = null,
      mediaType = null,
      options,
      CLEAR_OR_REMOVE_ANSWERS,
    )

  /** Removes the one statement from [subjectUri] through [predicateUri] to the IRI [targetUri] in the target context. */
  @Throws(IOException::class)
  fun removeEdge(
    subjectUri: String,
    predicateUri: String,
    targetUri: String,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> {
    val path = ResourceAddress.SystemRoute.edgePath(subjectUri, predicateUri, targetUri)
    require(!options.isConditional) {
      "An edge removal takes no condition, because the pod ignores one (SPS-CRUD-054); leave If-Match and If-None-Match unset."
    }
    return operations.writeAt("DELETE", path, content = null, mediaType = null, options, CLEAR_OR_REMOVE_ANSWERS)
  }

  private companion object {

    const val JSON_LD = "application/ld+json"

    val PUT_ANSWERS = setOf(200, 204)

    val ADD_ANSWERS = setOf(200, 201, 204)

    val CLEAR_OR_REMOVE_ANSWERS = setOf(200, 204)
  }
}
