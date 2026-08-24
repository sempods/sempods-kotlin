# Multi-tenancy review (M6.4)

The isolation review that closes the M6 hardening gate: every persisted collection and every
piece of in-memory state, checked for tenant keying; the endpoint-level gates that enforce it;
the findings and their fixes; and the residual risks accepted deliberately. Tenant = the stable
WebID `user`; the finer isolation bundle is `(user, profile)` (M5), and everything
connection/token-shaped is keyed `(user, profile, pod)` (`persist/PodKey.kt`).

Reviewed at M6.4 implementation time against the then-current DAOs. A new collection or query
added later must be checked against the same questions: *is every read/write filtered by the
tenant key, and if not, is the exception deliberate and caller-unreachable?*

## Collections

| Collection | DAO / store | Tenant keying | Review result |
|---|---|---|---|
| `podTokens` | `TokenVaultDao` | unique `(user, profile, pod)` | ✓ every query goes through `keyFilter` (full key) — **except** `findExpiringBefore` and `findNotRotatedSince`, the two sweep selections, which are deliberately cross-tenant: they drive the background refresh sweep, are never caller-exposed, and their results only flow into per-key refreshes. The two markers they select on — `lastUsedAt` and `lastRefreshAttemptAt` — are written by `touchLastUsed` and `markRefreshAttempted`, both of which carry the full key. Claim mutations (`tryClaimRefresh` / `replaceIfClaimedBy` / `releaseRefreshClaim`) all carry the full key plus the holder. |
| `connections` | `ConnectionRegistryDao` | unique `(user, profile, pod)` | ✓ `find`/`upsert`/`delete` use the full key; `listForProfile` filters `(user, profile)`. |
| `profiles` | `ProfileDao` | unique `(user, profile)` | ✓ all queries filter `user` (+ `profile`); the default profile is implicit, never stored. |
| `auditLog` | `AuditLogDao` (new in M6.4) | index `(user, profile, ts)` | ✓ append-only; the only read (`listFor`) requires `user` — there is deliberately no unscoped listing on the DAO. Rows carry no token material, no request bodies, no tool arguments (fixed `detail` labels only). TTL-bounded via `expiresAt`. |
| `oauth.refreshTokens` | `McpRefreshTokenStore` | lookup by SHA-256 `tokenHash`; rows carry `(user, profile, clientId)` | ✓ the hash lookup is secret-keyed (unguessable), and `AuthEndpoint.handleRefreshToken` re-binds the row before rotating: presented `client_id` must match the row, and the row's `profile` must match the token-endpoint path (each profile is its own AS issuer). `revokeFamily`/`findByFamily` key on the unguessable `familyId`. |
| `oauth.authCodes` | `AuthorizationCodeStore` | SHA-256 `_id` of the one-time code | ✓ consume is an atomic `findOneAndDelete`; the entry pins `(user, profile, clientId, redirectUri, PKCE)`, all re-checked at `/token` (incl. profile-vs-path). |
| `oauth.loginStates`, `oauth.consentTransactions`, `oauth.webLoginStates` | M6.3 one-time stores | SHA-256 `_id` of the one-time secret | ✓ same pattern: secret-keyed, single-use consume, TTL-indexed; the consumer re-binds the entry to the session where a session exists. |
| `oauth.reauthChallenges` | `ReauthorizeChallengeStore` (`:sempods-mcp-core` since consolidation M5) | `_id` = `(profile, clientId, user)` | ✓ the odd one out of the M6.3 stores: its `_id` is not a hash, because nothing here is redeemable — the row gates a loop-avoidance shortcut and mints nothing. Single-use consume is still atomic, and the replay predicate re-binds to the token (`jti` must differ, `iat` at-or-after the challenge). The `profile` in the key arrived with M5; before it, two sessions of one user and client in different profiles could consume each other's challenge. |
| `oauth.podConnectStates` | `PodConnectStateStore` | SHA-256 `_id` of the one-time `state` | ✓ plus an explicit tenant cross-check at consume: the pod-connect callback refuses a pending entry whose `user` differs from the web session (`WebUiEndpoint`, "session/user mismatch"), and the PKCE `codeVerifier` is `SecretCipher`-encrypted at rest. |
| `oauth.clientRegistrations` | `DcrClientDao` | `(profile, clientId)` / `(profile, fingerprint)` — **no user** | ✓ by design: DCR happens pre-authentication (RFC 7591), so there is no user yet. A `client_id` is a public identifier, not a capability — consent + tokens bind it to a user later. Queries are profile-scoped. |
| `oauth.signingKeys` | `SigningKeyDao` | service-global | ✓ by design: one signing key set for all profiles/users (the per-profile issuer is a claim/URL split, not a key split). Private JWK encrypted at rest (M6.1); bootstrap is race-safe. |
| `leases` | `LeaseDao` | service-global (`_id` = lease name) | ✓ by design: replica-coordination infrastructure (sweep leader election), carries no tenant data. |

## In-memory per-replica state

