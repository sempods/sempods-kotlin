package org.sempods.mcp.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariants that must hold for **every** variant. Anything asserted here is a property of the tool
 * surface as such, not of one of the two MCP endpoints.
 */
class ToolCatalogTest {

  private val mapper = jacksonObjectMapper()
  private fun args(json: String) = mapper.readTree(json)

  private fun allTools(catalog: ToolCatalog) = catalog.readTools() + catalog.writeTools()

  @Test
  fun `the advertised names are exactly the declared ones`() {
    for (variant in ToolVariant.entries) {
      val catalog = ToolCatalog.of(variant)
      assertEquals(catalog.readToolNames, catalog.readTools().map { it.name }.toSet(), "$variant reads")
      assertEquals(catalog.writeToolNames, catalog.writeTools().map { it.name }.toSet(), "$variant writes")
    }
  }

  @Test
  fun `read and write tool names are disjoint`() {
    // Both endpoints route read names before write names while `schemaFor`/`validate` resolve both
    // — an overlap would validate against one schema and dispatch to the other. `ToolCatalog`
    // asserts this itself when the catalog is first built; touching both variants here is what
    // makes that assertion run for the single-pod one too, which does not call validate() yet.
    for (variant in ToolVariant.entries) {
      val catalog = ToolCatalog.of(variant)
      assertEquals(emptySet(), catalog.readToolNames intersect catalog.writeToolNames, variant.name)
    }
  }

  @Test
  fun `every tool schema is a closed object`() {
    for (variant in ToolVariant.entries) {
      for (tool in allTools(ToolCatalog.of(variant))) {
        assertEquals("object", tool.inputSchema.type, "$variant ${tool.name}")
        assertEquals(false, tool.inputSchema.additionalProperties, "$variant ${tool.name} must reject unknown args")
      }
    }
  }

  @Test
  fun `every array argument declares its element type`() {
    // Without `items` an array argument says nothing about its contents, and `validate` cannot
    // reject `targets:[5]` — which would then be dropped downstream and fail open to "no filter".
    for (variant in ToolVariant.entries) {
      for (tool in allTools(ToolCatalog.of(variant))) {
        for ((field, property) in tool.inputSchema.properties) {
          if (property.type == "array") {
            assertNotNull(property.items, "$variant ${tool.name}.$field must declare items")
          }
        }
      }
    }
  }

  @Test
  fun `every required argument is a declared one`() {
    for (variant in ToolVariant.entries) {
      for (tool in allTools(ToolCatalog.of(variant))) {
        val undeclared = tool.inputSchema.required - tool.inputSchema.properties.keys
        assertEquals(emptyList(), undeclared, "$variant ${tool.name} requires arguments it does not declare")
      }
    }
  }

  @Test
  fun `the write tools are the seven mutating ones, in both variants`() {
    for (variant in ToolVariant.entries) {
      assertEquals(
        setOf(
          "create_resource", "update_resource", "delete_resource",
          "add_property_value", "set_property_values", "remove_property_value", "clear_property_values",
        ),
        ToolCatalog.of(variant).writeToolNames,
        variant.name,
      )
    }
  }

  @Test
  fun `every write tool requires a single context_iri`() {
    // A write names one context, in both variants — the pod resolves it against its own registry
    // and enforces `<context_iri>#write`. What differs is only whether a pod has to be named too.
    for (variant in ToolVariant.entries) {
      for (tool in ToolCatalog.of(variant).writeTools()) {
        assertTrue("context_iri" in tool.inputSchema.required, "$variant ${tool.name}")
        assertEquals("string", tool.inputSchema.properties.getValue("context_iri").type, "$variant ${tool.name}")
      }
    }
  }

