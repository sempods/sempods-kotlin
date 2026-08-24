package org.sempods.pods.contexts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a caller may name a context. Shared by the management route and the consent dialog, which is
 * the point — those two disagreed for a long time, and a live pod carried three different shapes as
 * a result.
 */
class ContextPathRulesTest {

  private fun accepted(path: String) =
    assertNull(ContextPathRules.rejectionReason(path), "'$path' should be creatable")

  private fun rejected(path: String, because: String) {
    val reason = assertNotNull(ContextPathRules.rejectionReason(path), "'$path' should be rejected")
    assertTrue(reason.contains(because), "'$path' rejected for the wrong reason: $reason")
  }

  @Test
  fun `an owner may name a context freely`() {
    // The pod's own working areas carry no type, because there is nothing delegated: the pod is
    // already the owner's. Anything else would be the server having an opinion about names.
    accepted("contacts")
    accepted("2026-sommer")
    accepted("projects/alpha")
    accepted("a/deeply/nested/one")
  }

  @Test
  fun `type names are reserved for the control plane`() {
    // `apps/<x>` is a sandbox root, handed out by provisioning — an owner claiming one would take
    // a name the control plane needs.
    rejected("apps/notes", because = "type root")
    // Below the root is the app's own business, which its `#manage` scope covers anyway.
    accepted("apps/notes/public")
    accepted("apps/notes/connectors/gcal/123")
  }

  @Test
  fun `a reserved type without an implementation cannot be used yet`() {
    // `users` is held for the guest case. Reserving the name now costs nothing; discovering later
    // that a pod already used it would cost a migration.
    rejected("users/alice", because = "reserved for a future context type")
    rejected("users/alice/notes", because = "reserved for a future context type")
  }

  @Test
  fun `_system is reserved anywhere in the path`() {
    // Kept free so a context IRI can later carry `<context-iri>/_system/<operation>`. Context names
    // and operation names are both open sets; banning a name after the fact would break pods that
    // already used it.
    rejected("_system", because = "reserved")
    rejected("_system/apps/x", because = "reserved")
    rejected("apps/notes/_system/shape", because = "reserved")
    rejected("privat/_system", because = "reserved")
  }

  @Test
  fun `relative segments cannot be addressed`() {
    // A context IRI is an identity, not a route: `a/../b` is collapsed in transit, so a GET on the
    // stored string lands somewhere else and the row can never be reached.
    notResolvable("..", because = "relative segments")
    notResolvable("privat/../apps/notes", because = "relative segments")
    notResolvable("./contacts", because = "relative segments")
    // Only whole segments — a name that merely contains dots is an ordinary name.
    accepted("2026.sommer")
    accepted("..hidden")
    resolved("2026.sommer")
  }

  @Test
  fun `empty segments cannot be addressed`() {
    // This is the `.../focus/` shape seen in production: a trailing slash yields a context whose
    // last segment is empty, which is a path nobody meant to create.
    notResolvable("privat//notes", because = "empty segments")
    // A *typed* trailing slash is normalised away rather than refused — `public/tasks/` is what a
    // person means by `public/tasks`. It is the stored form that is the defect, which is what
    // `addressabilityProblem` is asked directly about.
    assertEquals("${podBase}_system/contexts/apps/notes", resolved("apps/notes/"))
    assertNotNull(
      ContextPathRules.addressabilityProblem(podBase, "apps/notes/"),
      "a stored path with a trailing slash is unaddressable — normalising it would hide that",
    )
  }

  // ─── resolve: what can be a context IRI at all ────────────────────────────

  private val podBase = "https://sempods.org/alice/"

  private fun resolved(path: String): String {
    val result = ContextPathRules.resolve(podBase, path)
    assertTrue(result is ContextUriResolution.Resolved, "'$path' should resolve, got: $result")
    return result.uri.toString()
  }

  private fun notResolvable(path: String, because: String) {
    val result = ContextPathRules.resolve(podBase, path)
    assertTrue(result is ContextUriResolution.Rejected, "'$path' should not resolve, got: $result")
    assertTrue(result.reason.contains(because), "'$path' rejected for the wrong reason: ${result.reason}")
  }

  @Test
  fun `a path resolves to the IRI its management route addresses`() {
    // Identity and route are one string since contexts moved into the reserved area — nothing here
    // decomposes or recomposes, which is what kept the two from drifting apart before.
    assertEquals("${podBase}_system/contexts/contacts", resolved("contacts"))
    assertEquals("${podBase}_system/contexts/apps/notes/public", resolved("apps/notes/public"))
    // Normalised first, so the same context is reachable however the caller spelled it.
    assertEquals("${podBase}_system/contexts/contacts", resolved("  /contacts/ "))
  }

  @Test
  fun `a fragment or query cannot be part of a context IRI`() {
    // `#` is the separator of the scope grammar `<context-iri>#<permission>`. A context named
    // `foo#bar` would make `…foo#bar#read` ambiguous, and the row would not be addressable through
    // `_system/contexts/{path}` — created once and never removable.
    notResolvable("foo#bar", because = "fragment")
    notResolvable("privat/notes#read", because = "fragment")
    // Same addressability problem, and `?context=` would carry an IRI that re-parses differently.
    notResolvable("foo?bar", because = "query")
  }

  @Test
  fun `percent-encoding cannot survive the round trip and is refused`() {
    // These pass the `URI` checks — `foo%23bar` has no fragment, `foo%2Fbar` is one segment — but
    // both producers receive their path already decoded (`@PathParam` / `@FormParam`). A stored
    // `foo%23bar` is only ever asked for as `foo#bar`, which is refused; `foo%2Fbar` as `foo/bar`,
    // which finds a different row.
    notResolvable("foo%23bar", because = "percent-encoded")
    notResolvable("foo%2Fbar", because = "percent-encoded")
    notResolvable("foo%3Fbar", because = "percent-encoded")
    // Including the escaped form of the escape itself, which is how one would try to smuggle it in.
    notResolvable("foo%2523bar", because = "percent-encoded")
  }

  @Test
  fun `an empty or syntactically broken path resolves to nothing`() {
    notResolvable("", because = "missing context path")
    notResolvable("   ", because = "missing context path")
    notResolvable("/", because = "missing context path")
    notResolvable("a b", because = "invalid context path")
  }
}
