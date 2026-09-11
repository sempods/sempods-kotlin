# Hosted MCP service

A standalone Ktor service that exposes one MCP connection over a user's connected pods.
It uses the same single-pod tool executor as the pod-immanent MCP surface.

- [Runtime](runtime.md) — service identity, profiles, pod connections, renewal and operational constraints.
- [Tool contract](tool-contract.md) — current tools, arguments and result envelopes.
- [Architecture](../../docs/concepts/hosted-mcp.md) — credential custody and authority boundaries.
- [Interoperability proposal](proposals/interoperability.md) — proposed portable contract and its owning issue.
- [Multi-tenancy review](multi-tenancy-review.md) — review evidence.

Read [module instructions](../AGENTS.md) before modifying the service.
