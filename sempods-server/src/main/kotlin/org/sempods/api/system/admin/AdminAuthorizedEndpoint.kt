package org.sempods.api.system.admin

import org.sempods.admin.AdminAuthorization
import org.sempods.admin.AdminAuthorizer
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Base class for the host-level admin endpoints under `_system/admin/…`. Turns the
 * [AdminAuthorizer] seam's verdict into the HTTP contract every admin route shares.
 *
 * Deliberately **not** a [org.sempods.api.SempodsBaseEndpoint]: that base authenticates
 * *pod-scoped* callers (pod-issued token, per-context grants). Host-level authority is a different
 * authorization input and grants no pod scopes — see `docs/concepts/modularity.md` and the
 * control-plane admin roadmap.
 */
open class AdminAuthorizedEndpoint(
  private val adminAuthorizer: AdminAuthorizer,
) {

  /**
   * Authorizes the request and returns the admin client id, or throws:
   *
   * - **503** when the deployment configured no admin authority. Fail closed — an unconfigured
   *   authority never passes, and the distinct status tells an operator that the *server* is
   *   misconfigured rather than that their credential is wrong.
   * - **401** when the credential is missing, malformed or wrong.
   *
   * Neither response reveals which admin clients exist.
   */
  protected fun requireAdminOrThrow(authorization: String?): String {
    return when (val authorization = adminAuthorizer.authorize(authorization)) {
      is AdminAuthorization.Authorized -> authorization.adminClientId

      AdminAuthorization.NotConfigured -> throw WebApplicationException(
        errorResponse(503, "admin authority not configured"),
      )

      AdminAuthorization.Denied -> throw WebApplicationException(
        errorResponse(401, "invalid or missing admin credential"),
      )
    }
  }

  protected fun errorResponse(status: Int, message: String): Response {
    return Response.status(status)
      .entity(mapOf("error" to message))
      .type(MediaType.APPLICATION_JSON)
      .build()
  }
}
