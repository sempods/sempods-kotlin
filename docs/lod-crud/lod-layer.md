# LOD Layer — Resource HTTP API

Pure Linked Data CRUD on resource URIs inside the pod namespace. This
layer exposes resources at their canonical URIs and operates on them
as whole units, with RFC 9110 and RFC 7396 semantics preserved exactly.

Where RDF semantics would force a deviation from these standards, the
operation moves to the [System layer](system-layer.md) instead.

Status: **v1** — shipped. Known limitations are listed at the end of
this document.

## Scope

The LOD layer exposes one URL pattern:

```
https://sempods.org/{pod}/{resourcePath}
```

`{resourcePath}` is everything after the pod segment. Resources with
`resourcePath == "_system"` or `resourcePath` starting with `"_system/"`
are not addressable through the LOD layer — that prefix is reserved
for control-plane and System-layer routes.

## Routing

| Verb     | Path                          | Purpose                            |
|----------|-------------------------------|------------------------------------|
| `GET`    | `/{pod}/{resourcePath}`       | Dereference (LOD)                  |
| `HEAD`   | `/{pod}/{resourcePath}`       | Headers only                       |
| `OPTIONS`| `/{pod}/{resourcePath}`       | Allowed methods                    |
| `PUT`    | `/{pod}/{resourcePath}`       | Replace whole resource representation |
| `PATCH`  | `/{pod}/{resourcePath}`       | RFC 7396 merge patch on resource   |
| `DELETE` | `/{pod}/{resourcePath}`       | Remove resource (in target context)|

`POST` is intentionally **not** offered on resource URIs. Resource
creation uses `PUT` on the target URI (standards-legitimate; gives
clients control over identifier choice and idempotent creation).

## Context rule for writes and reads

The `?context=...` query parameter selects which named graph(s) an
operation targets. Semantics differ between writes and reads.

### Writes

All write operations (`PUT`, `PATCH`, `DELETE`) target exactly one
explicit context.

- **Required.** A write without `?context=` is rejected.
- **Single value only.** Repeating the parameter on a write is
  rejected — multi-context writes are intentionally not exposed.
- **Accepted values:**
  - canonical context URI
  - pod-relative context path (server resolves to canonical URI)
- The context must exist in the pod context registry.
- The `context` identifies the target RDF graph (named graph
  dimension) only.
- The resource URI and the target graph are **independent
  dimensions**. A write is allowed when the caller has write
  permission for the target graph, even if the resource path lies
  outside that graph's path.

Error responses on writes:

- `400 Bad Request` — missing `?context=`, syntactically invalid
  value, or repeated parameter
- `404 Not Found` — `?context=` value is not a known context
- `403 Forbidden` — known context, but caller lacks `<context>#write`
  or a covering `<root>#manage` scope

### Reads

Read operations (`GET`, `HEAD`) accept `?context=...` as an optional
**downscope filter** on the caller's readable contexts.

- **Zero values:** full union across every context the caller may
  read. Anonymous callers see public contexts only. Authenticated
  callers see their explicit context grants, plus public contexts
  **only if** the token carries the `public-read` scope
  (`public-read` is additive, pre-checked at consent time, but the
  user may deselect it — see `../auth/authorization.md`).
- **One value:** intersection of `{requested}` with readable
  contexts.
- **Multiple values:** repeat the parameter
  (`?context=A&context=B&context=C`). The server computes the
  intersection of `{requested}` with readable contexts.

Authorization on reads: requested contexts the caller cannot read are
**silently excluded** from the intersection. No error and no
diagnostic header — clients that need to know what they can see use
`list_contexts`. An empty result set (all requested contexts denied,
unknown, or simply containing no triples for the resource) returns
**`404 Not Found`**, deliberately indistinguishable from "resource
has no statements here", to avoid leaking context topology.

Comma-separated lists (`?context=A,B`) are **not** supported; use
parameter repetition. Rationale: context URIs may legally contain
commas under RFC 3986.

## GET semantics