| State | Keying | Review result |
|---|---|---|
| `TokenBucketRateLimiter` buckets (pod limiter + user quota + audit sampler; the class itself is `commons`' since the pod server took a budget too) | pod base / `"$profile\|$user"` | ✓ bounded: idle-evict sweep past 4096 keys, CAS-gated. The `"$profile\|$user"` key is unambiguous — `ProfilePath` restricts profile names to `[a-z0-9-]`, so the `\|` separator cannot be forged by a crafted WebID. |
| `PodTokenProvider.locks` (per-key refresh mutex) | `PodKey` | **Finding, fixed in M6.4** — see below. |
| `WebSession` cookie | service-signed JWT (`typ`-separated from MCP bearers) | ✓ binds only the verified `user`; profile is a per-request selection, gated by `ownedProfile()` for mutations. |

## Endpoint-level tenant gates (verified, not new)

- **MCP endpoint (M5 hard isolation):** a bearer's `profile` claim must match the path it
  arrived on; `iss` is bound to the profile. The per-user quota (M6.4) sits *after* these gates,
  so its key is a verified identity and an unauthenticated spray cannot drain a victim's budget.
- **Read fan-out / writes:** every pod resolution goes through
  `ConnectionRegistryDao.listForProfile(user, profile)` / `find(user, profile, pod)` — a
  `targets` entry naming a pod the profile never connected surfaces as `not_connected`, never as
  a cross-tenant read. Writes additionally refuse never-connected targets pre-pod.
- **Web UI:** every mutation requires the session cookie + per-session CSRF token + `ownedProfile()`
  (unknown/tampered profile → refused, not silently downgraded to the default bundle).
- **Service AS:** authorization codes and refresh tokens are profile-bound and re-validated
  against the endpoint path; rotation reuse revokes the family (audited since M6.4).

## Findings & fixes (M6.4)

1. **`PodTokenProvider.locks` grew unbounded** — one `Mutex` per `(user, profile, pod)` ever
   touched, never evicted; on a long-lived multi-tenant instance this is a slow leak keyed by
   tenant activity. **Fixed:** past 4096 entries a CAS-gated sweep (≤ 1/min) evicts
   currently-unlocked mutexes. Safety: since M6.3 the correctness primitive against
   double-refresh is the cross-replica vault claim (`tryClaimRefresh` + dueness re-check under
   the claim); the mutex only reduces claim contention. Two same-JVM coroutines briefly holding
   different mutex instances for one key behave exactly like two replicas — one wins the claim,
   the other skips (sweep) or polls (on-demand). A held mutex is never evicted.
2. **Per-replica rate-limit budget was an open TODO** (`PodRateLimiter`) — settled, not changed:
   both the per-pod outbound budget and the per-user quota are deliberately in-memory per
   replica (a shared Mongo counter would put a write on the per-request hot path). The effective
   budget scales with the replica count; acceptable for a small replica set, revisit only if the
   deployment grows past that.

## Accepted residual risks

- **Quota × replicas:** with N replicas and client-side load balancing, a user's effective
  `tools/call` budget is up to N × `USER_RATE_LIMIT_PER_MINUTE` (same for the per-pod budget and
  the `RATE_LIMITED` audit sampling, which is also per replica). Deliberate — see finding 2.
- **Quota-exempt paths:** the per-user `tools/call` quota is scoped to the vault-accessing pod
  tools (read/write) plus the unknown-tool rejection; `authorize` is **exempt**, alongside the
  handshake methods (`initialize` / `tools/list` / `ping`). It is the OAuth re-consent /
  pod-connect escape hatch, so throttling it would let an exhausted budget lock a user out of
  recovering (they could not re-consent until the bucket refilled). Same scope as the audit trail
  (both cover pod-tool dispatch, not `authorize`).
- **Pre-auth DCR is unthrottled:** `/register` requires no user (per RFC 7591), so `oauth.clientRegistrations`
  can be grown by an anonymous client. Fingerprint dedup absorbs the common re-register loop of
  real AI clients; a hostile flood is a disk-growth nuisance, not a tenant-isolation break.
  Revisit with an IP-level limit if it becomes real.
- **Audit retention is write-time:** `AUDIT_RETENTION_DAYS` stamps `expiresAt` per row at write
  time, so a retention change affects only new rows (consistent with the no-migrations stance).
- **Not audited, deliberately** (the `TOOL_CALL` trail covers pod-tool dispatch, not every
  `tools/call` verbatim): the `authorize` helper tool (touches no vault; its OAuth side is
  covered by the token events), the endpoint-level **unknown-tool rejection** (a protocol error
  before dispatch — a client bug, no pod/vault touched; note it still consumes quota, so a
  misbehaving client cannot spam it for free), transient infra failures during refresh
  (throttle/SSRF-block — they surface on the audited tool call as `pod_error` and would flood
  the trail), the mid-refresh discard when a re-connect supersedes an in-flight rotation
  (not a refresh outcome), and the refresh skipped for a connection already marked dead — that
  verdict was audited once, when the pod declared the grant finished; re-auditing it on every sweep
  tick is what the `deadGrantSince` short-circuit removed.

## Related

- [`tool-contract.md`](tool-contract.md) — the tool surface whose calls the audit trail records.
- [`../AGENTS.md`](../AGENTS.md) — module conventions + phase status (M6.4 in the milestone sequence).
