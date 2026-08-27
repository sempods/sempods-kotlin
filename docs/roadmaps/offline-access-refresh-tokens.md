# `offline_access` refresh-token hardening (SOLL)

> Progress is tracked in place. Completed items stay in this file, marked done, until the whole
> milestone is consolidated. Do not prune them individually — the roadmap documents progress, not
> only remaining work.

Concept: [`../concepts/app-installation.md`](../concepts/app-installation.md#token-lifetime-is-part-of-consent-soll)
names the consent rule: users must see not only the authority they grant, but the lifetime class of
the resulting credential. This roadmap applies that rule to refresh tokens and hosted MCP.

When this is done, pod refresh tokens are issued only when `offline_access` was explicitly granted,
the consent UI makes that lifetime visible, and hosted MCP requests the scope it depends on instead
of relying on today's permissive PoC behaviour.

## Work

- [ ] 1 — Inventory current refresh-token reliance before changing semantics. Hosted MCP currently
  builds the pod authorization URL without an explicit scope and stores the refresh token returned by
  the pod, so it relies on refresh tokens without requesting `offline_access`. Check existing PoC
  rows and decide whether they are migrated, preserved until reconnect, or intentionally broken with
  a reconnect requirement.
- [ ] 2 — Make long-lived interactive clients request `offline_access` explicitly. Hosted MCP must
  include the scope when it needs a durable pod connection, and tests pin the authorize URL so the
  dependency remains visible.
- [ ] 3 — Render token lifetime in consent. The consent UI distinguishes short-lived access tokens,
  rolling refresh tokens granted by `offline_access`, and durable service-client secrets. Tests assert
  that requesting `offline_access` changes the rendered text, and that requesting `service-clients`
  without `offline_access` does not imply a refresh token.
- [ ] 4 — Harden pod token issuance. The authorization-code exchange issues a refresh token only
  when `offline_access` was requested and granted. Token refresh keeps the existing rotating-family
  reuse detection, but refresh responses cannot silently widen feature scopes.
- [ ] 5 — Align revocation and liveness. Check that refresh-token revocation, context-grant
  revocation, service-client revocation and DCR liveness still agree after MCP starts asking for
  `offline_access`.
- [ ] 6 — Update docs and examples. OAuth docs, MCP setup docs and client examples must show the
  explicit `offline_access` request for durable interactive connections and the absence of refresh
  tokens otherwise.

## Open decisions

- PoC migration — from actual stored rows and users, choose reconnect-only, compatibility-until-use,
  or explicit migration before item 4 changes token issuance.
- Scope presentation — consent should describe the resulting lifetime class, not only the literal
  `offline_access` scope name.
- Refresh narrowing — decide whether refresh responses preserve the originally granted scope set
  exactly or allow a requested subset, but never allow widening.

## Acceptance

One focused command should cover the milestone once code exists:

```bash
./gradlew :sempods-server:test --tests "org.sempods.api.pod.system.auth.*" :sempods-mcp:test :sempods-client:test
```

Before implementation starts, inspect the hosted MCP authorize URL construction and refresh-token
storage path, then record the chosen PoC migration behaviour in item 1.