- Returns the RDF model of the resource: all statements where
  `subject == resource URI` that are visible in the selected contexts
  (see [Context rule](#context-rule-for-writes-and-reads)).
- If no statements are visible in the resulting set: `404 Not Found`.

### JSON-LD representation modes

LOD `GET` has two JSON-LD representation modes. The mode only changes
the response shape; it does not change which contexts are readable.
The `?context=...` parameter remains the read downscope filter.

- **Default (`include_contexts` absent or `false`)**: merged resource
  object. Named-graph provenance is collapsed, producing the ergonomic
  resource view used for normal Linked Data reads and LOD `PATCH`
  round-trips. **This is the recommended default** for every read
  except provenance-sensitive ones.
- **Graph-aware (`include_contexts=true`)**: JSON-LD named graph array.
  Statements are grouped by their RDF context, so callers can
  distinguish provenance after a unioned read. Reach for this only
  when the answer depends on **which context a triple came from** —
  e.g. an "audit who contributed what" view across multiple readable
  contexts.

Example graph-aware shape (literals are JSON-LD value objects, matching the canonical
form below):

```json
[
  {
    "@id": "https://sempods.org/alice/_system/contexts/contacts",
    "@graph": [
      {
        "@id": "https://sempods.org/alice/contacts/bob-smith",
        "https://schema.org/name": [{"@value": "Bob Smith"}]
      }
    ]
  }
]
```

`application/n-quads` is already graph-aware by format and is not
changed by `include_contexts`.

### Canonical JSON-LD representation

The LOD layer's canonical JSON-LD shape is the object returned by `GET`
with `Accept: application/ld+json` and without `include_contexts=true`.
It is the only JSON shape accepted by LOD `PATCH`.

- The top-level object contains `@id` equal to the resource URI.
- `@type` is present when the resource has `rdf:type` values.
- Predicate properties use absolute IRI keys only.
- Property values are arrays of JSON-LD value objects:
  - IRI objects: `{ "@id": "https://..." }`
  - literal objects: `{ "@value": "...", "@language": "..." }` or
    `{ "@value": "...", "@type": "http://www.w3.org/2001/XMLSchema#..." }`
- No top-level `@context` is emitted or accepted for PATCH.
- No compact terms or CURIE-like keys (`schema:name`) are emitted or
  accepted for PATCH.

### Content negotiation

| `Accept`                          | Response media type           |
|-----------------------------------|-------------------------------|
| `application/ld+json` (default)   | `application/ld+json`         |
| `application/json`                | `application/ld+json`         |
| `application/n-quads`             | `application/n-quads`         |

If `Accept` requests an unsupported media type: `406 Not Acceptable`.
`Vary: Accept` is set on negotiated responses.

### ETag and conditional reads

- Every `GET` response carries a strong `ETag` derived from the
  resource's last-modified timestamp and the response media type.
- `If-None-Match` is honored per RFC 7232 — a matching tag returns
  `304 Not Modified`.

### HEAD and OPTIONS

- `HEAD` returns the same headers as `GET` with no body.
- `OPTIONS` returns an `Allow` header listing the verbs available for
  the resource and the caller's permissions.

## PUT semantics

- **Full replacement** of outgoing edges (`subject == resource URI`)
  in the target context. Incoming edges from other resources are
  untouched.
- The request body MUST contain statements for the target resource
  only. Statements with other subjects in the payload are rejected
  with `400 Bad Request`.
- Any `@graph` / context fields inside the payload are advisory only;
  the server persists statements in the context selected via
  `?context=...`.

### Status codes

- `201 Created` with `Location` header — when the resource had no
  prior outgoing edges in the target context (creation).
- `200 OK` or `204 No Content` — when an existing representation is
  replaced.

### Supported request media types

- `application/ld+json` / `application/json`
- `application/n-quads`

### Conditional writes

- `If-Match: <etag>` MUST be honored. Mismatch returns
  `412 Precondition Failed`.
- `If-None-Match: *` MUST be honored for safe creation. Existing
  representation returns `412 Precondition Failed`.

## PATCH semantics

- **RFC 7396 JSON Merge Patch** applied to the canonical JSON-LD
  representation of the resource in the target context — the same
  JSON shape returned by `GET` with `Accept: application/ld+json`.
- The patch affects outgoing edges (`subject == resource URI`) in the
  target context only.
- Unspecified properties remain unchanged; properties set to `null`
  are removed.
- The server does **not** run JSON-LD expansion or prefix resolution on
  merge-patch payloads. Accepted top-level patch members are:
  - `@id`, optional for GET-modify-PATCH round-trips; when present it
    MUST equal the resource URI from the request path, otherwise
    `400 Bad Request`;
  - `@type`, for replacing or deleting `rdf:type`;
  - absolute IRI property keys, for replacing or deleting predicate
    values.
- Top-level `@context`, `@graph`, `@reverse`, `@nest`, `@included`, and
  any other JSON-LD keyword except `@id` and `@type` are rejected with
  `400 Bad Request`.
- Compact terms and CURIE-like keys such as `schema:name` are rejected
  with `400 Bad Request`. Clients must use absolute IRI keys, or use
  the [System layer](system-layer.md) for slot/edge operations.
- **Multivalued properties:** RFC 7396 requires arrays to be replaced
  wholesale. This layer preserves that behavior exactly. Clients that
  need to add or remove a single value from a multivalued property
  MUST use the [System layer](system-layer.md) instead. This is a
  property of RFC 7396, not a defect.

If no outgoing edges exist in that context: `404 Not Found`.

Supported request media type:

- `application/merge-patch+json`

Conditional writes (`If-Match`) MUST be honored as for `PUT`.

## DELETE semantics

- Removes outgoing edges (`subject == resource URI`) in the target
  context.
- Incoming edges from other resources are not deleted.
- If no outgoing edges exist in that context: `404 Not Found`.
- On success: `204 No Content`.
- Conditional `If-Match` MUST be honored.

A `DELETE` without `?context=` is rejected with `400 Bad Request`.
A cross-context delete is not exposed at this layer; use SPARQL
Update or System-layer per-context deletes.

## Conformance requirements

The LOD layer is conformant to RFC 9110 when:

1. `GET` is safe and idempotent; produces identical responses for
   identical request preconditions.
2. `PUT` is idempotent; repeated identical requests have the same
   effect as one.
3. `DELETE` is idempotent; deleting an absent representation returns
   the configured 404 deterministically.
4. `PATCH` follows RFC 7396 strictly over the canonical JSON-LD object.
   `@id` may be present only when it matches the request resource URI.
   No JSON-LD expansion, CURIE expansion, or semantic RDF-aware merge is
   silently applied.
5. ETags are returned on every `GET` / `HEAD` and honored on
   `If-Match` / `If-None-Match` for both reads and writes.
6. `OPTIONS` and `HEAD` are available for every addressable resource.
7. `406 Not Acceptable` is returned when `Accept` cannot be satisfied.
8. `405 Method Not Allowed` is returned with `Allow` header for
   unsupported verbs.

Deviations from this list are bugs, not features.

## Read-only SPARQL endpoint (related)

For queries that cross resources or contexts:

- `POST /{pod}/_system/sparql/query`
- `Content-Type: application/sparql-query`
- `SELECT` / `ASK` → `application/sparql-results+json`
- `CONSTRUCT` / `DESCRIBE` → `application/ld+json` (default) or
  `application/n-quads` via `Accept`

Lives at the System path because it is not LOD dereference. Full
shape is documented separately under the SPARQL surface.

## Examples

### Dereference

```http
GET /alice/contacts/bob-smith
Accept: application/ld+json

→ 200 OK
  Content-Type: application/ld+json
  ETag: "1716800000000-jsonld"
  Vary: Accept
  {
    "@id": "https://sempods.org/alice/contacts/bob-smith",
    "@type": "https://schema.org/Person",
    "https://schema.org/name": [
      {"@value": "Bob Smith", "@language": "de"}
    ]
  }
```

### Replace

```http
PUT /alice/contacts/bob-smith?context=https://sempods.org/alice/_system/contexts/contacts
Content-Type: application/ld+json
If-Match: "1716800000000-jsonld"

{ ... full resource ... }

→ 200 OK
```

The response does not echo an `ETag`. JSON-LD → RDF → JSON-LD is a
transformation; the stored representation is not byte-identical to
the request body, so per RFC 9110 §10.2.3 the server does not claim
an entity tag for the just-stored representation. Clients that need
the new tag perform a follow-up `GET`.

### Merge patch

```http
PATCH /alice/contacts/bob-smith?context=https://sempods.org/alice/_system/contexts/contacts
Content-Type: application/merge-patch+json

{
  "https://schema.org/birthDate": [
    { "@value": "1947-03-12", "@type": "http://www.w3.org/2001/XMLSchema#date" }
  ]
}

→ 200 OK
```

Rejected patch example:

```http
PATCH /alice/contacts/bob-smith?context=https://sempods.org/alice/_system/contexts/contacts
Content-Type: application/merge-patch+json

{
  "@context": { "schema": "https://schema.org/" },
  "schema:birthDate": "1947-03-12"
}

→ 400 Bad Request
```

`application/merge-patch+json` is JSON Merge Patch, not a JSON-LD
processing mode. Use absolute IRI keys here; use the System layer for
RDF-granular add/remove operations.

### Delete in context

```http
DELETE /alice/contacts/bob-smith?context=https://sempods.org/alice/_system/contexts/contacts

→ 204 No Content
```

## Known limitations

These limitations are intrinsic to the v1 contract and intentionally
out of scope here. Each is named so callers do not discover them as
surprises.

- **No server-side IRI canonicalisation.** Predicate IRIs are stored
  exactly as written. In particular, the server does not unify
  `http://schema.org/...` with `https://schema.org/...`. If a pod
  mixes the two, callers see two distinct predicates. The
  recommended workaround is to pick **one** canonical form per pod
  and document it in the pod's profile; clients that may have seen
  the other form should normalise on the way in.
- **No atomic cross-context writes.** Every `PUT` / `PATCH` /
  `DELETE` targets exactly one context. Compound writes that must
  land in multiple contexts atomically are not part of this layer,
  and there is no escape hatch elsewhere: the SPARQL surface is
  read-only (see §"Read-only SPARQL endpoint (related)"), and
  `SparqlQueryService.validateReadOnly()` rejects every Update form.
  A caller that needs the guarantee issues one write per context and
  handles partial failure itself. Transactional multi-context writes
  are tracked in the maintainer's internal roadmap.
- **TOCTOU between precondition check and write.** Conditional
  writes (`If-Match`, `If-None-Match: *`) are evaluated outside the
  storage transaction. A concurrent write between the precondition
  check and the actual mutation can slip through. Same gap is
  acknowledged in `system-layer.md`; closing it requires a
  storage-layer conditional-write API.
