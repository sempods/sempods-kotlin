package org.sempods.mcp.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.assertThrows
import org.sempods.client.SempodsClientException
import org.sempods.client.wire.PodResource
import org.sempods.client.wire.PodSlot
import org.sempods.client.wire.PodWireClient
import org.sempods.client.wire.PodWriteResult
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * What [PodToolExecutor] adds on top of [PodWireClient]: the argument-to-call mapping, the shape of
 * the result, and every refusal that keeps a call from reaching a pod at all.
 *
 * The wire itself is stubbed rather than served. Paths, base64url slot encoding, content types and
 * precondition *headers* are `PodWireClientTest`'s subject one module over, and repeating them here
 * would test that module twice while testing this one less: a stub can assert the exact `URI`,
 * `List<URI>`, `Boolean` and `Int?` values handed over, which is precisely what the mapping is.
 */
class PodToolExecutorTest {

  private val mapper = jacksonObjectMapper()
  private val wire = mockk<PodWireClient>()
  private val hosted = PodToolExecutor(ToolCatalog.of(ToolVariant.MULTI_POD), wire)
  private val perPod = PodToolExecutor(ToolCatalog.of(ToolVariant.SINGLE_POD), wire)

  private val pod = URI.create("https://sempods.org/alice")
  private val ctx = "https://sempods.org/alice/main"
  private val thing = "https://sempods.org/alice/thing"

  // --- the mapping -------------------------------------------------------------------------------

  @Test
  fun `list_contexts is the whole call — no arguments to map`() {
    every { wire.listContexts(pod, "tok") } returns mapper.readTree("""{"contexts":[]}""")
    assertEquals(mapper.readTree("""{"contexts":[]}"""), run("list_contexts", null))
  }

  @Test
  fun `a SPARQL tool passes the query verbatim and the context filter as IRIs`() {
    every { wire.sparqlSelect(any(), any(), any(), any()) } returns mapper.readTree("""{"head":{}}""")
    run("sparql_select", """{"query":"SELECT * WHERE {?s ?p ?o}","context_iri":["$ctx"]}""")
    verify { wire.sparqlSelect(pod, "SELECT * WHERE {?s ?p ?o}", listOf(URI.create(ctx)), "tok") }
  }

  @Test
  fun `sparql_graph and sparql_select share a schema but not a call`() {
    every { wire.sparqlGraph(any(), any(), any(), any()) } returns mapper.readTree("""{"@graph":[]}""")
    run("sparql_graph", """{"query":"CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}"}""")
    verify { wire.sparqlGraph(pod, any(), emptyList(), "tok") }
    verify(exactly = 0) { wire.sparqlSelect(any(), any(), any(), any()) }
  }

  @Test
  fun `find maps every optional argument, and an absent one stays absent`() {
    every { wire.find(any(), any(), any(), any(), any(), any(), any()) } returns mapper.readTree("[]")
    run("find", """{"text":"kita","type":["https://schema.org/Event"],"context_iri":["$ctx"],"include_contexts":true,"limit":5}""")
    verify {
      wire.find(pod, "kita", listOf(URI.create("https://schema.org/Event")), listOf(URI.create(ctx)), true, 5, "tok")
    }
    run("find", """{"text":"kita"}""")
    verify { wire.find(pod, "kita", emptyList(), emptyList(), false, null, "tok") }
  }

  @Test
  fun `find clamps limit to the range the catalog advertises`() {
    every { wire.find(any(), any(), any(), any(), any(), any(), any()) } returns mapper.readTree("[]")
    // Out of range in either direction is clamped rather than refused or passed on raw: the schema
    // says 1..100, and a 0 that reached the pod would come back as its 400, not as our refusal.
    run("find", """{"text":"x","limit":0}""")
    verify { wire.find(pod, "x", emptyList(), emptyList(), false, 1, "tok") }
    run("find", """{"text":"x","limit":5000}""")
    verify { wire.find(pod, "x", emptyList(), emptyList(), false, 100, "tok") }
  }

  @Test
  fun `get_resource answers the iri, the write-precondition etag and the document`() {
    every { wire.getResource(any(), any(), any(), any(), any()) } returns
      PodResource("\"v1\"", mapper.readTree("""{"@id":"$thing"}"""))
    val result = run("get_resource", """{"resource_iri":"$thing"}""") as Map<*, *>
    verify { wire.getResource(pod, URI.create(thing), emptyList(), false, "tok") }
    assertEquals(listOf("resource_iri", "etag", "jsonld"), result.keys.toList())
    assertEquals(thing, result["resource_iri"])
    assertEquals("\"v1\"", result["etag"])
  }

