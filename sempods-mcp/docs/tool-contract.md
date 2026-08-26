# sempods-mcp — internal tool contract (M3 read + M4 write surface)

The single, slim source of truth for the hosted service's MCP tool surface. Two things in
`:sempods-mcp-core` are its code source, both shared with the pod-immanent MCP:
[`ToolCatalog`](../../sempods-mcp-core/src/main/kotlin/org/sempods/mcp/core/ToolCatalog.kt) declares
both surfaces from one set of specs — what differs between them is a `ToolVariant`, `MULTI_POD` adds
`targets` / `target` and `list_pods`, `SINGLE_POD` does not — and
[`PodToolExecutor`](../../sempods-mcp-core/src/main/kotlin/org/sempods/mcp/core/PodToolExecutor.kt)
runs each tool against **one** pod, which is where the argument rules and `result` shapes below
actually live. What stays this service's own is everything the fan-out adds: `targets` / `target`,
the per-pod envelope, `partial` / `failed_pods`, `list_pods`. This doc records the **semantics** —
that envelope, the error model, SPARQL guardrails, provenance rules, and the write rules — so the
tool surface does not grow ad-hoc against implicit assumptions.

Names and shapes no longer have to be kept in lockstep by hand; they are one declaration. The
forward-looking conformance profile would expand that same artifact into a public, **versioned**
spec with cross-implementation tests — concept, not yet built (see
[`../../docs/concepts/hosted-mcp.md`](../../docs/concepts/hosted-mcp.md)).

## Identity & gating

- Every tool runs under an **authenticated** service session `(user, profile)` (M1). The read
  tools are advertised in `tools/list` only when a session is present; calling one without a valid
  bearer returns the same `401` + `WWW-Authenticate` OAuth-upgrade challenge as `authorize`.
- The service maps `(user, profile)` → connected pods (`connections`) and uses the per-pod
  vault token (`podTokens`, refreshed on demand via `PodTokenProvider`) as the **pod-scoped
  bearer**. Pod-side visibility is the pod's decision (its token scopes); the service does not add
  a second per-scope gate in M3.

## Read tools (M3)

