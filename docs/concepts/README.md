# Concepts

This folder contains retained architecture and proposed design material awaiting classification.
Its status, destinations and migration ownership are defined in
[the transition](../agents/documentation-strategy.md#transition).

## Here today

- [`modularity.md`](modularity.md) — sempods as a *reference implementation*: which behaviours are
  deployment-selected seams, which invariants are not, and what open-sourcing still needs.
- [`graph-retrieval.md`](graph-retrieval.md) — the read pattern every consumer builds on:
  consumer-agnostic `find` plus structural traversal.
- [`hosted-mcp.md`](hosted-mcp.md) — one MCP service fronting many pods, and the conformance
  profile a third-party pod would have to meet.
- [`mcp-agent-interface.md`](mcp-agent-interface.md) — where the per-pod MCP surface is going:
  SHACL-gated app contracts, cross-pod orchestration.
- [`inference-context.md`](inference-context.md) — a proposed TBox layer for type and predicate coverage;
  SOLL throughout, not implemented.
- [`app-installation.md`](app-installation.md) — retained owner-installation design;
  [the owning issue](https://github.com/sempods/sempods-kotlin/issues/35) carries target and progress.

## Template for verified architecture documentation

```markdown
# <Topic>

## Purpose

What this topic is and why it has this shape.

## Behaviour

What the system does today, verifiable in code. Link to the owning reference documentation and
code paths for detail.

## Constraints

Non-obvious boundaries and rationale a maintainer needs. Omit ordinary behaviour and history.

## See also

- Owning reference documentation and code paths
- Related planning issue, if further work is proposed
```

Use this template after verification under the strategy's
[preservation rule](../agents/documentation-strategy.md#preserving-current-explanations).
The strategy also defines placement at a module's documentation scope.
