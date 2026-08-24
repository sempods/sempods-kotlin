# Virtual Inference Context — a TBox layer for type/predicate coverage (concept)

> **Status: concept / proposal — not implemented.** This document sketches a
> future capability and its contract. It is intentionally kept out of the IST
> docs; nothing here describes current server behavior. The companion
> client-side guidance (what consumers do *today*, with no server support) lives
> in the chat app's system prompt and `apps/chat/docs/concepts/multi-pod.md`.

## Purpose

Give any consumer a reliable, pod-implementation-agnostic way to answer
**coverage questions** — "all events", "everyone mentioned", "every kind of
action" — that span a *set* of types or predicates rather than a single one.

The unit of the problem is RDFS/OWL entailment: `Festival` and `MusicEvent` are
subclasses of `Event`; `schema:mentions` and a domain-specific `mentionsPerson`
may be sub-predicates of one another. A correct "all X" answer needs the
**transitive closure** of those hierarchies, not a single guessed type.

## The problem this solves

The sempods protocol treats the pod store as **swappable**. A conformant pod is
free to apply no reasoning at all — the reference implementation (RDF4J
`SailRepository(MemoryStore())`) does exactly that. Consequences for a consumer:

- `?s a <Event>` matches only resources typed *exactly* `<Event>`; subclasses
  are not folded in.
- `find`'s `type` filter is, by contract, **exact match, no subclass reasoning**
  (see `ai-retrieval.md`).
- SPARQL 1.1 property paths (`?s a/rdfs:subClassOf* <Event>`) only help when the
  `rdfs:subClassOf` triples are themselves present in the data — usually they
  are not, because pods store instance data (ABox), rarely the ontology (TBox).

So today, inference is the **consumer's** job, resolved in three steps (see the
chat system prompt):

1. **Probe** what the graph itself asserts (a presence test for
   `rdfs:subClassOf` / `rdfs:subPropertyOf` — `ASK`, or a `SELECT … LIMIT 1`
   where the surface is SELECT-only, as the chat MCP tools are); if present,
   read the hierarchy or use a path.
2. If the graph asserts no ontology — the common case — **infer the set itself**
   from the model's own ontology knowledge plus the types actually present
   (manifest / `describe_pod`), and query it via `VALUES` / `UNION`.
3. Stay **transparent**: flag any coverage that rests on the consumer's own
   inference rather than pod-asserted data.

Step 2 works but is unverifiable and varies per consumer. **The virtual
inference context is the planned upgrade for step 3**: turn "the model guessed
the hierarchy" into "the model asked an authoritative, pod-side layer".

## Mental model

A **virtual inference context** is an implicit, read-only named graph that a
pod exposes alongside its data contexts. It carries **only schema-level
statements** — the TBox: class hierarchy, predicate hierarchy, domains/ranges,
equivalences — **never instance data**.

```
https://pod.example/alice/photos          ← data context (ABox, ACL-gated)
https://pod.example/alice/notes            ← data context (ABox, ACL-gated)
https://pod.example/alice/_system/inference ← virtual inference context (TBox)
```

"Virtual" because the pod need not store it as a real graph: it can be
materialized on demand from whatever the implementation knows — ontology
triples already in the pod, a curated ontology registry (schema.org, FOAF, the
sempods action vocabulary), or a real reasoner if the backing store happens to
have one. The consumer does not care which; it sees one stable contract.

Querying it answers exactly the closure questions:

```sparql
# "What are all subtypes of Event the pod knows about?"
SELECT ?t WHERE {
  ?t <http://www.w3.org/2000/01/rdf-schema#subClassOf>*
     <https://schema.org/Event>
}
```

The consumer feeds the returned set straight into a `VALUES` clause against the
real data contexts — keeping inference (TBox lookup) and retrieval (ABox query)
cleanly separated.

## Why a separate context (not store-level inference)

Materializing entailed triples *into* the data contexts is tempting but wrong
for sempods:

- **Access control.** sempods authorization is per named graph. An inferred
  triple has no well-defined home context; folding it into ABox graphs risks
  making facts derivable across context boundaries. A TBox-only context sidesteps
  this — schema is not sensitive and can be world-readable (or per-pod public)
  without leaking instance data.
