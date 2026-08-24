package org.sempods.admin

/**
 * Outcome of an admin-authority check. Three cases, deliberately distinct: a caller that presented
 * nothing usable ([Denied]) and a deployment that never configured an authority ([NotConfigured])
 * are different failures and must not collapse into one status code.
 */
sealed interface AdminAuthorization {

  /**
   * The caller is authorized. [adminClientId] identifies *which* configured admin credential was
   * presented — for logging and, later, per-caller audit. It is not a pod-scoped identity and
   * grants no pod permissions.
   */
  data class Authorized(val adminClientId: String) : AdminAuthorization

  /** A credential was expected but the presented one is missing, malformed or wrong. */
  data object Denied : AdminAuthorization

  /**
   * The deployment configured no admin authority at all. This is **not** a denial of a specific
   * caller but a configuration gap, and consumers must **fail closed**: answer 503 and never
   * execute the operation. Letting an unconfigured authority pass would turn every host-level
   * route into an unauthenticated one.
   */
  data object NotConfigured : AdminAuthorization
}

/**
 * The **admin authority seam** (`docs/modularity.md`): decides whether a caller may perform
 * host-level operations — pod lifecycle and app provisioning — which cannot be expressed as pod
 * scopes (at `createPod` the pod does not exist yet, so no `<context>#permission` can grant it).
 *
 * One implementation exists:
 *
 * - [StaticCredentialAdminAuthorizer] — a per-client secret presented as `Authorization: Bearer`.
 *   The right answer wherever the surface is reachable across a process or network boundary, which
 *   is every deployment shipped from this repository: the server always binds an HTTP connector, so
 *   the admin routes always cross a trust boundary. [org.sempods.SempodsModule.bindAdminAuthorizer]
 *   binds it unconditionally — there is no authority switch to get wrong.
 *
 * A WebID + operator-allowlist implementation for the human operator console is planned
 * (control-plane admin roadmap A3) and does not exist yet; that is what keeps this an interface
 * rather than a concrete class.
 *
 * Implementations must never widen authority beyond what the deployment configured, and must
 * return [AdminAuthorization.NotConfigured] rather than [AdminAuthorization.Authorized] when they
 * hold no configuration.
 */
interface AdminAuthorizer {

  /**
   * Evaluates the raw `Authorization` request header (`null` when absent).
   *
   * Implementations must not throw for malformed input — an unparseable header is
   * [AdminAuthorization.Denied].
   */
  fun authorize(authorizationHeader: String?): AdminAuthorization
}
