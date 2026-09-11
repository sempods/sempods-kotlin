# Architecture explanations

This folder contains maintained explanations of current code. The folder name is an
organizational choice, not a separate document type.

- [Modularity](modularity.md) — deployment-selected seams and invariant boundaries.
- [Graph retrieval](graph-retrieval.md) — current find and structural traversal.
- [Hosted MCP](hosted-mcp.md) — credential custody and the shared tool architecture.
- [Service-client provisioning](app-installation.md) — current operator registration and consent lifetime.

Unimplemented designs live in [proposals](../proposals/README.md), with planning owned by issues.

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
