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
  rows are **not** migrated: a family already rotating keeps rotating, and no stored row is
  rewritten to make that true. The decision stands; the cost recorded with it does not. Items 4 and
  5 settled that the stored decision needs a third state for "nothing recorded" and that the refresh
  path has to tell that apart from a refusal, which ends the family — cheaper than a migration, but
  a branch all the same, and it lives there. The price of not migrating is also steeper than "until
  the user reconnects": a reconnect mints a second family and retires nothing, and every rotation
  renews the full TTL. Which
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
- [x] 3 — Make the lifetime a choice in consent, not a sentence about the request. The dialog gets
  its own control for keeping the connection alive, beside the context grants, describing the
  lifetime class rather than naming a scope: without it a short-lived access token, with it a
  rolling refresh token until revoked or left unused. Reuse the service-client lifetime vocabulary
  owned by the owner-installation milestone; this item owns only the durable-connection text.
  `offline_access` in the request preselects the control and nothing more — except where item 5's
  veto already decides the answer: an authorization carrying the installer feature scope must not
  offer a ticked control it cannot honour, so the control is absent there, or shown unavailable
  with the reason.

  The dialog also gains the named way out, and loses a special case. Today an empty selection
  answers `access_denied` and returns before anything is written, so unticking every box — the most
  emphatic way to ask for a disconnect — changes nothing, while unticking all but one revokes the
  rest: the rule that the submission is the authoritative new state inverts at its own endpoint.
  Removing an app's access becomes an explicit, labelled action with a confirmation, and an empty
  submission leads there instead of into a denial that does nothing. The client still receives
  `access_denied` — the request really was denied; what changes is that it now has an effect. Only
  where there is something to remove: on a first authorization there are no grants and no family, so
  the action is not offered and an empty submission stays the plain denial it is today. Telling
  somebody they disconnected something they never connected is the same lie in the other direction.

  Holds I1, I2, I5, I11. Landed with the enforcement it promises, as required below: the control
  and the named way out in `consent.html`, the decision in `PodConsentDecisionStore` — a document
  per authorization, where an absent one is the third state and needs no migration — the gate in
  `exchangeAuthorizationCode`, and the revocation on withholding. `PodAuthEndpointHttpTest` covers
  I1, I2, I3, I7, I11 and the narrowing that the way out is not offered where nothing is held; two
  tests that pinned unconditional issuance now record a granted consent instead. I5 is vacuous
  until the installer feature scope exists.

  Which of the clients in [`../mcp/clients.md`](../mcp/clients.md) send the scope now that a pod
  advertises it is worth knowing for how the control is presented, but nothing waits on the answer.
  The `[oauth/authorize]` line carries the requested scope and whether the control was preselected,
  so the answer accumulates in a running deployment rather than needing an experiment.

  **Items 3 to 5 reach a user together.** They are three pieces of work and one release: a control
  that renders before the exchange honours it tells a person they chose a short-lived connection
  while a ninety-day rolling credential is minted for them, which is the failure this milestone
  exists to remove, dressed as a fix for it. The group's assertion is therefore end-to-end and not
  cosmetic — leave the control unticked and the token response carries no `refresh_token`.
- [x] 4 — Carry `offline_access` through the full consent transaction. The current authorize flow
  persists context grants and `public-read` differently from OIDC scopes, while token exchange later
  narrows to feature scopes. Add explicit persistence and tests from authorize request, consent form,
  authorization code, token exchange and auto-grant so the exchange can distinguish requested-only
  from what the person granted. The chain it builds — request parameter through consent to the
  code — is also what a later OIDC route needs for `nonce`, so it is worth building once.

  **The message layer stays hand-written, and this item is where that was settled.** Moving it onto
  `com.nimbusds:oauth2-oidc-sdk` was planned here and does not survive being tried against the jar:
  `Scope.parse("a\tb")` answers one scope containing a tab where `OAuthSyntax` answers two, so a
  swap changes behaviour in all three services; `Prompt.parse` throws on `none consent` *and* on any
  unknown value, so `isValid` is unreachable without losing the values a public endpoint must keep
  and the ability to tell a contradiction from a typo; and `OAuth2Error` would be a second
  vocabulary beside `OAuthErrorCode`, which exists for the reason nimbus would give. It also runs
  against a decision this repository already states in `../mcp/endpoint.md`: the parsing side uses
  the library, the producing side does not. The pod's OAuth surface is a producing side.

  Holds I4, I14, I15, all of them now. Item 3 brought I4 and I14; I15 is what this item added —
  auto-grant renders nothing, so an authorization whose grants predate the control could never
  acquire a decision and would work short-lived for ever. It falls through to the dialog once, and
  only where there is one: `prompt=none` keeps its silent code, which `PodAuthEndpointHttpTest`
  pins.
