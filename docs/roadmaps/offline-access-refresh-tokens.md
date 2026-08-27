# `offline_access` refresh-token hardening (SOLL)

> Progress is tracked in place. Completed items stay in this file, marked done, until the whole
> milestone is consolidated. Do not prune them individually — the roadmap documents progress, not
> only remaining work.

Concept: [`../concepts/app-installation.md`](../concepts/app-installation.md#token-lifetime-is-part-of-consent-soll)
names the consent rule: users must see not only the authority they grant, but the lifetime class of
the resulting credential. This roadmap applies that rule to refresh tokens and hosted MCP.
Consolidation of this roadmap may update only the refresh-token and `offline_access` parts of that
shared concept; the service-client installation state belongs to
[`owner-app-installation.md`](owner-app-installation.md).

When this is done, pod refresh tokens are issued only when `offline_access` was explicitly granted,
the consent UI makes that lifetime visible, and hosted MCP requests the standard-shaped
`openid offline_access` scope set it depends on instead of relying on today's permissive PoC
behaviour. If sempods deliberately accepts bare `offline_access`, that must be documented as a
sempods-specific extension rather than plain OAuth or OIDC.

## Work

- [ ] 1 — Inventory current refresh-token reliance before changing semantics. Hosted MCP currently
  builds the pod authorization URL without an explicit scope and stores the refresh token returned by
  the pod, so it relies on refresh tokens without requesting `offline_access`. Check existing PoC
  rows and decide whether they are migrated, preserved until reconnect, or intentionally broken with
  a reconnect requirement.
- [ ] 2 — Make long-lived interactive clients request refresh-token authority explicitly. Hosted MCP
  should request `openid offline_access` when it needs a durable pod connection, and tests pin the
  authorize URL so the dependency remains visible. If the chosen profile allows bare
  `offline_access`, the docs and metadata must advertise it as a sempods extension.
- [ ] 3 — Render refresh-token lifetime in consent. Reuse the service-client lifetime vocabulary
  owned by the owner-installation milestone, but this item owns only the `offline_access` text:
  short-lived access token without it, rolling refresh token with it. Tests assert that requesting
  `offline_access` changes the rendered text, and that requesting the installer feature scope
  without `offline_access` does not imply a refresh token.
- [ ] 4 — Carry `offline_access` through the full consent transaction. The current authorize flow
  persists context grants and `public-read` differently from OIDC scopes, while token exchange later
  narrows to feature scopes. Add explicit persistence and tests from authorize request, consent form,
  authorization code, token exchange and auto-grant so the exchange can distinguish requested-only
  from granted `offline_access`.
- [ ] 5 — Harden pod token issuance. The authorization-code exchange issues a refresh token only
  when `offline_access` was requested and granted. Token refresh keeps the existing rotating-family
  reuse detection, but refresh responses cannot silently widen feature scopes.
- [ ] 6 — Align revocation and liveness. Check that refresh-token revocation, context-grant
  revocation, service-client revocation and DCR liveness still agree after MCP starts asking for
  `offline_access`.
- [ ] 7 — Update docs and examples. OAuth docs, MCP setup docs and client examples must show the
  explicit `offline_access` request for durable interactive connections and the absence of refresh
  tokens otherwise.

## Open decisions

- PoC migration — from actual stored rows and users, choose reconnect-only, compatibility-until-use,
  or explicit migration before token issuance changes.
- Request shape — prefer the OIDC-shaped `openid offline_access` request for standards alignment.
  Accept bare `offline_access` only if the profile is deliberately documented as sempods-specific.
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
