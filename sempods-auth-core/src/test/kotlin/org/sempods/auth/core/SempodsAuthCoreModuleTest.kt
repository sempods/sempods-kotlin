package org.sempods.auth.core

import com.google.inject.CreationException
import com.google.inject.Guice
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * That the module binds what it claims to, and fails loudly when it cannot.
 *
 * The failure this guards against arrives from the library side: a provider that quietly stops
 * being reachable. Nothing in a consuming service's own suite notices — its injector just refuses,
 * and where it refuses is the whole question.
 */
class SempodsAuthCoreModuleTest {

  private fun injectorFor(allowLoopback: Boolean = false) = Guice.createInjector(
    SempodsAuthCoreModule(authorizationCodeCollection = "test.authCodes", allowLoopbackRedirects = allowLoopback),
    SempodsAuthCoreTestModule,
  )

  @Test
  fun `the redirect policy is bound as the interface`() {
    // As the interface on purpose: the pod server accepts `dyn:` registrations too and supplies
    // its own, so what the module provides is a default rather than a decision made for it.
    val policy = injectorFor().getInstance(ClientRedirectPolicy::class.java)

    assertTrue(policy.permits("did:web:pod.example.org", "https://pod.example.org/cb"))
    assertFalse(policy.permits("did:web:pod.example.org", "https://evil.example/cb"))
  }

  @Test
  fun `it is a singleton, so two callers share one`() {
    val injector = injectorFor()

    assertSame(
      injector.getInstance(ClientRedirectPolicy::class.java),
      injector.getInstance(ClientRedirectPolicy::class.java),
    )
  }

  @Test
  fun `loopback stays off unless the service asks for it`() {
    val production = injectorFor().getInstance(ClientRedirectPolicy::class.java)
    val development = injectorFor(allowLoopback = true).getInstance(ClientRedirectPolicy::class.java)

    // In production a loopback redirect address means anything running on the user's machine can
    // intercept an authorization code, so the default may never be the permissive one.
    assertFalse(production.permits("did:web:pod.example.org", "http://localhost:1234/cb"))
    assertTrue(development.permits("did:web:pod.example.org", "http://localhost:1234/cb"))
  }

  @Test
  fun `a service that installs this without a database fails at boot, not at first request`() {
    // The property worth having, and the reason it is asserted rather than assumed: Guice checks
    // the graph when the injector is built. A service that forgets `MongoModule` finds out while
    // starting up, not on the first authorization that needs a code.
    val failure = assertFailsWith<CreationException> {
      Guice.createInjector(SempodsAuthCoreModule(authorizationCodeCollection = "test.authCodes"))
    }

    assertTrue("MongoDatabase" in failure.message.orEmpty(), failure.message.orEmpty())
  }
}
