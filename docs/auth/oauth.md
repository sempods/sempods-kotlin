# OAuth Flows

How apps and MCP-style clients obtain pod-scoped access tokens.

The only user-facing flow sempods supports is **OAuth 2.1
Authorization Code with PKCE** (S256). Implicit flow and password grants
are not accepted. PKCE is mandatory for `dyn:*` clients (which register
with `token_endpoint_auth_method=none`) and strongly recommended for
`did:web:*` clients.

Backend services without a user in the loop use the **Client
Credentials grant** (RFC 6749 §4.4), restricted to out-of-band
registered service clients — see `service-clients.md`.

For the underlying authorization model (scopes, grants, enforcement)
see sempods-spec `spec/core/grants.md`. For identity tokens used during the authorize
step see `identity.md`.

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /{pod}/_system/auth/authorize` | Authorization Code request |
| `POST /{pod}/_system/auth/token` | Token exchange & refresh |
| `GET /{pod}/_system/auth/jwks.json` | Pod's public signing keys |
| `POST /{pod}/_system/auth/register` | RFC 7591 Dynamic Client Registration |
| `GET /{pod}/.well-known/oauth-protected-resource` | RFC 9728 Protected Resource Metadata |

## Client identity: `did:web:*` vs `dyn:*`

Two `client_id` shapes, with different rules:

Both shapes ask `RedirectUri.isValid` first, before any
client-specific rule: absolute, no fragment, no `code`, `response` or
`state` in the query, `https` on any host, `http` only on loopback.
`/register` applies it too, so an address a login could never honour is
refused at registration. A code therefore reaches a cleartext address
only on the user's own machine, and that case is gated again below.
The query rule has the same reason as the fragment one: those names
belong to the response, and a registered copy is read as the value this
server chose.

`client_uri`, `logo_uri`, `tos_uri` and `policy_uri` get a second,
separate rule — `ClientMetadataUri.isValid`: absolute, `https` on any
host, `http` only on loopback. RFC 7591 constrains none of them, so this
one is the pod's: those four are addresses shown to a person rather than
addresses the pod sends anything to, and a value that fails is refused
at registration and omitted where it is read.

### `did:web:*` — origin-bound apps (e.g., Focus, AppShell-based SPAs)

- The identifier *names* an address; nothing is dereferenced to check
  it. The check is local and structural — see
  `sempods-auth-core/src/main/kotlin/org/sempods/auth/core/DidWeb.kt`,
  which states what it deliberately does not do and why (no SSRF
  surface, no cache, no third-party availability in the login path).
- `redirect_uri` must match the DID host **and port** (or loopback for
  local development, which is wired from the environment and never
  defaulted on). `DidWeb.Target.covers()` says nothing about the scheme;
  the rule above is what keeps `http://` off the DID's own origin.
- **Loopback is development-only whichever way it is reached.** An
  identifier that names a loopback origin — `did:web:localhost%3A5173`
  — is refused outright in production, ahead of the host-and-port match
  that would otherwise answer for it. A `did:web:` is asserted, not
  issued, so anyone could otherwise route a code to whatever listens on
  that port on the user's machine.
- A path-scoped identifier narrows it further: `did:web:example.org:mcp`
  is answerable only at or below `/mcp`, matched **per path segment**,
  so `/mcp` and `/mcp/cb` are covered and `/mcp-other/cb` is not.
  Comparing host and port alone would let two services sharing a host
  receive each other's codes. `DidWeb.Target.covers()` is the one place
  that answers this, for both the pod and the id-server.
- No DCR; the app is its own identity.
- Consent behaves per the `prompt` parameter — see the table under
  §"The `prompt` parameter", which is where that rule lives.

### `dyn:*` — RFC 7591 dynamic clients (e.g., Claude Desktop, Copilot, ChatGPT)

- Registered via `/register`; client metadata stored verbatim along
  with a deterministic fingerprint (SHA-256 over `clientName`,
  `userAgent`, and the redirect-URI set with loopback ports stripped)
  for dedup. A repeat registration with the same fingerprint reuses the
  existing `dyn:` client id.