- [x] 5 — Harden pod token issuance. The authorization-code exchange issues a refresh token only
  where consent granted a durable connection, and no client is broken by that — one that cannot ask
  is still one the person can grant. Token refresh keeps the existing rotating-family reuse
  detection, and refresh responses cannot silently widen feature scopes.

  Holds I3 to I13 and I17, all of them now except two. I10 is closed by asking the recorded refusal
  again after each insert — the check and the insert are two moments, so whoever arrives second
  undoes the other's work — and I17 names the durable connection in the response while the bearer's
  claim stays slim, except where a client narrowed the request and gets exactly what it asked for.
  I8 holds where access *ends*: withdrawal, disconnect and the offer of the way out all ask about
  the person.

  **Two remainders, and neither is a line to slip in.** A consent page that predates a mere
  *narrowing* still submits: refusing it means retiring the coexisting screens
  `ConsentTransactionStore` allows on purpose, which `two sign-ins running at once in one browser
  both complete` pins — staleness against coexistence, and only one can win.

  And I8 stops at the paths that end access. Where access is *granted*, the subject's own rows are
  what count: `resolveFromGrants` and the refresh path both key on the token's subject, so counting
  an alias's rows at authorize would auto-grant a token carrying no context permissions whose first
  refresh fails. Making that work means re-keying the rows to the issued subject, or teaching
  request-time resolution the alias set — a change to how a grant is addressed, which belongs with
  the resolver rather than with a lifetime control.
  Most of the milestone's weight sits here, and the list is where
  it is checkable. If this item is still one piece of work when it is picked up, split it there.
- [x] 6 — Align revocation and liveness. Both decisions taken, and the four paths agree.

  **The refresh-token `revokeByContextScope` is gone.** A refresh row carries feature scopes only,
  so a family holds no authority over a context to lose, and what closes the re-create-with-same-URI
  window is the grant deletion beside it. The identically named service-client path stays: a
  registration's context scopes *are* what the resolver reads. What replaces the sweep is the
  condition the owner-level cascade already applied — an app left holding nothing loses its family —
  extended to reach the pairs a context deletion empties directly.

  **A reconnect retires the family it supersedes.** One rule from both ends: an answer to the
  lifetime question governs what stands after it. Withholding retires at consent, granting retires
  at the code exchange once the successor exists. It bounds accumulation rather than serialising it,
  and `docs/auth/oauth.md` §"Refresh token rotation" says so — electing a single winner was
  considered and refused, because the loser of that election is a client holding a legitimate token.

  The audit fixed one more disagreement of the same shape: the MCP surface's explicit re-authorize
  revoked for a single `webId`, so a family recorded under an alias kept rotating around the very
  consent screen the 401 exists to force. DCR liveness needed no change, and neither did the hosted
  service's own refresh layer, which item 1 put on this list — it rotates against its own store with
  no consent decision in it, and a vault row the pod handed no refresh token is never selected as
  due.

  Why each sweep reaches as far as it does, and which of these windows no test can enter, is stated
  where the code is: `PodRefreshTokenStore`, `PodGrantsFacade.revokeContextGrants` and the three
  re-asks in `exchangeRefreshToken`.

  **Left open by this item:**

  - `SPS-CTX-017` still describes the removed sweep. Not a conformance gap — the specification is
    pre-`0.1` and descriptive, so its own governance makes the code right and the text the bug — but
    the text needs correcting, and item 8 carries it.
  - `SPS-CTX-017` sits in `spec/modules/context-management.md` and is a module requirement, which
    the vendored index records once this change adopts the published one.
  - Every revocation path holding only a token `sub` reaches the derivable twins and stops there,
    so an identity merge would leave a family under a non-derivable alias standing. Nothing writes
    `linkedIdentities` today, so none exists. When something does, the answer is one answer for
    `revokeWebIdGrants`, the retirement and the MCP path alike, and it is the resolver's — the
    question item 5 parked, asked from the revoking side.

