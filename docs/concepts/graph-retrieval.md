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
  limit            int          # optional; an integer, else 400. Default 10, clamped to 1..100.
                                #   NO cursor, NO score.

response: an RDF graph (JSON-LD, CONSTRUCT-compatible), context-sandboxed:
  - the found resources
  - expanded where the properties exist:
      · rdf:type + rdfs:label + schema:name where present, plus the adapter's matched edge
  - an engine's observations, where it supplies any: <hit> sps:findResult _:b, with details on _:b
  - by default one graph without graph names; statement order carries no rank. Plain RDF the caller
    merges and traverses. With `include_contexts=true` every statement is in a named context
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
  hits exceed the limit it caps them by the lowest `schema:position` among a hit's
  observations, then by IRI; it has no rank fusion.
- **An engine that cannot scope itself is checked against the store.** One that is not
  [`selfScoped`](../../sempods-server/src/main/kotlin/org/sempods/retrieval/FindAdapter.kt) — an
  index that may lag behind the store, say — names a hit by an observation placed in the
  selected context it matched in; a match elsewhere is no hit (`SPS-FIND-010`). The hit stays only
  where the store holds it in the selection, and with a requested `type` only where the expansion
  reads that type for it there. Its stored statements stay out: what the answer holds as stored
  comes from the expansion, because the context an index gives a statement cannot be checked. A
  dropped hit
  takes its observations with it; a kept hit keeps them unchecked, since the store holds nothing to
  check a snippet against. The built-in SPARQL adapter is self-scoped.
  Only a candidate a selected context holds counts towards the limit, so a stale
  index entry takes no place. A kept hit keeps the observation nodes it links to, and
  their nested blank nodes. An observation an engine attributes to a context outside
  the selection is dropped.
  The caller picks no mode.
- **An observation lands in a context that holds its hit.** The built-in SPARQL adapter
  supplies none. An engine that does can name the context its match was in; otherwise
  `FindService` puts the observation in a context it knows to hold the hit — the first, by
  IRI, among those the answer and its store lookup show. Each adapter's blank nodes stay its own. The
  [`FindAdapter`](../../sempods-server/src/main/kotlin/org/sempods/retrieval/FindAdapter.kt)
  KDoc says what an adapter may contribute.
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
  statement is grouped by the context it came from, and each observation by the
  context it was placed in — named-graph JSON-LD (the same representation
  `get_resource?include_contexts=true` returns, just multi-subject) or the 4th term
  in N-Quads. No statement is left in the default graph. This lets a consumer that searched across several
  readable contexts tell results apart by source (e.g. public vs. private) without
  a follow-up read. It changes only the *representation*, never which resources match.
- **GET caching is permission-scoped.** Responses carry `Vary: Accept` plus the headers the
  request authenticator reads credentials from (`PodRequestAuthenticator.credentialHeaders`,
  `Authorization` for this server's bearer).
  Authenticated results use `Cache-Control: private, no-store`; anonymous results use
  `public`. This prevents a shared cache from serving one caller's private graph to another.

The `POST` form is the request envelope for any `find` that no longer
fits a URL, such as a downscope onto many contexts.
Its body mirrors the GET parameters as a JSON object; a member is either left out or
given a value:

```
POST /{pod}/_system/find
{ "text": "…", "type": [<iri>…]?, "contexts": [<iri>…]?, "include_contexts": false?, "limit": 10? }
```

`contexts` carries the same read-downscope semantics as the GET `context=`
parameter (`{requested} ∩ readable`, silent exclusion); `include_contexts` is the
same provenance switch as the GET parameter. Content negotiation (JSON-LD vs.
N-Quads) is driven by the `Accept` header, quality values included: `q=0` excludes a
format, a tie goes to JSON-LD, and `application/json` is a 406
([`GraphResultNegotiation`](../../sempods-server/src/main/kotlin/org/sempods/api/system/sparql/GraphResultNegotiation.kt)).

The body is parsed strictly
([`FindPostRequest`](../../sempods-server/src/main/kotlin/org/sempods/api/pod/system/find/FindPostRequest.kt)):
an unknown field is a 400 — `filter` included, since the general predicate filter is
not a supported field. Dropped silently, it would broaden the result. A member of the
wrong JSON type (`"limit":"10"`) or an explicit `null` (`"contexts":null`) is a 400 too.
That is what makes the envelope above
copyable as it stands.

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

- [`FindService`](../../sempods-server/src/main/kotlin/org/sempods/retrieval/FindService.kt) — merge, the store check for adapters that do not scope themselves, cap, observation placement and fixed expansion.
- [`FindEndpoint`](../../sempods-server/src/main/kotlin/org/sempods/api/pod/system/find/FindEndpoint.kt) — GET/POST, strict fields and representation.
- [`FindDownscope`](../../sempods-server/src/main/kotlin/org/sempods/api/pod/system/find/FindDownscope.kt) — the `context` downscope, from the credentials alone.
- [`FindEndpointHttpTest`](../../sempods-server/src/test/kotlin/org/sempods/api/pod/system/find/FindEndpointHttpTest.kt) — HTTP and sandbox contract.
- [`FindSpecExamplesHttpTest`](../../sempods-server/src/test/kotlin/org/sempods/api/pod/system/find/FindSpecExamplesHttpTest.kt) — the specification's examples, served by an engine that annotates its hits.
- [`SempodsPodFind`](../../sempods-client/src/main/kotlin/org/sempods/client/SempodsPodFind.kt) — the JVM client's `pod.find()`, which hosted and pod-immanent MCP call; [`SempodsRdf4jFind`](../../sempods-client-rdf4j/src/main/kotlin/org/sempods/client/rdf4j/SempodsRdf4jFind.kt) reads its answer as a model.
- [Inference proposal](../proposals/inference-context.md) — proposed hierarchy lookup; exact-match retrieval remains current.

## Embedding find

A deployment with its own stores and its own sign-in can serve `find` with this server's classes. It
binds each type itself, because there is no module that bundles them:

| Type | Binding |
|---|---|
| `FindEndpoint` | registered as a Jersey resource under `{pod}/_system/find` |
| `PodRequestAuthenticator` | the deployment's own: request → `SempodsCredentials`, see [modularity](modularity.md) |
| `FindService` | as it is |
| `FindExecutor` | the thread each adapter runs on: `VirtualThreadFindExecutor`, or the deployment's pool as `FindExecutor(pool::execute)` |
| `Set<FindAdapter>` | a Guice multibinder holding the deployment's adapters |
| `ResourceExpander` | the deployment's own, or `SparqlResourceExpander` |
| `SparqlQueryService`, `PodConnections` | with `SparqlResourceExpander` or `SparqlTextFindAdapter`: a read connection to the deployment's RDF4J store, holding that pod's statements alone |

`:consumer-probe:find` binds exactly this list, with MongoDB off the classpath. The endpoint asks
no pod registry or context registry: whether the pod exists, and which contexts
the caller reads, is the authenticator's answer. The pod's URI is `SempodsCredentials.pod.uri`,
and a relative `context` resolves against it.
