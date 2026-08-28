# MCP Tools

The pod exposes fourteen tools via `tools/list`, in four groups:
the OAuth handshake helper (`authorize`), the discovery helper
(`list_contexts`), the read tools (`sparql_select`, `sparql_graph`,
`find`, `get_resource`, `get_property_values`), the resource write tools
(`create_resource`, `update_resource`, `delete_resource`), and the
System-layer property-value tools (`set_property_values`,
`add_property_value`, `remove_property_value`, `clear_property_values`) —
each a thin MCP wrapper over the LOD-CRUD
[the system layer](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md).

All tools enforce the same context sandbox: the caller sees / writes
only the contexts their bearer covers (or the pod's public contexts when
anonymous). Cross-cutting rules are documented once at the bottom of
this file.

## Tool catalog

### `authorize`

Synthetic, MCP-only. Drives the OAuth handshake from inside the
JSON-RPC stream so defensive clients (Claude Desktop, Copilot,
Open-Code) that never trigger a 401 on their own can still get the
user authenticated. Full flow + replay handling lives in
[`authentication.md`](authentication.md#the-authorize-tool).

- Anonymous / public-read-only caller, or already-authorized caller
  passing `reauthorize: true` → server replies HTTP 401 +
  `WWW-Authenticate`, the MCP client follows the link to start OAuth.
- Already-authorized caller, no `reauthorize` flag → returns a JSON
  body confirming the active session (`{ "authorized": true,
  "client_id": "...", "scopes": N }`).

`tools/list` rewrites the `authorize` description per session so the
hint matches the caller's auth state (anonymous vs. authenticated). The
tool stays visible in both states — keeps `tools/list` stable and
removes the need for `tools/list_changed` notifications.

Argument: `reauthorize: boolean` (optional, default `false`).

### `list_contexts`

Returns the contexts (named graphs) this session can see, with the
permission level on each. The contract is:

```json
{
  "pod_base_url": "https://<host>/<pod>",
  "authenticated": true,
  "contexts": [
    {
      "context_iri": "https://<host>/<pod>/tasks",
      "permissions": ["read", "write"],
      "source": "grant"
    },
    {
      "context_iri": "https://<host>/<pod>/events/public",
      "permissions": ["read"],
      "source": "public"
    }
  ],
  "writable_contexts": ["https://<host>/<pod>/tasks"]
}
```

`source` indicates where a context's effective permissions come from:
`grant` (a direct per-context grant), `manage` (covered by a
`<root>#manage` grant via the slash-delimited rule), or `public` (a
public context, read-only, no explicit grant). Permissions are resolved
server-side per request from the grant store — they are not derived from
token scopes — so REST `GET /{pod}/_system/contexts` and this tool always
return the same effective set.

Models are instructed to call this **first** before any write tool —
`writable_contexts` is the authoritative list of valid `context_iri`
arguments for resource tools (`create_resource`, `update_resource`,
`delete_resource`) and the property-value tools
(`set_property_values`, `add_property_value`, `remove_property_value`,
`clear_property_values`). This matters because some MCP clients (Claude
Code among them) do not surface the `initialize` `instructions` block to
the model; the `list_contexts` response is the only path the model can
rely on.

No arguments.

### `sparql_select` (read)

Read-only SPARQL `SELECT` (and `ASK`) executed in the caller's
context sandbox. Returns SPARQL-Results-JSON (see
[W3C spec](https://www.w3.org/TR/sparql11-results-json/)) packaged in
the MCP `ToolCallResult.content[0].text` field.

Arguments:
- `query: string`.
- `context_iri: string[]` (optional) — read downscope: restrict the query
  to these contexts, within what the caller may read (`list_contexts`).
  Omit to query across all readable contexts. `{requested} ∩ readable`;
  unknown/unreadable contexts are silently ignored. If nothing remains,
  `SELECT` returns no bindings and `ASK` returns `false`. Fail-closed on a
  malformed shape.

`SELECT` and `ASK` share this tool because both produce
SPARQL-Results-JSON and both go through the same REST endpoint
(`_system/sparql/query`).

**Parity with the HTTP SPARQL endpoint.** `context_iri` is the MCP spelling of a read
downscope; the REST `POST /_system/sparql/query` endpoint honors the SPARQL-1.1-protocol
`default-graph-uri` / `named-graph-uri` query parameters for the same purpose. Both surfaces
resolve the requested contexts through the same registry-normalizing path
(`PodResourceReadService.resolveVisibleContexts` → `{requested} ∩ readable`, unknown/unreadable
contexts silently dropped) and pin the dataset with the same `SparqlSandbox.buildDataset`, so the
in-process MCP tool and the REST endpoint resolve `context_iri` identically. When the params are
present but nothing readable survives, the query fails closed to an empty result (never the whole
pod). A REST caller may still also scope inside the query with `GRAPH` / `FROM` (or `FROM NAMED`);
the protocol params, when present, pin the dataset and override the query's own `FROM` clauses per
the SPARQL protocol.

### `sparql_graph` (read)

Read-only SPARQL `CONSTRUCT` / `DESCRIBE` in the same sandbox. Returns
JSON-LD as text. Best for fetching all properties of a known resource
without guessing predicate names.

Arguments:
- `query: string`.
- `context_iri: string[]` (optional) — same read-downscope semantics as
  `sparql_select`. If nothing remains, the result graph is empty.

### `find` (read)

The semantic entry to the [graph retrieval pattern](../concepts/graph-retrieval.md):
search by text instead of composing SPARQL by hand. Returns a
context-sandboxed **flat RDF subgraph** (the matching resources plus a
fixed `rdf:type` / `rdfs:label` / `schema:name` expansion) as JSON-LD —
feed the hit IRIs into `get_resource` / `sparql_graph` to traverse
deeper. A resource matches when one of its literals contains all
whitespace-split, case-insensitive `text` tokens. Thin wrapper over the
same `FindService` as the REST `GET /{pod}/_system/find` endpoint, so the
two surfaces cannot drift; the search backend (SPARQL substring today,
vector later) is swappable behind the pinned contract.

Arguments:

- `text: string` (required) — whitespace-only is rejected.
- `type: string[]` (optional) — `rdf:type` IRIs, **OR-combined**: only
  resources of one of these types are returned. Fail-closed — a malformed
  `type` (not an array of strings) is a tool error, never silently
  dropped. The *general* predicate filter is a deferred extension.
- `context_iri: string[]` (optional) — read downscope: restrict the search
  to these contexts, within what the caller may read (`list_contexts`).
  Omit to search across all readable contexts. `{requested} ∩ readable`;
  unknown/unreadable contexts are silently ignored (no leak), an
  all-unreadable request yields an empty result. The downscope covers the
  expansion too. Fail-closed on a malformed shape, like `type`.
- `include_contexts: boolean` (optional, default `false`) — when `true`,
  return the **named-graph** form (each matched/expanded statement grouped by
  the context it came from) instead of the flat merged graph, the same
  representation as `get_resource` with `include_contexts`. Use it to tell
  which context a hit lives in (e.g. public vs. private) when searching across
  several readable contexts.
- `limit: integer` (optional) — clamped to `1..100`, default `10`.

The full request/response contract lives in
[`../concepts/graph-retrieval.md`](../concepts/graph-retrieval.md#find--text--expanded-subgraph).

### `get_resource` (read)

Fetch the pod's whole-resource view of a **known** `resource_iri` as
canonical JSON-LD, together with its `etag`. This is the read half of a
safe read-modify-write: pass the returned `etag` to
`update_resource` / `delete_resource` as `if_match`. Prefer it over
hand-written SPARQL when the IRI is known. `resource_iri` may be local or
external (same rule as `create_resource`; only the pod's own `_system` /
`.well-known` area is excluded). 404-style tool error if no statements are
visible.

Arguments:
- `resource_iri: string` — local or external.
- `context_iri: string[]` (optional) — restrict to one or more contexts;
  omit to union across readable contexts.
- `include_contexts: boolean` (optional) — return the named-graph
  (provenance) form instead of the merged canonical document. The `etag` is
  returned either way (see below).

Returns `{ "resource_iri", "etag", "jsonld" }`. The `etag` is always the
**write-precondition tag** — the canonical `application/ld+json` validator
— regardless of `include_contexts`, because that is the value
`update_resource` / `delete_resource` expect for `if_match`. It equals the
HTTP `ETag` for the default (canonical) representation; note it does **not**
equal the HTTP named-graph ETag (which carries a `-contexts` suffix) when
`include_contexts=true`. So conditional writes interoperate cross-transport
on the canonical representation.

Holding that promise costs a second read under `include_contexts=true`:
HTTP hands out the validator of the representation it just served, so the
canonical one is fetched separately. It is the only tool call that makes
more than one request to the pod.

### `get_property_values` (read)

Read all values of one slot `(subject_iri, predicate_iri)` as a JSON-LD
value array. With a single `context_iri` it also returns a slot `etag` for
`set_property_values` / `clear_property_values`'s `if_match`. `subject_iri`
may be local or external.

Arguments: `subject_iri`, `predicate_iri`, `context_iri: string[]`
(optional; exactly one readable context is required to get an `etag` back
— a multi-context union has no single validator). Returns
`{ "subject_iri", "predicate_iri", "values", "etag"? }`. The `etag` key is
**omitted**, not null, when there is no single validator or the slot is
empty or unreadable.

A slot with no values is an answer, not a failure: `values` is `[]` and
there is no `isError`.

`context_iri` shape note: all read-side MCP downscope filters use the
array form. Write tools use `context_iri: string` because every write
targets exactly one context.

`context_iri` ↔ HTTP naming: the read-side `context_iri[]` is the MCP
spelling of the same read-downscope the HTTP API exposes as the repeatable
`?context=` query parameter (`GET /_system/find`, LOD reads) and the
`contexts` JSON field (`POST /_system/find`). MCP keeps one name
(`context_iri`) across all read tools for consistency; the REST surface keeps
its query-parameter / body-field conventions. Same semantics either way:
`{requested} ∩ readable`, unknown/unreadable contexts silently dropped,
present-but-empty → empty result (never a broadening to pod-wide).

### `create_resource` (write)

Create or replace a resource inside a consented context (upsert).
Requires `<context_iri>#write` (or `<context_root>#manage`).

Arguments:

- `context_iri: string` — absolute IRI from `list_contexts.writable_contexts`.
- `resource_iri: string` — any absolute IRI: a resource in this pod **or
  an external URI** (`did:`, `urn:`, foreign `https://...`), so an external
  identity (e.g. a `foaf:`/`schema:Person`) can be enriched with pod-local
  statements. There is **no** exclusion — not even this pod's own `_system`
  IRIs, because a statement *about* a control-plane IRI is a statement, and
  where it is stored is what the writable *context* decides. That context,
  not the resource IRI, is the authorization boundary.
- `jsonld: object` — JSON-LD body. Predicates and `@type` MUST resolve
  to absolute IRIs (bare keys expand to nothing and the request fails
  with `parsed to 0 RDF statements`). The `@id` is set to `resource_iri`,
  overriding whatever the body carried — a body without one would expand
  to a blank node and be refused.
- `if_none_match: string` (optional) — pass `"*"` for create-or-fail:
  the call returns a precondition error instead of overwriting when the
  resource already exists. Omit for the default upsert.

Schema-level guard: `additionalProperties: false`. Conforming MCP
clients reject hallucinated extra fields (observed: ChatGPT inventing a
`statements` array) before the call leaves the wire.

Returns (the `etag` is the resource's new validator — feed it straight
into a follow-up `update_resource.if_match` without a separate read):

```json
{ "context_iri": "...", "resource_iri": "...", "status": 201, "etag": "..." }
```

`status` is the pod's own HTTP status (`201` created, `200` replaced).
Every write tool answers in this shape: the ids it was addressed with,
then what happened — `outcome` where the route reports one, `status`,
`etag` when there is one, and `response` for a route that returned a
body. Absent fields are omitted rather than null.

Failures from
[`PodResourceWriteService`](../../sempods-server/src/main/kotlin/org/sempods/api/pod/resources/PodResourceWriteService.kt)
(insufficient scope, malformed JSON-LD, a precondition that did not hold,
…) surface as `ToolCallResult.isError = true` with the pod's status and
its own message, not as JSON-RPC `-32603`. That keeps the AI client
able to report a precise error back to the user.

### `update_resource` (write)

RFC 7396 JSON merge-patch against the canonical JSON-LD representation
of an existing resource in a consented context. 404 if the resource does
not yet exist. Same scope rule as `create_resource`.

Important: `update_resource` follows the LOD-layer `PATCH` contract from
[`SPS-CRUD-035`](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md#SPS-CRUD-035).
The patch body is **not** JSON-LD-expanded. Top-level `@id` is allowed
only when it matches `resource_iri`, which keeps GET-modify-PATCH
round-trips usable. Top-level `@context`, `@graph`, `@reverse`, `@nest`,
`@included`, compact terms, and CURIE-like keys such as `schema:name`
are rejected. Use `@type` or absolute IRI property keys only.

For multivalued properties, arrays are replaced wholesale per RFC 7396.
Agents that need to add or remove one value must use the System-layer
property-value tools below instead of `update_resource`.

Arguments: `context_iri`, `resource_iri`, `jsonld_patch`. `resource_iri`
may be local or external, same as `create_resource`. Optional `if_match`
(ETag without quotes / `W/` prefix) → tool error if the resource changed.
Get the `etag` from `get_resource` or from a prior `create_resource` /
`update_resource` result. Returns the write shape above, with `status`
`200` and the resource's new validator as `etag`.

### `delete_resource` (write)

Remove a resource from a consented context. Same scope rule as
`create_resource`.

Arguments: `context_iri`, `resource_iri` (local or external, same as
`create_resource`). Optional `if_match` (ETag from `get_resource` or a
prior write) makes the delete conditional → precondition error if the
resource changed since then.

### System-layer property-value tools (write)

These tools are MCP wrappers over the specification's system layer
([`spec/core/lod-crud.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md) §5). They exist because
`update_resource` is deliberately RFC 7396-strict: it replaces arrays wholesale
and does not perform RDF-aware patching
([`SPS-CRUD-038`](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md#SPS-CRUD-038)).

All property-value tools:

- require `context_iri` from `list_contexts.writable_contexts`;
- accept full absolute IRIs only (`subject_iri`, `predicate_iri`, and
  `target_iri` where applicable);
- perform no CURIE or prefix expansion;
- use the same `<context>#write` / `<root>#manage` authorization rule as
  resource writes;
- map onto `/{pod}/_system/resources/{b64url(subject)}/...` HTTP
  operations internally. MCP callers pass IRIs, not base64url path
  segments;
- return the slot's new `etag` in their result, and accept an optional
  `if_match` (except `remove_property_value`, which is idempotent and
  returns no tag). Read a slot's current `etag` with
  `get_property_values` (single `context_iri`);
- report `outcome` where the operation is idempotent and the status
  cannot say what happened — `created`/`already_present`,
  `cleared`/`already_empty`, `removed`/`already_absent`. It is the pod's
  own word, taken from the HTTP twin's `{"outcome": …}` body (see
  [`SPS-CRUD-044`](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md#SPS-CRUD-044)),
  so MCP and plain-HTTP clients report identical outcomes.
  `set_property_values` has none: a wholesale replace has no second case.

#### `set_property_values`

Replace all values for `(subject_iri, predicate_iri)` in one context.
HTTP mapping: `PUT /{pod}/_system/resources/{s}/{p}?context=...`.

Arguments:

- `context_iri: string`
- `subject_iri: string`
- `predicate_iri: string`
- `values: array` — JSON-LD value objects or `{"@id": "..."}` IRI
  objects. Empty array clears the slot.
- `if_match: string` (optional but recommended after a slot read)

#### `add_property_value`

Add one value to a slot. Existing identical values are no-ops under RDF
set semantics: the first call returns `outcome=created`, a duplicate
call returns `outcome=already_present`. Both are success outcomes; no
`isError`. HTTP mapping:
`POST /{pod}/_system/resources/{s}/{p}?context=...`.

Arguments:

- `context_iri: string`
- `subject_iri: string`
- `predicate_iri: string`
- `value: object` — JSON-LD value object or `{"@id": "..."}` IRI object
- `if_match: string` (optional)

#### `clear_property_values`

Remove all values for `(subject_iri, predicate_iri)` in one context.
Idempotent: an already-empty slot returns `outcome=already_empty`
instead of failing. HTTP mapping:
`DELETE /{pod}/_system/resources/{s}/{p}?context=...`.

Arguments:

- `context_iri: string`
- `subject_iri: string`
- `predicate_iri: string`
- `if_match: string` (optional when the caller expects a specific prior
  slot state)

#### `remove_property_value`

Remove exactly one IRI-valued edge `(subject_iri, predicate_iri,
target_iri)`. Idempotent: removing an already-absent edge returns
`outcome=already_absent` instead of failing — safe to retry, and safe
to use for "ensure this triple does not exist" patterns without a
prior read. Literal-valued single-value removal uses read +
`set_property_values` because literals are not URL-addressable as edge
path segments.

HTTP mapping:
`DELETE /{pod}/_system/resources/{s}/{p}/{t}?context=...`.

Arguments:

- `context_iri: string`
- `subject_iri: string`
- `predicate_iri: string`
- `target_iri: string`

### Picking the right write tool

Mirror of the "CHOOSING A WRITE TOOL" block in the MCP server
`instructions`, kept here so clients that do not surface `instructions`
to their model still see the decision tree.

| Need                                                | Tool                          |
|-----------------------------------------------------|-------------------------------|
| Replace the whole resource / several fields at once | `update_resource`             |
| Add ONE more value to a multivalued property        | `add_property_value`          |
| Replace ALL values of a single property             | `set_property_values`         |
| Edit a single literal                               | read slot → `set_property_values` |
| Remove one IRI edge                                 | `remove_property_value`       |
| Empty a property entirely                           | `clear_property_values`       |

**Duplicate `add_property_value` is a no-op.** The first call returns
`outcome=created`, a second call with the same `value` returns
`outcome=already_present`. Both succeed (no `isError`). Use this for
"ensure this triple exists" patterns instead of read-before-write.

**Literal read-modify-write.** Literals have no addressable identity, so
editing one value uses a 3-step pattern:

1. Read the current slot with `get_property_values` (it also returns the
   slot `etag` for an optional `if_match` on step 3).
2. Replace the target literal client-side (e.g. "Bob" → "Bob Smith").
3. Call `set_property_values` with the full updated array.

## Autodiscovery pattern

Pods don't pin a vocabulary. A new client should:

1. List types in scope:
   ```sparql
   SELECT DISTINCT ?type WHERE { ?s a ?type } LIMIT 100
   ```
2. List predicates used by a chosen type:
   ```sparql
   SELECT DISTINCT ?p WHERE { ?s a <TYPE> ; ?p ?o } LIMIT 100
   ```
3. Fetch all properties of a known resource:
   ```sparql
   DESCRIBE <RESOURCE_IRI>
   ```
   or `CONSTRUCT { <R> ?p ?o } WHERE { <R> ?p ?o }`.

This pattern is also embedded in the per-session `instructions` block
returned by `initialize` so the model has it without an extra round
trip.

## Sandbox & security

Specified, not described here: one dispatch path shared with the HTTP query surface
([`SPS-MCP-019`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-019)), closed tool schemas that are **enforced** rather than advertised
([`SPS-MCP-024`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-024), [`SPS-MCP-025`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-025)), Update forms and `SERVICE`
rejected by parsing rather than by keyword search
([`SPS-SPARQL-003`](https://github.com/sempods/sempods-spec/blob/main/spec/core/sparql.md#SPS-SPARQL-003),
[`SPS-SPARQL-005`](https://github.com/sempods/sempods-spec/blob/main/spec/core/sparql.md#SPS-SPARQL-005)), any absolute resource IRI accepted
([`SPS-MCP-022`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-022)), and per-context write scope checked at write time
([`SPS-GRANT-025`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md#SPS-GRANT-025)).

Two things this implementation chose and the specification leaves open. The query timeout is
10 seconds. And the closure is enforced in two places, because the tool catalogue has two sources:
`ToolCatalog.validate()` refuses an undeclared argument for every catalogue tool, and
`McpEndpoint.unknownArgumentsRefusal()` does the same for the synthetic `authorize`, which is this
surface's own and not in the catalogue.

What keeps the advertised schema and the enforced one from drifting is that both read the same
`ToolCatalog` specs — `buildTools` only assembles the list it advertises from them. Hardening the
check means the two validators above, not that assembly.

## Instructions block

`InitializeResult.instructions` is built per session and includes:

- The pod base URL.
- The complete list of granted contexts with permission levels (or
  `(none — only the pod metadata is reachable)` for empty grants).
- A short writable-contexts hint (or the "no write access" fallback).
- The autodiscovery recipe above.
- The list of rejected SPARQL keywords.

The block is regenerated on every `initialize` so reconnects after a
consent change immediately reflect new scopes.

## Examples

The endpoint is `POST /{pod}/_system/mcp` with
`Content-Type: application/json`. All requests below assume that URL.

### `initialize`

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": { "name": "example-client", "version": "1.0.0" }
  }
}
```

### `tools/list`

```json
{ "jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {} }
```

### Discovery — `sparql_select`

```json
{
  "jsonrpc": "2.0", "id": 3,
  "method": "tools/call",
  "params": {
    "name": "sparql_select",
    "arguments": { "query": "SELECT DISTINCT ?type WHERE { ?s a ?type } LIMIT 100" }
  }
}
```

### Fetch a resource — `sparql_graph`

```json
{
  "jsonrpc": "2.0", "id": 4,
  "method": "tools/call",
  "params": {
    "name": "sparql_graph",
    "arguments": { "query": "DESCRIBE <https://<host>/<pod>/events/abc>" }
  }
}
```

### Create a resource (bearer required)

```http
Authorization: Bearer <pod-scoped-jwt>
```

```json
{
  "jsonrpc": "2.0", "id": 5,
  "method": "tools/call",
  "params": {
    "name": "create_resource",
    "arguments": {
      "context_iri":  "https://<host>/<pod>/tasks",
      "resource_iri": "https://<host>/<pod>/tasks/feed-cat",
      "jsonld": {
        "@context": { "schema": "https://schema.org/" },
        "@id":      "https://<host>/<pod>/tasks/feed-cat",
        "@type":    "schema:Action",
        "schema:name": "Feed the cat"
      }
    }
  }
}
```

### Patch a resource

```json
{
  "jsonrpc": "2.0", "id": 6,
  "method": "tools/call",
  "params": {
    "name": "update_resource",
    "arguments": {
      "context_iri":  "https://<host>/<pod>/tasks",
      "resource_iri": "https://<host>/<pod>/tasks/feed-cat",
      "jsonld_patch": {
        "https://schema.org/actionStatus": {
          "@id": "https://schema.org/CompletedActionStatus"
        }
      }
    }
  }
}
```

### Forbidden SPARQL — rejected via parser

```json
{
  "jsonrpc": "2.0", "id": 7,
  "method": "tools/call",
  "params": {
    "name": "sparql_graph",
    "arguments": {
      "query": "INSERT DATA { <http://example.org/test> <http://example.org/prop> \"value\" }"
    }
  }
}
```

Returns a `ToolCallResult` with `isError: true` and a message naming
the rejected keyword.

## Related

- [`endpoint.md`](endpoint.md) — JSON-RPC envelope, methods, error codes.
- [`authentication.md`](authentication.md) — bearer / anonymous / `authorize` tool.
- [`spec/core/grants.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md) — context model
  and `manage` slash-delimited rule.
- Tests:
  [`McpEndpointHttpTest`](../../sempods-server/src/test/kotlin/org/sempods/api/pod/system/mcp/McpEndpointHttpTest.kt)
  exercises the happy path for every tool plus the sandbox parity, the
  scope failures, and the SPARQL-keyword / SERVICE rejections.
