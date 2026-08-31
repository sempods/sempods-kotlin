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

When this is done, a pod issues a refresh token only where the person granted a durable connection
in consent, the dialog shows that lifetime as a choice rather than as a scope name, and hosted MCP
asks for the sempods-specific `offline_access` extension it depends on instead of relying on today's
permissive PoC behaviour.

The grant is the person's and not the client's: the request preselects the control, the consent
decides it. That rule, why it has to be that way and the two limits the dialog cannot overrule are
the concept's, not this file's — see its §"Token lifetime is part of consent".

This roadmap introduces no OIDC Provider behaviour. The standard-shaped `openid offline_access`
request belongs to the planned per-pod OIDC route, on a parallel path with an issuer of its own, and
that is a separate milestone. The **lifetime policy** below needs nothing from it and holds on
either route, because it sits on the consent transaction rather than on the request. The
**machinery** is shared in one direction: item 4 builds the request-to-consent-to-code chain that an
OIDC route needs for `nonce`, so an OIDC milestone starting after this one inherits it and one
starting before it builds it.

## Work

- [x] 1 — Inventory current refresh-token reliance before changing semantics. The pod issues a
  refresh token on every authorization-code exchange except the anonymous `public-read` one, and
  `offline_access` is read nowhere on the authorize, consent or token path. Three consumers rely on
  that: hosted MCP, which builds the pod authorization URL without a scope and keeps the returned
  token in its vault; MCP clients connected to a pod directly, which cannot ask for a scope the pod
  does not advertise; and the hosted service's own MCP clients, a second refresh layer this
  milestone leaves as it is — the goal above is pod-issued refresh tokens, so item 6 checks only
  that its revocation and liveness still agree. Whether that surface should ask for `offline_access`
  too is its own question, and naming it in an inventory is not the same as planning it. Existing
  rows are **not** migrated: the item 5 gate sits in the authorization-code exchange and the refresh
  grant checks nothing new, so families already issued keep rotating. That
  costs no code and no legacy branch, and the price is steeper than "until the user reconnects": a
  reconnect mints a second family and retires nothing, and every rotation renews the full TTL. Which
  events do end a family is item 6's subject — and one of the answers there is already known to be
  wrong.
- [x] 2 — Make long-lived interactive clients request refresh-token authority explicitly. Hosted MCP
  now sends `scope=offline_access` on connect and re-authorize — from every pod whose discovery
  advertises it, and from no other, because this service connects to pods it does not host and an
  authorization server may refuse a scope it does not know. Both halves are pinned by
  `WebUiEndpointTest`. The pod side advertises `scopes_supported` in both discovery documents,
  pinned by `PodOAuthMetadataEndpointHttpTest`, which flips two assertions that used to require the
  field's absence, and `docs/auth/oauth.md` §`offline_access` states the extension. Nothing about
  issuance changed — the pod still returns a refresh token whether or not the scope was asked for,
  which is what makes this safe to land ahead of item 5 and is exactly what item 5 removes.
- [ ] 3 — Make the lifetime a choice in consent, not a sentence about the request. The dialog gets
  its own control for keeping the connection alive, beside the context grants, describing the
  lifetime class rather than naming a scope: without it a short-lived access token, with it a
  rolling refresh token until revoked or left unused. Reuse the service-client lifetime vocabulary
  owned by the owner-installation milestone; this item owns only the durable-connection text.
  `offline_access` in the request preselects the control and nothing more. Tests assert both
  directions: asking does not grant, and a client that never asked can still be granted durability
  by the person in front of the dialog. Which of the clients in
  [`../mcp/clients.md`](../mcp/clients.md) send the scope now that a pod advertises it is worth
  knowing for how the control is presented, but nothing here waits on the answer.

  **Items 3 to 5 reach a user together.** They are three pieces of work and one release: a control
  that renders before the exchange honours it tells a person they chose a short-lived connection
  while a ninety-day rolling credential is minted for them, which is the failure this milestone
  exists to remove, dressed as a fix for it. The group's assertion is therefore end-to-end and not
  cosmetic — leave the control unticked and the token response carries no `refresh_token`.
