# OAuth Flows

How apps and MCP-style clients obtain pod-scoped access tokens.

The only user-facing flow sempods supports is **OAuth 2.1
Authorization Code with PKCE** (S256). Implicit flow and password grants
are not accepted. PKCE is mandatory for `dyn:*` clients (which register
with `token_endpoint_auth_method=none`) and strongly recommended for
`did:web:*` clients.

Backend services without a user in the loop use the **Client
Credentials grant** (RFC 6749 §4.4), restricted to registered service
clients — see `service-clients.md`.

For the underlying authorization model (scopes, grants, enforcement)
see sempods-spec `spec/core/grants.md`. For identity tokens used during the authorize
step see `identity.md`.

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /{pod}/_system/auth/authorize` | Authorization Code request |
| `POST /{pod}/_system/auth/authorize/consent` | The consent form: authorize, cancel, remove an app's access, sign out |
| `POST /{pod}/_system/auth/token` | Token exchange & refresh |
| `GET /{pod}/_system/auth/jwks.json` | Pod's public signing keys |
| `POST /{pod}/_system/auth/register` | RFC 7591 Dynamic Client Registration: a public client, a service registering itself, or the owner's tool registering one |
| `GET`, `POST /{pod}/_system/auth/service-consent` | The service consent: the owner decides which contexts a registered service reaches, which activates it — [`service-clients.md`](service-clients.md#consent) |
| `/{pod}/_system/auth/service-clients` | An owner's reads, grant replace, rotation and revocation — [`service-clients.md`](service-clients.md#managing-service-clients) |
| `GET /{pod}/.well-known/oauth-protected-resource` | RFC 9728 Protected Resource Metadata |
| `GET /{pod}/.well-known/oauth-authorization-server` | RFC 8414 Authorization Server Metadata. Its `issuer` is the pod base URL; the endpoints above stay under `/_system/auth`. The sempods member `sempods_service_consent_endpoint` names the service consent |

## Client identity: `did:web:*`, `dyn:*` and `svc:*`

Three `client_id` shapes, with different rules:

A body asking for a secret — `token_endpoint_auth_method` other than
`none`, or a grant type outside the browser flow — asks for the third
shape; everything else is a public registration. A service client earns
credentials and no data: registered without a bearer it stays provisional
until the owner grants it contexts. §"Registering a service client" has
the rest.

`did:web:*` and `dyn:*` ask `RedirectUri.isValid` first, before any
client-specific rule: absolute, no fragment, no `code`, `response` or
`state` in the query, `https` on any host, `http` only on loopback.
`/register` applies it too, so an address a login could never honour is
refused at registration, and omitted where a stored one is read back. A
code therefore reaches a cleartext address only on the user's own
machine, and that case is gated again below.
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
  receive each other's codes, and a `.` or `..` anywhere in either is
  refused rather than matched — `/mcp/../evil` starts with the prefix and
  arrives outside it. `DidWeb.Target.covers()` is the one place that
  answers this, for both the pod and the id-server.
- No DCR; the app is its own identity.
- Consent behaves per the `prompt` parameter — see the table under
  §"The `prompt` parameter", which is where that rule lives.

### `dyn:*` — RFC 7591 dynamic clients (e.g., Claude Desktop, Copilot, ChatGPT)

- Registered via `/register`; client metadata stored verbatim along
  with a deterministic fingerprint (SHA-256 over `clientName`,
  `userAgent`, and the redirect-URI set with loopback ports stripped)
  for dedup. A repeat registration with the same fingerprint reuses the
  existing `dyn:` client id — a unique index, so two arriving together
  still come out as one
  ([`../mcp/authentication.md`](../mcp/authentication.md#dcr-fingerprint)).
- Loopback redirect URIs are matched with port-stripping (RFC 8252
  §7.3); non-loopback redirect URIs stay port-strict.
- **Always render the consent UI** on `/authorize`, regardless of
  `prompt` or existing grants. Existing grants are pre-checked, so
  the common path is one click. Rationale: MCP clients only reach
  `/authorize` when the user just triggered the flow, so an explicit
  confirmation is wanted; `did:web:*` clients hit `/authorize` from
  background-facing UI where a pop-up would be disruptive.

### `svc:*` — service clients registered at the pod

- Assigned by the server at `/register` to a service registering itself,
  or to one the owner's tool registers. §"Registering a service client"
  is that flow, its rules and its refusals.
- Confidential: `client_secret_basic` and `client_credentials`, and from
  there an ordinary service client ([`service-clients.md`](service-clients.md)).
- `/authorize` answers no identifier of this class. A service client
  authenticates with its secret; the owner decides what it reaches at the
  [service consent](service-clients.md#consent) or
  [over the API](service-clients.md#managing-service-clients).

`/token` exchanges are unaffected by the consent override — in-session
refreshes stay silent for both browser-facing classes.

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
   the public-read toggle, the lifetime control, a way to
   [sign out](#signing-out), and — only for an app that already holds
   something — a named way to remove its access. A request naming
   `service-clients:manage` or `contexts:manage` gets a screen of its own
   instead; see "Managing service clients" below.
5. On success, redirects to `redirect_uri?code=...`, carrying `state` back
   exactly as it arrived where the client sent one — an empty `state=` counts
   as none (RFC 6749 §3.1).
6. On failure, redirects with `?error=...&error_description=...` and the same
   `state`; `error_uri` only where the deployment configures one.

No other page may frame a consent dialog (`Content-Security-Policy:
frame-ancestors 'none'` and `X-Frame-Options: DENY`), so none can steer
the person's clicks onto its buttons.

Submitting the consent form requires two things: the pod session cookie
(who) and a single-use token minted for that one screen (which screen,
and not already submitted). Neither alone is enough — a token lifted
out of a page cannot be spent without the cookie, and the cookie alone
does not imply consent to anything. The single-use half matters because
a submission writes the ticked selection as *the* grant set, so a
replayable form could restore a selection the person has since
narrowed.

The token also records what the screen was rendered for: the client,
`redirect_uri`, `state`, the PKCE challenge, the rows it offered and
whether it offered to create a context. The submission reads these from
the token, so the form posts only the token, the ticked boxes and the
button pressed. A submission the dialog could not have produced changes
nothing:

| Submission | Answer | Grants |
|---|---|---|
| Rows ticked | `code` | Replaced by the selection |
| Nothing ticked | `access_denied`, `app disconnected` — `no scopes selected` where the app held nothing | Removed, with the app's refresh tokens |
| Cancel | `access_denied`, `cancelled` | Unchanged |
| Rows the person lost the right to delegate while the page was open | `code` for the rows that remain; `consent_required` where none remains | Those rows are not granted; contexts created in the dialog stay, private, and get only the grants that remain |
| A row the dialog did not offer | `invalid_scope` | Unchanged |
| A context to create that the path rules refuse, that exists already, or on a dialog that offers no creation | `invalid_request` | Unchanged; nothing is created |
| A client, `redirect_uri`, `state`, PKCE challenge or challenge method other than the rendered one | `400`, no redirect | Unchanged |

The replacement covers every explicit grant the app holds on this pod
under the WebID the person is signed in as, `public-read` included.
Grants written under a linked identity stay; removing the app's access
clears those too. A row below a context the
app holds `manage` on keeps its own boxes; the dialog notes that the
root reaches it too. A context created in the dialog is private and
owner-only.

**Rollout.** A token written by a node that predates this binding
carries no request. The submission then reads the request from the form
that node rendered and checks no rows, for the token's fifteen minutes.
This holds for the rest of 0.2.x and is removed in the next minor
release ([#341](https://github.com/sempods/sempods-kotlin/issues/341)).
The other direction is not covered: a page from a new node carries no
request fields, so an older node refuses its submission with `400`, and
the person starts the authorization again.

`scope` never carries contexts — the person ticks those in the consent UI.
What it carries is the values the discovery documents advertise:
`public-read` ("Public-read flow" below), `service-clients:manage` and
`contexts:manage` (the two "Managing …" sections below), and
[`offline_access`](#offline_access), which preselects the lifetime control
rather than deciding it. All are optional, and a delegation flow that
sends none of them is the ordinary case.

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
`client_id`, `scope`, and an `exp` of at most an hour — less where the
refresh-token family behind it ends sooner, so a connection that lasts
seven days lasts seven days rather than seven days and an hour.
`expires_in` and `exp` come out of one subtraction. The `scope` claim carries **feature
scopes only** (e.g. `public-read`); per-context permissions are not in
the token — they are resolved server-side per request from the grant
store, and the `scope=` down-scope on refresh applies to feature scopes
only (sempods-spec `spec/core/grants.md` "Pod-issued access tokens"). Public keys are
published at `jwks.json` and rotation-prepared
(`kid`/`algorithm`/`retiredAt` columns); auto-rotation is open work.

### Rate limit

`/token`'s budget exists because it was measured without one: a client holding a refresh token this server did
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

Asking is not getting. The scope preselects the consent page's control
and settles nothing else. What the tick decides is **how long** the
connection lives, not whether there is one: every answered authorization
is issued a refresh token, and the answer picks the family's terms.

| | idle window | absolute ceiling |
|---|---|---|
| unticked | `SEMPODS_SESSION_CONNECTION_IDLE_HOURS` (default 96) | `SEMPODS_SESSION_CONNECTION_ABSOLUTE_DAYS` (default 7) |
| ticked | `SEMPODS_DURABLE_CONNECTION_IDLE_DAYS` (default 90) | `SEMPODS_DURABLE_CONNECTION_ABSOLUTE_DAYS` (default 180) |

The numbers are the deployment's, and the consent page names the configured
ones. The server refuses to start where a value is not positive or an idle
window exceeds its ceiling.

Both classes end. An app that syncs daily and has been connected
indefinitely re-authorizes on a schedule from here on, which is a
product decision about background apps rather than a corollary of the
rest.

An app that runs only in front of somebody answers "no" honestly and
still needs a way back when the hour is up. There is no silent one: a
hidden-iframe `prompt=none` carries the pod session only where the app
and the pod share a site, and a sempods app is meant to work against any
pod. `grant_type=refresh_token` is that way back, and it needs no site
relationship at all.

The exchange reads the decision from the store rather than from the
authorization code, so a code carries the request and never the
authority.

An authorization nobody has answered has its codes refused, and there are
two ways to be in that state. Its document may be absent, in which case the
code carries no generation and is not exchangeable. Or the document may
exist carrying no answer, which is what a
[privileged consent](#managing-service-clients) leaves behind: it moves the
generation without settling the lifetime question, so the exchange reads
the answer rather than the row. Every code minted for a person otherwise
comes from an authorization that has been answered — consent records the
answer, and auto-grant reaches its code only where one is already on
record.

That refusal is also what makes the consent write order safe. Grants are
written first and the answer second, so a run dying between them keeps the
selection the person just made under the answer that stood before it; the
pair it can leave on a first consent, grants with no answer beside them,
redeems nothing.

Nothing on the wire says which lifetime a client got. `offline_access`
is accepted in the request and does not appear in the response `scope`:
RFC 6749 §5.1 defines that member as the access token's scope, and a
credential's lifetime has no standing in it. A client could do nothing
with the answer either — it starts a fresh flow when the family ends,
whatever it was told beforehand, which is the same reasoning that rules
out `refresh_token_expires_in`. The person is told at the consent screen.

Three flows mint no family at all. Two because nobody was asked in them:
an anonymous `public-read` exchange and a service client's
`client_credentials` are short-lived by construction. The third is a
privileged authority ("Managing service clients" below), where the
refusal is the point of the flow. It makes `offline_access` a condition of
nothing, so `SPS-AUTH-059` stands untouched by it. Authenticated
`public-read` takes the ordinary path, and its lifetime is the consent
answer like anybody else's.

On refresh the scope is accepted rather than refused. `scope=` there is a
down-scope over feature scopes (see "Token exchange") and `offline_access`
is not one, so it is taken out before the comparison instead of being
reported as a scope this token does not cover. Clients hold scope lists
carrying it and send them back, which is the standard thing to do with the
`scope` of a token response; refusing the echo would break exactly the
clients that behaved correctly.

### Refresh token rotation

Per RFC 6749 §10.4 / OAuth 2.1 best practice. Refresh tokens belong to
a **token family** seeded at code exchange. On detected reuse of a
previously-rotated token, the entire family is revoked. Plaintext
tokens are SHA-256 hashed at rest.

A family carries its class and its deadline from the mint, and a rotation
inherits both rather than deciding them again. The idle window is the
class's, as configured when the rotation runs (the table under
[`offline_access`](#offline_access)): a rotation renews that window rather
than the family's life, and a changed setting reaches a live family at its
next refresh while its deadline stays where it was.
`PodRefreshTokenStore.termsOf` holds the numbers,
`RefreshTokenStore.issueInFamily` the inheritance, RFC 10017 §6.3.2.3 the
requirement behind it: a rotation may not extend the new token's lifetime
beyond the initial token's where the family has a preestablished
deadline. The deadline is fixed when the family is seeded and copied
verbatim afterwards, so nothing a rotation does moves it — without one, a
family that rotates daily never ends.

A family that reaches a rotation without a deadline acquires one there,
and it is **the predecessor's own expiry**: the only one such a family
demonstrably has, and taking it extends nothing. Two populations arrive
that way — the families seeded before the fields existed, and those
seeded after them but before this server decided what each class means.
An actively used one therefore has up to ninety days left and then asks
for a fresh authorization once. No migration script, no backfill.

`PodRefreshTokenStore.issueInFamily` owns that rule. The shared store
asks it only of a family naming no class at all, and leaves one that names
a class to the service that minted it.

**A deployment older than the consent control clears its delegations
once.** Those authorizations hold grants with no answer beside them, so
their codes are refused and their families die at the next rotation.
Predating the control is a property of the deployment rather than of a
tenant, so this empties three collections for **every pod on the server**:

```js
db.grants.deleteMany({})
db["oauth.refreshTokens"].deleteMany({})
db["oauth.authCodes"].deleteMany({})
```

The documents, not the collections — both stores build their indexes in
their constructors, so a `drop()` against a running server leaves them
unindexed until the next boot. The codes are in flight rather than
durable and are here because the decisions are kept: one minted just
before the reset still matches its generation and would redeem against
grants that are gone.

A [sign-out](#signing-out) revokes every family the person holds on the
pod, whichever app and lifetime.

**A reconnect replaces, it does not accumulate.** An answer to the
lifetime question governs what stands after it: a consent granting a
durable connection retires the families it supersedes once the successor
exists, one withholding it retires them outright, and both span every URI
derivable from the person's WebID. So reconnecting replaces the client's
refresh token rather than leaving a second credential beside it, each
renewing a window of its own. Both answers mint, so both retire: an
auto-granted reconnect records no new decision and revokes nothing, which
is how one per visit would otherwise accumulate.

Each exchange retires what it observed before minting its own, which
bounds it rather than serialising it: two codes redeemed at the same
instant under one standing consent can each observe only the families
that predate both, and two then coexist until the next answer. That is
deliberate. The alternative is a compare-and-set electing one winner,
and the loser of that election is a client that completed a legitimate
exchange — it would have to be handed an error, or a token already dead.
An extra credential of a connection the person did just grant is the
smaller failure.

Deleting a context revokes no refresh token *for naming it*: a family
carries feature scopes only and context permissions are resolved per
request, so the deletion's own cascade — the grant rows — is what ends
the access. A family goes there on one condition, the same one the
refresh exchange applies: it belongs to an app whose delegation the
deletion removed, and that app is left holding no grant at all.
A connection the deletion never held a grant of is not examined, so a
consent replacing its grants at that moment cannot be caught mid-write.

**Anonymous** public-read tokens (see below) do not receive a refresh
token — there is nobody to grant one, so the client re-authorizes when it
expires. Authenticated public-read is not that case: it takes the ordinary
path, and its lifetime is the consent answer like anybody else's.

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
code — but the token endpoint refuses it, for want of a generation or of an answer
([`#offline_access`](#offline_access)). The redirect still carries a `code`, and
spending it answers `invalid_grant`; the flow works again once the person has
answered once, and does not arise at all on a pod that has run the clearing step
above. Answering `consent_required` at `/authorize` instead would be tidier and
is not done, because that is a live contract for every authorization that *has*
an answer, and this one is transitional.

