package org.sempods.client.core

import java.io.IOException

/**
 * Any subject, by its IRI, through the System route `{pod}/_system/resources/{b64url(iri)}`: the same
 * operations as [SempodsPodResources], for an IRI of any scheme, inside the pod or not (SPS-CRUD-003).
 *
 * ```java
 * pod.subjects().put("did:web:bob.example", SempodsGraphFormat.JSON_LD, SempodsContent.of(bob),
 *     SempodsWriteOptions.inContext(contacts));
 * pod.subjects().delete("did:web:bob.example", SempodsWriteOptions.inContext(contacts));
 * ```
 *
 * The answers, the selection, the resend and the size limit are [SempodsPodResources]'. What differs is
 * the address: the IRI travels as base64url without padding over its UTF-8 bytes (SPS-CRUD-005), and
 * only a blank one is refused before sending. A creation's `Location` is this route.
 *
 * Deleting a subject is one [delete] per context it has statements in.
 */
class SempodsPodSubjects private constructor(
  private val operations: ResourceOperations,
) {

  /** The subject as the text the pod sent, in [format]. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getText(
    subjectUri: String,
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
    options: SempodsReadOptions = SempodsReadOptions.defaults(),
  ): SempodsResponse<String> = operations.read(subjectUri, format, options, BodyReading.TEXT)

  /** The subject as the bytes the pod sent, in [format]. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getBytes(
    subjectUri: String,
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
    options: SempodsReadOptions = SempodsReadOptions.defaults(),
  ): SempodsResponse<ByteArray> = operations.read(subjectUri, format, options, BodyReading.BYTES)

  /** Replaces the subject's statements in the target context with [content], sent as [format]. */
  @Throws(IOException::class)
  fun put(
    subjectUri: String,
    format: SempodsGraphFormat,
    content: SempodsContent,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> = operations.put(subjectUri, format, content, options)

  /** Applies [mergePatch], `application/merge-patch+json`, to the subject in the target context. */
  @Throws(IOException::class)
  fun patch(
    subjectUri: String,
    mergePatch: SempodsContent,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> = operations.patch(subjectUri, mergePatch, options)

  /** Removes the subject's statements in the target context. */
  @Throws(IOException::class)
  fun delete(
    subjectUri: String,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> = operations.delete(subjectUri, options)

  internal companion object {

    @JvmSynthetic
    internal fun of(operations: ResourceOperations): SempodsPodSubjects = SempodsPodSubjects(operations)
  }
}
