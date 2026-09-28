package org.sempods.client

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
  fun `a base is bound as a URL parser writes it`() {
    SempodsPodBaseVectors.respelled.forEach { (given, bound) ->
      assertNull(SempodsPodBase.reject(given), given)
      assertEquals(bound, SempodsPodBase.of(given).toString(), given)
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
    listOf("/etc/passwd", "../bob/secret", "a/../../bob", "a\\..\\bob", "%2e%2e/bob", "a/.%2E/../bob", "%2F..%2Fbob", "a%5c..%5c..%5cbob").forEach {
      assertThrows<IllegalArgumentException>(it) { base.resolve(it) }
    }
  }

  @Test
  fun `a pod at the host root resolves without a double slash`() {
    val root = SempodsPodBase.of("https://pods.example/")
    assertEquals("https://pods.example/_system/contexts", root.resolve("_system/contexts").toString())
    assertTrue(root.resolve("_system/contexts") in root)
  }

  @Test
  fun `a query or a fragment alone addresses the base itself`() {
    val base = SempodsPodBase.of("https://pods.example/alice")
    assertEquals("https://pods.example/alice?view=summary", base.resolve("?view=summary").toString())
    assertEquals("https://pods.example/alice#top", base.resolve("#top").toString())
    assertEquals("https://pods.example/?view=summary", SempodsPodBase.of("https://pods.example").resolve("?view=summary").toString())
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
    assertFalse("https://pods.example//alice/secret".toHttpUrl() in base)
    assertFalse("https://pods.example/alice/%2F..%2Fbob".toHttpUrl() in base)
  }

  @Test
  fun `an address changes where requests go and leaves the name`() {
    val base = SempodsPodBase.of("https://acme.example/api/pod").reachedAt("http://localhost:8080/internal/")

    assertEquals("https://acme.example/api/pod", base.toString())
    assertEquals("http://localhost:8080/internal", base.address.toString())
    assertEquals("https://acme.example/api/pod/_system/auth/authorize", base.resolve("_system/auth/authorize").toString())
    assertEquals("http://localhost:8080/internal/_system/contexts", base.dial("_system/contexts").toString())
    assertTrue("https://acme.example/api/pod/x".toHttpUrl() in base)
    assertFalse(base.reaches("https://acme.example/api/pod/x".toHttpUrl()))
    assertTrue(base.reaches("http://localhost:8080/internal/x".toHttpUrl()))
    assertFalse("http://localhost:8080/internal/x".toHttpUrl() in base)
  }

  @Test
  fun `without an address the pod is reached at its name`() {
    val base = SempodsPodBase.of("https://pods.example/alice")
    assertEquals(base.url, base.address)
    assertEquals(base, SempodsPodBase.of("https://pods.example/alice/"))
    assertEquals(base, base.reachedAt("https://pods.example/alice"))
    assertFalse(base == base.reachedAt("https://internal.example/alice"))
  }

  @Test
  fun `plain http off loopback is an address only when asked for by name`() {
    val name = SempodsPodBase.of("https://acme.example/api/pod")
    val refused = assertThrows<IllegalArgumentException> { name.reachedAt("http://sempods.internal:8080/api/pod") }
    assertTrue(refused.message!!.contains("reachedOverPlaintextAt"), refused.message)

    val reached = name.reachedOverPlaintextAt("http://sempods.internal:8080/api/pod")
    assertEquals("http://sempods.internal:8080/api/pod", reached.address.toString())
    // The name is held to SPS-CORE-019 however the pod is reached.
    assertThrows<IllegalArgumentException> { SempodsPodBase.of("http://acme.example/api/pod") }
  }

  @Test
  fun `an address is held to every other clause of a base URL`() {
    val name = SempodsPodBase.of("https://acme.example/api/pod")
    SempodsPodBaseVectors.refused.filterNot { (_, requirement) -> requirement == "SPS-CORE-019 http off loopback" }
      .forEach { (url, requirement) ->
        assertThrows<IllegalArgumentException>("$url ($requirement)") { name.reachedAt(url) }
        assertThrows<IllegalArgumentException>("$url ($requirement)") { name.reachedOverPlaintextAt(url) }
      }
  }

  @Test
  fun `the default port is the same port`() {
    assertTrue("https://pods.example:443/alice/x".toHttpUrl() in SempodsPodBase.of("https://pods.example/alice"))
    assertTrue("https://pods.example/alice/x".toHttpUrl() in SempodsPodBase.of("https://pods.example:443/alice"))
  }
}
