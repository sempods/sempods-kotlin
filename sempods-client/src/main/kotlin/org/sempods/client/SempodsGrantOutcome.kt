package org.sempods.client

import okhttp3.HttpUrl

/**
 * What the pod's grant consent sent the browser back with: the scopes the owner granted a service
 * client, or a refusal. The shape is a sempods extension, which no OAuth library reads.
 *
 * | The redirect carries | [isGranted] | [scopes] | [error] |
 * |---|---|---|---|
 * | `result=granted&scope=…` | true | what the owner granted, possibly fewer rows than were asked for | null |
 * | `error=access_denied` | false | empty | `access_denied` — refused, nothing ticked, or not grantable; the pod does not say which |
 * | `error=invalid_scope` or `invalid_request` | false | empty | the error code: the request or the form was wrong |
 *
 * **A refusal is not a failed installation.** The registration and its secret stand, holding no
 * grants, and the owner can grant later through another consent. [SempodsPodServiceClients] has the
 * whole sequence.
 */
class SempodsGrantOutcome private constructor(
  /** Whether the owner granted anything. */
  val isGranted: Boolean,
  /** The granted scopes, `<context-iri>#read` and the like. Empty when [isGranted] is false. */
  val scopes: Set<String>,
  /** The error code, null when [isGranted]. */
  val error: String?,
  /** The pod's `error_description`, when it sent one. */
  val errorDescription: String?,
) {

  override fun toString(): String = "SempodsGrantOutcome(granted=$isGranted, scopes=$scopes, error=$error)"

  companion object {

    /**
     * Reads the redirect's [encodedQuery], as it arrived, after checking its `state` against
     * [expectedState].
     *
     * @throws SempodsClientException when `state` is missing or different, when a member repeats,
     *   or when the query carries neither `result=granted` nor `error`.
     */
    @JvmStatic
    @Throws(SempodsClientException::class)
    fun readQuery(encodedQuery: String?, expectedState: String): SempodsGrantOutcome {
      val query = RedirectQuery.of(encodedQuery, expectedState, WHAT)
      val result = query.single("result", WHAT)
      val error = query.single("error", WHAT)
      return when {
        result == "granted" && error == null -> SempodsGrantOutcome(
          isGranted = true,
          scopes = query.single("scope", WHAT).orEmpty().split(' ').filter { it.isNotEmpty() }.toCollection(LinkedHashSet()),
          error = null,
          errorDescription = null,
        )
        result == null && error != null -> SempodsGrantOutcome(false, emptySet(), error, query.single("error_description", WHAT))
        else -> throw SempodsClientException("The $WHAT redirect carries neither a granted result nor an error.")
      }
    }

    /** [readQuery] on [redirect]'s query. */
    @JvmStatic
    @Throws(SempodsClientException::class)
    fun read(redirect: HttpUrl, expectedState: String): SempodsGrantOutcome = readQuery(redirect.encodedQuery, expectedState)

    private const val WHAT = "grant consent"
  }
}
