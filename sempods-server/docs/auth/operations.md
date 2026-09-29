# OAuth operations

[Pod authentication](README.md) · [OAuth reference](oauth.md) · [Connection lifetimes](connections.md)

## Rate limit

`/token` charges an address budget before a finer address-and-client budget. The address is the
rightmost `X-Forwarded-For` entry appended by the trusted reverse proxy. Without that header,
`/token` has no rate limit. The client identity is the Basic username for Client Credentials and the
form `client_id` for other grants. The pod is not part of either key, so cycling through pods or
inventing client IDs cannot multiply the address budget.

| Setting | Deployment default |
|---|---|
| `SEMPODS_TOKEN_RATE_LIMIT_PER_MINUTE` | 20 |
| `SEMPODS_TOKEN_RATE_LIMIT_BURST` | 300 |
| `SEMPODS_TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE` | 100 |
| `SEMPODS_TOKEN_RATE_LIMIT_ADDRESS_BURST` | 1000 |

The burst accommodates provisioning sweeps; the refill rate bounds sustained retry loops. A
refusal returns `429`, `Retry-After: 60` and `slow_down`, before any pod lookup. Logging identifies
the refusing tier once per address per minute. Terminal `invalid_grant` requires reauthorization;
other retryable errors need backoff.

A per-client rate of zero disables both tiers while the address rate keeps its default; an
explicit positive address rate beside it fails startup. An address rate of zero disables only that
tier. Limits default to off outside a deployment. Negative settings fail startup. Buckets are
per process, so replicas multiply capacity. Bounded key maps evict entries rather than refusing
new callers because the map is full. Rate limits do not prevent refresh-token replay: one accepted
reuse is enough to revoke a family.

## Registration rate limit

`/register` uses three budgets:

| Budget | Key | Checked when | Default rate/min, burst |
|---|---|---|---|
| Public | Address | No bearer, before pod lookup | 10, 30 |
| Protected | Address | Bearer present, before pod lookup | 10, 20 |
| Service | Pod | Valid self-registration body, before creating a secret | 2, 5 |

Address handling matches `/token`; without the header, only the service budget applies. Each
address budget spans pods. Repeat public DCR spends a request even when it returns an existing
client ID. A hosted service registering many users' connections shares an address budget; size
`SEMPODS_REGISTER_RATE_LIMIT_PUBLIC_*` accordingly.

The service budget bounds secret creation on one pod. Invalid bodies do not spend it. Registration
with the owner's management bearer skips this budget and uses the protected address budget.

