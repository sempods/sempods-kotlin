# System Layer — Slot HTTP API

Triple-granular CRUD on RDF property slots and individual edges,
exposed under the pod's reserved `_system` prefix. This layer
accepts pragmatic RDF set-semantics deviations from pure HTTP, which
the [LOD layer](lod-layer.md) refuses by design.

Works uniformly for resources with **local URIs** (same pod) and
**external URIs** (any IRI scheme: `did:web:`, `urn:`, `mailto:`,
arbitrary `https://other.example/...`).

Status: **v1** — shipped. The slot route, the edge route, and the
whole-resource node route (CRUD by IRI) are all live.

## Scope

One concept: the **slot**. A slot is the addressable container for
*all* values of the pair `(subject, predicate)` within one context.

- Slot for `(bob-smith, schema:children)` in `/alice/_system/contexts/contacts`
  holds every `schema:children` triple for Bob in that context.
- Slot for `(bob-smith, schema:name)` in `/alice/_system/contexts/contacts` holds
  every `schema:name` literal in that context.

A slot is a real HTTP sub-resource: it has content identity, supports
`GET`, and answers the standard verbs.

The single edge `(subject, predicate, target)` is also addressable
when `target` is an IRI — letting clients remove one value from a
multivalued property without read-modify-write.

## URL scheme

