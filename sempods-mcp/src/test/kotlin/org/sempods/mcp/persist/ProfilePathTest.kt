package org.sempods.mcp.persist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilePathTest {

  @Test
  fun `valid names are path-safe lowercase`() {
    assertTrue(ProfilePath.isValidName("private"))
    assertTrue(ProfilePath.isValidName("cron-agent"))
    assertTrue(ProfilePath.isValidName("p1"))
  }

  @Test
  fun `invalid names are rejected`() {
    assertFalse(ProfilePath.isValidName(""))
    assertFalse(ProfilePath.isValidName("-leading"))
    assertFalse(ProfilePath.isValidName("Upper"))
    assertFalse(ProfilePath.isValidName("has space"))
    assertFalse(ProfilePath.isValidName("under_score"))
    assertFalse(ProfilePath.isValidName("dot.name"))
  }

  @Test
  fun `reserved route segments can never be profiles`() {
    for (r in setOf("_system", ".well-known", "authorize", "token", "register", "jwks.json", "oidc", "healthz", "mcp")) {
      assertFalse(ProfilePath.isValidName(r), "'$r' must be reserved")
    }
  }

  @Test
  fun `the default sentinel is reserved so an explicit default segment 404s`() {
    // Blank/absent still maps to the implicit default (root); an explicit `/default` must not be a
    // routable second URL for the root profile.
    assertFalse(ProfilePath.isValidName(PodKey.DEFAULT_PROFILE))
    assertNull(ProfilePath.normalize(PodKey.DEFAULT_PROFILE))
    assertEquals(PodKey.DEFAULT_PROFILE, ProfilePath.normalize(null))
  }

  @Test
  fun `baseUrlFor is root for default-or-blank and suffixed for a named profile`() {
    val base = "https://mcp.test"
    assertEquals(base, ProfilePath.baseUrlFor(base, PodKey.DEFAULT_PROFILE))
    assertEquals(base, ProfilePath.baseUrlFor(base, ""))
    assertEquals("$base/private", ProfilePath.baseUrlFor(base, "private"))
  }

  @Test
  fun `normalize maps a blank segment to the default profile`() {
    assertEquals(PodKey.DEFAULT_PROFILE, ProfilePath.normalize(null))
    assertEquals(PodKey.DEFAULT_PROFILE, ProfilePath.normalize(""))
    assertEquals(PodKey.DEFAULT_PROFILE, ProfilePath.normalize("  "))
  }

  @Test
  fun `normalize passes a valid name and rejects a reserved or malformed one`() {
    assertEquals("private", ProfilePath.normalize("private"))
    assertNull(ProfilePath.normalize("token"))
    assertNull(ProfilePath.normalize("Bad Name"))
  }
}
