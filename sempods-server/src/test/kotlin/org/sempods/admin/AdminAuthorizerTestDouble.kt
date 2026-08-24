package org.sempods.admin

import org.sempods.commons.trace.TraceContextHolder
import java.util.concurrent.ConcurrentHashMap

/**
 * Test binding for the [AdminAuthorizer] seam: delegates to a [StaticCredentialAdminAuthorizer]
 * configured with [TEST_ADMIN_SECRET], and lets a test swap the delegate **for its own requests**.
 *
 * The swap exists because the production binding reads `SEMPODS_ADMIN_CLIENTS` from the environment
 * at boot and `SempodsIntegrationTest.sempodsInjector` is one lazily built injector shared by every
 * sempods test in the JVM — so the *unconfigured* case (fail closed → 503) is unreachable over HTTP
 * without it.
 *
 * Keyed on the trace, the way [org.sempods.commons.guice.GuiceAppTestProxy] keys its delegates,
 * and for the same reason. This used to be one `@Volatile` field on the
 * grounds that "Gradle runs one JVM per module with no test parallelism" — true when it was
 * written, and no longer: classes now run side by side, so a global swap would answer 503 to any
 * *other* class that happened to send an admin request during the window. `deletePodViaAdminApi`
 * on the shared base class makes that a broad target, not a narrow one.
 *
 * [withAuthorizer] binds a trace, which is what puts a `traceparent` on the requests inside the
 * block (`AhcTraceparentFilter` writes one only for a thread that carries a trace) and what lets
 * `TraceContextFilter` hand the same id back on the server side. A request from anywhere else
 * carries a different trace and gets the default authorizer.
 */
class AdminAuthorizerTestDouble : AdminAuthorizer {

  private val overrides = ConcurrentHashMap<String, AdminAuthorizer>()

  override fun authorize(authorizationHeader: String?): AdminAuthorization {
    val authorizer = TraceContextHolder.getTraceId()?.let(overrides::get) ?: defaultAuthorizer
    return authorizer.authorize(authorizationHeader)
  }

  /** Runs [block] with [authorizer] in place for the requests it makes, and for nobody else's. */
  fun <T> withAuthorizer(authorizer: AdminAuthorizer, block: () -> T): T =
    TraceContextHolder.withInherited { bound ->
      overrides[bound.traceId] = authorizer
      try {
        return@withInherited block()
      } finally {
        overrides.remove(bound.traceId)
      }
    }

  companion object {

    /** The bearer secret every admin HTTP test presents. */
    const val TEST_ADMIN_SECRET = "sc_test-admin-secret"

    /** The client id [TEST_ADMIN_SECRET] resolves to. */
    const val TEST_ADMIN_CLIENT_ID = "test-admin"

    private val defaultAuthorizer: AdminAuthorizer =
      StaticCredentialAdminAuthorizer(mapOf(TEST_ADMIN_CLIENT_ID to TEST_ADMIN_SECRET))

    /** An authorizer with no credentials at all — the fail-closed (503) case. */
    val unconfigured: AdminAuthorizer = StaticCredentialAdminAuthorizer(emptyMap())
  }
}