All URIs embedded in path segments use **base64url without padding**
(RFC 4648 §5; see [README](README.md#base64url-encoding-convention)
for the convention and rationale).

```
/{pod}/_system/resources/{b64url(resourceIri)}                                # whole resource (CRUD by IRI)
/{pod}/_system/resources/{b64url(subject)}/{b64url(predicate)}                # slot
/{pod}/_system/resources/{b64url(subject)}/{b64url(predicate)}/{b64url(target)}  # single edge (IRI target only)
```

This URL scheme:

- Carries no schema sniffing, no `://` detection, no marker tokens
  beyond the namespace prefix `_system/resources/`.
- Works for any IRI scheme (`http(s)`, `urn`, `did`, `mailto`, `tag`,
  ...).
- Imposes no constraint on resource-ID character sets — embedded URIs
  are opaque blobs to the path parser.

## HTTP verbs on the resource node (whole-resource CRUD by IRI)

The single-segment route `/{pod}/_system/resources/{b64u(resourceIri)}`
addresses an **entire** resource at an arbitrary IRI — including IRIs
outside the pod namespace, for which the LOD path (`/{pod}/{resourcePath}`)
has no route at all. It is an alternate *addressing* of the same shared
write/read path the LOD layer uses, not a second implementation.

| Verb              | Semantics                                                                 |
|-------------------|---------------------------------------------------------------------------|
| `GET` / `HEAD`    | Return the pod's whole-resource view of `resourceIri` as canonical JSON-LD (or named-graph form with `include_contexts=true`; n-quads via `Accept`), with an `ETag`. |
| `OPTIONS`         | Advertise the allowed verbs (`PUT`/`PATCH`/`DELETE` only when the caller holds a write/manage scope). |
| `PUT`             | Replace the resource in the target context (RFC 9110); `201` + `Location` on create, `200` on replace. |
| `PATCH`           | RFC 7396 merge-patch on the canonical JSON-LD representation (`application/merge-patch+json`). |
| `DELETE`          | Remove the resource from the target context; `404` if absent there.       |

**Identical semantics to the LOD layer — one identity per resource.**
Context rules (`?context=`, single-context writes), conditional writes
(`If-Match`, `If-None-Match: *`), the canonical JSON-LD representation,
and the ETag validator are byte-identical to the LOD layer. A pod-owned
IRI therefore yields the **same ETag and body** whether fetched via the
pretty canonical path or this b64 route, so cross-route conditional
writes interoperate. The shared collaborators are
`PodResourceWriteService` (writes) and `PodResourceReadService`
(visibility + ETag base).

**Any IRI is addressable, including the pod's own control plane.** The
writable *context*, not the resource IRI, is the authorization boundary.
Statements about `did:web:bob.example`, about another pod's resources, or
about this pod's own `_system/contexts/apps/notes` are all the same
kind of thing: claims, stored in whichever context the caller may write.

They cannot change what they describe. Contexts, grants and service-client
registrations live in MongoDB, not in the graph — no amount of RDF about a
context IRI alters the context. The **media registry** ([`../media.md`](../media.md))
is the same kind of thing: a `schema:ImageObject` naming a media URL is a
claim, and deleting that claim does not release the bytes. And the
authoritative answer for such an IRI comes from the control plane itself,
not from the graph:

| Question | Route |
|---|---|
| What *is* this context? | `GET {pod}/_system/contexts/{path}` — the registry |
| What has anyone *said about* this IRI? | `GET {pod}/_system/resources/{b64url(iri)}` — the claims, with their context |

That is the same arrangement that already applies to a foreign resource:
you can hold statements about Alice's resource in your pod, and
dereferencing her IRI still gives Alice's answer. Here the "other party"
happens to be the control plane on the same server.

> Reading a triple whose subject is a context IRI therefore does **not**
> mean you are looking at the context's definition. Consumers that want
> the definition must ask the registry route.

**`Location` on create** points at this b64 route, because the canonical
path does not exist for external IRIs.

Both this route and the canonical LOD path emit the same `[lod/audit]`
log marker, so resource-level writes stay greppable across both routes
(distinct from the slot routes' `[slot/audit]`).

## HTTP verbs on a slot

| Verb     | Semantics                                                                  | Idempotent | Notes                                                |
|----------|----------------------------------------------------------------------------|------------|------------------------------------------------------|
| `GET`    | Return all values as a JSON-LD array (IRIs and/or literal value objects). | yes        | Default is merged/contextless; response order is not meaningful. |
| `PUT`    | Replace the entire slot content with the provided array.                  | yes        | Empty array clears the slot.                         |
| `POST`   | Add the provided value(s) to the slot.                                    | see below  | `201` + `{"outcome":"created"}`, or `200` + `{"outcome":"already_present"}`. `Location` for the new edge if IRI-valued. |
| `DELETE` | Empty the slot — remove all triples for `(subject, predicate)`.           | yes        | `200` + `{"outcome": …}` — `cleared` or `already_empty` — plus the post-clear `ETag`. Audit carries the same word. |

## HTTP verbs on a single edge

Only available when `target` is an IRI.

| Verb     | Semantics                                          |
|----------|----------------------------------------------------|
| `DELETE` | Remove exactly the triple `(subject, predicate, target)`. Other values of the slot are untouched. |

`DELETE` on a single edge is **idempotent**: a missing edge succeeds
just like removing a present one. Retries after a successful delete, and
"ensure this triple does not exist" patterns, both return success. Since
the status code alone cannot tell the two apart, the response follows
RFC 9110 §9.3.5 — `200 OK` with a representation describing the result:

```json
{ "outcome": "removed" }          // an edge was present and is now gone
{ "outcome": "already_absent" }   // the edge was not there
```

The `[slot/audit]` line carries the same `result=removed|already_absent`.
MCP `remove_property_value` returns the identical `outcome` value, so the
HTTP and MCP surfaces stay in parity. (The body — rather than a custom
header — keeps the outcome readable by a browser `fetch` without any
`Access-Control-Expose-Headers` entry.)

`GET` on a single edge MAY return `200 OK` with an empty body or
`204 No Content` for existence checks; not a primary use case.

## The `outcome` representation

All three idempotent slot mutations answer with it, for one reason: the
distinction they report is the one an idempotent status code cannot
carry.

| Route | Outcomes |
|---|---|
| `POST` slot | `created` (201) · `already_present` (200) |
| `DELETE` slot | `cleared` · `already_empty` (both 200) |
| `DELETE` edge | `removed` · `already_absent` (both 200) |

Slot `DELETE` used to answer a bare `204`, which meant "there was
nothing to clear" and "the slot is now empty" were indistinguishable to
every caller outside the server — the difference existed only in the
audit log. It now answers `200` with the body, still carrying the
post-clear `ETag`. Slot `POST` already separated its two outcomes by
status; it repeats them in the body so a caller reading these routes
through a tool layer sees a result object rather than a status line.

## Acknowledged deviations from HTTP

RDF set-semantics force three honest deviations from a pure HTTP
reading. They are named here so clients do not have to discover them.

1. **`POST` is idempotent in practice.** RFC 9110 defines `POST` as
   non-idempotent. Adding a triple that already exists is a no-op
   under RDF set semantics, so repeated `POST` of the same value has
   no additional effect. The verb is still semantically `POST`, not
   `PUT`, because it expresses *extend*, not *replace*. Clients may
   rely on retry without conditional headers; audit logs record
   duplicate adds as no-ops.
2. **`GET` on a slot returns an unordered set.** Array order is not
   meaningful and may differ between calls. Clients that need
   stability sort client-side.
3. **`POST` status for a value that already existed.** The server
   returns `201 Created` with `Location` when a new edge was
   inserted, and `200 OK` (no `Location`) when the value was already
   present. The Location header is the unambiguous signal that a new
   edge exists.

These three are the complete list. Any other behavior that surprises
a competent HTTP client is a bug.

## Context binding

Every write targets exactly one context. The same `?context=...`
query parameter as the LOD layer, validated against the same context
registry, authorized against the same `<context>#write` and
`<root>#manage` scopes.

- `PUT` / `POST` / `DELETE` on a slot affect only the target context.
- Triples for the same `(subject, predicate)` in other contexts are
  untouched.
- A write without `?context=` is `400 Bad Request`.

### Cross-context reads

`GET` on a resource or slot accepts `?context=...` as an optional
**downscope filter**, identical in semantics to the LOD-layer
[Context rule](lod-layer.md#context-rule-for-writes-and-reads).

- **Zero values:** union across every readable context.
- **One value:** that context only (if readable).
- **Multiple values via parameter repetition**
  (`?context=A&context=B`): intersection of the requested set with
  readable contexts.

Slot `GET` has the same JSON-LD representation mode switch as the LOD
layer:

- **Default (`include_contexts` absent or `false`)**: merged slot
  value array. Named-graph provenance is collapsed, giving clients the
  direct value list they normally need for slot edits. **This is the
  recommended default** for every read except provenance-sensitive ones.
- **Graph-aware (`include_contexts=true`)**: JSON-LD named graph array.
  Statements are grouped by their RDF context, so callers can
  distinguish provenance after a unioned read. Reach for this only
  when the answer depends on **which context a triple came from**.

The mode only changes the response shape. `?context=...` remains the
read downscope filter.

Requested-but-unreadable contexts are silently excluded; an empty
result set returns `404 Not Found` (no topology leak). Comma-separated
lists are not supported. Writes remain single-context per the
[Writes section](lod-layer.md#writes) of the LOD-layer spec.

## Literal handling

Literals cannot be addressed in URL paths the way IRI objects can —
encoding a literal together with datatype and language tag is
brittle. Therefore:

- **Removing or modifying an individual literal value uses `PUT`**
  (read-modify-write): the client `GET`s the slot, modifies the
  array, `PUT`s it back.
- `DELETE` on a slot with literal values empties the entire slot, not
  individual values.
- `POST` adds a literal without ambiguity: the body carries
  `@value`, optional `@language`, optional `@type`.

This is the only structural asymmetry between IRI-valued and
literal-valued slots. It is a URL-encoding limit, not a design
defect.

## Conditional requests

`ETag` and `If-Match` are central to the System-layer write protocol;
they prevent lost updates without requiring locks.

**Tag form.** The ETag is a deterministic strong tag derived from the
subject resource's stored `dateModified` value and the slot identity
`(subject, predicate, context)`. This makes the tag conservative when
the stored `dateModified` changes: even a change to a different
predicate invalidates every slot tag of that subject. For a
never-touched subject (`dateModified` is null), the tag falls back to
a fixed timestamp anchor (`"0"`) so an `If-None-Match: *` create flow
still has a stable validator.

**Known limitation.** The current `dateModified` anchor is not yet a
dedicated server-managed revision. It is derived from the persisted
resource metadata, which may in turn reflect RDF `schema:dateModified`
data supplied by clients. If a write changes a resource without
changing that stored timestamp, the slot ETag may remain stable even
though the slot state changed. This is accepted for v1 as best-effort
optimistic concurrency. A later storage-layer revision/updatedAt value
will replace this anchor and make every successful resource mutation
invalidate the relevant tags.

**ETag scope.**

- `GET` on a slot with **exactly one** resolved `?context=` value
  emits the ETag *when the slot has at least one triple in that
  context*. An empty slot returns `404` (no readable representation
  to tag); `If-None-Match: *` remains the supported way to write
  create-or-fail into a yet-empty slot — it does not require a prior
  `GET` or any tag.
- `GET` on a multi-context union (`?context=` repeated, or no
  `?context=` so the union spans every readable context) does **not**
  emit an ETag — the representation is the union of multiple
  snapshots that no single tag can validate.
- `PUT`, `POST`, and whole-slot `DELETE` responses echo the slot's
  new ETag so clients can chain conditional writes without an extra
  `GET`. The tag is deterministic from `dateModified` + identity, not
  from the response body, so this is safe even though storage is
  canonicalised JSON-LD → RDF. (Whole-slot `DELETE` empties the slot
  on success; the echoed tag describes the now-empty state for
  retry-after-failure flows.)

**`If-None-Match: *` is slot-as-resource.** It succeeds iff the
target slot contains zero triples `(subject, predicate, *)` in the
write context. This means an external `did:web:` subject can be the
target of a create-or-fail PUT even though the DID itself "exists"
elsewhere; and a subject that already has values for *other*
predicates can still take a create-or-fail PUT on a yet-empty
predicate. (The alternative — "subject doesn't exist" — would break
both cases.)

**Verb-by-verb scope.**

- `PUT` MUST honor `If-Match` when the client provides one; mismatch
  returns `412 Precondition Failed`. Clients SHOULD send `If-Match`
  whenever they derive the new value from a prior `GET` (the typical
  read-modify-write case, including all literal slot updates).
- `PUT` MAY use `If-None-Match: *` for create-or-fail semantics
  (target slot must be empty); non-empty slot returns `412`.
- `POST` MUST honor `If-Match` when provided, but does **not** require
  it. Add operations are idempotent under RDF set semantics, so
  concurrent POSTs of the same value collapse to one statement
  without conflict.
- `DELETE` of a single edge ignores `If-Match` entirely (the
  operation references a specific triple by identity, not by current
  state — present or not, the outcome is the same).
- `DELETE` of a whole slot SHOULD use `If-Match` when the client
  expects a specific prior state; without it the operation is
  unconditional.

**Acknowledged TOCTOU.** The precondition check (slot cardinality +
current tag read) and the write step are not transactional: a
parallel write between the two can slip through. This is the same
gap the LOD-layer resource path carries; closing it requires a
storage-layer conditional-write API, which is tracked separately.

## Local vs. external URIs

The System layer is the **only** route for triples about external
URIs (no LOD path can dereference `did:web:bob.example`).

For **local URIs** — those that resolve under the pod's own
namespace — the System path is an operations endpoint over the same
underlying resource the LOD path identifies. Implementation choice:

- **`GET`** on a local resource's System path returns the same RDF
  the LOD path would (or `307 Temporary Redirect` to the LOD URL —
  implementation detail; both are conformant).
- **Slot operations** (`GET`/`PUT`/`POST`/`DELETE` on the slot or
  edge level) dispatch directly to the same store the LOD-layer
  writes touch. No data duplication; one source of truth.

The identity of the resource is always the LOD URI, not the System
URL.

## Discovery from LOD to System

Clients construct the System-layer slot URL **directly** from the subject and
predicate IRIs they already hold (e.g. from the LOD `GET` body):

```
/{pod}/_system/resources/{b64u(subject)}/{b64u(predicate)}
```

The mapping is deterministic and total (every resource/predicate has a slot URL),
so no server-side advertisement is needed. For local pod resources the LOD URI
itself is already the editable surface via standard `PUT`/`PATCH`/`DELETE`.

> **Removed:** earlier versions emitted one `rel="https://sempods.org/rels/edit-slot"`
> `Link` header per distinct predicate on every `GET`. That scaled response headers
> with the resource's predicate count (each header also carries the base64url subject)
> and blew the server's response-header-size limit on rich resources. Header-based
> discovery is unbounded by nature and was dropped. If declarative discovery is wanted
> later, the bounded options are `OPTIONS` on the resource (affordances off the hot GET
> path — now served on the resource node) or a single resource-level `Link` to the
> System-layer resource node.

## Examples

Throughout, `b64u(x)` abbreviates `base64url(x)` for readability.

### Read a slot

```http
GET /alice/_system/resources/b64u(https://sempods.org/alice/contacts/bob-smith)/b64u(https://schema.org/name)?context=https://sempods.org/alice/_system/contexts/contacts

→ 200 OK
  Content-Type: application/ld+json
  ETag: "1716800000000-slot"
  [
    {"@value": "Bob Smith", "@language": "de"}
  ]
```

### Read a slot with named-graph provenance

```http
GET /alice/_system/resources/b64u(https://sempods.org/alice/contacts/bob-smith)/b64u(https://schema.org/name)?include_contexts=true

→ 200 OK
  Content-Type: application/ld+json
  [
    {
      "@id": "https://sempods.org/alice/_system/contexts/contacts",
      "@graph": [
        {
          "@id": "https://sempods.org/alice/contacts/bob-smith",
          "https://schema.org/name": [{"@value": "Bob Smith", "@language": "de"}]
        }
      ]
    }
  ]
```

### Replace a slot

```http
PUT /alice/_system/resources/b64u(https://sempods.org/alice/contacts/bob-smith)/b64u(https://schema.org/name)?context=...
Content-Type: application/ld+json
If-Match: "1716800000000-slot"

[
  {"@value": "Bob Smith", "@language": "de"},
  {"@value": "Bob H. Smith", "@language": "de"}
]

→ 200 OK
```

### Add a child (IRI)

```http
POST /alice/_system/resources/b64u(https://sempods.org/alice/contacts/bob-smith)/b64u(https://schema.org/children)?context=...
Content-Type: application/ld+json

{"@id": "https://sempods.org/alice/contacts/carol-smith"}

→ 201 Created
  Location: /alice/_system/resources/b64u(https://sempods.org/alice/contacts/bob-smith)/b64u(https://schema.org/children)/b64u(https://sempods.org/alice/contacts/carol-smith)
  Content-Type: application/json
  {"outcome": "created"}
```

(A repeat of the same request returns `200 OK` with
`{"outcome": "already_present"}` — RDF set semantics, see
§"Acknowledged deviations from HTTP".)

### Remove one specific child

```http
DELETE /alice/_system/resources/b64u(https://sempods.org/alice/contacts/bob-smith)/b64u(https://schema.org/children)/b64u(https://sempods.org/alice/contacts/dave-smith)?context=...

→ 200 OK
  Content-Type: application/json
  {"outcome": "removed"}
```

(A repeat of the same request — the edge already gone — also returns
`200 OK` with `{"outcome": "already_absent"}`; single-edge DELETE is
idempotent, see §"HTTP verbs on a single edge".)

### Clear a slot

```http
DELETE /alice/_system/resources/b64u(https://sempods.org/alice/contacts/bob-smith)/b64u(https://schema.org/children)?context=...

→ 200 OK
  ETag: "…"
  Content-Type: application/json
  {"outcome": "cleared"}
```

(An already-empty slot answers `200 OK` with `{"outcome": "already_empty"}`.)

### Write triples about an external DID

```http
POST /alice/_system/resources/b64u(did:web:bob.example)/b64u(http://xmlns.com/foaf/0.1/knows)?context=...
Content-Type: application/ld+json

{"@id": "https://sempods.org/alice/contacts/bob-smith"}

→ 201 Created
  Location: /alice/_system/resources/b64u(did:web:bob.example)/b64u(http://xmlns.com/foaf/0.1/knows)/b64u(https://sempods.org/alice/contacts/bob-smith)
```

### Create and merge-patch a whole resource at an external IRI

Enrich an external `foaf:`/`schema:Person` identity with pod-local
statements — the sync-app and chat write path.

```http
PUT /alice/_system/resources/b64u(https://example.org/people/alice)?context=https://sempods.org/alice/_system/contexts/contacts
Content-Type: application/ld+json

{"@id": "https://example.org/people/alice", "https://schema.org/name": "Alice"}

→ 201 Created
  Location: /alice/_system/resources/b64u(https://example.org/people/alice)

PATCH /alice/_system/resources/b64u(https://example.org/people/alice)?context=https://sempods.org/alice/_system/contexts/contacts
Content-Type: application/merge-patch+json

{"@id": "https://example.org/people/alice", "https://schema.org/jobTitle": "Engineer"}

→ 204 No Content   # name preserved, jobTitle added
```

## Out of scope

- **SHACL enforcement** of cardinality, datatype, value range — a
  separate layer; until it lands, the System layer accepts any
  structurally valid write.
- **JSON Patch / N3 Patch / SPARQL Update bodies** — not exposed on
  this layer. Slot CRUD is the additive/granular alternative; SPARQL
  Update is the federated escape hatch.
- **Bulk slot updates across multiple subjects in one call** — use
  the LOD layer's `PUT`/`PATCH` for whole-resource swaps, or SPARQL
  Update for multi-resource transactions.
- **Predicate-IRI canonicalisation** (`http://schema.org/` vs.
  `https://schema.org/`) — server does not normalise. Pods document
  their canonical form; clients normalise before write. Otherwise
  fidelity is lost silently.
