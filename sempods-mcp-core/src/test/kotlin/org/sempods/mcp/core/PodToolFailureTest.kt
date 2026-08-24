package org.sempods.mcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PodToolFailureTest {

  @Test
  fun `a refusal is the pod's own words plus its status`() {
    assertEquals(
      "Error (403): insufficient scope for context https://sempods.org/alice/main",
      PodToolFailure.describe(
        "create_resource", 403, "insufficient scope for context https://sempods.org/alice/main",
      ),
    )
  }

  @Test
  fun `a failure with no status still reads as one`() {
    assertEquals("Error: connection refused", PodToolFailure.describe("find", null, "connection refused"))
  }

  @Test
  fun `an empty body does not produce a sentence that trails off`() {
    assertEquals("Error (502): the pod refused the call", PodToolFailure.describe("find", 502, ""))
  }

  @Test
  fun `a query sent to the wrong SPARQL tool is answered in tools, not in media types`() {
    // The pod enforces the SELECT/CONSTRUCT split by content negotiation, so the mistake arrives as
    // a 406 about `application/ld+json`. Correct HTTP, useless advice: the model chose a tool, not a
    // media type, and only the tool is something it can change.
    val selectHint = PodToolFailure.describe("sparql_select", 406, "Graph queries can only produce application/ld+json")
    assertTrue("sparql_graph" in selectHint, selectHint)
    assertTrue("application/ld+json" !in selectHint, selectHint)

    val graphHint = PodToolFailure.describe("sparql_graph", 406, "SELECT queries can only produce application/sparql-results+json")
    assertTrue("sparql_select" in graphHint, graphHint)
  }

  @Test
  fun `the hint is scoped to the two tools and the one status`() {
    // A 406 on anything else, or any other status on a SPARQL tool, is passed through — inventing a
    // second special case is how a describer starts guessing at HTTP the pod was explicit about.
    assertEquals("Error (406): not acceptable", PodToolFailure.describe("find", 406, "not acceptable"))
    assertEquals("Error (400): bad query", PodToolFailure.describe("sparql_select", 400, "bad query"))
  }

  @Test
  fun `detail leaves the status to a caller that reports it separately`() {
    // The hosted service carries the status on the envelope; repeating it inside the message would
    // say the same thing twice in one JSON object.
    assertEquals("insufficient scope", PodToolFailure.detail("update_resource", 403, "insufficient scope"))
    assertTrue(PodToolFailure.detail("sparql_select", 406, "…").startsWith("sparql_select expects"))
  }
}