  @Test
  fun `only the multi-pod variant carries a pod selector, and list_pods carries none`() {
    val multi = ToolCatalog.of(ToolVariant.MULTI_POD)
    for (tool in multi.readTools()) {
      if (tool.name == "list_pods") {
        // It answers from this service's own state and names the pods the others filter by, so a
        // `targets` on it would be circular.
        assertTrue(tool.inputSchema.properties.isEmpty(), "list_pods takes no arguments")
      } else {
        assertTrue("targets" in tool.inputSchema.properties, "${tool.name} must expose targets")
      }
    }
    for (tool in multi.writeTools()) {
      assertTrue("target" in tool.inputSchema.required, "${tool.name} must require a single target")
    }

    val single = ToolCatalog.of(ToolVariant.SINGLE_POD)
    for (tool in single.readTools() + single.writeTools()) {
      assertTrue("targets" !in tool.inputSchema.properties, "${tool.name} must not offer a pod filter")
      assertTrue("target" !in tool.inputSchema.properties, "${tool.name} must not name a pod")
    }
  }

  @Test
  fun `the context-addressed read tools advertise context_iri as a string array`() {
    // Passed through to the pod as the SPARQL-protocol dataset params (default-graph-uri /
    // named-graph-uri); the pod applies {requested} ∩ readable, so it only ever narrows.
    for (variant in ToolVariant.entries) {
      val catalog = ToolCatalog.of(variant)
      for (name in listOf("sparql_select", "sparql_graph", "get_resource", "find", "get_property_values")) {
        val property = catalog.readTools().first { it.name == name }.inputSchema.properties["context_iri"]
        assertEquals("array", property?.type, "$variant $name")
        assertEquals("string", property?.items?.type, "$variant $name")
      }
    }
  }

  @Test
  fun `no tool description claims a restriction that is not applied`() {
    // Tool descriptions are the contract with the model on the other end: it reads them and acts on
    // them. A stale one is worse than a stale code comment — it steers behaviour. The claim this
    // guards against is that the pod's `_system` / `.well-known` area is off limits for a
    // `resource_iri`. Neither surface applies it: the hosted service forwards and the pod decides,
    // and the pod allows a statement *about* a control-plane IRI on purpose (see
    // `parseResourceUriOrThrow`, and `create_resource may describe a control-plane IRI like any
    // other` in `McpEndpointHttpTest`).
    //
    // A whole-catalog sweep rather than a per-tool check, so a description added later cannot
    // reintroduce the claim somewhere nobody thought to look.
    for (variant in ToolVariant.entries) {
      val descriptions = allTools(ToolCatalog.of(variant)).flatMap { tool ->
        listOf("${tool.name}: ${tool.description}") +
          tool.inputSchema.properties.map { (arg, p) -> "${tool.name}.$arg: ${p.description}" }
      }
      val offenders = descriptions.filter { line ->
        val lower = line.lowercase()
        ("_system" in lower || ".well-known" in lower) &&
          listOf("reject", "refus", "not allowed", "forbidden", "exclu").any { it in lower }
      }
      assertTrue(
        offenders.isEmpty(),
        "$variant: a description promises a restriction that is not applied:\n" + offenders.joinToString("\n"),
      )
    }
  }

  // --- validate() enforces the advertised schema, whichever variant declared it ---

  @Test
  fun `valid arguments pass validation`() {
    val single = ToolCatalog.of(ToolVariant.SINGLE_POD)
    assertNull(single.validate("find", args("""{"text":"berlin","limit":5,"type":["https://schema.org/Event"]}""")))
    assertNull(single.validate("list_contexts", null))

    val multi = ToolCatalog.of(ToolVariant.MULTI_POD)
    assertNull(multi.validate("find", args("""{"text":"berlin","targets":["https://sempods.org/alice"]}""")))
    assertNull(multi.validate("list_pods", null))
  }

  @Test
  fun `an unknown argument is rejected`() {
    for (variant in ToolVariant.entries) {
      val catalog = ToolCatalog.of(variant)
      assertNotNull(catalog.validate("sparql_select", args("""{"query":"SELECT * WHERE{?s ?p ?o}","bogus":["x"]}""")), variant.name)
      assertNotNull(catalog.validate("list_contexts", args("""{"bogus":true}""")), variant.name)
    }
  }

