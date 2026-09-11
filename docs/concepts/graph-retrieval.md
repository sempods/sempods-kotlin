# Graph retrieval — resource navigation for any consumer

## Purpose

This document describes the retrieval pattern for any consumer of pod data —
not just AI. It applies wherever a client answers a question or builds a view
from a pod: a website chatbot, a personal AI assistant, an enterprise knowledge
layer, **and** an ordinary non-LLM app (event listing, calendar widget, search
box).

The same `find` and structural traversal serve ordinary applications and AI clients.
The caller owns its working graph and any answer-generation loop.
[Proposed extensions](../proposals/graph-retrieval.md) have a separate design owner.

## The retrieval unit: resources, not chunks

Pod data is structured RDF with:

- **Resources as atomic units** — an event is one entity with all its properties.
- **Explicit links** — `schema:location`, `schema:image`, `schema:offers` are
  URIs that can be dereferenced.
- **Context-based visibility** — the same query returns different depth
  depending on who's asking.
- **Cross-pod linking** — a resource in pod A can link to a resource in pod B
  via URI.

The retrieval unit is therefore a semantic entity — an event with all its
properties — not an arbitrary text fragment. A returned graph is still bounded by authorization and query limits; following a URI
requires a further authorized read and may fail.

## `find` — text → expanded subgraph

`find` is the entry primitive. `SempodsModule` binds `SparqlTextFindAdapter` through
a Multibinder and `SparqlResourceExpander` for the fixed expansion. The interfaces
permit replacement; this deployment has one lexical engine and no engine-selection setting. The caller sends
`text`, never a "mode". The result is **itself RDF** — a CONSTRUCT-compatible
subgraph the caller merges into its working / view graph.

The name is deliberate: `find` ("locate, give me a foothold"), not `search`
(which implies a ranked engine). Different engines can satisfy the same
contract.

```
GET /{pod}/_system/find?text=xyz&type=<iri>&context=<iri>&include_contexts=true&limit=10
POST /{pod}/_system/find  { "text": …, "type": […]?, "contexts": […]?, "include_contexts": …?, "limit": …? }

  text             string       # REQUIRED. Terms OR a natural-language question. How it is interpreted
                                #   (literal match · rewriting · semantic) is the implementation's business.
  type             IRI, repeat  # OPTIONAL. Constrains the returned hit's rdf:type. Repeatable → OR
                                #   (type=schema:Event&type=schema:MusicEvent). Exact match, no subclass reasoning.
  context          IRI, repeat  # OPTIONAL. Read downscope: restrict the search to these contexts (graphs),
                                #   within what the caller may read. Repeatable (context=A&context=B). Absent →
                                #   pod-wide across all readable contexts. Same {requested} ∩ readable /
                                #   silent-exclusion semantics as the LOD read routes. (POST body: `contexts`.)
  include_contexts bool         # OPTIONAL, default false. true → named-graph form: each result statement
                                #   grouped by the context it came from (provenance). Mirrors get_resource.
  limit            int          # optional; default 10, max 100. NO cursor, NO score.

response: an RDF graph (JSON-LD, CONSTRUCT-compatible), context-sandboxed:
  - the found resources
  - expanded where the properties exist:
      · rdf:type + rdfs:label + schema:name where present, plus the adapter's matched edge
  - by default a FULLY FLAT graph: no ordering, no score, no hit/expansion marker. Plain RDF the caller
    merges and traverses. With `include_contexts=true` the same graph is grouped by source context
    (named-graph JSON-LD, or the 4th N-Quads term) so a caller can tell which context a hit lives in.
```

**Contract semantics:**

