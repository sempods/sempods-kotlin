# LOD-CRUD — Resource and Slot HTTP Layer

This folder is the canonical home for sempods's HTTP CRUD model on RDF
data. It covers **how clients read, write, and modify RDF resources and
their individual property values over plain HTTP**, separated cleanly
from authentication (`../auth/`), MCP tooling (`../mcp/`), and SPARQL.

If a topic isn't here, it isn't part of the CRUD model.

Status: **v1** — shipped. The contract is stable; further work lands
as additive features without breaking the shapes described here.
Known limitations are named explicitly inside each layer document.

## Mental model

Two layers, two purposes:

- **LOD layer** — addresses RDF resources by their canonical pod URI
  (`https://sempods.org/{pod}/{resourcePath}`). Pure Linked Data: GET
  dereferences the resource per Tim Berners-Lee's four principles, and
  PUT/PATCH/DELETE operate on the resource as a whole using strict
  RFC 9110 / RFC 7396 semantics. No RDF-specific verb deviations.
- **System layer** — addresses individual property *slots* and
  *edges* via `/{pod}/_system/resources/{b64url(uri)}[/{b64url(predicate)}[/{b64url(target)}]]`.
  Operates at triple granularity, accepts pragmatic RDF set-semantics
  deviations from pure HTTP idempotency, and works uniformly for local
  and external URIs.

Both layers share the same pod, the same store, the same context model,
and the same authorization rules. They differ in **granularity** and in
**which standards they refuse to bend**.

### Identity vs. operations

The LOD URI is the resource's **identity**. The System-layer URL is an
**operations endpoint** that happens to reference the same resource.
Two URIs, one resource. Analogy: a git commit has one SHA (identity)
but is operated on through many `git` commands (operations).

For a local resource, the two are deterministically related:

```
identity:    https://sempods.org/alice/contacts/bob-smith
operations:  https://sempods.org/alice/_system/resources/aHR0cHM6Ly9zZW1wb2RzLm9yZy9hbGljZS9jb250YWN0cy9ib2Itc21pdGg
```

For an external resource (`did:web:bob.example`, `urn:isbn:...`), only
the System-layer URL exists in this pod — there is no local LOD URI to
dereference.

## Design principles

- **LOD layer is standards-pure.** GET satisfies LOD principles. Write
  verbs follow RFC 9110 exactly. Where RDF semantics would force a
  deviation, the operation moves to the System layer instead.
- **System layer is honest about its deviations.** RDF set-semantics
  make POST idempotent in practice and GET responses unordered; both
  are named and explained, not hidden.
- **Writes are context-bound, reads are context-filterable.** Every
  write targets exactly one named graph via `?context=...`. Reads
  default to the union of readable contexts; `?context=...` (repeat
  for multiple values) downscopes to an intersection of requested ∩
  readable. No CRUD operation spans contexts atomically. Applies to
  both layers.
- **Context provenance is opt-in.** JSON-LD `GET` defaults to a
  merged/contextless representation — that is the recommended shape
  for nearly all reads. Add `include_contexts=true` on LOD resource
  reads or System slot reads to get JSON-LD named graph output grouped
  by RDF context; reach for that only when the answer depends on
  which context a triple came from.
- **No URI sniffing.** The System layer encodes embedded URIs as
  **base64url** (RFC 4648 §5, no padding) path segments. No `://`
  detection, no schema whitelist, no container-specific decoding rules.
- **External URIs are first-class.** The System layer treats
  `did:web:...`, `urn:...`, `mailto:...`, and any other IRI scheme
  identically to local URIs.
- **No CURIE expansion in the server.** Predicates and IRIs are full,
  absolute. Prefix resolution is a client-side concern.

## base64url encoding convention

All URIs embedded in System-layer path segments use **base64url without
padding** (RFC 4648 §5; alphabet `A-Z a-z 0-9 - _`). Precedent: JWT
(RFC 7519), WebAuthn credential IDs, OAuth 2.0 PKCE `code_challenge`
(RFC 7636).

Encoding is a one-liner in every mainstream language:

