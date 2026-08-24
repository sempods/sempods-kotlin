package org.sempods

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The base URL is stated by the caller — since D5 the only way to build one of these.
 *
 * These tests pin that path, including the shape mismatch that made the trailing-slash handling
 * worth having: callers configure an address as a URL (`https://sempods.org/`) while every
 * builder here appends `/`-prefixed segments.
 *
 * What is gone with the injected `Set<AppConfig>` constructor is the case two of these tests used
 * to document: a process registering only its own app got that app's address as the pod base,
 * silently, and persisted it. There is no longer a code path that can produce it.
 */
class SempodsUriBuilderBaseUrlTest {

  private val pod = "alice"

  @Test
  fun `a trailing slash in the configured base does not double up`() {
    val withSlash = SempodsUriBuilder("https://sempods.org/")
    val withoutSlash = SempodsUriBuilder("https://sempods.org")

    assertEquals(URI("https://sempods.org/$pod"), withSlash.buildPodUri(pod))
    assertEquals(withoutSlash.buildPodUri(pod), withSlash.buildPodUri(pod))
  }

  @Test
  fun `a URI base is accepted as-is`() {
    val builder = SempodsUriBuilder(URI("https://sempods.org/"))

    assertEquals(URI("https://sempods.org/$pod"), builder.buildPodUri(pod))
  }

  @Test
  fun `every minted URI hangs off the configured base`() {
    val builder = SempodsUriBuilder("https://pods.example.org/")

    assertEquals(
      URI("https://pods.example.org/$pod/_system/contexts/apps/notes/public"),
      builder.buildContext(pod, "apps/notes/public"),
    )
    assertEquals(
      URI("https://pods.example.org/$pod/some/path"),
      builder.buildResourceUri(pod, "some/path"),
    )
    assertEquals(
      URI("https://pods.example.org/$pod/_system/media/m1/content"),
      builder.buildMediaContentUri(pod, "m1"),
    )
  }

  /**
   * Two callers configured differently mint different URIs from the same pod name — which is the
   * point: the address is the deployment's identity, and nothing here falls back to a default
   * when it is not given.
   */
  @Test
  fun `two configured bases stay apart`() {
    val server = SempodsUriBuilder("https://sempods.org/")
    val other = SempodsUriBuilder("https://pods.example.org/")

    assertEquals(URI("https://sempods.org/$pod"), server.buildPodUri(pod))
    assertEquals(URI("https://pods.example.org/$pod"), other.buildPodUri(pod))
  }

  @Test
  fun `parsePodName is the inverse of buildPodUri for the configured base`() {
    val builder = SempodsUriBuilder("https://sempods.org/")

    assertEquals(pod, builder.parsePodName(builder.buildResourceUri(pod, "events/e1")))
    assertNull(builder.parsePodName(URI("https://pods.example.org/$pod/events/e1")))
  }
}