- Loopback redirect URIs are matched with port-stripping (RFC 8252
  §7.3); non-loopback redirect URIs stay port-strict.
- **Always render the consent UI** on `/authorize`, regardless of
  `prompt` or existing grants. Existing grants are pre-checked, so
  the common path is one click. Rationale: MCP clients only reach
  `/authorize` when the user just triggered the flow, so an explicit
  confirmation is wanted; `did:web:*` clients hit `/authorize` from
  background-facing UI where a pop-up would be disruptive.

`/token` exchanges are unaffected by the consent override — in-session
refreshes stay silent for both client classes.

## Authorize flow (overview)

1. Client generates PKCE (`code_verifier`, `code_challenge` via S256)
   and `state`.
2. Browser is redirected to `/authorize` with `response_type=code`,
   `client_id`, `redirect_uri`, `state`, `code_challenge`,
   `code_challenge_method=S256`, optional `scope`, optional `prompt`.
3. The pod resolves identity (see `identity.md`). With nobody signed
   in it parks the whole request server-side, sends the browser to the
   id-server's own `/authorize`, and resumes at
   `{pod}/_system/auth/oidc/callback` once the code exchange has named
   the person.
4. The pod resolves the user's scopes on this pod (owner: implicit;
   others: explicit grants) and either auto-grants from existing
   `PodGrants` or shows the consent UI. The dialog carries the contexts,
   the public-read toggle, the lifetime control, and — only for an app
   that already holds something — a named way to remove its access.
5. On success, redirects to `redirect_uri?code=...&state=...`.
6. On failure, redirects with `?error=...&error_description=...&error_uri=...`.

Submitting the consent form requires two things: the pod session cookie
(who) and a single-use token minted for that one screen (which screen,
and not already submitted). Neither alone is enough — a token lifted
out of a page cannot be spent without the cookie, and the cookie alone
does not imply consent to anything. The single-use half matters because
a submission writes the ticked selection as *the* grant set, so a
replayable form could restore a selection the person has since
narrowed.

There is no `scope` parameter for the standard delegation flow — the
user picks contexts in the consent UI. `scope` **is** used for
`public-read` (below).

## Token exchange

`POST /{pod}/_system/auth/token` (`application/x-www-form-urlencoded`):

- Authorization code grant:
  - `grant_type=authorization_code`, `code`, `redirect_uri`,
    `client_id`, `code_verifier`.
- Refresh token grant:
  - `grant_type=refresh_token`, `refresh_token`, `client_id`,
    optional `scope` (down-scope only).
- Client credentials grant (service clients only, HTTP Basic
  authentication): see `service-clients.md`.

Response is the standard OAuth token response. Pod access tokens are
RS256-signed JWTs with `iss = pod base URL`, `sub = <WebID>`,
`client_id`, `scope`, `exp = 1h`. The `scope` claim carries **feature
scopes only** (e.g. `public-read`); per-context permissions are not in
the token — they are resolved server-side per request from the grant
store, and the `scope=` down-scope on refresh applies to feature scopes
only (sempods-spec `spec/core/grants.md` "Pod-issued access tokens"). Public keys are
published at `jwks.json` and rotation-prepared
(`kid`/`algorithm`/`retiredAt` columns); auto-rotation is open work.

### Rate limit

`/token` is the one OAuth endpoint with a budget, and it exists because it
was measured without one: a client holding a refresh token this server did
not recognise sent 102,642 requests in twenty-one hours — 819 inside its
densest minute — and stopped only when its user re-authorised by hand.