  @Test
  fun `a wrongly-typed argument is rejected`() {
    for (variant in ToolVariant.entries) {
      val catalog = ToolCatalog.of(variant)
      assertNotNull(catalog.validate("find", args("""{"text":"berlin","context_iri":"not-an-array"}""")), variant.name)
      assertNotNull(catalog.validate("find", args("""{"text":"berlin","limit":"5"}""")), variant.name)
    }
  }

  @Test
  fun `a missing required argument is rejected`() {
    for (variant in ToolVariant.entries) {
      val catalog = ToolCatalog.of(variant)
      assertNotNull(catalog.validate("sparql_select", args("""{}""")), variant.name)
      assertNotNull(catalog.validate("get_property_values", args("""{"subject_iri":"https://x/s"}""")), variant.name)
    }
  }

  @Test
  fun `an array argument with a non-string element is rejected, not silently dropped`() {
    // The fail-open this guards: `type:[5]` must not pass and fall back to an unfiltered search.
    for (variant in ToolVariant.entries) {
      val catalog = ToolCatalog.of(variant)
      assertNotNull(catalog.validate("find", args("""{"text":"x","type":[5]}""")), variant.name)
      assertNotNull(
        catalog.validate("get_resource", args("""{"resource_iri":"https://x","context_iri":[""]}""")),
        "$variant: a blank element is rejected",
      )
      assertNull(catalog.validate("get_resource", args("""{"resource_iri":"https://x","context_iri":["https://x/c"]}""")), variant.name)
    }
    // The multi-pod selector is the same rule applied to the pod list.
    assertNotNull(ToolCatalog.of(ToolVariant.MULTI_POD).validate("list_contexts", args("""{"targets":[5]}""")))
  }

  @Test
  fun `an explicit null for an optional argument is rejected, not treated as absent`() {
    // null for a filter-like argument must not fall through to "no filter".
    for (variant in ToolVariant.entries) {
      val catalog = ToolCatalog.of(variant)
      assertNotNull(catalog.validate("get_resource", args("""{"resource_iri":"https://x","context_iri":null}""")), variant.name)
      assertNotNull(catalog.validate("find", args("""{"text":"x","include_contexts":null}""")), variant.name)
    }
    assertNotNull(ToolCatalog.of(ToolVariant.MULTI_POD).validate("list_contexts", args("""{"targets":null}""")))
  }

  @Test
  fun `write-tool validation enforces the advertised schema`() {
    val multi = ToolCatalog.of(ToolVariant.MULTI_POD)
    assertNull(multi.validate("create_resource", args("""{"target":"https://p","context_iri":"https://p/c","resource_iri":"https://p/x","jsonld":{"@id":"https://p/x"}}""")))
    assertNotNull(multi.validate("create_resource", args("""{"context_iri":"https://p/c","resource_iri":"https://p/x","jsonld":{}}""")), "missing target must be rejected")
    assertNotNull(multi.validate("create_resource", args("""{"target":"https://p","context_iri":"https://p/c","resource_iri":"https://p/x","jsonld":{},"bogus":1}""")), "unknown arg must be rejected")
    assertNotNull(multi.validate("add_property_value", args("""{"target":"https://p","context_iri":"https://p/c","subject_iri":"https://p/s","predicate_iri":"https://p/pr"}""")), "missing value must be rejected")

    val single = ToolCatalog.of(ToolVariant.SINGLE_POD)
    assertNull(single.validate("create_resource", args("""{"context_iri":"https://p/c","resource_iri":"https://p/x","jsonld":{"@id":"https://p/x"}}""")))
    assertNotNull(single.validate("create_resource", args("""{"target":"https://p","context_iri":"https://p/c","resource_iri":"https://p/x","jsonld":{}}""")), "a single-pod surface has no target argument")
  }