Configure `SEMPODS_REGISTER_RATE_LIMIT_{PUBLIC,PROTECTED,SERVICE}_PER_MINUTE` and `_BURST`.
`INSTALLER` names are read when the matching `SERVICE` setting is unset. A zero rate disables that
budget; a zero burst follows its rate. Negative values fail startup. Refusals return `429`,
`Retry-After: 60`, `Cache-Control: no-store` and `slow_down`. Budgets are per process.
Provisional services expire at their activation deadline. Unused public registrations are
never removed ([#251](https://github.com/sempods/sempods-kotlin/issues/251)).

## Protected Resource Metadata (RFC 9728)

Both pod-relative metadata endpoints exist: `/{pod}/.well-known/oauth-protected-resource` and
`/{pod}/.well-known/oauth-authorization-server`. This host also serves the origin-rooted forms
used by generic discovery. [MCP discovery](../../../docs/mcp/endpoint.md#oauth-discovery-routes)
lists all routes.

Protected-resource metadata includes `resource`, `authorization_servers`, `bearer_methods_supported`
and `scopes_supported`. Two extensions follow: the count `public_contexts`, always present, and a
human-readable `name` when the pod has one. The count exposes no context IRIs. Authorization-server
metadata advertises the same scope list; its issuer is the pod base URL. It also advertises
`sempods_service_consent_endpoint` for service consent.

## Sharp edges

There is no application-level rate limit on `/authorize`; use deployment infrastructure where
one is needed. Signing keys persist, but nothing rotates them automatically. DPoP is not implemented
([#112](https://github.com/sempods/sempods-kotlin/issues/112)).

Pod access tokens carry no `aud` claim. A pod accepts every token it signed on all its routes: a
token an app obtained for the REST API also works at the pod's MCP endpoint. This is a
[specification deviation](oauth.md#specification-deviations).

Non-owner grants have storage but no owner management UI. Public-context visibility is held in the
operational store, not in RDF. [User connections](connections.md) covers sign-out and refresh limits.

| OIDC leg | Deadline | Implementation |
|---|---|---|
| Pod → identity service | 10 s whole call | Explicit OkHttp `callTimeout`; cancels the socket as well as the wait |
| Identity service → Google/Apple token exchange | 15 s whole request, 5 s connect | Ktor CIO defaults; one attempt |
| Identity service → Google/Apple JWKS | 500 ms connect and read | Nimbus defaults; five-minute cache and refresh ahead of expiry |

The first login after startup is particularly sensitive to the JWKS deadline. These budgets
are not configurable. [CommonsHttpTransportTest](../../src/test/kotlin/org/sempods/auth/CommonsHttpTransportTest.kt)
and [OidcHttpTimeoutsTest](../../../sempods-auth/src/test/kotlin/org/sempods/auth/oidc/OidcHttpTimeoutsTest.kt)
verify them.

## Service audit retention

Service-authenticated requests are recorded in `oauth.serviceAuditLog`. Retention defaults to
90 days, configured by `SEMPODS_SERVICE_AUDIT_RETENTION_DAYS`. Each new row gets an `expiresAt`
value from its timestamp and that setting. Changing retention affects only newly written rows.
Pod deletion also removes its audit records.

Rows without `expiresAt` do not expire. An operator can backfill them using the deployment's
retention period (`90` days in this example):

<!-- doc-example: illustrative; maintenance command reviewed against PodServiceAuditLogDbo and DAO TTL index -->
```js
db.getCollection("oauth.serviceAuditLog").updateMany(
  { expiresAt: { $exists: false } },
  [{ $set: { expiresAt: { $add: ["$ts", 90 * 24 * 60 * 60 * 1000] } } }],
)
```

Rows already older than that period become eligible for deletion by MongoDB's TTL index.
The [audit row contract](../../src/main/kotlin/org/sempods/pods/oauth/serviceclients/persist/PodServiceAuditLogDbo.kt)
describes stored fields and the currently unpopulated response status.

## Consent and grant updates

Service consent and the management API check the grants version they read. Their answers are in
[Consent](service-clients.md#consent) and [Managing service clients](service-clients.md#managing-service-clients).

Delegated consent replaces grants for one app and WebID. Two concurrent confirmations can leave a
combined selection ([#338](https://github.com/sempods/sempods-kotlin/issues/338)).

During a mixed rollout, an old consent token without a bound request stays valid for its remaining
fifteen minutes. Its posted rows are taken as offered; only rows the person can still delegate are
granted. New forms submitted to an older node can return `400`, requiring authorization to restart.
This compatibility ends after 0.2.x ([#341](https://github.com/sempods/sempods-kotlin/issues/341)).

## Upgrading old delegations

A deployment upgraded from a release without the connection-lifetime question holds delegations
with no stored answer. The pod refuses their codes and ends their refresh families at the next
rotation. Clear them once; this affects **every pod on the server**:

<!-- doc-example: illustrative; destructive operator maintenance, reviewed against PodTokenExchange and consent-generation checks -->
```js
db.grants.deleteMany({})
db["oauth.refreshTokens"].deleteMany({})
db["oauth.authCodes"].deleteMany({})
```

Use `deleteMany`: the stores create their indexes when the server starts, so `drop()` on a running
server leaves the collections unindexed until the next start. Clear the codes too: a code issued
just before the reset still matches its consent generation and would redeem against grants that
are gone.