- **Two tiers, address first.** The address is the **rightmost**
  `X-Forwarded-For` entry — the one the reverse proxy in front of the server
  appended; everything to its left is text the caller wrote. An aggregate
  budget is spent on that alone, and only then a finer one keyed
  `<address>|<client identity>`. The order is the point: `client_id` is a form
  parameter, so a caller can vary it, and counting it first would hand out a
  fresh budget per invented name. **The grant decides which name identifies the
  caller**, because that is what decides which name the endpoint reads:
  `client_credentials` authenticates the Basic username and never sees the form
  field, the other grants authenticate the form `client_id` and ignore the
  header. Taking whichever is present would give a caller two key spaces to
  pick from. Neither name is verified at this point and neither needs to be,
  since an unverified name is enough to tell one caller's budget from another's;
  both are folded to a digest beyond a length this server chose, so a caller
  does not decide what a retained key or a log line costs. A refusal is logged
  once per address per minute and names **which** tier refused — a newcomer
  behind a busy address has spent nothing of its own, and a line blaming its
  own budget would send the reader after the wrong caller.
- **The pod is deliberately not in the key.** One client holding grants on
  several pods spends one budget; otherwise each pod would see a fraction of
  the traffic that client is actually causing.
- **The budget is for clients that have no backoff of their own.** A client
  that records a terminal `invalid_grant` as terminal and backs off the rest
  stays orders of magnitude below it — the measurement above came from one that
  did neither. Sizing this to catch a well-behaved client would refuse a
  provisioning sweep instead.
- **No proxy, no limit.** With nothing in front of the server there is no way
  to tell one caller from another, and a single shared bucket would be an
  outage rather than a limit.
- **Answer:** `429` with `Retry-After: 60` and the OAuth error body
  `slow_down` (RFC 8628, registered for this endpoint and meaning exactly
  this). The check runs before the pod row is read, so a refused request
  costs no database query.
- **Budget: a rate and a burst, and they are two numbers on purpose.**
  `SEMPODS_TOKEN_RATE_LIMIT_PER_MINUTE` (default 20) is what a caller earns
  back; `SEMPODS_TOKEN_RATE_LIMIT_BURST` (default 300) is what an idle one may
  spend at once. One value for both cannot work: sized for the spike a service
  client's provisioning sweep produces, it would never empty against a steady
  stream, and the loop above sustained only 78 a minute for twenty-one hours.
  The rate therefore sits below that, and the capacity above the spike. Both
  are `0` — off — outside a deployment, and a negative value is refused at
  boot rather than read as "disabled". The address tier has the same pair,
  `SEMPODS_TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE` (100) and
  `..._ADDRESS_BURST` (1000), and has to sit above the per-client tier it
  gates. **`SEMPODS_TOKEN_RATE_LIMIT_PER_MINUTE=0` is the endpoint's off
  switch and takes the address tier with it**; the address rate has a `0` of
  its own for dropping that tier alone. An off switch that silenced one tier
  of two would mislead whoever reached for it, which is generally somebody
  mid-incident. The buckets are in memory per process, so a deployment running
  several replicas hands out one budget per replica, and the number of keys
  tracked is capped — enforced by **evicting** the least useful entry, never
  by refusing a caller: the map is shared, so refusing on a full map would let
  whoever kept it full reserve it and deny everyone arriving after.

What it does **not** address is replay: one accepted request is enough to
revoke a family, and every limiter admits the first request. That is the
rotation rule below.

### `offline_access`

A client whose connection has to outlive the access token's hour asks for
`scope=offline_access`. It is a **sempods extension**, not OIDC: the name
is OIDC's, but it is requested bare — a pod is not an OIDC Provider,
issues no `id_token`, and does not advertise `openid`. Both discovery
documents list it under `scopes_supported`, which is where a client that
has read no sempods documentation finds it.

Asking is not getting. The scope preselects the consent page's
"keep this app connected" control; what grants a refresh token is the
person ticking it, which is why a client that cannot send the scope is
not thereby denied a durable connection. The exchange reads that decision
from the store rather than from the authorization code, so a code carries
the request and never the authority.

An authorization that predates the control has no decision recorded, and
that is not a grant either: it mints no new family, while the one it
already rotates is left alone. The hosted MCP service asks a pod whose
authorization server advertises the scope, and a pod that advertises
nothing is asked for nothing — RFC 6749 §4.1.2.1 lets an authorization
server refuse a scope it does not know, and the service connects to pods
it does not host.