Mirror the pod-immanent MCP read tools 1:1, plus an optional `targets` array (a subset of the
caller's connected pod base URLs; from `list_pods`). `targets` is **tri-state**: **absent** → fan
out to all the profile's connected pods; an explicit **`[]`** → select none (empty envelope); a
**non-empty** list → exactly that subset. (A malformed array such as `targets: [5]` is rejected by
validation, not silently treated as "none".)

| tool | required | optional | pod endpoint |
|---|---|---|---|
| `list_pods` | — | — | *(none — registry read)* |
| `list_contexts` | — | `targets` | `GET /_system/contexts` |
| `sparql_select` | `query` | `context_iri`, `targets` | `POST /_system/sparql/query` (SELECT/ASK) |
| `sparql_graph` | `query` | `context_iri`, `targets` | `POST /_system/sparql/query` (CONSTRUCT/DESCRIBE) |
| `find` | `text` | `type`, `context_iri`, `include_contexts`, `limit`, `targets` | `POST /_system/find` |
| `get_resource` | `resource_iri` | `context_iri`, `include_contexts`, `targets` | `GET /_system/resources/{b64url(iri)}` |
| `get_property_values` | `subject_iri`, `predicate_iri` | `context_iri`, `targets` | `GET /_system/resources/{b64url(subj)}/{b64url(pred)}` |

`context_iri` is an effective downscope on **all** the context-addressed read tools — `find` /
`get_resource` / `get_property_values` and now `sparql_select` / `sparql_graph`. The values are
pod-scoped, the same list may be passed to every targeted pod, and each pod **silently drops** the
contexts it does not own or the caller may not read. For the SPARQL tools the service forwards each
`context_iri` to the pod's SPARQL endpoint as the SPARQL-1.1-protocol `default-graph-uri` **and**
`named-graph-uri` query parameters (each context as both, so a narrowed query keeps full reach within
those graphs); the pod applies `{requested} ∩ readable`, so the param can only narrow. A caller may
still also scope with `GRAPH`/`FROM` inside the query.

Argument schemas are **enforced server-side** (`ToolCatalog.validate`, shared): unknown arguments
(`additionalProperties: false`), missing required arguments, wrong-typed arguments (e.g.
`context_iri` as a string, `limit` as a string), and **string arrays with a non-string or blank
element** (e.g. `targets: [5]`) are all rejected as a tool error — never silently dropped, which
could otherwise widen a read beyond what the caller asked for (a dropped `targets`/`context_iri`
filter would fail open to "all pods" / "no filter"). The **absolute-IRI rule under the write table
below applies to the reads too**: `resource_iri`, `subject_iri`, `predicate_iri`, and every element
of `context_iri` / `type`, refused before any pod is contacted.

## Result envelope (per pod)

Every read tool returns a single text content block carrying:

```json
{ "pods": [
  { "pod": "https://sempods.org/alice",        "ok": true,  "result": { … } },
  { "pod": "https://sempods.org/ai-playground", "ok": false, "error": { "kind": "pod_error", "message": "…" } }
], "partial": true, "failed_pods": ["https://sempods.org/ai-playground"] }
```

- Pods are queried **concurrently**; one pod's failure (unreachable, 5xx, no usable token) is
  captured into its own `ok:false` entry and **never poisons** the others.
- Each failed entry carries a stable `kind` — `not_connected` | `no_token` | `pod_error` — so a
  caller can react per-pod (e.g. prompt a reconnect on `no_token`), plus the pod's HTTP `status`
  whenever a pod actually answered (a 403 scope refusal stays distinguishable from a 502 without
  parsing the message). No `status` means no pod response existed — a token that could not be
  acquired, a blocked address. When any pod failed, the envelope flags `partial: true` and lists the
  `failed_pods`, so an incomplete read is never mistaken for a complete one.
- `list_pods` returns `{ "pods": [ { "pod", "issuer", "scopes", "pod_subject", "foreign_identity",
  "subject_verified", "similar_to", "reconnect_required" } ], "note"? }` (no `ok`/`result` — it makes no pod call). `scopes`
  is the access token's additive feature scopes (e.g. `public-read`), **not** the per-context grants;
  when at least one pod is listed a `note` says so and points at `list_contexts` (the authoritative
  live read/write view). `pod_subject` is the WebID the pod authorized the caller as; `foreign_identity`
  is true when it differs from the service identity (the caller acts on that pod as `pod_subject`), and
  the `note` then also carries the foreign-identity warning; `subject_verified` is false when the pod
  exposes no JWKS (the subject is trusted via the direct TLS token, not a signature). `similar_to` is
  the caller's sempods WebID that `pod_subject` **likely** denotes the same person as — a weak
  correlation hint (like `rdfs:seeAlso`), **not** an asserted `owl:sameAs`; null when not foreign.
  `reconnect_required` is true when the pod declared this connection's grant finished (RFC 6749 §5.2
  `invalid_grant`) — every call to that pod will fail until the person reconnects at `/_system/ui`,
  so there is nothing to gain by retrying it. A
  read fan-out entry and a write success envelope both gain the same `foreign_identity` / `pod_subject`
  / `similar_to` fields when that pod's identity is foreign. When nothing is connected, fan-out tools
  return `{ "pods": [], "hint": "…/_system/ui" }`.
- A malformed **call** (missing required arg, a write/SERVICE in a SPARQL tool) is a tool-level
  error (`isError: true` with a text message), not a per-pod entry.

