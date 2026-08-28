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

Service clients are **registered out-of-band**, not via RFC 7591 DCR:

- Registration happens through the host-level admin surface,
  `POST /_system/admin/pods/{pod}/service-clients/{clientId}`
  (`api/system/admin/pods/AdminPodsEndpoint`) — authorized by the admin-authority seam, not by a pod
  scope. Registering an app is a host-operator act, not something a pod token can do.
- Registration is per pod (`PodServiceClientDbo`,
  `oauth.serviceClients`), keyed `(podId, clientId)`. The pod's
  AS metadata advertises `client_credentials` in
  `grant_types_supported`; DCR responses do not — dynamic clients
  cannot obtain service tokens.
- The secret is an opaque random value, minted once at registration,
  stored only as a bcrypt hash on the pod side. Unknown-clientId
  requests run a dummy bcrypt verification so timing does not leak
  which clientIds exist.
- Scopes are fixed at registration and restricted: only per-context
  scopes (`<context-iri>#read|write|manage`) are accepted — no OIDC
  scopes and no `public-read`. A service client is confined to the
  subtree its `manage` root names, and that subtree can never be all of
  them: a `manage` root is refused when it sits at or above the context
  namespace `<pod>/_system/contexts`, because the slash-delimited rule
  would make any ancestor of it match every context on the pod. That
  covers `<pod>#manage` and `<pod>/_system#manage` alike, rather than
  the one spelling somebody happened to think of.

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

`POST /_system/admin/pods/{pod}/service-clients/{clientId}` performs
the whole pod-side setup for an app:

1. registers the app root context `<pod>/_system/contexts/apps/{clientId}`
   **private**, and demotes it to private if it already existed and was
   public — a public root would expose every future descendant write to
   anonymous reads;
2. registers the client with the single scope
   `<app-root>#manage` (the sandbox above);
3. returns the minted secret — **exactly once**, at the moment it is
   minted. The pod keeps only the bcrypt hash, so it can never be
   produced again;
4. returns `scopes` (the registration's stored scope set) and
   `contextRoot` (the sandbox root this call created or ensured) — both
   on **both** results. They are redundant today, `scopes ==
   {"<contextRoot>#manage"}`, but they answer different questions: one
   is state, the other is what this call did. Once a caller may pass its
   own scopes there is no single "the root" to derive from them.

Callers should use the returned `contextRoot` rather than rebuilding
the path from the convention. The server owns where the sandbox lives;
a caller that derives it independently keeps writing under the old root
the day that location changes, while its scope points at the new one —
a runtime 403, not a build error. Where the sandbox lives today is
sempods-spec `spec/core/contexts.md` §2.

**Idempotency.** The server cannot know whether the caller still holds
a working credential — only the caller can decrypt and verify its own
secret. So the caller asserts what it holds via
`expectedRegistrationId` in the request body:

- it matches the current registration **and** the registered scope set
  is exactly the sandbox scope → nothing is written,
  `{"result":"alreadyProvisioned"}`, **no secret in the response**;
- anything else (omitted, stale, or scope drift) → the registration is
  replaced and `{"result":"provisioned"}` carries a fresh secret.

Re-minting invalidates the previous secret; outstanding service tokens
ride out their ≤10-minute TTL. An omitted `expectedRegistrationId`
therefore always re-mints, which is the correct answer both for a fresh
pod and for a half-provisioned caller whose credential row was lost.

The caller keeps its own bookkeeping — the encrypted credential row,
its internal user ids (which must never reach the pod: sempods knows
persons only as WebID URIs) and the health decision. None of that is the
pod's business, and none of it is defined here.

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
Authorization: Basic base64(clientId:secret)
grant_type=client_credentials
```

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
  grants: scopes anchored at the deleted context are stripped,
  scope-less registrations removed. Deleting the app root therefore
  revokes the client; outstanding tokens ride out their ≤10-minute TTL.
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
- Secret rotation is manual (unregister + re-register); overlapping-
  validity rotation is open.
- Clients are expected to handle a 401 by re-minting; a transparent
  single-retry in a client's token provider is still open (tracked as
  `TODO` in code).

Open work across the auth model is named in [`README.md`](README.md)
("Known limitations"). The admin surface that would own service-client
provisioning does not exist yet.
