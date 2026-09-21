package org.sempods.pods.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure unit — no pod row, no Mongo, because the registration this asks about is a function.
 *
 * The `did:web:` half is `ClientRedirectPolicyTest`'s subject in `sempods-auth-core` and is not
 * retested here; what these cases cover is the split this class adds on top of it and the `dyn:`
 * arm, which the identity service has no equivalent of.
 */
class PodClientDirectoryTest {

  private val registered = mapOf(
    "dyn:known" to setOf("https://app.example/cb"),
    "dyn:loopback" to setOf("http://127.0.0.1:5173/cb"),
    "dyn:none" to emptySet(),
  )

  private fun directory(allowLoopback: Boolean = true) =
    PodClientDirectory(allowLoopback = allowLoopback, registrationOf = { registered[it] })

  @Test
  fun `a did-web identity is known without asking the registration store`() {
    val asked = mutableListOf<String>()
    val directory = PodClientDirectory(allowLoopback = false, registrationOf = { asked += it; null })

    assertEquals(
      PodClientIdentity.Known("did:web:app.example"),
      directory.identify("did:web:app.example"),
    )
    assertTrue(asked.isEmpty(), "a did:web identity is asserted, not registered: $asked")
  }

  @Test
  fun `a dyn client this pod holds no registration for is unregistered, not malformed`() {
    // The two failures are statements about different things, and answering `malformed` sent a
    // client looking for a typo in a format that was never broken.
    assertIs<PodClientIdentity.Known>(directory().identify("dyn:known"))
    assertEquals(PodClientIdentity.Unregistered, directory().identify("dyn:cleared"))
  }

  @Test
  fun `a registration with no redirect address is still a registration`() {
    assertIs<PodClientIdentity.Known>(directory().identify("dyn:none"))
  }

  @Test
  fun `what is not one of the two shapes is malformed`() {
    assertEquals(PodClientIdentity.Malformed, directory().identify(null))
    assertEquals(PodClientIdentity.Malformed, directory().identify("   "))
    assertEquals(PodClientIdentity.Malformed, directory().identify("https://app.example"))
    // Outside RFC 6749 Appendix A.1's `*VSCHAR`, which is what lets every log line interpolate it.
    assertEquals(PodClientIdentity.Malformed, directory().identify("dyn:with separator"))
  }

  @Test
  fun `surrounding whitespace is not part of a client id`() {
    assertEquals(PodClientIdentity.Known("dyn:known"), directory().identify("  dyn:known  "))
  }

  @Test
  fun `a dyn client is answered only at an address it registered`() {
    assertTrue(directory().permits("dyn:known", "https://app.example/cb"))
    assertFalse(directory().permits("dyn:known", "https://app.example/elsewhere"))
    assertFalse(directory().permits("dyn:cleared", "https://app.example/cb"))
    assertFalse(directory().permits("dyn:none", "https://app.example/cb"))
  }

  @Test
  fun `a loopback address matches with its port stripped`() {
    // RFC 8252 §7.3 — a native client binds an ephemeral port per invocation, and the registration
    // fingerprint's dedup reads the address the same way.
    assertTrue(directory().permits("dyn:loopback", "http://127.0.0.1:61234/cb"))
    assertFalse(directory().permits("dyn:loopback", "http://127.0.0.1:61234/other"))
  }

  @Test
  fun `an address that is no redirect address at all is refused before either branch`() {
    assertFalse(directory().permits("dyn:known", "not a uri"))
    assertFalse(directory().permits("did:web:app.example", "not a uri"))
  }

  @Test
  fun `a did-web client is answered on the origin its identifier names, and not beside it`() {
    // Delegation, not a second reading: the identity service asks `DidWebRedirectPolicy` the same
    // question, and two readings of "may this address answer for this origin" eventually disagree.
    val directory = directory(allowLoopback = false)

    assertTrue(directory.permits("did:web:app.example", "https://app.example/cb"))
    assertFalse(directory.permits("did:web:app.example", "https://other.example/cb"))
  }
}