- [x] 7 — Update docs and examples. [`../auth/oauth.md`](../auth/oauth.md) §`offline_access` now
  keeps "not asked for" and "not granted" apart in both directions: asking is not getting, and a
  response carrying no `refresh_token` is no evidence about the request — either the person left the
  control unticked, or the authorization predates it and has nothing recorded. It also states the
  one thing on the refresh path that "down-scope only" does not predict: `offline_access` is taken
  out before the comparison instead of being reported as a scope the token does not cover, so a
  client may echo back the set it was granted. The MCP side gains §"Durable connections" in
  [`../mcp/authentication.md`](../mcp/authentication.md) — where a client discovers the extension,
  and why sending it settles nothing — plus the note under [`../mcp/clients.md`](../mcp/clients.md)
  §Setup that no client config carries a scope and none needs one. And
  [`../concepts/hosted-mcp.md`](../concepts/hosted-mcp.md) says what server-side token refresh, the
  thing that service exists to buy, now rests on: a connection consented as short-lived holds
  nothing to rotate and is never selected by the sweep.

  **Two of this item's own premises were false by the time it was reached.**
  [`../auth/oauth-errors.md`](../auth/oauth-errors.md) was rewritten with item 3 and describes
  `app disconnected` today. And there are no client examples in this repository to update — the MCP
  setup snippets carry a URL and nothing else, and nothing here builds an authorization request. So
  the explicit request is shown where a client author looks for it: `scopes_supported`, which
  [`../mcp/endpoint.md`](../mcp/endpoint.md) documents, and the prose saying what sending it does
  and does not do.

  It also settles the refresh-narrowing question that stood below as an open decision, which item 5
  had already answered in code: a refresh response carries the requested subset, answers
  `invalid_scope` for a feature scope the token does not cover, and never widens
  (`refresh_token down-scope to a granted feature subset succeeds`). It leaves that list decided,
  not dropped.
- [ ] 8 — Carry the change into sempods-spec. The OAuth profile belongs to the specification rather
  than to this repository ([`../auth/README.md`](../auth/README.md)), so a second implementation
  reading `spec/core/auth.md` would still build the permissive issuance this milestone removes.
  It carries item 6's edit too: `SPS-CTX-017` still has context deletion remove "refresh tokens
  scoped to it", which no token issued today can be, so the clause needs to go or to say what it now
  means. That one sits in `spec/modules/context-management.md` rather than in the core chapter.
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
  the moment of consent is what most people read into such a list, and it is a second stored field
  and a second check on the refresh path. Item 6 was where that would have been decided and it was
  not: retiring what a reconnect supersedes bounds how many credentials a person accumulates, not
  how long one of them lives. Until it is decided the classes stay two, short-lived and durable —
  an offered choice that means something other than it says is the failure this milestone exists to
  remove.
- Strictness at `/authorize` — the half no specification settles, the grammars themselves having
  stopped being the question. An unknown scope is dropped in silence today, so a typo
  (`offline-access`) is answered with a working token and no explanation. Answering `invalid_scope`
  (RFC 6749 §4.1.2.1) is the standard behaviour and no longer costs anyone their durable
  connection, now that consent decides it; what it still costs is a refusal for clients that send
  scope names from their own world. Decide it on its own and after item 5, so a client broken by
  one can be told which.

## Invariants

What items 3 to 5 have to be true about, each with the failure it prevents — I12 is the one that
says what a disconnect deliberately does not do. They are the milestone's
definition of done: an implementation is finished when every one of them has a test, and each is
written so that the test is HTTP-level where it can be.

**The decision**

- **I1 — Asking does not grant.** `offline_access` in the request preselects the control and nothing
  more; without the control granted there is no refresh token in the token response.
- **I2 — Not asking does not forbid.** A client that never sent the scope receives one when the
  person grants it. This is what keeps the MCP clients working, and it is why the gate is on the
  grant.
- **I3 — Nothing recorded is not a grant.** An absent decision keeps an already-rotating family
  alive and never mints a new one. Otherwise a static client whose grants predate the control mints
  ninety-day credentials for ever through auto-grant, which renders no dialog at all.
- **I4 — The three states stay apart.** Granted, refused, and nothing recorded are distinguishable
  in storage. Collapsing the third into either of the others kills deployed connections or reopens
  the bypass.

**Limits the dialog cannot overrule**

