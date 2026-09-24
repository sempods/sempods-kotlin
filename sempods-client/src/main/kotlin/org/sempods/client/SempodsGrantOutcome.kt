package org.sempods.client

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
 * A refusal leaves the service installed ([SempodsPodServiceClients]).
 */
class SempodsGrantOutcome private constructor(
  /** The granted scopes, `<context-iri>#read` and the like. Empty when [isGranted] is false. */
  val scopes: Set<String>,
  /** The error code, null when [isGranted]. */
  val error: String?,
  /** The pod's `error_description`, when it sent one. */
  val errorDescription: String?,
) {

  /** Whether the owner granted anything. */
  val isGranted: Boolean get() = error == null

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
      val query = RedirectQuery.of(encodedQuery, expectedState, "grant consent")
      val result = query.single("result")
      val error = query.single("error")
      return when {
        result == "granted" && error == null -> SempodsGrantOutcome(scopesOf(query.single("scope")), error = null, errorDescription = null)
        result == null && error != null -> SempodsGrantOutcome(emptySet(), error, query.single("error_description"))
        else -> throw query.refused("carries neither a granted result nor an error")
      }
    }
  }
}