- The normative contract belongs to [sempods-spec find](https://github.com/sempods/sempods-spec/blob/main/spec/core/find.md).
  This section describes the reference implementation.
- **Optional `type` is a return-type constraint, not a match-scope.** It
  restricts which *hit types* come back (the hit's `rdf:type`), repeatable and
  OR-combined. It does **not** restrict where matching happens: a semantic
  engine may match via linked resources and still return only the requested
  types. `rdf:type` is the one structured facet supported, because every engine
  knows a resource's type cheaply; general predicate filters are [proposed separately](../proposals/graph-retrieval.md). Exact match — a caller ORs known subtypes rather than relying on
  subclass reasoning.
- `FindService` merges registered adapters and expands their hit subjects. When merged
  hits exceed the limit it caps them deterministically by IRI; it has no rank fusion.
  The caller picks no mode.
- **The context sandbox always applies** — the same permission layer as SPARQL
  and CRUD. An anonymous visitor sees public data; an authenticated owner sees
  internal contexts too. Same `find`, different depth.
- **Optional `context` downscope.** A caller may narrow the search to specific
  contexts (graphs) *within* what it may read, via the repeatable `context=<iri>`
  parameter (MCP: `context_iri` array). This is the universal read-downscope the
  LOD read routes already expose ([`SPS-CRUD-014`](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md#SPS-CRUD-014)), **not** the
  proposed general predicate filter: the server intersects `{requested}` with the
  readable set and silently drops unknown/unreadable contexts (no 403/404 — no
  topology leak); an all-unreadable request yields an empty result, not an error.
  Absent → pod-wide within the readable ceiling. The downscope applies to the whole
  find pattern, the type/label/name expansion included. Every engine can honor it
  (it is just a narrower context set), so it is part of the core contract.
- **Optional `include_contexts` exposes provenance.** By default the result is a
  flat graph (context dropped). With `include_contexts=true` each matched/expanded
  statement is grouped by the context it came from — named-graph JSON-LD (the same
  representation `get_resource?include_contexts=true` returns, just multi-subject)
  or the 4th term in N-Quads. This lets a consumer that searched across several
  readable contexts tell results apart by source (e.g. public vs. private) without
  a follow-up read. It changes only the *representation*, never which resources match.
- **GET caching is permission-scoped.** Responses carry `Vary: Accept, Authorization`.
  Authenticated results use `Cache-Control: private, no-store`; anonymous results use
  `public`. This prevents a shared cache from serving one caller's private graph to another.

The `POST` form is the request envelope for any `find` that no longer
fits a URL, such as a downscope onto many contexts.
Its body mirrors the GET parameters as a JSON object, with `contexts` as a nullable
list:

```
POST /{pod}/_system/find
{ "text": "…", "type": [<iri>…]?, "contexts": [<iri>…]?, "include_contexts": false?, "limit": 10? }
```

`contexts` carries the same read-downscope semantics as the GET `context=`
parameter (`{requested} ∩ readable`, silent exclusion); `include_contexts` is the
same provenance switch as the GET parameter. Content negotiation (JSON-LD vs.
N-Quads) is still driven by the `Accept` header.

The body is parsed with a **strict** mapper
([`FindEndpoint`](../../sempods-server/src/main/kotlin/org/sempods/api/pod/system/find/FindEndpoint.kt)
enables `FAIL_ON_UNKNOWN_PROPERTIES`), so an unknown field is a 400 rather than a
silently broadened result — `filter` included, since the general predicate filter
is not a supported field. That is what makes the envelope above copyable as it
stands.

## Structural traversal — `find` is only the entry

Once a first result exists, the caller has a foothold in the graph: URIs and
local structure are visible. From there, traversal is **structural**, using
primitives that already ship today:

- `get_resource` — fetch a full resource by URI.
- `sparql_select` / `sparql_graph` (CONSTRUCT) — the workhorse for following
  relevant neighbours. This *is* "structured expansion"; it subsumes a
  dedicated `expand` primitive.

`find` is re-usable at any point as a **semantic pivot** — to jump to a
different region of meaning when structural traversal runs out. The read model
is therefore an **alternation** between semantic entry (`find`) and structural
traversal (SPARQL / `get_resource`), not a fixed `find → retrieve → expand`
pipeline.

## Consumer orchestration

A consumer can merge returned RDF into a working graph, follow relevant URIs through
`get_resource` or SPARQL, and render a view or generate an answer. That graph and loop
belong to the consumer; the pod stores neither a retrieval session nor an answer model.
A resource IRI pointing elsewhere does not authorize dereferencing it with this pod's
credential. Connect to the target pod with its own authority; hosted MCP performs
[explicit cross-pod selection](hosted-mcp.md#cross-pod-reads-vs-writes).

## Implementation

- [`FindService`](../../sempods-server/src/main/kotlin/org/sempods/retrieval/FindService.kt) — merge and fixed expansion.
- [`FindEndpoint`](../../sempods-server/src/main/kotlin/org/sempods/api/pod/system/find/FindEndpoint.kt) — GET/POST, strict fields and representation.
- [`FindEndpointHttpTest`](../../sempods-server/src/test/kotlin/org/sempods/api/pod/system/find/FindEndpointHttpTest.kt) — HTTP and sandbox contract.
- [Inference proposal](../proposals/inference-context.md) — proposed hierarchy lookup; exact-match retrieval remains current.