### Refresh token rotation

Per RFC 6749 §10.4 / OAuth 2.1 best practice. Refresh tokens belong to
a **token family** seeded at code exchange. On detected reuse of a
previously-rotated token, the entire family is revoked. Plaintext
tokens are SHA-256 hashed at rest; default TTL is 90 days.

Public-read tokens (see below) **do not** receive a refresh token —
the client re-authorizes when expired.

**A miss names the token it missed, by prefix.** `RefreshTokenStore.lookup` carries a
12-character prefix of the presented token's SHA-256 out on its result, so the warning a
failed redemption logs says *which* token missed rather than only that one did. A prefix
and not the digest, because the digest is the collection's lookup key and a log is not the
place to publish it.

The outcome is `NOT_FOUND` or `EXPIRED`, and the two are not a clean split: `EXPIRED` is
reachable only between the expiry instant and the next TTL sweep, so a token that has been
reaped reads as `NOT_FOUND`. The log line says so in words rather than implying a
distinction the store cannot make — the alternative was making the sweep the sole reaper,
which costs the TTL index.

**What a miss still cannot say is whose it was.** There is no token, so no family id and no
WebID; the submitted `client_id` names an app rather than an installation. Attributing a
failed redemption to a person means retaining something durable about a credential that no
longer exists, which by [`../logging.md`](../logging.md) rule 3 is an audit row and not a log
line. Until that exists, a pod owner reading his own logs cannot tell his client from
another person's holding a grant on the same pod.

## The `prompt` parameter

OIDC Core 1.0 §3.1.2.1 multi-valued, space-separated:

| Value | Behavior |
|---|---|
| (not set) | Auto-grant if grants exist **and** the lifetime question has been answered once for this app; otherwise consent UI |
| `none` | No UI. Auto-granted only when all of the prerequisites below hold; `login_required` or `consent_required` otherwise |
| `consent` | Always show consent UI |
| `login` / `select_account` | Force fresh authentication; the value is forwarded to the id-server, which passes it to the upstream provider where supported (Google honours both; Apple does not document `prompt`) |

An unanswered lifetime question is what sends an authorization older than the
control to the dialog, once, so it can acquire an answer at all; afterwards the
auto-grant is back. `prompt=none` has no dialog to render, so it keeps its silent
code and receives what an absent answer means — an access token and nothing more.

`prompt=none` succeeds only when **three** things hold together, and it
is worth being exact because the common case does not qualify:

1. **The pod remembers the person.** The sign-in leaves a session
   cookie on the pod's own origin, scoped to that pod, and a later
   authorization reads it instead of running the round trip again.
   Without one — first visit, expired, another pod — the answer is
   `login_required`.
2. **The client is not `dyn:`.** A dynamically registered client always
   gets the consent screen (see above), so it never auto-grants. **The
   AI clients that reach a pod through the hosted MCP service are
   `dyn:`** — for them `prompt=none` is therefore always
   `consent_required`, session or not.
3. **Grants for that client survive.** With none, the answer is
   `consent_required` rather than a code.

So the session removes the round trip to the id-server; it does not by
itself make silent authorization possible. An app should treat
`login_required` and `consent_required` alike: fall back to a full
interactive re-authorize.

`prompt=login` is never satisfied by that session: the person asked to
prove themselves again, and the cookie is exactly what they are asking
to bypass. The browser AppShell uses a 60 s loop
guard to avoid infinite redirects on persistent errors.

## Public-read flow

Two variants of `/authorize?scope=public-read`:

- **With identity session** — the token's `sub` is the user's WebID.
- **Anonymous (`prompt=none`, no ID session)** — the token's `sub` is
  a synthetic `urn:sempods:anon:<uuid>`, opaque per request.

Order of checks at `/authorize` for `scope=public-read`:

1. Validate basic params (PKCE, redirect_uri, client_id).
2. Identity resolution. **Invalid JWT > invalid scope > missing JWT** —
   a manipulated/expired JWT is always a hard `access_denied`, never
   silently downgraded to anonymous.
