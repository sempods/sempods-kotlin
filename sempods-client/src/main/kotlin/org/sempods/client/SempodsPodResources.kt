package org.sempods.client

import java.io.IOException

/**
 * A resource the pod hosts, at its own address `{pod}/{path}` (SPS-CRUD-001): read, replace, merge-patch
 * and delete. [SempodsPodSubjects] offers the same operations for any IRI through the System route.
 *
 * ```java
 * SempodsPodResources resources = pod.resources();
 * resources.put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of(jsonLd), SempodsWriteOptions.inContext(tasks));
 * SempodsResponse<String> read = resources.getText(event, SempodsGraphFormat.JSON_LD,
 *     SempodsReadOptions.of(SempodsContextSelection.of(tasks)));
 * resources.patch(event, SempodsContent.of(patch), SempodsWriteOptions.inContext(tasks).withIfMatch(read.getHeaders().get("ETag")));
 * ```
 *
 * **The address is the IRI.** It must lie under the pod base with a path the pod can take as it is. An
 * IRI outside the pod, under `_system` or `.well-known` (SPS-CRUD-004), with a query, a fragment, a
 * dot or empty segment, or a character its path would have to escape is an [IllegalArgumentException],
 * and nothing is sent. `%` and `;` are among those characters: the pod decodes the path and cuts a
 * segment at `;` before it composes the IRI. Such IRIs are reached through [SempodsPodSubjects].
 *
 * **Answers.** Every status an operation lists is a [SempodsResponse] with its headers as the pod sent
 * them, `ETag`, `Location`, `Vary` and `Retry-After` included; any other is a [SempodsStatusException].
 *
 * | Operation | Answers |
 * |---|---|
 * | [getText], [getBytes] | `200`; `404` without a body; `304` without a body only with `If-None-Match` |
 * | [put] | `200`, `201`, `204`; `412` without a body only when conditional |
 * | [patch], [delete] | `200`, `204`, `404`; `412` only when conditional |
 *
 * A `404` on [patch] or [delete] means nothing of the resource is in that context, or no such context
 * (SPS-CRUD-010).
 *
 * **A read with [SempodsContextSelection.none] sends nothing.** A read route cannot be asked for no
 * context: it drops an empty `context` parameter and answers from every readable one. The core
 * answers instead, as the absence the pod gives when nothing is visible (SPS-CRUD-017): `404`, no
 * headers, no body — an answer that did not come from the pod.
 *
 * **After a connection lost before an answer**, a read, a `PUT` and a `DELETE` are sent once more; a
 * `PATCH` and a write with stream content are not. When the first attempt already took effect, a
 * creation then reports `200`, a conditional write `412` and a deletion `404`.
 *
 * **A body is read into memory, up to 16 MiB**; a larger one is a [SempodsDecodingException].
 */
class SempodsPodResources private constructor(
  private val operations: ResourceOperations,
) {

  /** The resource as the text the pod sent, in [format]. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getText(
    resourceUri: String,
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
    options: SempodsReadOptions = SempodsReadOptions.defaults(),
  ): SempodsResponse<String> = operations.read(resourceUri, format, options, BodyReading.TEXT)

  /** The resource as the bytes the pod sent, in [format]. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getBytes(
    resourceUri: String,
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
    options: SempodsReadOptions = SempodsReadOptions.defaults(),
  ): SempodsResponse<ByteArray> = operations.read(resourceUri, format, options, BodyReading.BYTES)

  /** Replaces the resource's statements in the target context with [content], sent as [format] (SPS-CRUD-031). */
  @Throws(IOException::class)
  fun put(
    resourceUri: String,
    format: SempodsGraphFormat,
    content: SempodsContent,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> = operations.put(resourceUri, format, content, options)

  /** Applies [mergePatch], `application/merge-patch+json`, to the resource in the target context (SPS-CRUD-035). */
  @Throws(IOException::class)
  fun patch(
    resourceUri: String,
    mergePatch: SempodsContent,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> = operations.patch(resourceUri, mergePatch, options)

  /** Removes the resource's statements in the target context (SPS-CRUD-039). */
  @Throws(IOException::class)
  fun delete(
    resourceUri: String,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> = operations.delete(resourceUri, options)

  internal companion object {

    @JvmSynthetic
    internal fun of(operations: ResourceOperations): SempodsPodResources = SempodsPodResources(operations)
  }
}
