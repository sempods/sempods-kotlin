package org.sempods.client

/**
 * A permission on a context, as a grant names it: `<context-iri>#read`, `#write` or `#manage`
 * (sempods-spec `spec/core/grants.md`). [value] is the spelling after the `#`.
 *
 * What one implies is the specification's, not this type's: `SPS-GRANT-009` has `write` imply
 * `read` and `manage` imply both, on the same context. A listing that reports what a holder can do
 * carries every permission that follows; one that reports a grant carries the one it names.
 */
enum class SempodsPermission(val value: String) {
  READ("read"),
  WRITE("write"),
  MANAGE("manage"),
  ;

  companion object {

    /** The permission [value] spells, or `null` for a spelling no grant uses. */
    @JvmStatic
    fun of(value: String): SempodsPermission? = entries.firstOrNull { it.value == value }
  }
}