  @Test
  fun `include_contexts reads the document once and the write-precondition etag once more`() {
    // HTTP hands out a representation's validator, so the named-graph read answers with a tag that
    // `if_match` refuses. The tool promises the write-precondition tag in both forms, so it takes it
    // from the canonical representation — one extra GET, only on this path.
    every { wire.getResource(pod, URI.create(thing), emptyList(), true, "tok") } returns
      PodResource("\"v1-contexts\"", mapper.readTree("""{"@graph":[]}"""))
    every { wire.getResource(pod, URI.create(thing), emptyList(), false, "tok") } returns
      PodResource("\"v1\"", mapper.readTree("""{"@id":"$thing"}"""))

    val result = run("get_resource", """{"resource_iri":"$thing","include_contexts":true}""") as Map<*, *>

    assertEquals("\"v1\"", result["etag"])
    assertEquals(mapper.readTree("""{"@graph":[]}"""), result["jsonld"])
  }

  @Test
  fun `get_property_values answers the slot coordinates, its values and the slot etag`() {
    every { wire.getPropertyValues(any(), any(), any(), any(), any()) } returns
      PodSlot("\"v7\"", mapper.readTree("""[{"@value":"Ada"}]"""))
    val result = run(
      "get_property_values",
      """{"subject_iri":"$thing","predicate_iri":"https://schema.org/name","context_iri":["$ctx"]}""",
    ) as Map<*, *>
    assertEquals(listOf("subject_iri", "predicate_iri", "values", "etag"), result.keys.toList())
    assertEquals("\"v7\"", result["etag"])
  }

  @Test
  fun `a slot with no etag omits the key rather than answering null`() {
    // The pod withholds a slot validator for a union read (there is no single one) and for an empty
    // or unreadable slot (which is what keeps a subject's global change state from leaking). Omitted
    // is what the write path already does with an absent tag, and `"etag": null` is what invites a
    // model to send the string "null" back as `if_match`.
    every { wire.getPropertyValues(any(), any(), any(), any(), any()) } returns
      PodSlot(null, mapper.readTree("""[{"@value":"Ada"}]"""))
    val result = run("get_property_values", """{"subject_iri":"$thing","predicate_iri":"https://schema.org/name"}""") as Map<*, *>
    assertEquals(listOf("subject_iri", "predicate_iri", "values"), result.keys.toList())
    assertFalse(result.containsKey("etag"))
  }

  @Test
  fun `an empty slot is no values, not a failure`() {
    // The route has no representation for an empty slot and answers 404 — the same 404 it answers
    // for a context the caller may not read, which must stay indistinguishable. Asked what the
    // values are, "none" is the answer; an error here would read to a model as a broken call.
    every { wire.getPropertyValues(any(), any(), any(), any(), any()) } throws
      SempodsClientException("GET … failed: HTTP 404", 404)
    val result = run("get_property_values", """{"subject_iri":"$thing","predicate_iri":"https://schema.org/name"}""") as Map<*, *>
    assertEquals(listOf("subject_iri", "predicate_iri", "values"), result.keys.toList())
    assertEquals(0, (result["values"] as JsonNode).size())
  }

  @Test
  fun `any other slot-read failure still propagates`() {
    every { wire.getPropertyValues(any(), any(), any(), any(), any()) } throws
      SempodsClientException("GET … failed: HTTP 403", 403)
    assertThrows<SempodsClientException> {
      run("get_property_values", """{"subject_iri":"$thing","predicate_iri":"https://schema.org/name"}""")
    }
  }

  @Test
  fun `an idempotent write lifts the pod's outcome out of the body`() {
    // The tool descriptions promise `outcome` by name ("the second call returns
    // `outcome=already_present`"), and it is the one thing an idempotent status cannot say.
    every { wire.clearPropertyValues(any(), any(), any(), any(), any(), any()) } returns
      PodWriteResult(200, null, mapper.readTree("""{"outcome":"already_empty"}"""))
    val result = run(
      "clear_property_values",
      """{"target":"$pod","context_iri":"$ctx","subject_iri":"$thing","predicate_iri":"https://schema.org/name"}""",
    ) as Map<*, *>
    assertEquals("already_empty", result["outcome"])
    // The whole body still travels: `outcome` is a summary, not a replacement.
    assertEquals(mapper.readTree("""{"outcome":"already_empty"}"""), result["response"])
  }

  @Test
  fun `a write echoes the ids it addressed, then what the pod answered`() {
    every { wire.createResource(any(), any(), any(), any(), any(), any()) } returns PodWriteResult(201, "\"v1\"", null)
    val result = run(
      "create_resource",
      """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld":{"@id":"$thing"}}""",
    ) as Map<*, *>
    verify { wire.createResource(pod, URI.create(ctx), URI.create(thing), any(), null, "tok") }
    // Key order is the JSON order the caller reads: what was addressed, then what happened.
    assertEquals(listOf("context_iri", "resource_iri", "status", "etag"), result.keys.toList())
    assertEquals(201, result["status"])
  }

