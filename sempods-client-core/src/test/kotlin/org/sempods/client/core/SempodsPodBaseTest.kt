package org.sempods.client.core

import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The two requirements a base URL has to satisfy, and the one property everything above it relies
 * on: that nothing resolved against a base leaves it.
 */
class SempodsPodBaseTest {

  @Test
  fun `a conforming base is accepted`() {
    SempodsPodBaseVectors.accepted.forEach { assertNull(SempodsPodBase.reject(URI(it)), it) }
  }

  @Test
  fun `a trailing slash names the same pod and is dropped`() {
    SempodsPodBaseVectors.canonicalized.forEach { (given, canonical) ->
      assertEquals(canonical, SempodsPodBase.of(given).toString(), given)
    }
  }

  @Test
  fun `a base the specification forbids is refused, naming the clause`() {
    SempodsPodBaseVectors.refused.forEach { (url, requirement) ->
      val uri = runCatching { URI(url) }.getOrNull()
      val reason = uri?.let { SempodsPodBase.reject(it) } ?: "not a valid URL"
      assertTrue(reason.isNotBlank(), "$url should be refused ($requirement)")
    }
  }

  @Test
  fun `a nested deployment path survives resolution`() {
    // The failure this closes: `URI.resolve` against a base without a trailing slash replaces the
    // last segment, so `https://example.org/pods/alice` + `_system/contexts` used to address
    // `https://example.org/pods/_system/contexts` — another pod's neighbour, with this pod's bearer.
    val base = SempodsPodBase.of("https://example.org/pods/alice")
    assertEquals(
      URI("https://example.org/pods/alice/_system/contexts"),
      base.resolve("_system/contexts"),
    )
  }

  @Test
  fun `a path that would leave the pod is refused rather than resolved`() {
    val base = SempodsPodBase.of("https://pods.example/alice")
    listOf("/etc/passwd", "../bob/secret", "a/../../bob", "a\\..\\bob").forEach {
      assertThrows<IllegalArgumentException>(it) { base.resolve(it) }
    }
  }

  @Test
  fun `containment is by path segment, not by string prefix`() {
    val base = SempodsPodBase.of("https://pods.example/alice")
    assertTrue(URI("https://pods.example/alice") in base)
    assertTrue(URI("https://pods.example/alice/_system/contexts") in base)

    // The one a `startsWith` on the text would wave through, and the reason this is a method
    // rather than a comparison at each call site.
    assertFalse(URI("https://pods.example/alice-archive/secret") in base)
    assertFalse(URI("https://pods.example/bob") in base)
    assertFalse(URI("https://elsewhere.example/alice") in base)
    assertFalse(URI("http://pods.example/alice") in base)
    assertFalse(URI("https://pods.example:8443/alice") in base)
    assertFalse(URI("https://pods.example/alice/../bob") in base)
  }

  @Test
  fun `the default port is the same port`() {
    assertTrue(URI("https://pods.example:443/alice/x") in SempodsPodBase.of("https://pods.example/alice"))
    assertTrue(URI("https://pods.example/alice/x") in SempodsPodBase.of("https://pods.example:443/alice"))
  }
}
