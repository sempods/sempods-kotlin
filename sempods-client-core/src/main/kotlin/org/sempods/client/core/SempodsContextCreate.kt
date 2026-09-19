package org.sempods.client.core

/**
 * What a context is created with (SPS-CTX-015): the specification's `ContextCreate` members, or JSON
 * the caller encoded.
 *
 * ```java
 * pod.contexts().create(iri, SempodsContextCreate.fields().withLabel("Tasks").withPublic(true));
 * pod.contexts().create(iri, SempodsContextCreate.json("{\"label\":\"Tasks\"}"));
 * ```
 *
 * The body stays JSON while the answer is RDF: creating a context says what to register, and the
 * registry then describes what it holds (SPS-CTX-037).
 */
sealed class SempodsContextCreate {

  /** The body as it goes on the wire. */
  @JvmSynthetic
  internal abstract fun encoded(): ByteArray

  /**
   * `label`, `description` and `public`, in that order, each sent only when it is set: null leaves the
   * member out, and an empty string is sent as it is. A context created without `public` is private
   * (SPS-CTX-027, SPS-CTX-030).
   */
  class Fields private constructor(
    private val label: String?,
    private val description: String?,
    private val public: Boolean?,
  ) : SempodsContextCreate() {

    fun withLabel(label: String?): Fields = Fields(label, description, public)

    fun withDescription(description: String?): Fields = Fields(label, description, public)

    fun withPublic(public: Boolean?): Fields = Fields(label, description, public)

    @JvmSynthetic
    override fun encoded(): ByteArray {
      val members = LinkedHashMap<String, Any>()
      label?.let { members["label"] = it }
      description?.let { members["description"] = it }
      public?.let { members["public"] = it }
      return encodeObject(members)
    }

    internal companion object {

      @JvmSynthetic
      internal fun of(label: String?, description: String?, public: Boolean?): Fields =
        Fields(label, description, public)
    }
  }

  private class Encoded(private val bytes: ByteArray) : SempodsContextCreate() {
    @JvmSynthetic
    override fun encoded(): ByteArray = bytes
  }

  companion object {

    /** No members yet: `{}`, which creates a private context without label or description. */
    @JvmStatic
    fun fields(): Fields = Fields.of(label = null, description = null, public = null)

    /** [encoded], sent byte for byte and not parsed. The array is copied. */
    @JvmStatic
    fun json(encoded: ByteArray): SempodsContextCreate = Encoded(encoded.copyOf())

    /** [encoded] as UTF-8, sent byte for byte and not parsed. */
    @JvmStatic
    fun json(encoded: String): SempodsContextCreate = Encoded(encoded.toByteArray(Charsets.UTF_8))
  }
}