| Language | Snippet                                                                 |
|----------|-------------------------------------------------------------------------|
| Node     | `Buffer.from(uri).toString('base64url')`                                |
| Java     | `Base64.getUrlEncoder().withoutPadding().encodeToString(uri.getBytes(UTF_8))` |
| Python   | `base64.urlsafe_b64encode(uri.encode()).rstrip(b'=').decode()`          |
| Browser  | `btoa(uri).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'')`   |

Rejected alternatives:

- **Percent-encoding** — fragile with `%2F`/`%23`/`%3F` across containers
  and reverse proxies; pleasant for trivial URIs, dangerous for IRIs
  containing fragments or query components.
- **base32 (RFC 4648 §6)** — ~60% length overhead vs. base64url's ~33%;
  case-insensitivity gains nothing in URL paths.
- **Custom hex / multibase / CID** — overspecified; no Web precedent
  for this use case.

A decoding helper endpoint (`GET /{pod}/_system/resources/_decode/{b64url}`)
is a deferred DX convenience, not part of the core spec.

## Standards used

| Standard                             | Where it shows up                            |
|--------------------------------------|----------------------------------------------|
| RFC 9110 (HTTP Semantics)            | Both layers — verb semantics, status codes   |
| RFC 7396 (JSON Merge Patch)          | LOD-layer PATCH                              |
| RFC 7232 (Conditional Requests)      | ETag, If-Match on Slot-layer writes          |
| RFC 8288 (Web Linking)               | LOD-layer GET advertises System edit URLs    |
| RFC 4648 §5 (base64url)              | System-layer URI embedding                   |
| Linked Data Principles (Berners-Lee) | LOD layer GET contract                       |

Standards are *named*, not re-explained.

## What lives elsewhere

- **Authentication and authorization** — `../auth/`. The CRUD layer
  inherits the auth model and the `?context=` write-scope check; it
  does not redefine them.
- **SHACL enforcement** of cardinality, datatype, and value range — a
  separate layer, not yet specified. Until SHACL is in place, both
  CRUD layers accept any write that is structurally valid.
- **SPARQL Query** — `_system/sparql/query`. Stays the power-user
  route for cross-resource and cross-context *reads* within one pod
  that do not fit the slot model. It federates nothing — `SERVICE` is
  rejected anywhere in the query, for the same SSRF reason `did:web`
  dereferences nothing — and it is read-only, so it is no escape
  hatch for writes either.
- **Binaries** — [`../media.md`](../media.md). `_system/media/...`
  holds bytes, not RDF, and is deliberately not part of this model:
  the two share the base64url id convention and the context permission
  model, and nothing else. A `schema:ImageObject` written through the
  CRUD layers and the media it points at are separate writes.
- **MCP tooling** — `../mcp/`. MCP tools (`create_resource`,
  `update_resource`, `delete_resource`, `set_property_values`,
  `add_property_value`, `remove_property_value`,
  `clear_property_values`) are thin wrappers on top of this HTTP layer.
  System-layer HTTP operations and their MCP wrappers ship together.

## Doc map

- **`lod-layer.md`** — LOD-layer spec: routing, context rule,
  GET/PUT/PATCH/DELETE semantics, conformance requirements,
  known limitations.
- **`system-layer.md`** — System-layer spec: slot model, base64url
  routes, HTTP verbs per slot, acknowledged deviations, ETag rules,
  local-vs-external URI handling.

## When to use which layer

| Need                                                | Use         |
|-----------------------------------------------------|-------------|
| Dereference a resource for an LOD crawler           | LOD GET     |
| Replace an entire resource representation           | LOD PUT     |
| Apply RFC 7396 merge patch to canonical JSON-LD     | LOD PATCH   |
| Delete an entire resource in a context              | LOD DELETE  |
| Add a single value to a multivalued property        | System POST |
| Remove a single edge by `(subject, predicate, target)` | System DELETE |
| Replace just the values of one property             | System PUT  |
| Read/write triples about an external URI            | System layer (only option) |
| Read across resources or contexts in one pod        | SPARQL query |
| Update several resources at once                    | One CRUD write each — no atomic form exists |
