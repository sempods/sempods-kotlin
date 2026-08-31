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
the consent UI makes that lifetime visible, and hosted MCP requests the sempods-specific
`offline_access` OAuth extension it depends on instead of relying on today's permissive PoC
behaviour. This roadmap deliberately does not introduce OIDC Provider behaviour; using the
standard-shaped `openid offline_access` request would require a separate milestone for OIDC
discovery, ID-token issuance and validation.

## Work

- [x] 1 — Inventory current refresh-token reliance before changing semantics. The pod issues a
  refresh token on every authorization-code exchange except the anonymous `public-read` one, and
  `offline_access` is read nowhere on the authorize, consent or token path. Three consumers rely on
  that: hosted MCP, which builds the pod authorization URL without a scope and keeps the returned
  token in its vault; MCP clients connected to a pod directly, which cannot ask for a scope the pod
  does not advertise; and the hosted service's own MCP clients, a second refresh layer this
  milestone leaves as it is — the goal above is pod-issued refresh tokens, so item 6 checks only
  that its revocation and liveness still agree. Whether that surface should ask for `offline_access`
  too is its own question, and naming it in an inventory is not the same as planning it. Existing rows are **not** migrated: the item 5 gate sits in the authorization-code
  exchange and the refresh grant checks nothing new, so families already issued keep rotating. That
  costs no code and no legacy branch, and the price is steeper than "until the user reconnects": a
  reconnect mints a second family and retires nothing, and every rotation renews the full TTL. Which
  events do end a family is item 6's subject — and one of the answers there is already known to be
  wrong.
- [x] 2 — Make long-lived interactive clients request refresh-token authority explicitly. Hosted MCP
  now sends `scope=offline_access` on connect and re-authorize, pinned by `WebUiEndpointTest`; both
  discovery documents advertise `scopes_supported`, pinned by `PodOAuthMetadataEndpointHttpTest`,
  which flips two assertions that used to require the field's absence. `docs/auth/oauth.md`
  §`offline_access` states the extension. Nothing about issuance changed — the pod still returns a
  refresh token whether or not the scope was asked for, which is what makes this safe to land ahead
  of item 5 and is exactly what item 5 removes.
- [ ] 3 — Render refresh-token lifetime in consent. Reuse the service-client lifetime vocabulary
  owned by the owner-installation milestone, but this item owns only the `offline_access` text:
  short-lived access token without it, rolling refresh token with it. Tests assert that requesting
  `offline_access` changes the rendered text, and that requesting the installer feature scope
  without `offline_access` does not imply a refresh token.
- [ ] 4 — Carry `offline_access` through the full consent transaction. The current authorize flow
  persists context grants and `public-read` differently from OIDC scopes, while token exchange later
  narrows to feature scopes. Add explicit persistence and tests from authorize request, consent form,
  authorization code, token exchange and auto-grant so the exchange can distinguish requested-only
  from granted `offline_access`. It touches every station, so it is also where the hand-written
  message layer can move onto `com.nimbusds:oauth2-oidc-sdk` — already used on the MCP client side
  and held inside `sempods-auth-core` behind its own types. `Scope` and `OAuth2Error.INVALID_SCOPE`
  are drop-in, and `Prompt.isValid` carries the same rule as `OAuthSyntax.isContradictoryPrompt`
  **inverted** — it answers true for a legal set — so a substitution has to negate it. `Prompt.parse`
  is no substitute at all: it refuses unknown values our parser keeps deliberately. `sempods-server`
  does not carry the SDK yet.
- [ ] 5 — Harden pod token issuance. The authorization-code exchange issues a refresh token only
  when `offline_access` was requested and granted. Token refresh keeps the existing rotating-family
  reuse detection, but refresh responses cannot silently widen feature scopes.
- [ ] 6 — Align revocation and liveness. Check that refresh-token revocation, context-grant
  revocation, service-client revocation and DCR liveness still agree after MCP starts asking for
  `offline_access`. One of them is already empty: `PodRefreshTokenStore.revokeByContextScope` selects
  refresh rows by a context-shaped scope, and no row issued today can hold one, because the code
  exchange stores feature scopes only. Decide whether it goes or whether rows predating slim tokens
  still justify it — the identically named service-client path is unaffected and stays. The same
  item decides whether a reconnect should retire the family it supersedes, which today it does not:
  the code exchange mints a new family and revokes nothing.
- [ ] 7 — Update docs and examples. OAuth docs, MCP setup docs and client examples must show the
  explicit `offline_access` request for durable interactive connections and the absence of refresh
  tokens otherwise.
- [ ] 8 — Carry the change into sempods-spec. The OAuth profile belongs to the specification rather
  than to this repository ([`../auth/README.md`](../auth/README.md)), so a second implementation
  reading `spec/core/auth.md` would still build the permissive issuance this milestone removes.
  The companion change says when a refresh token may be issued, that `offline_access` is a sempods
  extension and not an OIDC scope, and what consent must show about lifetime; check whether
  `spec/modules/mcp.md` needs the same for clients that connect to a pod directly. It is a pull
  request in `sempods/sempods-spec` and cannot ride in this repository's commits, and the
  requirement identifiers cited here have to point at requirements that exist — so it lands before
  this roadmap is consolidated, not after.

## Open decisions

- Directly connected MCP clients — `offline_access` is not part of the MCP authorization chain, and
  a client requests only what the resource server advertises. Item 2 advertises it; what has to be
  measured then is which of the clients in [`../mcp/clients.md`](../mcp/clients.md) actually ask for
  it. A client holding grants beyond `public-read` then has no silent path at all: consent is always
  rendered for a `dyn:` client and `prompt=none` answers `consent_required`, so item 5 either costs
  it an hourly consent dialog or needs a rule of its own. Anonymous public-read is not in that
  bind — it never received a refresh token and keeps its `prompt=none` shortcut, which is reached
  before the dynamic-client rule. Decide on the measurement, not on a guess.
- Strictness at `/authorize` — an unknown scope is dropped in silence today, so a typo
  (`offline-access`) buys a one-hour token and no explanation once item 5 lands. Answering
  `invalid_scope` (RFC 6749 §4.1.2.1) is the standard behaviour, but it is a breaking change of its
  own for clients that send scope names from their own world, and turning it at the same time as
  item 5 makes the two indistinguishable in a bug report. Decide it on the same measurement.
- Request shape — this milestone keeps bare `offline_access` as a deliberately documented sempods
  OAuth extension. Moving to the standard-shaped `openid offline_access` request requires first
  making the pod an OIDC Provider with discovery, ID-token issuance and validation.
- Scope presentation — consent should describe the resulting lifetime class, not only the literal
  `offline_access` scope name.
- Refresh narrowing — decide whether refresh responses preserve the originally granted scope set
  exactly or allow a requested subset, but never allow widening.

## Acceptance

One focused command should cover the milestone once code exists:

```bash
./gradlew :sempods-server:test --tests "org.sempods.api.pod.system.auth.*" :sempods-mcp:test :sempods-client:test
```
