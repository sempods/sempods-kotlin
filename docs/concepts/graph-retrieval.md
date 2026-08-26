# Graph retrieval — resource navigation for any consumer (Concept)

## Purpose

This document describes the retrieval pattern for any consumer of pod data —
not just AI. It applies wherever a client answers a question or builds a view
from a pod: a website chatbot, a personal AI assistant, an enterprise knowledge
layer, **and** an ordinary non-LLM app (event listing, calendar widget, search
box).

**This is a foundational pattern.** Every read-side use case on sempods should
build on it, not reinvent retrieval per application. The retrieval primitives
are **consumer-agnostic**: the same `find` + structural traversal serves a
React event list and a GPT-class agent. The only AI-specific addition is the
final answer step (`model2text`).

Sections below describe the shipped behaviour (IST) unless they say otherwise;
§"Status & where the work lives" is where the two are separated.

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
properties — not an arbitrary text fragment. No information is lost to chunking,
and every URI property is an exact traversal path: the data structure guides
navigation instead of approximate embedding similarity.

## `find` — text → expanded subgraph

`find` is the entry primitive. It is **a specification, not an algorithm**: the
search engine behind it (lexical, vector, query-rewriting, hybrid) is
swappable and chosen by the implementation / pod config. The caller sends
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
  - best-effort expanded ("preferred rule", not mandatory):
      · default: type + label  (an implementation MAY also include the matched edge, but need not)
      · later: a per-type expansion registry (per pod / per context, manage-extendable —
        "app-context infos"), conditionally recursive, e.g. Event → location → {name, address}.
        Full `DESCRIBE`-style expansion is avoided (payload explosion).
  - by default a FULLY FLAT graph: no ordering, no score, no hit/expansion marker. Plain RDF the caller
    merges and traverses. With `include_contexts=true` the same graph is grouped by source context
    (named-graph JSON-LD, or the 4th N-Quads term) so a caller can tell which context a hit lives in.
```

**Contract semantics:**

- **Core (`text` + `limit`) is MUST** for any conformant implementation —
  universally implementable. The contract is deliberately this small; richer
  query shapes (beyond `type`) are additive extensions (see below).
- **Optional `type` is a return-type constraint, not a match-scope.** It
  restricts which *hit types* come back (the hit's `rdf:type`), repeatable and
  OR-combined. It does **not** restrict where matching happens: a semantic
  engine may match via linked resources and still return only the requested
  types. `rdf:type` is the one structured facet supported, because every engine
  knows a resource's type cheaply; the general predicate filter stays deferred
  (below). Exact match — a caller ORs known subtypes rather than relying on
  subclass reasoning.
- **Which engines run** (lexical / vector / rewriting / hybrid) is
  implementation / pod config; their results are merged into the one result
  graph. The caller picks no mode.
- **The context sandbox always applies** — the same permission layer as SPARQL
  and CRUD. An anonymous visitor sees public data; an authenticated owner sees
  internal contexts too. Same `find`, different depth.
- **Optional `context` downscope.** A caller may narrow the search to specific
  contexts (graphs) *within* what it may read, via the repeatable `context=<iri>`
  parameter (MCP: `context_iri` array). This is the universal read-downscope the
  LOD read routes already expose (`lod-crud/lod-layer.md` §"Reads"), **not** the
  general predicate filter below: the server intersects `{requested}` with the
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
- **GET caching is permission-scoped.** Public/anonymous reads may use normal
  HTTP caching. Bearer-backed reads must not leak across callers; shared caches
  need `Vary: Authorization` / effective-context semantics, or the response is
  private / not stored.

**Possible later extension — general structured filters (beyond `type`).**
`type` is supported (above); a *general* equality filter on arbitrary
predicates (`author = <iri>`, `status = "open"`, …), carried in a `POST` body,
is the deferred part. Two difficulties: resolving an arbitrary predicate to a
concrete filter is hard because each search engine has its own structure — a
vector or text index does not natively map `author = <iri>` onto its query
(whereas `rdf:type` is a universal facet every engine already has); and a
half-honored filter would reintroduce the plausible-but-wrong results graph
retrieval exists to avoid (so it would have to be fail-closed: honor fully or
reject). It can be added later as a **purely additive** extension — an optional
`filter` parameter plus a `POST` form — without breaking the contract, once the
impact and per-engine implementation are understood. Until then a consumer
narrows by other predicates client-side (the expansion returns type + label).

The `POST` form (**live**) is the request envelope for any `find` that no longer
fits a URL — not only the general filter, but also a downscope onto many contexts.
Its body mirrors the GET parameters as a JSON object, with `contexts` as a nullable
list:

```
POST /{pod}/_system/find
{ "text": "…", "type": [<iri>…]?, "contexts": [<iri>…]?, "include_contexts": false?, "limit": 10?, "filter": {…}? }
```

`contexts` carries the same read-downscope semantics as the GET `context=`
parameter (`{requested} ∩ readable`, silent exclusion); `include_contexts` is the
same provenance switch as the GET parameter. Content negotiation (JSON-LD vs.
N-Quads) is still driven by the `Accept` header. `filter` is the deferred general
predicate filter above and remains the only POST-body field not yet implemented.

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

## Two consumer faces (the counter-check)

The same primitives serve both consumer classes; only the final step differs.

| | Non-LLM app | LLM / agent |
|---|---|---|
| Entry | `find` with `text` + `type=` | `find` with NL `text` |
| Traversal | `sparql_graph` for the exact neighbours the view needs | `get_resource` / SPARQL, driven by inspection |
| Iteration | usually one shot per view | inspect → traverse ↺ re-`find` to pivot |
| Working graph | the data backing the current view | the agent's working memory |
| Answer step | render directly | `model2text` |

An app benefits from the primitives without any AI: "fetch event + venue +
image + offers in one shot" is exactly `find` + a targeted CONSTRUCT, and it
avoids N+1 dereferencing. Strip `model2text` and the pattern is a clean,
consumer-agnostic data-access API.

## The iterative loop

```
                        question / view need
                                 │
                                 ▼
              ┌──────────────────────────────────┐
   pivot ───► │ FIND  (semantic entry)           │ ◄─── re-find to jump
   (re-find)  │ text → expanded subgraph         │      to other semantics
              │ (context-sandboxed)              │
              └───────────────┬──────────────────┘
                              ▼
              ┌──────────────────────────────────┐
              │ INSPECT the working graph         │
              │ - URI properties to follow?       │
              │ - question answered / view ready? │
              └───────┬───────────────┬───────────┘
            traverse  │               │  done
          structurally│               ▼
                      ▼      ┌───────────────────────────┐
   ┌──────────────────────┐  │ ANSWER (AI cap) / RENDER  │
   │ get_resource          │  │ - model2text  (LLM)       │
   │ sparql_select / graph │  │ - direct render (app)     │
   │ (CONSTRUCT)           │  └───────────────────────────┘
   │ → merge into graph    │
   │ → back to INSPECT     │
   └──────────────────────┘
