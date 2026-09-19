package org.sempods.mcp.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.sempods.client.core.SempodsOkHttp
import org.slf4j.event.Level
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What [PodToolExecutor] makes of a `tools/call`: the argument-to-route mapping, the shape of the
 * result, and every refusal that keeps a call from reaching a pod at all.
 *
 * **Served rather than stubbed.** The executor now speaks the client core's endpoint groups, and a
 * stub of those would be a stub of the thing under test: what the mapping *is* is the request that
 * leaves — the path, the base64url slot segments, the context parameter, the content type and the
 * precondition header. A MockServer pod is what can be asked about those.
 */
class PodToolExecutorTest {

  private val mapper = jacksonObjectMapper()
  private val hosted = PodToolExecutor(ToolCatalog.of(ToolVariant.MULTI_POD))
  private val perPod = PodToolExecutor(ToolCatalog.of(ToolVariant.SINGLE_POD))

  private val calls: OkHttpClient = SempodsOkHttp.install(OkHttpClient.Builder(), admission = null).build()

  private lateinit var server: ClientAndServer
  private lateinit var pod: String

  private val ctx = "https://sempods.org/alice/main"
  private val thing = "https://sempods.org/alice/thing"
  private val name = "https://schema.org/name"

  @BeforeEach
  fun setup() {
    server = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    pod = "http://localhost:${server.port}/alice"
  }

  @AfterEach
  fun teardown() = server.stop()

  // --- the mapping -------------------------------------------------------------------------------

  @Test
  fun `list_contexts is the whole call — no arguments to map`() {
    answer("GET", "/alice/_system/contexts", body = """{"contexts":[]}""")

    assertEquals(mapper.readTree("""{"contexts":[]}"""), run("list_contexts", null))

    // Both media types, for a pod that still answers the pre-SPS-CTX-033 envelope as plain JSON.
    val recorded = server.retrieveRecordedRequests(request().withPath("/alice/_system/contexts")).last()
    assertEquals("application/ld+json, application/json", recorded.getFirstHeader("Accept"))
    assertEquals("Bearer tok", recorded.getFirstHeader("Authorization"))
  }

  @Test
  fun `a SPARQL tool passes the query verbatim and the context filter as IRIs`() {
    answer("POST", "/alice/_system/sparql/query", body = """{"head":{}}""")

    run("sparql_select", """{"query":"SELECT * WHERE {?s ?p ?o}","context_iri":["$ctx"]}""")

    val sent = server.retrieveRecordedRequests(request().withPath("/alice/_system/sparql/query")).last()
    // Raw bytes: MockServer base64s a body whose content type it does not read as text.
    assertEquals("SELECT * WHERE {?s ?p ?o}", String(sent.bodyAsRawBytes, Charsets.UTF_8))
    assertEquals("application/sparql-query", sent.getFirstHeader("Content-Type"))
    assertEquals("application/sparql-results+json", sent.getFirstHeader("Accept"))
    // Each context in both dataset parameters, so a narrowed query keeps its reach inside them.
    server.verify(
      request().withPath("/alice/_system/sparql/query")
        .withQueryStringParameter("default-graph-uri", ctx)
        .withQueryStringParameter("named-graph-uri", ctx),
    )
  }

  @Test
  fun `sparql_graph and sparql_select share a schema but not a call`() {
    answer("POST", "/alice/_system/sparql/query", body = """{"@graph":[]}""")

    run("sparql_graph", """{"query":"CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}"}""")

    val sent = server.retrieveRecordedRequests(request().withPath("/alice/_system/sparql/query")).last()
    // The tool is the content negotiation: the pod answers 406 to a SELECT asked for as a graph.
    assertEquals("application/ld+json", sent.getFirstHeader("Accept"))
    assertTrue(sent.queryStringParameterList.isEmpty(), "no selection means no dataset parameters")
  }

  @Test
  fun `find maps every optional argument, and an absent one stays absent`() {
    answer("POST", "/alice/_system/find", body = "[]")

    run("find", """{"text":"kita","type":["https://schema.org/Event"],"context_iri":["$ctx"],"include_contexts":true,"limit":5}""")
    assertEquals(
      mapper.readTree("""{"text":"kita","type":["https://schema.org/Event"],"contexts":["$ctx"],"include_contexts":true,"limit":5}"""),
      lastFindPayload(),
    )

    run("find", """{"text":"kita"}""")
    assertEquals(mapper.readTree("""{"text":"kita"}"""), lastFindPayload())
  }