  @Test
  fun `an array element of the wrong declared type is rejected`() {
    // `values` declares `items.type = "object"`. Without enforcing it, `values:[1]` passed the array
    // check, reached the pod, came back a 400, and was classified as a pod failure rather than as
    // the invalid argument it is. A declared `items` the validator ignores is a promise not kept.
    for (variant in ToolVariant.entries) {
      val catalog = ToolCatalog.of(variant)
      val target = if (variant == ToolVariant.MULTI_POD) """"target":"https://p",""" else ""
      val base = """$target"context_iri":"https://p/c","subject_iri":"https://p/s","predicate_iri":"https://p/pr""""
      assertNotNull(catalog.validate("set_property_values", args("{$base,\"values\":[1]}")), variant.name)
      assertNotNull(catalog.validate("set_property_values", args("{$base,\"values\":[\"x\"]}")), variant.name)
      assertNull(catalog.validate("set_property_values", args("{$base,\"values\":[{\"@value\":\"x\"}]}")), variant.name)
      assertNull(catalog.validate("set_property_values", args("{$base,\"values\":[]}")), "$variant: an empty array clears the slot")
    }
  }

  @Test
  fun `a worked example carries every argument its variant requires`() {
    // The write descriptions say "copy this shape". An example missing a required argument produces
    // a call `validate` refuses before it reaches a pod — which is what happened when the pod's
    // examples became the shared text and the hosted variant's required `target` was not in them.
    for (variant in ToolVariant.entries) {
      val catalog = ToolCatalog.of(variant)
      for (tool in catalog.writeTools()) {
        val example = tool.description.substringAfter("WORKING EXAMPLE", "").ifEmpty { continue }
        for (required in tool.inputSchema.required) {
          assertTrue(
            "\"$required\"" in example,
            "$variant ${tool.name}: the worked example omits the required `$required`",
          )
        }
      }
    }
  }

  @Test
  fun `a description only claims to take no arguments when it takes none`() {
    // The generalised form of the same slip as the worked examples: a sentence that was true on the
    // surface it was written for and false on the one it was merged into. `list_contexts` says
    // "Takes no arguments" on the pod, where it is true, and carries `targets` on the hosted
    // service, where it is not — a model reading the prose would omit a restriction it was asked
    // for and fan the call out to every connected pod.
    for (variant in ToolVariant.entries) {
      for (tool in allTools(ToolCatalog.of(variant))) {
        if ("takes no arguments" in tool.description.lowercase()) {
          assertTrue(
            tool.inputSchema.properties.isEmpty(),
            "$variant ${tool.name}: says it takes no arguments but declares " +
              tool.inputSchema.properties.keys.sorted().joinToString(", "),
          )
        }
      }
    }
  }

  @Test
  fun `an unknown tool is refused rather than waved through`() {
    // The guard has to be safe on its own: `PodToolExecutor` calls it before dispatch, so a name
    // the catalog does not carry must not come back as "these arguments are fine".
    assertEquals(
      "Unknown tool: no_such_tool",
      ToolCatalog.of(ToolVariant.MULTI_POD).validate("no_such_tool", args("""{"anything":1}""")),
    )
    // A tool the *other* variant carries is unknown here, which is what keeps `list_pods` from
    // reaching a single-pod surface.
    assertEquals(
      "Unknown tool: list_pods",
      ToolCatalog.of(ToolVariant.SINGLE_POD).validate("list_pods", null),
    )
  }

  @Test
  fun `schemaFor answers only for tools the variant carries`() {
    assertNotNull(ToolCatalog.of(ToolVariant.MULTI_POD).schemaFor("list_pods"))
    assertNull(ToolCatalog.of(ToolVariant.SINGLE_POD).schemaFor("list_pods"))
    assertNull(ToolCatalog.of(ToolVariant.SINGLE_POD).schemaFor("authorize"), "authorize is each surface's own")
  }
}