- [ ] 4 — Carry `offline_access` through the full consent transaction. The current authorize flow
  persists context grants and `public-read` differently from OIDC scopes, while token exchange later
  narrows to feature scopes. Add explicit persistence and tests from authorize request, consent form,
  authorization code, token exchange and auto-grant so the exchange can distinguish requested-only
  from what the person granted. The chain it builds — request parameter through consent to the
  code — is also what a later OIDC route needs for `nonce`, so it is worth building once. It
  touches every station, so it is also where the hand-written
  message layer can move onto `com.nimbusds:oauth2-oidc-sdk` — already used on the MCP client side
  and held inside `sempods-auth-core` behind its own types. `Scope` and `OAuth2Error.INVALID_SCOPE`
  are drop-in, and `Prompt.isValid` carries the same rule as `OAuthSyntax.isContradictoryPrompt`
  **inverted** — it answers true for a legal set — so a substitution has to negate it. `Prompt.parse`
  is no substitute at all: it refuses unknown values our parser keeps deliberately. `sempods-server`
  does not carry the SDK yet.
- [ ] 5 — Harden pod token issuance. The authorization-code exchange issues a refresh token only
  where consent granted a durable connection, and the token response names what was granted wherever
  that differs from what was asked for (RFC 6749 §3.3). No client is broken by this, which is the
  point of gating on the grant: one that cannot ask is still one the person can grant. Two rules the
  dialog cannot overrule — an authorization that carries the installer feature scope at all never
  becomes durable ([`owner-app-installation.md`](owner-app-installation.md)), because a checkbox
  cannot make that escalation visible and pairing the scope with `public-read` does not change that;
  and anonymous public-read keeps its refresh-token-free shortcut. Withholding durability at a later
  consent revokes the families that authorization already has: today `exchangeRefreshToken` gives up
  a family only when every grant for the app is gone, so a person who unticks the control while
  keeping their context grants would otherwise have changed nothing they can observe. Test that the
  old refresh token stops working, not merely that no new one is minted — and that a code minted
  under an earlier, durable consent cannot mint a durable family after the withdrawal, since it
  stays redeemable for five minutes and the client holds its verifier. It must survive a refresh
  landing in the same instant too: rotation is two writes, `markRotated` then `issueInFamily`, and a
  revocation in between revokes what exists and misses the successor — bind the successor to the
  stored decision or re-check it after the insert, and test the two running together. Token refresh
  keeps the existing rotating-family reuse detection, and refresh responses cannot silently widen
  feature scopes.
- [ ] 6 — Align revocation and liveness. Check that refresh-token revocation, context-grant
  revocation, service-client revocation and DCR liveness still agree after MCP starts asking for
  `offline_access`. One of them is already empty: `PodRefreshTokenStore.revokeByContextScope` selects
  refresh rows by a context-shaped scope, and no row issued today can hold one, because the code
  exchange stores feature scopes only. Decide whether it goes or whether rows predating slim tokens
  still justify it — the identically named service-client path is unaffected and stays. The same
  item decides whether a reconnect should retire the family it supersedes, which today it does not:
  the code exchange mints a new family and revokes nothing. Item 5's withdrawal path is the same
  question asked from the other end, and the two answers have to agree.
- [ ] 7 — Update docs and examples. OAuth docs, MCP setup docs and client examples must show the
  explicit `offline_access` request for a client that wants the durable option preselected, and must
  keep "not asked for" and "not granted" apart: a refresh token follows the grant, so a client that
  never sent the scope can still hold one, and only a consent that withheld durability means there
  is none.
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

- Graduated lifetimes — a dialog offering "one month" or "two days" has to say which clock it
  means, and there is only one today: a family's TTL is rolling and every rotation renews it in
  full, so a chosen duration would silently mean "after this much disuse". An absolute deadline from
  the moment of consent is what most people read into such a list, and it is a second stored field,
  a second check on the refresh path and a question for item 6. Until that is decided the classes
  stay two, short-lived and durable — an offered choice that means something other than it says is
  the failure this milestone exists to remove.
- Strictness at `/authorize` — an unknown scope is dropped in silence today, so a typo
  (`offline-access`) is answered with a working token and no explanation. Answering `invalid_scope`
  (RFC 6749 §4.1.2.1) is the standard behaviour and no longer costs anyone their durable
  connection, now that consent decides it; what it still costs is a refusal for clients that send
  scope names from their own world. Decide it on its own and after item 5, so a client broken by
  one can be told which.
- Refresh narrowing — decide whether refresh responses preserve the originally granted scope set
  exactly or allow a requested subset, but never allow widening.

## Acceptance

One focused command should cover the milestone once code exists:

```bash
./gradlew :sempods-server:test --tests "org.sempods.api.pod.system.auth.*" :sempods-mcp:test :sempods-client:test
```