  @Test
  fun `find clamps limit to the range the catalog advertises`() {
    answer("POST", "/alice/_system/find", body = "[]")
    // Out of range in either direction is clamped rather than refused or passed on raw: the schema
    // says 1..100, and a 0 that reached the pod would come back as its 400, not as our refusal.
    run("find", """{"text":"x","limit":0}""")
    assertEquals(1, lastFindPayload()["limit"].asInt())
    run("find", """{"text":"x","limit":5000}""")
    assertEquals(100, lastFindPayload()["limit"].asInt())
  }

  @Test
  fun `get_resource of one context answers the iri, its etag and the document`() {
    answer("GET", "/alice/_system/resources/${b64(thing)}", body = """{"@id":"$thing"}""", etag = "\"v1\"")

    val result = run("get_resource", """{"resource_iri":"$thing","context_iri":["$ctx"]}""") as Map<*, *>

    server.verify(request().withMethod("GET").withPath("/alice/_system/resources/${b64(thing)}").withQueryStringParameter("context", ctx))
    assertEquals(listOf("resource_iri", "etag", "jsonld"), result.keys.toList())
    assertEquals(thing, result["resource_iri"])
    assertEquals("\"v1\"", result["etag"])
  }

  @Test
  fun `get_resource of every context withholds the etag, in either form, with one read`() {
    // A tag validates writes to the context it was read from, and a union read names none. Handed
    // out, it would come back as `if_match` and fail.
    answer("GET", "/alice/_system/resources/${b64(thing)}", body = """{"@graph":[]}""", etag = "\"v1-contexts\"")

    val result = run("get_resource", """{"resource_iri":"$thing","include_contexts":true}""") as Map<*, *>

    assertEquals(listOf("resource_iri", "jsonld"), result.keys.toList())
    assertEquals(mapper.readTree("""{"@graph":[]}"""), result["jsonld"])
    server.verify(request().withPath("/alice/_system/resources/${b64(thing)}").withQueryStringParameter("include_contexts", "true"))
    assertEquals(1, server.retrieveRecordedRequests(request().withPath("/alice/_system/resources/${b64(thing)}")).size)
  }

  @Test
  fun `a read of a resource that is not there is a refusal`() {
    // The route lists that 404 as an answer, so it arrives without a body — and a resource either
    // exists or does not, which is the answer this tool was asked for.
    answer("GET", "/alice/_system/resources/${b64(thing)}", status = 404, body = "")

    val refused = assertThrows<PodToolRefusal> { run("get_resource", """{"resource_iri":"$thing"}""") }

    assertEquals(404, refused.status)
  }