`result` shapes: `list_contexts` → the pod context document; `sparql_select` → SPARQL-Results-JSON;
`sparql_graph`/`find` → JSON-LD; `get_resource` → `{ resource_iri, etag, jsonld }`;
`get_property_values` → `{ subject_iri, predicate_iri, values, etag? }` (the key is omitted, never
null, when there is no single validator — and an empty slot is `values: []`, not the pod's 404).

`get_resource`'s `etag` is the **write-precondition** tag in both representations, which under
`include_contexts` costs a second read: HTTP hands out the validator of the representation it just
served, and the named-graph one carries a `-contexts` marker that `if_match` refuses.

## Provenance

- **Per pod** for every tool (the envelope key).
- **Per context** where the tool shape carries it: `list_contexts` (the contexts themselves),
  `get_resource` / `find` (via `include_contexts`, the pod groups statements by named graph).
- `sparql_select` / `sparql_graph` narrow via `context_iri` (above), but their result rows are still
  annotated **per pod, not per context** — attributing a free-form query result to the context each
  row came from needs a `GRAPH ?g`-binding rewrite. Scoping itself no longer needs a rewriter; only
  the row-level *provenance* rewrite stays parked (the chat app has a TS one).

## SPARQL guardrails

The SPARQL tools accept **read-only** queries. Read-only enforcement is the **pod's** job: it parses
the query (`validateReadOnly`) and rejects updates / `SERVICE` with a 400 that surfaces as a per-pod
error. The service does **not** pre-screen with a keyword regex — that false-positives on keywords
inside literals, IRIs, and prefix names (e.g. a `FILTER` on the literal `"Create"`, or a `…/service#`
prefix), which would reject valid reads. **Context scope:** `context_iri` narrows via the pod's
SPARQL-protocol dataset params (`default-graph-uri` / `named-graph-uri`, forwarded per pod); omitting
it queries the bearer's whole readable set. No service-side AST rewrite is involved — the pod applies
the `{requested} ∩ readable` downscope. The query runs **independently on each pod**, so `ORDER BY` /
`LIMIT` apply **per pod, not globally** —
a caller wanting a global top-N must re-rank the per-pod result sets (the fan-out does not merge them).

## Write tools (M4)

The write / property-mutation tools **never fan out**. Each carries a required single `target` (one
connected pod base URL) and a required single `context_iri` (a string, not an array), so a write
lands in exactly one pod and one graph and can never be sprayed across pods by accident.

| Tool | required (beyond `target`, `context_iri`) | optional | pod endpoint |
|---|---|---|---|
| `create_resource` | `resource_iri`, `jsonld` | `if_none_match` (`"*"` = create-or-fail) | `PUT /_system/resources/{b64url(iri)}?context=` — `@id` is set from `resource_iri`, overriding the body's |
| `update_resource` | `resource_iri`, `jsonld_patch` | `if_match` | `PATCH …` (`application/merge-patch+json`) |
| `delete_resource` | `resource_iri` | `if_match` | `DELETE …` |
| `add_property_value` | `subject_iri`, `predicate_iri`, `value` | `if_match` | `POST /_system/resources/{b64url(subj)}/{b64url(pred)}?context=` |
| `set_property_values` | `subject_iri`, `predicate_iri`, `values` | `if_match` | `PUT …` (empty array clears the slot) |
| `remove_property_value` | `subject_iri`, `predicate_iri`, `target_iri` | — | `DELETE …/{b64url(target)}` (idempotent, no preconditions) |
| `clear_property_values` | `subject_iri`, `predicate_iri` | `if_match` | `DELETE …` |

**Single-pod envelope.** A write returns `{ "pod", "ok": true, "result": { …echoed ids, "outcome"?,
"status", "etag"?, "response"? } }` — `outcome` where the route reports one
(`created`/`already_present`, `cleared`/`already_empty`, `removed`/`already_absent`), lifted out of
the pod's `{"outcome": …}` body because it is the one thing an idempotent status cannot say — (plus `"foreign_identity": true` + `"pod_subject"` + `"similar_to"` when the
write landed on the pod as a foreign WebID — same markers as the read fan-out), or
`{ "pod", "ok": false, "error": { "kind", "message", "status"? } }` when the pod refuses — the **same** `kind` discriminator the reads carry (`no_token` | `pod_error`),
plus the pod's HTTP `status` (e.g. **412** precondition, **403** scope) so an optimistic-concurrency
caller branches structurally instead of parsing the message. Argument/target errors (missing or
non-absolute IRI argument, empty precondition, unconnected `target`) are **tool-level** errors
(`isError: true`) validated **before** any pod call — they never reach a pod. Authorization is not
among them: where a write may land is the pod's decision, surfaced as its own 403/404.

