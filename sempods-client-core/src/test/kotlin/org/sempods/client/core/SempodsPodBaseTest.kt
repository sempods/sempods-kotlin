package org.sempods.client.core

import okhttp3.HttpUrl.Companion.toHttpUrl
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
    SempodsPodBaseVectors.accepted.forEach { assertNull(SempodsPodBase.reject(it), it) }
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
      val reason = SempodsPodBase.reject(url) ?: ""
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
      "https://example.org/pods/alice/_system/contexts",
      base.resolve("_system/contexts").toString(),
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
    assertTrue("https://pods.example/alice".toHttpUrl() in base)
    assertTrue("https://pods.example/alice/_system/contexts".toHttpUrl() in base)

    // The one a `startsWith` on the text would wave through, and the reason this is a method
    // rather than a comparison at each call site.
    assertFalse("https://pods.example/alice-archive/secret".toHttpUrl() in base)
    assertFalse("https://pods.example/bob".toHttpUrl() in base)
    assertFalse("https://elsewhere.example/alice".toHttpUrl() in base)
    assertFalse("http://pods.example/alice".toHttpUrl() in base)
    assertFalse("https://pods.example:8443/alice".toHttpUrl() in base)
    assertFalse("https://pods.example/alice/../bob".toHttpUrl() in base)
  }

  @Test
  fun `the default port is the same port`() {
    assertTrue("https://pods.example:443/alice/x".toHttpUrl() in SempodsPodBase.of("https://pods.example/alice"))
    assertTrue("https://pods.example/alice/x".toHttpUrl() in SempodsPodBase.of("https://pods.example:443/alice"))
  }
}