- **Swappability.** The contract is a *specification*, like `find`: a pod with a
  real reasoner, a pod with a static curated ontology, and a pod with nothing
  can all satisfy it. Consumers write one query path regardless.
- **Cost.** Forward-chaining a full RDFS/OWL closure into per-pod in-memory
  stores is expensive at scale and must be excluded from backups. A virtual,
  on-demand TBox layer keeps the hot data path untouched.

## Contract (sketch — to be pinned when the server work starts)

Open design space; two plausible access shapes, not mutually exclusive:

- **As a context IRI** the consumer may name in reads (a reserved
  `_system/inference` graph), so existing SPARQL/`get_resource` paths reach it
  without a new verb.
- **As a discovery extension**: `describe_pod` / schema-hint responses include
  the relevant hierarchy slice for the types they report, so a consumer often
  needs no extra round-trip at all.

Either way the response is plain RDF (CONSTRUCT-compatible), so it composes with
the existing retrieval primitives. Field-level shape belongs in code/KDoc once
implemented; this doc stays at the contract level.

### Primary access pattern: pull the TBox with one CONSTRUCT, no ping-pong

The hierarchy is small, so the consumer's cheapest path is a single `CONSTRUCT`
that pulls the relevant TBox slice from the inference context into its local
model — replacing both the capability probe and the hierarchy round-trip:

```sparql
CONSTRUCT {
  ?sub <http://www.w3.org/2000/01/rdf-schema#subClassOf> <https://schema.org/Event> .
}
WHERE {
  ?sub <http://www.w3.org/2000/01/rdf-schema#subClassOf>+ <https://schema.org/Event> .
}
```

(`+`, one-or-more, not `*` — `*` would also bind the superclass to itself via
the zero-length match and materialize a bogus reflexive `Event subClassOf Event`
triple.) If the inference context contributes the subclass hierarchy, this one
call returns the covering types (combine them with the superclass itself); if it
contributes nothing — a pod with no TBox source, or, in a narrowed read, an
ontology graph outside the active scope — the result is empty and the consumer
falls back to its own inference. The thin result is itself the capability
signal.

**Keep schema and content separate.** This CONSTRUCT fetches the *schema*, not
instances — and the schema is tiny, so it never has a scale problem. Once the
consumer holds the covering types, it lists or counts instances as a normal
ABox query (`SELECT` / `COUNT(DISTINCT ?s)` with `VALUES` over the type set).
Widening the CONSTRUCT to drag instances along would reship the whole match set
and truncate at scale — and CONSTRUCT cannot aggregate anyway. This keeps
inference (TBox lookup) and retrieval (ABox query) cleanly separated.

**Scope nuance.** The inference context must be in query scope: in whole-pod
mode it is part of the union automatically; in a narrowed (subset) read the
consumer must include the inference context's IRI in the context set, or the
TBox is out of scope and the closure silently empties.

### What it should expose

- `rdfs:subClassOf` (and its transitive closure) for class coverage.
- `rdfs:subPropertyOf` for predicate coverage.
- Optionally `owl:equivalentClass` / `owl:equivalentProperty`,
  `rdfs:domain` / `rdfs:range` as hints.
- Provenance per statement: was this **asserted** in the pod, or **supplied**
  by a curated registry? The consumer surfaces that distinction the same way it
  flags its own inference today (transparency, step 3).

### Non-goals

- No instance-level entailment (no `owl:sameAs` materialization over ABox, no
  inferred memberships returned as data).
- Not a general reasoner API; it answers hierarchy/coverage lookups, nothing more.
- Does not replace consumer-side step 2 — it is the *authoritative source* a
  capable pod offers so the consumer can stop guessing.

## Open questions

- Registry curation: which vocabularies ship by default, how pod owners extend
  or override them.
- Whether discovery (`describe_pod`) inlining is enough for most cases, making a
  separately queryable context optional.
- Caching / invalidation of the materialized TBox relative to data writes.

## Related

- `ai-retrieval.md` — retrieval primitives; `find`'s exact-match `type` contract.
- `apps/chat/docs/concepts/multi-pod.md` (sempods-apps repo) — the consumer-side
  three-step coverage strategy this layer upgrades.
- `mcp/vision.md` — retrieval primitives and cross-pod orchestration.