3. Probe public contexts. If the pod has none, return
   `consent_required` rather than issue a useless token.
4. Issue, no consent dialog when `prompt=none`. `prompt=consent` with
   `scope=public-read` returns `consent_required` until the dedicated
   public-read consent screen lands (open work).

`public-read` is additive at the model level — see
`SPS-GRANT-020` (sempods-spec). A `scope` value naming a context is
accepted rather than refused, but it grants nothing: contexts are ticked
in the consent dialog, not requested. The anonymous variant above is the
one place the rest of the value is read — it requires `public-read` and
nothing else, so `scope=public-read <context>#read` without a session is
`login_required` rather than an anonymous code. At token issuance and at
resource access the union semantics described there apply.

## Protected Resource Metadata (RFC 9728)

`GET /{pod}/.well-known/oauth-protected-resource` advertises:

- `resource`, `authorization_servers`, `bearer_methods_supported`,
  `scopes_supported` (RFC 9728 §2). The authorization-server metadata
  carries the same scope list.
- `name` — optional human-readable display name (from
  `PodDbo.displayName`); SDKs use this for `PodConnection.displayName`.
- `public_contexts` — count of public-read contexts (not the URIs;
  URIs would leak topology).

Both extensions are optional, so older PRM consumers stay valid.

## Sharp edges (current state)

These are not deviations from the model; they're known operational
constraints. The full list is in [`README.md`](README.md)
("Known limitations"); the two that bear on this document:

- **No rate limiting on `/authorize` or `/register`** beyond what the
  surrounding infrastructure provides. `/register` is unauthenticated per
  RFC 7591 and accepts registrations from anyone who can reach the pod.
  Neither can key on a client identity the way `/token` does — `/authorize`
  carries one in the query string, `/register` carries none at all — so what
  they want is an address-keyed limit rather than a copy of that one.
- **The HTTP timeouts on the two OIDC legs are nobody's decision, bar
  one.** A sign-in crosses two of them, and they are bounded differently
  for different reasons:

  | Leg | Deadline | Where it comes from |
  |---|---|---|
  | pod server → identity service | **10 s** | chosen, in `CommonsHttpTransport`, as OkHttp's `callTimeout` — which cancels the call and closes the socket rather than only ending the wait, so an abandoned login stops costing the id-server a connection the moment the caller gives up. The shared client underneath would otherwise allow 5 s connect / 60 s socket / 60 s call, and replays nothing |
  | identity service → Google, Apple (token exchange) | **15 s** | Ktor CIO defaults, unset by anyone: 5 s connect, 15 s whole request, one attempt, and an unbounded socket read the request budget keeps from mattering |
  | identity service → Google, Apple (JWKS) | **0.5 s** | Nimbus `JWKSourceBuilder` defaults, also unset: 500 ms connect *and* 500 ms read. Two orders of magnitude tighter than the exchange it follows on the same login — survivable only because the key source caches for five minutes and refreshes ahead of expiry in the background, so the window is a cold cache, i.e. each process's first login |

  So a slow provider is bounded everywhere, but by three different
  libraries' opinions rather than by one decision — and the tightest
  budget of the three sits on the step nobody thinks about. The pod
  server's leg is now the one that names its own number rather than
  inheriting one; `sempods-client`'s `SempodsHttpTimeouts` — an explicit
  model with a per-call override — is still the shape the other two want.
  `CommonsHttpTransportTest` and `OidcHttpTimeoutsTest` pin all of these
  figures, because this paragraph has now been wrong about them three
  times.

## What lives elsewhere

- Identity tokens, OIDC bridge, anonymous subjects → `identity.md`.
- Scopes, grants, server-side enforcement, error semantics →
  sempods-spec `spec/core/grants.md`.
- Service clients (2-leg client credentials, service tokens, audit) →
  `service-clients.md`.
- Open items and follow-up work → [`README.md`](README.md)
  ("Known limitations").
