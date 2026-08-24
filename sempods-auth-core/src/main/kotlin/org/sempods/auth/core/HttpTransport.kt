package org.sempods.auth.core

/**
 * The two HTTP calls an OIDC client makes, as a port.
 *
 * Four lines of interface rather than a transport dependency. The three services that need this
 * hold different clients between them — the pod server `commons-okhttp`'s, `sempods-mcp`
 * `:sempods-client`'s own, `sempods-auth` a Ktor one — so a shared implementation owning the
 * transport would hand one of them another's, and `sempods-server` is the module that spent a
 * milestone shedding an inherited framework. Note that the first two run the same *library* and
 * still do not share a client: `:sempods-client` hides its engine because it is published.
 *
 * What it buys is that the *flow* can live here rather than three times over: discover, redirect,
 * exchange, validate, in that order, with the checks that make each step mean something. Getting
 * that order wrong is the entire class of defect this project's own reviews keep finding, and it
 * is not a thing to ask three call sites to remember.
 *
 * Implementations should fail loudly. A `null` or an empty string in place of a response body
 * turns a network problem into a parse error two frames away from its cause.
 */
interface HttpTransport {

  /** @throws Exception on a transport failure or a non-success status. */
  fun get(url: String): String

  /**
   * `application/x-www-form-urlencoded`.
   *
   * @return the body even on a 4xx — an OAuth error is a JSON document with a reason in it, and
   *   throwing before it is read discards the only thing that says what went wrong.
   */
  fun postForm(url: String, form: Map<String, String>): String
}
