# Service Clients (2-leg OAuth)

How backend services obtain pod-scoped access tokens without a user in
the loop. The typical client is an application backend that manages its
own app data on its users' pods through this flow.

The flow is the standard **OAuth 2.0 Client Credentials grant**
(RFC 6749 §4.4) with `client_secret_basic` authentication at the token
endpoint. Nothing sempods-specific happens at the protocol level; the
sempods profile below defines which clients may use it and what the
resulting tokens look like.

For the user-facing flows (Authorization Code + PKCE, refresh,
public-read) see `oauth.md`. For scopes, grants, and enforcement see
sempods-spec `spec/core/grants.md`.

## Registration

Two routes lead into one registry.

**The host operator** registers at
`POST /_system/admin/pods/{pod}/service-clients/{clientId}`
(`api/system/admin/pods/AdminPodsEndpoint`). The admin-authority seam
authorizes it, so no pod token reaches it. The caller names the
`clientId`, and the sandbox below is derived from it.

**A service**, or the owner's own tool, registers at
`POST /{pod}/_system/auth/register` — [`oauth.md`](oauth.md#registering-a-service-client)
is that flow, what the body must say and what the answer carries. There
the server names the client `svc:…`, and there is no sandbox to derive.
Without a bearer the registration is provisional until the owner confirms
its [consent](#consent), and removed after 24 hours if they never do. With
the owner's `service-clients:manage` bearer it is active at once.

Either way:

- Registration is per pod (`PodServiceClientDbo`,
  `oauth.serviceClients`), keyed `(podId, clientId)`. The pod's
  AS metadata advertises `client_credentials` in
  `grant_types_supported`; a public DCR response does not — a `dyn:`
  client cannot obtain a service token.
- The secret is an opaque random value, minted once at registration,
  stored only as a bcrypt hash on the pod side. Unknown-clientId
  requests run a dummy bcrypt verification so timing does not leak
  which clientIds exist.
- Scopes are restricted: only per-context
  scopes (`<context-iri>#read|write|manage`) are accepted. An OIDC scope
  is refused, and so is every feature scope, `public-read` included. A service client is confined to the
  subtree its `manage` root names, and that subtree can never be all of
  them: a `manage` root is refused when it sits at or above the context
  namespace `<pod>/_system/contexts`, because the slash-delimited rule
  would make any ancestor of it match every context on the pod. That
  covers `<pod>#manage` and `<pod>/_system#manage` alike, rather than
  the one spelling somebody happened to think of.
- An operator-provisioned client starts with the sandbox scope. A `svc:`
  one starts with none. Afterwards the owner decides the grants of both,
  at the [consent](#consent) or [over the API](#managing-service-clients).
- The scope set may be empty: the registration holds a credential and no
  authority, and the token endpoint answers it `invalid_scope`. Only the
  first of these cases has a deadline:
  - a provisional registration, removed at `activation_expires_at` unless
    the owner confirms it;
  - an active one the owner gave nothing, or took everything from: it
    stays until it is revoked;
  - an active one whose last anchor was deleted: it stays too.

## Consent

A registered service asks the owner for access with one URL. This is a sempods extension, named in
the pod's AS metadata as `sempods_service_consent_endpoint`. It assigns grants after registration,
which deviates from
[`SPS-AUTH-013`](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md#SPS-AUTH-013);
[sempods-spec#123](https://github.com/sempods/sempods-spec/issues/123) proposes the profile.

```
GET {pod}/_system/auth/service-consent?client_id=<service>&state=<opaque>[&redirect_uri=<registered>]
```

- **`client_id`** names a live service registration: a `svc:` one, pending or active, or an
  operator-provisioned one, whose grants are the owner's too. An unknown one, a `dyn:` one or one
  past its deadline gets a plain `400` and no redirect.
- **`redirect_uri`** is optional. It must be one the service registered; a loopback one matches on
  any port (RFC 8252 §7.3). Another gets a plain `400` and no redirect.
- **Nothing else is read.** The URL suggests no rows: the owner picks them.
- **Only the owner decides.** Without a session the owner signs in first. Anyone else gets `403`.

The dialog shares its rows and context creation with delegated access
([`oauth.md`](oauth.md#authorize-flow-overview)); rows arrive ticked with what the service holds
now. Anyone can build this URL for any service, and any registration can call itself
`sempods-syncer`, so the dialog shows only what the pod knows: the name as the service's claim,
that it acts as itself, its identifier, when it registered, and what it holds now. It does
not say the service asked, and it does not show the return address, which receives nothing.

| The owner | With `redirect_uri` | Without | Grants |
|---|---|---|---|
| Confirms rows | `303` to `?state=…` | a page: go back to the program | Replaced by the selection; a provisional registration is activated |
| Confirms nothing | `303` to `?state=…` | the same page | All removed; the registration stays and is activated |
| Cancels | `303` to `?error=access_denied&state=…` | a page: nothing changed | Unchanged |

The return carries no grant and no credential. Every other answer is a page and writes nothing:
a replayed or foreign form (`403`), a form for another screen (`400`), a selection the dialog could
not have produced (`400`), grants that changed after the page was rendered (`409`), and a
registration removed meanwhile (`404`). Contexts created in the dialog before a `409` or `404` stay,
private and without grants, and the page names them.

### Learning the result

The service learns what it may do the way it uses it: a Client Credentials token, then
`GET {pod}/_system/contexts`.

| The service sees | Means |
|---|---|
| A token, and every context it needs listed | Done |
| A token, and a context it needs missing | The owner has not decided, or chose other contexts |
| `400 invalid_scope` at the token endpoint | It holds no grant: pending, or confirmed empty |
| `401 invalid_client` | The registration expired or was removed. Stop |

So it waits for the contexts it needs, never for a token. A service holding `contacts#read` that
asks for `calendar` gets a token before the owner decides. The wait is bounded, cancellable, and
backs off under the token endpoint's [rate limit](oauth.md#rate-limit).
`SempodsServiceAccessWait` does this for a JVM program
([`../pod-client.md`](../pod-client.md#registering-a-service-client)).

**Accepted limit of 0.2.** Nothing reports a particular consent. A headless service cannot tell a
cancelled dialog from an open one, or an empty confirmation from no decision; its time limit ends
the wait.

**Phishing.** A link that arrives from someone else can name any service. The owner compares the
identifier with the one their program shows and cancels when they differ. No other page may frame
the dialog, as for delegated access ([`oauth.md`](oauth.md#authorize-flow-overview)).

## Sandbox via manage-root

The shape is a single scope `<app-root>#manage`, where the app root
follows the app-context convention
`<pod>/_system/contexts/apps/<app>/...` (for an app called `notes`:
`<pod>/_system/contexts/apps/notes#manage`). The slash-delimited
`manage` semantics (`SPS-GRANT-007` (sempods-spec)) give the
client an automatic sandbox under that root — no new scope type and no
super-scope.

Contexts live inside `_system` because they are control-plane state
(`../vision.md` §5): created by a control API, carrying permissions, and
named in every scope string and in the named-graph position of every
quad. There they inherit the control-plane protection instead of sitting
in the freely writable resource namespace. The rest of the `_system`
tree — auth, resources, admin — stays out of a service client's reach as
before; only the context subtree its `manage` root covers is writable.

Pod lifecycle (create/delete/backup) is deliberately **not**
expressible as a service-client scope; it is operator/control-plane
authority — it lives on the admin surface (below), authorized
host-level.

## Provisioning over the admin surface

`POST /_system/admin/pods/{pod}/service-clients/{clientId}` creates a
registration for an app. Only the call that creates it touches the pod:

1. registers the app root context `<pod>/_system/contexts/apps/{clientId}`
   **private**, and demotes it to private if it already existed and was
   public — a public root would expose every future descendant write to
   anonymous reads;
2. registers the client with the single scope
   `<app-root>#manage` (the sandbox above);
3. returns the minted secret — **exactly once**, at the moment it is
   minted. The pod keeps only the bcrypt hash, so it can never be
   produced again.

Every answer carries `registrationId`, `secretId` (names the secret that
authenticates now), `scopes` (the grants stored now) and `contextRoot`
(the sandbox root). Callers should use the returned
`contextRoot` rather than rebuilding the path from the convention. The
server owns where the sandbox lives; a caller that derives it
independently keeps writing under the old root the day that location
changes, while its scope points at the new one — a runtime 403, not a
build error. Where the sandbox lives today is sempods-spec
`spec/core/contexts.md` §2.

**Provisioning creates; the owner decides afterwards.** A later call for
the same `clientId` writes no grant and leaves the root alone. The owner
may have narrowed the grants, emptied them, deleted the root or made it
public, and provisioning again restores none of it. An emptied
registration stays until the operator removes it, so an automatic
re-run finds it and leaves it empty. `scopes` then says what the client
holds, possibly nothing, and `contextRoot` is a name the call did not check.

**Idempotency.** The server cannot know whether the caller still holds
a working credential — only the caller can decrypt and verify its own
secret. So the caller asserts what it holds via
`expectedRegistrationId` and `expectedSecretId` in the request body, the
two identifiers an earlier answer gave:

| The caller sends | A registration exists | Answer |
|---|---|---|
| the current `registrationId` and `secretId` | yes | `{"result":"alreadyProvisioned"}`, nothing written, **no secret** |
| anything else, or nothing | yes | `{"result":"provisioned"}` with a new secret for the same registration; `registrationId` and grants stay, `secretId` changes |
| anything | no | `{"result":"provisioned"}`: the registration is created, with its secret |

A new secret invalidates the previous one; outstanding service tokens
ride out their ≤10-minute TTL. A caller that lost its credential row
therefore sends nothing and gets a working secret back. Two callers that
both sent nothing each got a secret, and only the later works; the
earlier one's `secretId` is no longer current, so its next call gets a
new secret instead of `alreadyProvisioned`. A `409` means
another call issued a secret or created the registration in between.

The caller keeps its own bookkeeping — the encrypted credential row,
its internal user ids (which must never reach the pod: sempods knows
persons only as WebID URIs) and the health decision. None of that is the
pod's business, and none of it is defined here.

## Managing service clients

The owner manages the registrations on their pod with a bearer carrying
`service-clients:manage` — [`oauth.md`](oauth.md#managing-service-clients)
is how one is granted, and what it lets the holder do. The same bearer
registers a service active at `POST {pod}/_system/auth/register`
([Registration](#registration)). The client id travels path-encoded
(`svc%3A…`). From a JVM program these are `SempodsPodServiceClients`
([`../pod-client.md`](../pod-client.md#registering-a-service-client)).

| Route | What it does |
|---|---|
| `GET {pod}/_system/auth/service-clients` | Every registration, described as below. Never a secret |
| `GET …/service-clients/{clientId}` | One registration, with its `grants_version` as a strong `ETag` |
| `PUT …/service-clients/{clientId}/grants` | Replaces the grants with the JSON array of scopes in the body, at the version `If-Match` names. `[]` removes every grant and keeps the registration. A provisional registration is activated |
| `POST …/service-clients/{clientId}/secret` | A new `client_secret`, answered once with `Cache-Control: no-store`. `409` when another rotation landed in between |
| `DELETE …/service-clients/{clientId}` | Removes the registration. The contexts it wrote to stay |

A registration is described by `client_id`, `client_name`,
`client_id_issued_at`, `last_used_at`, `scope`, `grants_version`,
`origin`, and `activation_expires_at` while it is provisional.

- **`last_used_at`** is when the client last minted a token. A secret does
  not expire, so this is what makes a forgotten service visible.
- **`grants_version`** moves with every change to the grants: the owner's
  replace, a consent, a context deletion.
- **`origin`** is `registered` for a `svc:` client and `provisioned` for
  an operator's. The owner replaces the grants of both. Rotating or
  removing a provisioned one is `403`: the operator holds its secret and
  its registration. The owner takes its access away with `[]`.
- Any other bearer is `403 insufficient_scope`, and so is an authority
  approved under an [earlier consent text](oauth.md#managing-service-clients)
  at the registration and at a replace that does more than narrow.

The replace answers:

| Case | Answer |
|---|---|
| `If-Match` names the current version | `200` and the registration, with the new `ETag` |
| `If-Match` names another version | `412`; nothing changed |
| No `If-Match` | `428` |
| `If-Match` is `*`, a list or a weak tag | `400` |
| A body that is not an array of strings | `400` |
| `public-read`, an OIDC scope, a `manage` root at or above the context namespace, or a scope on no context of the pod | `400`, naming the scope; nothing changed |

What each change does to tokens the service already holds:

| Change | The old secret | A token already minted |
|---|---|---|
| Grants replaced | Still mints, for what it holds now | Reaches only that, from its next request |
| Rotated | Stops minting at once | Keeps its grants until it expires (≤ 10 minutes) |
| Revoked | Stops minting at once | Authenticates until it expires and reaches no context |

Where a secret may have leaked, revoke the client: its tokens then reach nothing.

## When not to use a service client

A service client fits app-mediated operations inside the app's own
sandbox, where the app is the honest actor. The moment a backend needs
to act **outside its sandbox** (other contexts of a user's pod), or
other parties must trust pod-level per-user attribution, that access is
user-delegated — Authorization Code + PKCE with an explicit user grant
(see `oauth.md`). Service tokens cannot express a user (`sub` is the
`client_id`).

## Token exchange

```
POST /{pod}/_system/auth/token
Authorization: Basic base64(formEncode(clientId):formEncode(secret))
grant_type=client_credentials
```

The form-encoding step is RFC 6749 §2.3.1's and matters here: an
server-named `client_id` carries a `:`, so it travels as `svc%3A…`. A
client that joins the raw strings sends a username of `svc` and is
answered `invalid_client`.

Service tokens are RS256 JWTs signed by the pod like user tokens
(`iss = pod base URL`), with three differences:

- `sub = client_id` (no WebID — there is no user), and
  `client_type = "service"` marks the token class.
- Short TTL (10 minutes) and **no refresh token** — the client mints
  a new token on demand and caches it until shortly before expiry.
- **Slim token: it carries no context scopes** (the `scope` claim holds
  feature scopes only, which is empty for service clients today). Context
  permissions are resolved on every request from the client's
  registration (`PodServiceClientDao`), so a registration edited or
  cascaded away (e.g. context deletion) takes effect on the next request.
- Down-scoping is **not supported**: the token endpoint rejects a
  `scope=` parameter on `client_credentials` with `invalid_scope` (a slim
  token has no per-token state to express a subset; the token grants the
  client's full registered set).

## Auditing and revocation

- Every request authenticated by a service token is recorded in a
  per-pod audit log (`oauth.serviceAuditLog`):
  `{ ts, podId, clientId, operation, path, expiresAt }`.
- **Retention: 90 days**, configurable via
  `SEMPODS_SERVICE_AUDIT_RETENTION_DAYS`. `expiresAt` is stamped at write
  time (`ts` + retention) and a Mongo TTL index reaps by it, the same shape
  the hosted MCP service's trail uses. The anchor sits in the row rather
  than in the index, so a retention change is configuration, not a
  migration — it reaches only rows written afterwards.
- Deleting a context cascades to service clients like it does to user
  grants: scopes anchored at the deleted context are stripped. Deleting
  the app root therefore leaves the registration holding nothing and its
  secret minting nothing; outstanding tokens ride out their ≤10-minute
  TTL.
- Pod deletion cascades to registrations and the audit log.

## Deviations and open points

- Like all sempods access tokens, service tokens carry **no `aud`
  claim** — they are issued by and validated against a single pod
  (`iss` match). See `identity.md` and the 2026-04 security audit (K1).
- `statusCode` is schema-reserved in the audit log but not populated
  yet (needs a response filter).
- **Rows written before the retention existed carry no `expiresAt`, and a
  TTL index reaps only rows that have one** — so they are kept for ever
  unless an operator backfills them. On the live host that is 100,140 rows
  (measured 2026-08-21), all younger than the retention, so nothing is lost
  by giving them the deadline they would have been written with:

  ```js
  db.getCollection("oauth.serviceAuditLog").updateMany(
    { expiresAt: { $exists: false } },
    [{ $set: { expiresAt: { $add: ["$ts", 90 * 24 * 60 * 60 * 1000] } } }],
  )
  ```

  (`90` there is the retention the deployment runs on, not a constant.) A
  one-off operator step in a maintenance window rather than code — the same
  way the collections were moved between databases. Skipping it is safe and
  leaves a fixed floor of rows that never expire; the trail is bounded from
  the change forward either way.
- An operator-provisioned client rotates by provisioning again; an
  `svc:` one at `POST …/secret`. Neither overlaps validity: the old
  secret stops at once.
- Clients are expected to handle a 401 by re-minting; a transparent
  single-retry in a client's token provider is still open (tracked as
  `TODO` in code).

Open work across the auth model is named in [`README.md`](README.md)
("Known limitations").
