# AGENTS.md — sempods-mcp

Scope: applies to `sempods-mcp/**`. The [repository instructions](../AGENTS.md) apply.

## Architecture constraints

- This Ktor service fronts multiple pods over their public HTTP/Auth routes. Do not import
  pod-server services or per-pod endpoint classes.
- Shared tool semantics belong to `sempods-mcp-core`; hosted fan-out, profile selection,
  quotas, tokens and audit stay here. Shared OAuth machinery belongs to `sempods-auth-core`.
- Inbound HTTP is Ktor; outbound pod access uses `SempodsHttpTransport`. Do not bypass its
  outbound policy. `PodIo` bridges blocking calls into suspending code with cancellation and tracing.
- Keep the service an ordinary OAuth client to every pod. The pod remains the authority
  for consent, grants and revocation.
- Keep Guice wiring in service composition. Follow the repository documentation and issue-work procedures.

## Deployment stance (PoC — no migrations)

The deployment is a **PoC used only by the maintainer**: a breaking schema / crypto change
assumes a **fresh setup** (drop the DB, re-connect pods and AI clients) instead of carrying
migration logic. The code deliberately holds **no startup migration passes** and no
legacy-tolerant credential reads: a token row missing its registration, issuer or subject is
unreadable. Reporting can fall back to registry descriptions; missing verification evidence is
reported as unverified. Encryption-at-rest expects ciphertext with no plaintext fallback.
Once the service carries **real user state**, migrations become a hard requirement.

## Documentation

- [Module overview](docs/README.md).
- [Runtime](docs/runtime.md) — persistence, profiles, renewal, outbound policy and audit.
- [Tool contract](docs/tool-contract.md).
- [Hosted MCP architecture](../docs/concepts/hosted-mcp.md) — custody and authority boundaries.
- [Interoperability proposal](docs/proposals/interoperability.md) — proposed design with its owning issue.
- [Multi-tenancy review](docs/multi-tenancy-review.md) — review evidence, outside published runtime documentation.