  @Test
  fun `get_property_values answers the slot coordinates, its values and the slot etag`() {
    answer("GET", "/alice/_system/resources/${b64(thing)}/${b64(name)}", body = """[{"@value":"Ada"}]""", etag = "\"v7\"")

    val result = run(
      "get_property_values",
      """{"subject_iri":"$thing","predicate_iri":"$name","context_iri":["$ctx"]}""",
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
    answer("GET", "/alice/_system/resources/${b64(thing)}/${b64(name)}", body = """[{"@value":"Ada"}]""")

    val result = run("get_property_values", """{"subject_iri":"$thing","predicate_iri":"$name"}""") as Map<*, *>

    assertEquals(listOf("subject_iri", "predicate_iri", "values"), result.keys.toList())
    assertFalse(result.containsKey("etag"))
  }

  @Test
  fun `an empty slot is no values, not a failure`() {
    // The route has no representation for an empty slot and answers 404 — the same 404 it answers
    // for a context the caller may not read, which must stay indistinguishable. Asked what the
    // values are, "none" is the answer; an error here would read to a model as a broken call.
    answer("GET", "/alice/_system/resources/${b64(thing)}/${b64(name)}", status = 404, body = "")

    val result = run("get_property_values", """{"subject_iri":"$thing","predicate_iri":"$name"}""") as Map<*, *>

    assertEquals(listOf("subject_iri", "predicate_iri", "values"), result.keys.toList())
    assertEquals(0, (result["values"] as JsonNode).size())
  }

  @Test
  fun `any other slot-read failure still propagates`() {
    answer("GET", "/alice/_system/resources/${b64(thing)}/${b64(name)}", status = 403, body = """{"errors":[{"message":"no"}]}""")

    val refused = assertThrows<PodToolRefusal> {
      run("get_property_values", """{"subject_iri":"$thing","predicate_iri":"$name"}""")
    }

    assertEquals(403, refused.status)
    // A status no route lists keeps its body, which is the sentence a model is shown.
    assertEquals("""{"errors":[{"message":"no"}]}""", refused.reason)
  }

  @Test
  fun `an idempotent write lifts the pod's outcome out of the body`() {
    // The tool descriptions promise `outcome` by name ("the second call returns
    // `outcome=already_present`"), and it is the one thing an idempotent status cannot say.
    answer("DELETE", "/alice/_system/resources/${b64(thing)}/${b64(name)}", body = """{"outcome":"already_empty"}""")

    val result = run(
      "clear_property_values",
      """{"target":"$pod","context_iri":"$ctx","subject_iri":"$thing","predicate_iri":"$name"}""",
    ) as Map<*, *>

    assertEquals("already_empty", result["outcome"])
    // The whole body still travels: `outcome` is a summary, not a replacement.
    assertEquals(mapper.readTree("""{"outcome":"already_empty"}"""), result["response"])
  }

  @Test
  fun `a write echoes the ids it addressed, then what the pod answered`() {
    answer("PUT", "/alice/_system/resources/${b64(thing)}", status = 201, body = "", etag = "\"v1\"")

    val result = run(
      "create_resource",
      """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld":{"@id":"$thing"}}""",
    ) as Map<*, *>

    val sent = server.retrieveRecordedRequests(request().withMethod("PUT")).last()
    assertEquals(ctx, sent.getFirstQueryStringParameter("context"))
    assertEquals("application/ld+json", sent.getFirstHeader("Content-Type"))
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
    answer("PUT", "/alice/_system/resources/${b64(thing)}", status = 201, body = "")

    run("create_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld":{"$name":"Ada"}}""")
    assertEquals(thing, lastBody("PUT")["@id"].asText())

    run("create_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld":{"@id":"https://elsewhere.example/x"}}""")
    assertEquals(thing, lastBody("PUT")["@id"].asText())
  }

  @Test
  fun `a pod response body rides along as response, and a missing etag simply is not there`() {
    answer("POST", "/alice/_system/resources/${b64(thing)}/${b64(name)}", body = """{"outcome":"already_present"}""")

    val result = run(
      "add_property_value",
      """{"target":"$pod","context_iri":"$ctx","subject_iri":"$thing","predicate_iri":"$name","value":{"@value":"Ada"}}""",
    ) as Map<*, *>

    // Key order is the JSON order: what was addressed, what happened in one word, then the detail.
    assertEquals(
      listOf("context_iri", "subject_iri", "predicate_iri", "outcome", "status", "response"),
      result.keys.toList(),
    )
  }

  @Test
  fun `remove_property_value carries the edge it removes and takes no precondition`() {
    val about = "https://schema.org/about"
    answer("DELETE", "/alice/_system/resources/${b64(thing)}/${b64(about)}/${b64(ctx)}", body = "")

    val result = run(
      "remove_property_value",
      """{"target":"$pod","context_iri":"$ctx","subject_iri":"$thing","predicate_iri":"$about","target_iri":"$ctx"}""",
    ) as Map<*, *>

    assertEquals(listOf("context_iri", "subject_iri", "predicate_iri", "target_iri", "status"), result.keys.toList())
  }

  // --- preconditions -----------------------------------------------------------------------------

  @Test
  fun `a precondition is normalized to something the pod's header parser accepts`() {
    answer("PATCH", "/alice/_system/resources/${b64(thing)}", body = "")
    // A bare token is the case that matters: forwarded unquoted the pod drops the header and
    // proceeds UNCONDITIONALLY, silently losing the lost-update protection the caller asked for.
    for ((given, forwarded) in listOf("v1" to "\"v1\"", "\"v1\"" to "\"v1\"", "W/\"v1\"" to "W/\"v1\"", "*" to "*")) {
      val ifMatch = mapper.writeValueAsString(given)
      run("update_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld_patch":{},"if_match":$ifMatch}""")
      assertEquals(forwarded, server.retrieveRecordedRequests(request().withMethod("PATCH")).last().getFirstHeader("If-Match"))
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

  @Test
  fun `a merge-patch on a resource that is not in the context is a refusal the tool can explain`() {
    // The route lists that 404, so the core closes its body: the words come from `PodToolFailure`.
    answer("PATCH", "/alice/_system/resources/${b64(thing)}", status = 404, body = "")

    val refused = assertThrows<PodToolRefusal> {
      run("update_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld_patch":{}}""")
    }