  @Test
  fun `create_resource sets the id it was addressed with, present or not`() {
    // A JSON-LD body without `@id` expands to a blank node, and the pod refuses blank nodes with a
    // 400 that talks about RDF rather than about the field the caller left out — the single most
    // common shape a model produces. A body naming a different `@id` is a caller contradicting its
    // own `resource_iri`, and the argument wins, because it is what the result echoes back.
    every { wire.createResource(any(), any(), any(), any(), any(), any()) } returns PodWriteResult(201, null, null)

    run("create_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld":{"https://schema.org/name":"Ada"}}""")
    verify { wire.createResource(any(), any(), any(), match { it.path("@id").asText() == thing }, any(), any()) }

    run("create_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld":{"@id":"https://elsewhere.example/x"}}""")
    verify(exactly = 2) { wire.createResource(any(), any(), any(), match { it.path("@id").asText() == thing }, any(), any()) }
  }

  @Test
  fun `a pod response body rides along as response, and a missing etag simply is not there`() {
    every { wire.addPropertyValue(any(), any(), any(), any(), any(), any(), any()) } returns
      PodWriteResult(200, null, mapper.readTree("""{"outcome":"already_present"}"""))
    val result = run(
      "add_property_value",
      """{"target":"$pod","context_iri":"$ctx","subject_iri":"$thing","predicate_iri":"https://schema.org/name","value":{"@value":"Ada"}}""",
    ) as Map<*, *>
    // Key order is the JSON order: what was addressed, what happened in one word, then the detail.
    assertEquals(
      listOf("context_iri", "subject_iri", "predicate_iri", "outcome", "status", "response"),
      result.keys.toList(),
    )
  }

  @Test
  fun `remove_property_value carries the edge it removes and takes no precondition`() {
    every { wire.removePropertyValue(any(), any(), any(), any(), any(), any()) } returns PodWriteResult(200, null, null)
    val result = run(
      "remove_property_value",
      """{"target":"$pod","context_iri":"$ctx","subject_iri":"$thing","predicate_iri":"https://schema.org/about","target_iri":"$ctx"}""",
    ) as Map<*, *>
    assertEquals(listOf("context_iri", "subject_iri", "predicate_iri", "target_iri", "status"), result.keys.toList())
  }

  // --- preconditions -----------------------------------------------------------------------------

  @Test
  fun `a precondition is normalized to something the pod's header parser accepts`() {
    every { wire.updateResource(any(), any(), any(), any(), any(), any()) } returns PodWriteResult(200, null, null)
    // A bare token is the case that matters: forwarded unquoted the pod drops the header and
    // proceeds UNCONDITIONALLY, silently losing the lost-update protection the caller asked for.
    for ((given, forwarded) in listOf("v1" to "\"v1\"", "\"v1\"" to "\"v1\"", "W/\"v1\"" to "W/\"v1\"", "*" to "*")) {
      val ifMatch = mapper.writeValueAsString(given)
      run("update_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld_patch":{},"if_match":$ifMatch}""")
      verify { wire.updateResource(pod, any(), any(), any(), forwarded, "tok") }
    }
  }

  @Test
  fun `a precondition that cannot become an entity-tag is refused rather than dropped`() {
    assertEquals(
      "if_match is not a valid ETag (use the `etag` from a read, or \"*\"): ",
      refusal("update_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld_patch":{},"if_match":""}"""),
    )
    assertEquals(
      "if_none_match must be \"*\" or a valid ETag: a\"b",
      refusal("create_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld":{},"if_none_match":"a\"b"}"""),
    )
  }

  // --- the IRI rule ------------------------------------------------------------------------------

  @Test
  fun `every IRI argument must be absolute — on the reads as much as on the writes`() {
    // The reads used to skip this and pay for it: the value blew up on URI parsing inside the
    // fan-out and came back as "the pod failed", which it had not.
    assertEquals(
      "resource_iri must be an absolute IRI: not-an-iri",
      refusal("get_resource", """{"resource_iri":"not-an-iri"}"""),
    )
    assertEquals(
      "context_iri must be an absolute IRI: relative/path",
      refusal("find", """{"text":"x","context_iri":["relative/path"]}"""),
    )
    assertEquals(
      "context_iri must be an absolute IRI: nope",
      refusal("create_resource", """{"target":"$pod","context_iri":"nope","resource_iri":"$thing","jsonld":{}}"""),
    )
  }

  @Test
  fun `a prefixed IRI is absolute and stays legal`() {
    every { wire.find(any(), any(), any(), any(), any(), any(), any()) } returns mapper.readTree("[]")
    run("find", """{"text":"x","type":["schema:Person"]}""")
    verify { wire.find(pod, "x", listOf(URI.create("schema:Person")), emptyList(), false, null, "tok") }
  }

  // --- the schema is the first gate ---------------------------------------------------------------

  @Test
  fun `the advertised schema is enforced before anything is parsed`() {
    assertEquals("unknown argument: 'filter'", refusal("find", """{"text":"x","filter":"y"}"""))
    assertEquals("missing required argument: query", refusal("sparql_select", "{}"))
    assertEquals("argument 'limit' must be of type integer", refusal("find", """{"text":"x","limit":"5"}"""))
  }

  @Test
  fun `a blank required string is refused where the schema cannot see it`() {
    assertEquals("missing or blank required argument: text", refusal("find", """{"text":"   "}"""))
    assertEquals("missing required argument: query", refusal("sparql_select", """{"query":"  "}"""))
  }

  // --- what this executor is not ------------------------------------------------------------------

  @Test
  fun `list_pods and authorize are each surface's own, not a pod call`() {
    // Both exist on the hosted surface; neither addresses a pod, so neither has a branch here.
    assertIs<PodToolPlan.UnknownTool>(hosted.plan("list_pods", null))
    assertIs<PodToolPlan.UnknownTool>(hosted.plan("authorize", null))
    assertEquals("Unknown tool: list_pods", (hosted.plan("list_pods", null) as PodToolPlan.UnknownTool).message)
  }

  @Test
  fun `the executor maps exactly the pod tools the catalog carries beside list_pods`() {
    val catalog = ToolCatalog.of(ToolVariant.MULTI_POD)
    assertEquals(
      (catalog.readToolNames + catalog.writeToolNames - "list_pods").sorted(),
      PodToolExecutor.TOOL_NAMES.sorted(),
    )
    // And the single-pod variant carries the thirteen and nothing else.
    val single = ToolCatalog.of(ToolVariant.SINGLE_POD)
    assertEquals(PodToolExecutor.TOOL_NAMES.sorted(), (single.readToolNames + single.writeToolNames).sorted())
  }

  @Test
  fun `the fan-out vocabulary exists only in the multi-pod variant`() {
    every { wire.listContexts(any(), any()) } returns mapper.readTree("{}")
    assertIs<PodToolPlan.Call>(hosted.plan("list_contexts", args("""{"targets":["$pod"]}""")))
    assertEquals(
      "unknown argument: 'targets'",
      (perPod.plan("list_contexts", args("""{"targets":["$pod"]}""")) as PodToolPlan.InvalidArguments).message,
    )
    assertEquals(
      "missing required argument: target",
      (hosted.plan("delete_resource", args("""{"context_iri":"$ctx","resource_iri":"$thing"}""")) as PodToolPlan.InvalidArguments).message,
    )
  }

  // --- failures belong to the caller ---------------------------------------------------------------

  @Test
  fun `a pod failure propagates instead of becoming a result`() {
    // Deliberate: on the hosted side this runs inside `podIo` on a virtual thread, where a cancelled
    // coroutine arrives as an ordinary socket failure. An executor that turned exceptions into
    // envelopes here would answer a cancelled request with a well-formed "the pod failed".
    every { wire.listContexts(any(), any()) } throws SempodsClientException("GET … failed: HTTP 502", 502)
    val thrown = assertThrows<SempodsClientException> { run("list_contexts", null) }
    assertEquals(502, thrown.statusCode)
  }

  @Test
  fun `an anonymous call carries no bearer`() {
    // The pod-immanent surface serves public contexts without a token; nullable is not an oversight.
    every { wire.listContexts(pod, null) } returns mapper.readTree("{}")
    (perPod.plan("list_contexts", null) as PodToolPlan.Call).execute(pod, null)
    verify { wire.listContexts(pod, null) }
  }

  // --- helpers -------------------------------------------------------------------------------------

  private fun args(json: String): JsonNode = mapper.readTree(json)

  /** Plans against the hosted (multi-pod) catalog and runs the call against [pod] with a bearer. */
  private fun run(tool: String, json: String?): Any? =
    when (val plan = hosted.plan(tool, json?.let(::args))) {
      is PodToolPlan.Call -> plan.execute(pod, "tok")
      is PodToolPlan.InvalidArguments -> fail("expected a call, got: ${plan.message}")
      is PodToolPlan.UnknownTool -> fail("expected a call, got: ${plan.message}")
    }

  private fun refusal(tool: String, json: String?): String =
    when (val plan = hosted.plan(tool, json?.let(::args))) {
      is PodToolPlan.InvalidArguments -> plan.message
      is PodToolPlan.UnknownTool -> plan.message
      is PodToolPlan.Call -> fail("expected a refusal, got a call")
    }
}
