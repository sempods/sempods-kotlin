# Concepts

This folder holds current architecture explanations under the maintained IST documentation type.
Public targets and progress belong in issues; substantive proposed design may use a proposal under
`docs/proposals/` at the responsible scope. See
[the strategy](../agents/documentation-strategy.md) for ownership, placement and writing rules.

The six existing documents below still contain mixed or proposed material. They await paragraphwise
verification and transfer in [#121](https://github.com/sempods/sempods-kotlin/issues/121), under the
[transition](../agents/documentation-strategy.md#transition). A `(Concept)` title does not establish
implemented status. Useful implemented explanations survive under the strategy's
[preservation rule](../agents/documentation-strategy.md#preserving-current-explanations).

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
- [`app-installation.md`](app-installation.md) — how a pod owner turns an interactive decision into
  a durable service-client credential.

## Template for current architecture documentation

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

The folder may also exist at a module's `docs/concepts/` for architecture specific to that module.
Reference the strategy rather than copying its rules or this index into the module.