**Pre-pod validation (uniform across every tool, read and write).** Every IRI argument (`target`,
`context_iri`, `resource_iri`, `subject_iri`, `predicate_iri`, `target_iri`, and the elements of
`type`) must be an **absolute IRI** — a malformed one is a tool error, not a pod call. It was a
write-only rule until consolidation M3, and the reads paid for that twice: a bad `resource_iri` came
back as "the pod failed", and a bad `context_iri` was dropped, which fails **open** — `find` then
searched every readable context instead of the one named. Prefixed forms (`schema:Person`) are
absolute and stay legal. A precondition (`if_match` / `if_none_match`),
when present, is **normalized to a valid HTTP entity-tag** before forwarding: `*` passes through, an
already-quoted (optionally weak `W/"…"`) tag is kept, and a **bare token** (`v1`) is quoted (`"v1"`).
This matters because a tag forwarded verbatim without quotes is rejected by the pod's header parser,
which then proceeds **unconditionally** — silently losing the lost-update protection the caller asked
for. An empty value, or one whose opaque part contains a `"`, cannot be normalized and is a tool error.

**No reserved-area guard.** The service does not pre-judge which IRIs a write may address. It used
to: anything under the target pod's `_system` / `.well-known` was refused, with an exemption for
`_system/apps/…` so app contexts stayed writable. That was neither necessary nor correct.

Not necessary, because the pod resolves `?context=` against its own registry — a control-plane path
is not a registered context and comes back **404**, and a context the caller holds no scope on comes
back **403**.

Not correct, twice over. It refused resource subjects under `_system`, which the pod allows on
purpose: a control-plane IRI is describable like any foreign resource, and a statement *about* a
context is ordinary data. And the exemption carried a copy of the context namespace, which went
stale the moment contexts moved to `_system/contexts/` — every write into a migrated context was
refused here before the pod was ever asked.

`target` is still checked, for a different reason: it must be a pod this user has connected. That is
this service's own state, not the pod's.

**Scope.** The pod enforces the `<context_iri>#write` scope — a missing scope is a pod **403**
surfaced as the per-pod error (not a crash). ETag preconditions pass straight through:
`if_match` → `If-Match` (a stale tag → pod **412**), `if_none_match: "*"` → `If-None-Match`.

**Partial-error surfacing on reads (M4).** A failed pod in a multi-pod read carries a stable error
`kind` — `not_connected` | `no_token` | `pod_error` — and the envelope flags `partial: true` with a
`failed_pods` list, so a caller cannot mistake an incomplete read for a complete one. Since
consolidation M3 a read entry also carries the pod's `status`, the same as a write.

## Parked

- Per-context SPARQL **provenance** — row-level attribution of a free-form query result to the
  context each row came from, via a `GRAPH ?g`-binding AST rewrite (the chat app has a TS one).
  Context **scoping** is done (pod SPARQL-protocol dataset params, forwarded from `context_iri`); only
  the provenance rewrite remains, until a use case needs it.

The public conformance profile + capability-discovery endpoint (the versioned "what counts as a pod")
is forward-looking concept, tracked in
[`../../docs/concepts/hosted-mcp.md`](../../docs/concepts/hosted-mcp.md), not here.
