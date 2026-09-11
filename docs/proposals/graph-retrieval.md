# Graph retrieval extensions

> **Disposition: proposed — not implemented.** [Issue #136](https://github.com/sempods/sempods-kotlin/issues/136) owns review,
> decisions and adoption links. Acceptance does not assert implementation.

## Current baseline and proposed adapters

The `find` entry primitive shipped — as `GET /{pod}/_system/find` and an
MCP `find` tool over one `FindService` (see [`../mcp/tools.md`](../mcp/tools.md#find-read)
and the contract in [`graph-retrieval.md`](../concepts/graph-retrieval.md)). Structural
traversal uses the shipped `get_resource` / `sparql_*`; there is no separate
`retrieve` / `expand` primitive. What is still vision, all **behind the same
contract** (no consumer change):

- **Vector / hybrid `find`** — a vector engine (or OpenSearch hybrid) behind
  the same swappable adapter SPI, so fuzzy questions land the right resources
  without exact label matches. Follows the pod-level vector index (see
  [`../vision.md`](../vision.md)), and brings cross-adapter rank fusion
  that replaces the PoC's deterministic IRI-order cap once several engines
  merge.
- **Per-type expansion registry** — replace the fixed `type`/`label`/`name`
  expansion with a per-pod / per-context, manage-extendable set ("app-context
  infos"), conditionally recursive (e.g. `Event → location → {name, address}`).
- **General predicate filter** — a caller filter on arbitrary predicates
  beyond the shipped `type` facet, carried by the existing `POST` form; the
  difficulties are spelled out below.

## General predicate filters

`type` is supported (above); a *general* equality filter on arbitrary
predicates (`author = <iri>`, `status = "open"`, …), carried in a `POST` body,
is the deferred part. Two difficulties: resolving an arbitrary predicate to a
concrete filter is hard because each search engine has its own structure — a
vector or text index does not natively map `author = <iri>` onto its query
(whereas `rdf:type` is a universal facet every engine already has); and a
half-honored filter would reintroduce the plausible-but-wrong results graph
retrieval exists to avoid (so it would have to be fail-closed: honor fully or
reject). It can be added later as a **purely additive** extension — an optional
`filter` parameter in the existing `POST` form — without breaking the contract, once the
impact and per-engine implementation are understood. Until then a consumer
narrows by other predicates client-side after fetching the required predicates; the fixed expansion alone may not contain them.

## Answer generation

A proposed `model2text` step would turn retrieved RDF into an answer. `AiSemFacade`
currently implements text2model and model2model; callers own answer generation.
A session graph can accumulate results and avoid duplicate fetches, but cannot guarantee
coverage or prevent a language model from making unsupported claims. Review provenance,
citation and unsupported-answer evaluations before adopting a server-side answer contract.