- **I5 — The installer scope is never durable.** An authorization carrying it does not become
  durable whatever is ticked, and the control is not offered as available there — a dialog must not
  promise what issuance will refuse. Pairing the scope with `public-read` changes nothing.
- **I6 — Anonymous public-read stays short-lived.** No person, no grant, no refresh token.

**A choice that takes effect**

- **I7 — Withdrawal kills what is already held.** The old refresh token stops working, not merely no
  new one is minted: `exchangeRefreshToken` gives up a family only when every grant for the app is
  gone, so unticking while keeping context grants would otherwise change nothing observable.
- **I8 — The person is a set of URIs, on every path that ends access.** A family issued under an
  alias the pod stores dies when its owner withdraws under their canonical WebID — `revokeForUser`
  matches one exact `webId` today, and the survivor would read as "nothing recorded" and be
  grandfathered. The same holds for the grants and for the dialog: `fetchGrantStrings` is called with
  a single `webId` at authorize, so an authorization stored under an alias reads as a first
  authorization, hides the disconnect action by I11's own rule, and leaves a bearer whose `sub` is
  that alias resolving those rows. An HTTP test proves alias-bound context access stops.
- **I9 — A code is bound to the consent it was issued under.** It stays redeemable for five minutes
  and the client holds its verifier, so once the stored decision has moved the code is spent: a
  withdrawal, or a disconnect followed by a narrower reconnect inside that window, must not let it
  mint what the earlier consent allowed. Refusing only an authorization that now holds nothing is
  too weak — after the reconnect it holds something again.
- **I10 — A withdrawal landing mid-issuance still wins.** Both paths read the decision and then
  insert a row — `markRotated` then `issueInFamily` on refresh, read-then-insert on the code
  exchange — so a revocation between the two misses the successor unless each insert is bound to the
  decision it read, or re-checked after it. The code exchange also mints an access token, which is
  no row and cannot be recalled once returned, so the binding covers what the exchange hands back
  and not only what it stores: a disconnect that lands first must not be answered with a bearer.
- **I11 — Disconnect leaves neither.** The app's grants are gone and its refresh token is dead; the
  client still receives `access_denied`. A code it was still holding is spent with the consent that
  issued it (I9), and the action is offered only where there is something to remove — which, by I8,
  is a question about the person and not about one URI.
- **I12 — A disconnect does not recall a bearer already issued.** Access tokens are self-contained
  and carry their feature scopes, so nothing retracts one inside its hour; context access stops at
  once, because that is resolved per request from the grant store. This is the promise narrowed on
  purpose rather than a gap to discover later. What follows for a privileged feature scope is a
  constraint and not a policy: a disconnect cannot shorten a bearer already issued, so whichever
  lifetime the installer scope ends up with — one-shot or deliberately durable, which
  [`owner-app-installation.md`](owner-app-installation.md) decides — it has to hold without relying
  on a revocation reaching that bearer.
- **I13 — A consent page rendered before a disconnect cannot undo it.** Several consent screens may
  coexist on purpose (`ConsentTransactionStore`: "Several can coexist"), so a page opened before the
  disconnect stays submittable after it and would write the grants straight back. I9 does not catch
  this one: nothing is stale from the code's point of view — the form is, and the code that form
  yields is correctly bound to the state it has just written. So a consent transaction carries the
  app's consent generation and compares it on submit, and a two-tab HTTP test proves the older page
  cannot reconnect.

**Nothing that worked stops working**

- **I14 — `prompt=none` keeps its silent code.** With nothing recorded it yields a short-lived token
  and no refresh token. `PodAuthEndpointHttpTest` pins the contract; falling through to consent
  there would answer `consent_required` and retire it.
- **I15 — Auto-grant can acquire a decision.** With nothing recorded and interaction allowed it
  falls through to consent once, or an authorization that predates the control could never hold one.
- **I16 — Legacy families keep rotating.** Item 1's decision, unchanged by any of the above.

**What the response says**

- **I17 — The response names what was granted** wherever that differs from what was asked for
  (RFC 6749 §3.3), in the token response — the authorization response carries `code` and `state`
  only (§4.1.2).

## Acceptance

Every invariant above has a test, and one focused command runs them once code exists:

```bash
./gradlew :sempods-server:test --tests "org.sempods.api.pod.system.auth.*" :sempods-mcp:test :sempods-client:test
```
