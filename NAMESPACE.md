# The sempods vocabulary namespace

sempods publishes RDF terms under one namespace:

```
https://schema.sempods.org/        prefix: sps:
```

This document states who may mint terms there, what stability implementers can
rely on, and how a term gets added. It matters more than it looks: RDF terms
end up inside other people's stored data. A term that changes meaning breaks
data that was written years earlier, in systems the project never sees.

## Scope

The namespace holds terms that are **specific to sempods** and have no
established equivalent elsewhere — retrieval metadata, control-plane concepts,
conformance markers.

It deliberately holds as little as possible. Where a standard vocabulary
already says the thing, sempods uses the standard vocabulary and does not mint
a synonym:

| Purpose | Vocabulary used |
|---|---|
| General content semantics | `https://schema.org/` |
| RDF / RDFS / OWL primitives | the W3C namespaces |
| Hypermedia affordances | `http://www.w3.org/ns/hydra/core#` |
| Shapes and validation | `http://www.w3.org/ns/shacl#` |
| Provenance | `http://www.w3.org/ns/prov#` |

A term is only minted here when the alternative would be to misuse an existing
one.

## Stability guarantees

For every term published in this namespace:

1. **The IRI never changes.** URIs are permanent identifiers. A term is not
   moved, renamed or re-cased after publication.
2. **The meaning never narrows.** A term's definition may be clarified or
   extended, never redefined in a way that makes previously valid data wrong.
3. **Terms are deprecated, not deleted.** A retired term is marked
   `owl:deprecated true`, keeps resolving, and names its successor via
   `rdfs:seeAlso`. It stays in the published vocabulary document
   indefinitely.
4. **Deprecation is announced at least 12 months** before a term is removed
   from the recommended set, via the vocabulary document and the release
   notes.

The namespace IRI itself is **not versioned**. Versioning lives in the
vocabulary *document* (`dcterms:hasVersion`), not in the identifiers — a
versioned namespace would mean every schema revision invalidates stored data,
which is the outcome these guarantees exist to prevent.

## What is in it today

Three terms, all describing the metadata a `find` response carries about each hit:

| Term | |
|---|---|
| `sps:FindResult` | the per-hit metadata node |
| `sps:findResult` | links a matched resource to that node |
| `sps:engine` | which search engine produced the hit |

They arrived the way the process below describes: an implementation needed them,
used them, and they were adopted rather than reinvented. The excerpt and the rank
on that node are `schema:text` and `schema:position` — schema.org terms, reused
rather than duplicated here.

The definitions live in [`vocabulary/sempods.ttl`](vocabulary/sempods.ttl).

## Dereferencing

**Today the vocabulary document is the file in this repository**, and
`https://schema.sempods.org/` does not yet serve it. Nothing breaks — an RDF term
is an identifier first and a URL second — but it is a gap, and stating it is
better than implying otherwise.

The intent is that the namespace IRI resolves to the vocabulary document with
content negotiation for Turtle, JSON-LD and HTML, and that each term IRI resolves
to its definition within it. Until then, cite the file.

## Third parties: mint in your own namespace

If you implement sempods and need a term the vocabulary does not have, define
it under a namespace **you control** — for example
`https://example.com/ns/yourfeature#`. Do not mint terms under
`https://schema.sempods.org/`.

This is not territorial. It is the only way either side stays safe:

* Your data keeps working regardless of what this project decides, because
  nobody else governs your identifiers.
* Readers of your data can tell which parts are the standard and which are
  yours.
* The project cannot accidentally redefine a term you depend on, because it
  never had the term.

Terms minted by a third party under this namespace carry no guarantee from this
project and may collide with a later official term of the same name — which is
the concrete failure this rule prevents.

## Proposing a term

Extensions that prove useful in practice are exactly how a vocabulary should
grow. If a term you defined in your own namespace turns out to be generally
useful:

1. Open an issue describing the term, its domain and range, and the data you
   already write with it.
2. If it is adopted, it is minted here, and your original term is documented
   as an alias via `owl:equivalentProperty` / `owl:equivalentClass` so existing
   data keeps validating.
3. Nothing you already wrote has to be rewritten. Both IRIs resolve, both stay
   valid.

Adoption favours terms with running implementations over terms with good
arguments.

## Licence

The vocabulary document and these definitions are licensed **CC BY 4.0**,
consistent with the rest of the project's documentation. Using the terms in
your data requires no licence, no attribution and no permission — that is what
a vocabulary is for.
