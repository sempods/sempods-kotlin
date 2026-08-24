package org.sempods.retrieval

import org.sempods.pods.grants.SempodsCredentials
import org.sempods.spec.PodRef
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [FindSandbox.effectiveScope] — the single place that encodes the "context filter
 * else the readable ceiling" fallback every adapter / the expander scopes to.
 */
class FindSandboxTest {

  private val a = URI("https://example.org/p/a")
  private val b = URI("https://example.org/p/b")

  private fun sandbox(visibleContexts: Set<URI>?, contextFilter: Set<URI>?) = FindSandbox(
    podUri = URI("https://example.org/p"),
    credentials = SempodsCredentials(pod = PodRef(uri = URI("https://example.org/p"), name = "p", owner = "o"), restrictedContexts = visibleContexts),
    visibleContexts = visibleContexts,
    contextFilter = contextFilter,
  )

  @Test
  fun `no filter falls back to the readable ceiling`() {
    assertEquals(setOf(a, b), sandbox(visibleContexts = setOf(a, b), contextFilter = null).effectiveScope())
  }

  @Test
  fun `null ceiling with no filter stays null (all readable)`() {
    assertNull(sandbox(visibleContexts = null, contextFilter = null).effectiveScope())
  }

  @Test
  fun `filter takes precedence over the ceiling`() {
    assertEquals(setOf(a), sandbox(visibleContexts = setOf(a, b), contextFilter = setOf(a)).effectiveScope())
  }

  @Test
  fun `empty filter (all requested unreadable) scopes to nothing, not the ceiling`() {
    // An empty contextFilter means "everything the caller asked for was unknown/unreadable" — it
    // must NOT fall back to the ceiling, or the downscope would silently broaden to pod-wide.
    assertEquals(emptySet(), sandbox(visibleContexts = setOf(a, b), contextFilter = emptySet()).effectiveScope())
  }
}