```

INSPECT decides between **traverse structurally** (SPARQL / `get_resource`) and
**pivot semantically** (`find` again). ANSWER (`model2text`) is the AI-specific
cap; an app renders the graph directly.

## Session graph accumulates

Results join into a single graph that grows with each step — conceptually an
RDF model. Event + Location + Performer + Venue becomes a coherent, connected
picture. For an app this is the data backing the current view; for an agent it
is working memory within the request. Either way the graph is verifiable: it
holds real facts with citable URIs, which is what keeps an LLM from filling
gaps with invention.

## Cross-pod discovery via LOD

An event in the aaltra pod links to a performer who has their own pod. A
consumer follows the URI, fetches the performer's public data, and suddenly has
biography + discography — without anyone modelling this connection. This is
Linked Open Data working as intended, and it is the same for app and agent: a
URI that points to another pod is just another resource to fetch (subject to
that pod's own sandbox).

Example — cross-pod navigation from a personal task:

```
FIND:     task "Tickets kaufen: aaltranacht" in the personal pod
TRAVERSE: task has schema:about → https://sempods.org/aaltra/events/xyz  (another pod)
          → get the aaltra event: startDate, schema:location URI, schema:offers
          → follow location URI: venue name, address, coordinates
ANSWER:   "aaltranacht is on April 11 at aaltra (Hohe Str. 33, Chemnitz). Tickets at rausgegangen.de."
```

The task stored only a link (`schema:about`) — no date, no location. The
consumer navigated two pods with no pre-built integration between them.

## `model2text` — the only AI-specific addition

The AI consumer gathers structured facts first, then formulates a
human-friendly answer from the accumulated graph. Because the graph supplies
verifiable facts and citable URIs, `model2text` has little to hallucinate. It
is the single primitive a non-LLM app does not use.

## Classic RAG vs. graph retrieval (for the AI consumer)

For the AI consumer specifically, this replaces classic RAG. RAG chunks
documents, embeds the chunks, and stuffs the top-N similar fragments into the
prompt — which works for unstructured text but destroys structure: an event's
name lands in a different chunk than its price, relationships are lost, and each
retrieval is independent.

| Aspect | Classic RAG | Graph retrieval |
|---|---|---|
| Retrieval unit | Text chunk (arbitrary boundary) | Resource (semantic entity) |
| Finding related info | Embedding similarity (approximate) | Explicit links (exact) |
| Multi-step retrieval | Independent queries, no memory | Session graph accumulates |
| Structure | Destroyed by chunking | Preserved — RDF is the format |
| Access control | Separate index per permission level | One context sandbox |
| Cross-source linking | Not possible | Automatic via LOD / URI dereferencing |
| Verifiability | Chunk may be out of context | Resource is self-contained, URI is citable |

The explicit links and the flat RDF result are the "explicit, exact"
counter-pole to RAG's approximation.

## Relation to the existing AI layer

The existing AI layer (`AiService`, `AiSemFacade`) provides the **write** path:

- **text2model** — natural language → structured RDF.
- **model2model** — structured RDF → transformed RDF.

This pattern adds the **read** path, consumer-agnostic:

- **find** — text → expanded subgraph (shipped; the contract above).
- **structural traversal** — `get_resource`, `sparql_select` / `sparql_graph`
  (shipped).

…and the AI-only **answer** step:

- **model2text** — session graph + question → natural-language answer (new).

Together: text2model (write) + graph retrieval (read) + model2text (answer) =
the complete AI interaction cycle, with the read half equally usable without
any AI.

## Status & where the work lives

- **Live** — the website chatbot navigates pod data with SPARQL + LOD
  expansion (2026-03-26), using the shipped `sparql_*` / `get_resource` tools.
- **Live** — `find` ships as `GET`/`POST /{pod}/_system/find` and an MCP `find`
  tool over one `FindService`: the SPARQL substring engine behind the
  swappable adapter SPI, the optional OR-combined `type` facet, the optional
  `context` read-downscope, the optional `include_contexts` provenance form, and
  the fixed `type` / `label` / `name` expansion. MCP reference:
  [`mcp/tools.md`](../mcp/tools.md#find-read).
- **Open** — a vector / hybrid `find` engine (same contract), the per-type
  expansion registry, the general predicate filter, and SHACL-gated app
  surfaces. The concept for all of it is
  [`mcp-agent-interface.md`](mcp-agent-interface.md); whichever piece is being
  implemented has its breakdown in [`../roadmaps/`](../roadmaps/).