`prompt=none` succeeds only when **three** things hold together, and it
is worth being exact because the common case does not qualify:

1. **The pod remembers the person.** The sign-in leaves a session
   cookie on the pod's own origin, scoped to that pod, and a later
   authorization reads it instead of running the round trip again.
   Without one — first visit, expired, signed out, another pod — the
   answer is `login_required`.
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

**The twelve hours measure the gap between authorizations.** Every
`/authorize` that arrives with a valid session answers with a fresh cookie,
so the clock starts at the last one. Connecting a second app, reconnecting
one and passing a consent screen each reset it. Ordinary work never reaches
`/authorize` — a connection holding a refresh token renews on that one — so
somebody who authorizes nothing is forgotten twelve hours after their last.

Renewal carries `auth_time`, the original sign-in, forward unchanged and
stops thirty days after it. Short of a [sign-out](#signing-out), that
ceiling is what ends a cookie. The deadline bounds the renewal's own
lifetime too: one issued in the final hours gets what is left of the thirty
days, and its `Max-Age` says the same, so a browser stops presenting the
cookie at the moment the pod stops accepting it.

`prompt=login` is never satisfied by that session: the person asked to
prove themselves again, and the cookie is exactly what they are asking
to bypass. The browser AppShell uses a 60 s loop
guard to avoid infinite redirects on persistent errors.

## Signing out

The consent screen offers "Sign out everywhere" to whoever is signed in.
It submits the consent form, so it needs the same two proofs. It ends
everything the person holds on this pod, at once:

| Credential | After the sign-out |
|---|---|
| The session cookie, in every browser | Reads as no session: `/authorize` sends the person to sign in, `prompt=none` answers `login_required`, the consent form answers 401 |
| Every app's refresh-token families, both lifetimes | Revoked: a refresh answers `invalid_grant` |
| Authorization codes not yet exchanged | Refused at the exchange with `invalid_grant` |
| Access tokens already issued | Refused on every pod route with 401 |

The app that opened the screen gets `access_denied` with
`error_description=signed out`, and the response withdraws the cookie.

The grants stay. After signing in again, an app that auto-granted before
does so again. Another person, a service client and the same person's
other pods are untouched.

`PodSignOutStore` keeps the instant of the person's last sign-out, under
every URI the person is known by. A session or access token dated at or
before it is refused, compared in whole seconds because `auth_time` and
`iat` are: a sign-in within the same second counts as signed out.
`PodSignOut.signOut` states the order of the writes and why an exchange
running beside it cannot hand out a credential that survives.

A session that expires on its own ends nothing else. A connected app
never calls `/authorize` again, so a family tied to the session's clock
would end twelve hours after the app's last authorization, however busy
the person was. The family's own deadline ends it instead.

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

## Registering a service client

A service registers itself at `POST {pod}/_system/auth/register`, without a bearer:

```json
{
  "client_name": "Notes Sync",
  "grant_types": ["client_credentials"],
  "token_endpoint_auth_method": "client_secret_basic",
  "redirect_uris": ["http://127.0.0.1/callback"]
}
```

The registration is **provisional**: it holds a `svc:` identifier and a secret, and no data rights.
Only the owner's [consent](service-clients.md#consent) activates it. Without that consent within 24
hours, the pod removes it. A JVM program runs the whole sequence through
`sempods-client` ([`../pod-client.md`](../pod-client.md#registering-a-service-client)).

- **The server names it.** The answer carries a `svc:` identifier, `client_id_issued_at`, the
  secret, `client_secret_expires_at: 0` — RFC 7591 §3.2.1's spelling for a secret that does not
  expire — and the sempods member `activation_expires_at`, the deadline in epoch seconds.
- **A lost answer costs nothing but the row.** The secret lives only in that response
  ([`service-clients.md`](service-clients.md#registration)). A retry registers a second service; the
  first holds nothing and is removed at its deadline.
- **Provisional means no token.** Client Credentials answers `invalid_scope` while the registration
  holds no grants, as for any registration without grants. Past its deadline it is gone to every
  read — authentication, listing, the consent — even before the TTL monitor removes the row,
  so a token request is `invalid_client` and a late consent activates nothing.
- **Activation is one write.** The consent writes the grants and removes the deadline in the same
  single-document update, filtered on the deadline still lying ahead. An active registration
  carries no deadline and is never removed by it.
- **The owner's bearer activates it.** A bearer is read as RFC 7591 §3.1's initial access token.
  The owner's standing [`service-clients:manage`](#managing-service-clients) authority registers the
  service active: no `activation_expires_at`, no deadline, and no grants until the owner
  [replaces them](service-clients.md#managing-service-clients). Any other bearer is
  `403 insufficient_scope` and registers nothing, and one this pod cannot verify is `401`.
- **The installer scope is retired.** `/authorize` answers `service-clients:install` with
  `invalid_scope`. A code minted for it before the release is refused at the exchange
  (`invalid_grant`), and a token lives out its hour as it was minted: it reaches no data and passes
  no gate that asks only for an app.

What the body may carry (RFC 7591 §2):

| Member | Answer |
|---|---|
| `client_name` | Required: it names the service in the consent |
| `grant_types`, `token_endpoint_auth_method` | `["client_credentials"]` and `client_secret_basic`; another value is `invalid_client_metadata` |
| `redirect_uris` | Optional, checked like a public client's; a bad one is `invalid_redirect_uri`. The consent returns only to one of these, a loopback one on any port |
| `jwks`, `jwks_uri`, `scope`, non-empty `response_types` | `invalid_client_metadata`: a key, a scope or a browser flow this profile does not have |
| `client_uri`, `logo_uri`, `contacts`, `tos_uri`, `policy_uri`, `software_*` | Dropped: not stored, not echoed |
| Anything else | Ignored |

Anyone can register, so the name is a claim: the consent shows it beside the identifier and the
registration time, which are the pod's own. The deadline and the per-pod budget
(§"Registration rate limit") bound what an open endpoint costs; neither confirms an identity.

The `dyn:` prefix, the grant types a registration response may advertise and out-of-band service
clients are bound by
[`SPS-AUTH-008`](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md#SPS-AUTH-008),
[`SPS-AUTH-011`](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md#SPS-AUTH-011)
and [`SPS-AUTH-012`](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md#SPS-AUTH-012),
so this profile is an experimental extension with known deviations — [sempods-spec#122](https://github.com/sempods/sempods-spec/issues/122) carries them.

## Managing service clients

`/authorize?scope=service-clients:manage` asks the pod owner for the authority to register
services on the pod and decide what each reaches: list and read them, register new ones, replace
the grants of any of them, rotate and revoke the ones registered there.

- **The owner's to grant.** Ownership is alias-aware — any URI that names the owner does — and
  anyone else is answered `invalid_scope`.
- **It stands alone.** The scope beside `public-read`, a context scope or the other privileged scope
  is refused rather than trimmed. `offline_access` beside it is ignored, because the control it
  preselects is not on the screen.
- **A screen of its own.** The dialog offers the authority unticked and carries no context rows, no
  public-read toggle, no way to build a context, no lifetime control and no way out. Ticking
  nothing declines it and leaves whatever that app already holds exactly as it was, and a
  submission carrying any of the fields this screen does not render is refused.
- **It answers nothing else.** The consent moves the `(pod, client, person)` decision's generation
  only: a lifetime answer already on record survives, and where none is on record none is written.
- **Asked every time.** A standing consent never answers it: `prompt=none` is `consent_required`,
  and a stored grant naming the scope is dropped rather than re-issued.
- **An hour, no renewal.** The code exchange mints an access token good for an hour with **no
  refresh token**, and records the authority under that token's `jti`, with every URI the dialog
  recognised the owner by. Each call compares the pod's *current* owner against that set.
- **It dies when the app is disconnected**, whatever the bearer has left of its hour. Until then the
  app's ordinary consent dialog offers "Remove access", even where the app holds no grant.
- **No data of its own.** A token carrying the scope resolves no context permissions and no public
  contexts. It does not pass a gate that asks only for an app, which is how the AI routes ask.

Its own token reaches no context, but it reaches data through the services it holds secrets for.
The caller registers a service and receives its secret, or rotates one and receives the new
secret, gives that service contexts, and mints Client Credentials tokens as it. The secret does not
expire, so this outlasts the hour. Register a service, give it `apps/notes#write`, and the caller
still writes `apps/notes` the next day. The consent says so.

**An authority approved under an earlier consent text keeps what that text promised.** The row
records the text it was approved under (`PrivilegedAuthorityRows.CONSENT`). One approved before the
text named registering and assigning still lists, rotates and revokes, and is `403
insufficient_scope` at the registration and the replace.

The operations are [`service-clients.md`](service-clients.md#managing-service-clients)'s.

## Managing contexts

`/authorize?scope=contexts:manage` asks the pod owner for the authority to create and delete any
context on the pod, through `PUT` and `DELETE {pod}/_system/contexts/{path}`. It follows the rules of
the management scope above.

Without it, a bearer is an app, whatever its `sub` names. It creates and deletes only what a
`#manage` grant covers, so an app the owner approved for `apps/notes` cannot delete `contacts`.

| Bearer | `PUT` / `DELETE` on a context |
|---|---|
| `contexts:manage`, approved by the pod's current owner | every context |
| a `#manage` grant, the owner's app included | what the grant covers (`SPS-GRANT-007`) |
| any other bearer | `403` |
| no bearer | `401 invalid_token` |
| `contexts:manage` after the app was disconnected | `401 invalid_token` |

It reads no data, but deleting a context deletes what it holds. The catalogue lists every registered
context for it with `manage` alone: `sps:manageableContext`, in JSON `permissions: ["manage"]` and
`source: "owner"`. `SPS-CTX-034` and `SPS-GRANT-009` would make that `manage` imply read and write,
so this is an experimental deviation, carried by
[sempods-spec#114](https://github.com/sempods/sempods-spec/issues/114).

## Registration rate limit

`/register` has three budgets, in the order a request meets them. Each profile has its own, so a
flood of public registrations does not hold up a service's, and the other way round.

| Budget | Key | Asked | Default (rate/min, burst) |
|---|---|---|---|
| public | address | before the pod row, without a bearer | 10, 30 |
| protected | address | before the pod row, with a bearer | 10, 20 |
| service | pod | after a service's body is accepted | 2, 5 |

- **The address** is read as at `/token`: the rightmost `X-Forwarded-For` entry. No proxy, no
  address limit.
- **The pod is not in an address key**, so one address spends one budget across all pods.
- **A reconnect is counted.** A client registering the same metadata again gets its existing
  `dyn:` identifier but still spends a request; the public burst leaves room for that.
- **Which address budget is charged depends on whether a bearer is present**, which the caller
  decides. Both are bounded, so choosing buys nothing.
- **A service registering on behalf of many people shares one address.** The hosted MCP service
  registers once per pod and profile a user connects; reaching the pod server through the public
  proxy, all of it spends one public budget. An operator running it raises
  `SEMPODS_REGISTER_RATE_LIMIT_PUBLIC_*`, or routes it to the pod server without the proxy.
- **The service budget** bounds secret minting on one pod: each service registration mints a
  bcrypt-hashed secret, nothing authenticates the caller, and many addresses can reach one pod. A
  refused body is not charged. A caller can spend a pod's budget and delay other registrations for
  a minute; it cannot activate anything.
- **Answer:** `429`, `Retry-After: 60`, `Cache-Control: no-store` and
  `{"error":"slow_down",…}`. RFC 7591 registers no code for this, so the answer is `/token`'s.
- **Configuration:** `SEMPODS_REGISTER_RATE_LIMIT_{PUBLIC,PROTECTED,SERVICE}_PER_MINUTE` and
  `…_BURST`. Where the `SERVICE` names are unset, the former `…_INSTALLER_…` names are read. A rate of `0` turns that budget off and leaves the others; a burst of `0` follows the
  rate. All are off outside a deployment, and a negative value is refused at boot. The buckets are
  in memory per process, as at `/token`.

A service nobody activates is removed at its deadline. Public registrations are bounded by rate,
not removed: [#251](https://github.com/sempods/sempods-kotlin/issues/251) sweeps unused ones.

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

- **No rate limiting on `/authorize`** beyond what the surrounding
  infrastructure provides. It carries a client identity only in the query
  string, so what it wants is an address-keyed limit like `/register`'s
  (§"Registration rate limit") rather than a copy of `/token`'s.
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
  inheriting one; `:sempods-client`'s deadline — one whole-call budget
  `SempodsOkHttp.install` sets, which an operation needing another runs under on
  a client derived with `newBuilder()` — is still the shape the other two want.
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
