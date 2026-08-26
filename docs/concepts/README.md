# Concepts

A concept document owns one topic. It says what the topic *is*, what is true today, and what the
target state is — and it links to the roadmap implementing that target and to the IST documents
describing the parts already built.

A concept outlives the roadmaps that implement it. Where a roadmap is dissolved by design, a
concept's SOLL section is rewritten as IST when it comes true, and the document stays. The one
exception is a concept that ends up with nothing left to say: if it is entirely IST and small
enough, it folds into the IST document it points at and is deleted — see
[`../agents/roadmap-lifecycle.md`](../agents/roadmap-lifecycle.md) §2. Keeping a document that only
repeats another one is not preservation.

This folder is the repository-wide one. A module may hold its own — `<module>/docs/concepts/` — for
a topic that is true of that module only. The rules below apply at every level; do not copy this
file into a module, reference it.

See [`../agents/documentation-strategy.md`](../agents/documentation-strategy.md) for the four
documentation types and the writing rules.

## Here today

- [`modularity.md`](modularity.md) — sempods as a *reference implementation*: which behaviours are
  deployment-selected seams, which invariants are not, and what open-sourcing still needs.
- [`graph-retrieval.md`](graph-retrieval.md) — the read pattern every consumer builds on:
  consumer-agnostic `find` plus structural traversal.
- [`hosted-mcp.md`](hosted-mcp.md) — one MCP service fronting many pods, and the conformance
  profile a third-party pod would have to meet.
- [`mcp-agent-interface.md`](mcp-agent-interface.md) — where the per-pod MCP surface is going:
  SHACL-gated app contracts, cross-pod orchestration.
- [`inference-context.md`](inference-context.md) — a TBox layer for type and predicate coverage.

## Template

```markdown
# <Topic> (Concept)

## Purpose

What this topic is, and why it exists in the shape it does. Two or three paragraphs. Sections
below are marked **IST** (implemented, verifiable in code) or **SOLL** (target state).

## <Aspect> (IST)

What the system does today. Present tense. Links to the IST documents and code paths that carry
the detail.

## <Aspect> (SOLL)

The target state, and the constraint or trade-off behind it. Not a plan — a plan is a roadmap.

## Not in scope

What this concept deliberately does not cover, where a reader might expect it to.

## See also

- IST documentation: `../<topic>.md`
- Roadmap: `../roadmaps/<milestone>.md`   ← while one is running
```

## Rules

- **Never mix IST and SOLL in one section.** Mark each section, or the title if the whole document
  is one or the other.
- The concept carries the reasoning permanently. A roadmap links here rather than repeating it —
  that is what keeps roadmaps thin and consolidation cheap.
- Do not restate the IST documents. Link to them. The concept is the level above.
- No history and no decision log. Keep only the reasoning a future reader needs in order not to undo
  the decision.