    assertEquals(404, refused.status)
    assertEquals("", refused.reason)
    assertEquals(
      "the resource does not exist in that context — use create_resource to create it",
      PodToolFailure.detail("update_resource", refused.status, refused.reason),
    )
  }

  @Test
  fun `a create-or-fail on a resource that is already there says which condition failed`() {
    // `create_resource` carries `if_none_match`, never `if_match`: its 412 means the resource is
    // there, and advice to retry with a fresh etag would name an argument it does not take.
    answer("PUT", "/alice/_system/resources/${b64(thing)}", status = 412, body = "")

    val refused = assertThrows<PodToolRefusal> {
      run(
        "create_resource",
        """{"target":"$pod","context_iri":"$ctx","resource_iri":"$thing","jsonld":{},"if_none_match":"*"}""",
      )
    }

    assertEquals(412, refused.status)
    assertEquals(
      "the resource already exists in that context — omit if_none_match to replace it, or use update_resource to merge into it",
      PodToolFailure.detail("create_resource", refused.status, refused.reason),
    )
    // The tools that do carry `if_match` keep the answer that names it.
    assertEquals(
      "if_match is not the resource's current etag — read it again and retry with the etag that read returns",
      PodToolFailure.detail("update_resource", 412, ""),
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
    answer("POST", "/alice/_system/find", body = "[]")

    run("find", """{"text":"x","type":["schema:Person"]}""")

    assertEquals(mapper.readTree("""["schema:Person"]"""), lastFindPayload()["type"])
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
    answer("GET", "/alice/_system/contexts", status = 502, body = "upstream is down")

    val thrown = assertThrows<PodToolRefusal> { run("list_contexts", null) }

    assertEquals(502, thrown.status)
    assertEquals("upstream is down", thrown.reason)
  }

  @Test
  fun `a 2xx whose body is not JSON says so, without the URL it was read from`() {
    // The status cannot carry this one: a `200` reaching `PodToolFailure` with nothing beside it
    // reads as "the pod refused the call", and the word that helps is in the exception's message,
    // which no surface shows because it names the URL.
    answer("GET", "/alice/_system/contexts", body = "<html>not json</html>")

    val refused = assertThrows<PodToolRefusal> { run("list_contexts", null) }

    assertEquals(200, refused.status)
    assertEquals("the pod answered list_contexts with a body that is not JSON", refused.reason)
    assertEquals(
      "the pod answered list_contexts with a body that is not JSON",
      PodToolFailure.detail("list_contexts", refused.status, refused.reason),
    )
    assertFalse(refused.reason.contains("localhost"), "the reason a model reads must not name the URL")
  }

  @Test
  fun `an anonymous call carries no bearer`() {
    // The pod-immanent surface serves public contexts without a token; nullable is not an oversight.
    answer("GET", "/alice/_system/contexts", body = "{}")

    (perPod.plan("list_contexts", null) as PodToolPlan.Call).execute(podAt(pod, null, calls))

    val sent = server.retrieveRecordedRequests(request().withPath("/alice/_system/contexts")).last()
    assertTrue(sent.getHeader("Authorization").isEmpty(), "an anonymous call must send no Authorization header")
  }

  // --- helpers -------------------------------------------------------------------------------------

  private fun b64(iri: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(iri.toByteArray(Charsets.UTF_8))

  private fun answer(method: String, path: String, status: Int = 200, body: String, etag: String? = null) {
    val answer = response().withStatusCode(status).withBody(body, MediaType.parse("application/ld+json"))
    server.`when`(request().withMethod(method).withPath(path))
      .respond(etag?.let { answer.withHeader("ETag", it) } ?: answer)
  }

  private fun lastFindPayload(): JsonNode =
    mapper.readTree(server.retrieveRecordedRequests(request().withPath("/alice/_system/find")).last().bodyAsString)

  private fun lastBody(method: String): JsonNode =
    mapper.readTree(server.retrieveRecordedRequests(request().withMethod(method)).last().bodyAsString)

  private fun args(json: String): JsonNode = mapper.readTree(json)

  /** Plans against the hosted (multi-pod) catalog and runs the call against the pod with a bearer. */
  private fun run(tool: String, json: String?): Any? =
    when (val plan = hosted.plan(tool, json?.let(::args))) {
      is PodToolPlan.Call -> plan.execute(podAt(pod, "tok", calls))
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
