# Owner-installed service clients (SOLL)

> Progress is tracked in place. Completed items stay in this file, marked done, until the whole
> milestone is consolidated. Do not prune them individually — the roadmap documents progress, not
> only remaining work.

Concept: [`../concepts/app-installation.md`](../concepts/app-installation.md) — what this is and
why. Not repeated here.

When this is done, a pod owner can install a durable service client without host-admin credentials:
the built-in pod UI obtains an ordinary pod access token, protected DCR registers a confidential
client, sempods records explicit service-client grants, and the service later uses Client
Credentials. Each step checks whether PoC state can be migrated or deliberately broken.

## Work

- [ ] 1 — Settle and implement the installer feature scope. `service-clients` is the working name:
  it matches the existing credential class and the repository's noun-style feature scopes
  (`public-read`). Decide this before persistence, because the literal reaches consent screens,
  stored grants and client requests. `PodScopeValidator.featureScopes`, authorization-code issuance,
  refresh-token narrowing and token sanitization all learn the new feature scope.
- [ ] 2 — Build first-party OAuth for the pod UI rather than a UI-only session bypass. The built-in
  UI is a first-party installer client, but it still runs Authorization Code + PKCE against the pod,
  requests `service-clients`, receives a short-lived pod access token, calls the protected install
  surface, and discards the token. Choose and document the first-party client identity shape
  (`did:web` if the existing redirect coverage fits; a pod-local first-party identity otherwise).
  Covered by an HTTP flow test; no service-client registration route accepts a browser cookie alone.
- [ ] 3 — Carry requested feature scopes through consent. Today `runAuthorize` renders no requested
  feature-scope set and the auto-grant branch re-issues stored feature scopes without intersecting
  the request. The new path must render privileged feature scopes only when requested, never
  preselect `service-clients`, persist `requested ∩ granted`, and auto-grant only the same
  intersection. `public-read` keeps its existing preselect and public-context rule; neither
  generalizes.
- [ ] 4 — Make token lifetime visible in consent and install UI. The consent page distinguishes a
  short-lived access token from `offline_access` and from a service client secret. It states the
  effective lifetime class, not only the scope name: access token, rolling refresh token, and
  service-client registration. Tests assert that requesting `offline_access` changes the rendered
  text and that requesting `service-clients` without `offline_access` does not imply a refresh token.
- [ ] 5 — Harden refresh-token issuance around `offline_access`. Before changing behaviour, measure
  who relies on refresh tokens without requesting it: hosted MCP currently builds pod authorize URLs
  with `scope = null` and persists the refresh token it receives, so this is a real migration point.
  Update MCP to request `offline_access` where it needs long-lived pod connections, add reconnect or
  compatibility handling for existing vault rows, then change the pod token endpoint to issue a
  refresh token only when `offline_access` was granted. The implementation plan for this item must
  say whether PoC rows are migrated, kept until reconnect, or intentionally invalidated.
- [ ] 6 — Improve refresh-token handling while touching the flow. Keep rotating-family reuse
  detection, but make the new offline-access boundary explicit in tests and docs; ensure refresh
  responses cannot silently widen feature scopes; and check whether refresh-token revocation,
  context-grant revocation and DCR liveness still agree after MCP starts asking for `offline_access`.
  Depends on 5.
- [ ] 7 — Split `/register` into its two profiles without changing the public one. Unauthenticated
  RFC 7591 DCR continues to register public `dyn:` clients for Authorization Code. The protected
  profile is selected by an owner bearer plus `grant_types=["client_credentials"]` and
  `token_endpoint_auth_method="client_secret_basic"`; it requires `sub == pod.owner` and, after item
  1, the `service-clients` feature scope. It returns a confidential client registration with the
  secret exactly once and rejects public-client metadata on the protected profile.
- [ ] 8 — Keep sempods grants out of OAuth client registration state. Protected DCR creates the
  service client with no data authority by default. A separate owner-authorized grant operation, used
  by the same UI flow, assigns read/write/manage grants to that service client. The UI may offer
  "create a private `apps/<clientId>` sandbox and grant manage" as a convenience, but a permanent
  reader with only `#read` on existing contexts is equally valid. Depends on 7.
- [ ] 9 — Add list, revoke and rotate surfaces for owner-installed service clients. Revoking a
  service client removes the registration and stops future Client Credentials tokens without
  deleting the data context. Secret rotation follows the same once-returned-secret rule as
  registration and must not require deleting the context. Depends on 7 and 8.
- [ ] 10 — Budget the registration surfaces. `/register` is unauthenticated for public DCR today and
  unbounded; the protected service-client profile also mints bcrypt-cost secrets. Add rate limits
  that preserve real MCP reconnect behaviour but prevent unbounded DCR rows and secret-minting
  bursts. Decide whether this item also starts a sweep for orphaned public DCR rows. Depends on 7.
- [ ] 11 — Update clients and examples. `SempodsPodClient` can drive the protected DCR and grant
  operations for first-party UI tests and future CLI installers. Hosted MCP requests `offline_access`
  explicitly for long-lived pod connections, and tests pin the exact authorize URL scopes so the
  dependency is visible.
- [ ] 12 — End-to-end acceptance test: built-in first-party UI flow, owner token with
  `service-clients`, protected DCR, explicit grant assignment, Client Credentials token, allowed read
  or write inside the selected grants, refused outside them, revoke, and token mint refused. A second
  test covers the migration-sensitive MCP path: it requests `offline_access`, receives and rotates a
  refresh token, and a request without `offline_access` receives no refresh token.

## Open decisions

- Scope name — `service-clients` is probably right because it names the capability surface, not one
  verb. Alternatives such as `install-service-client` are clearer for item 7 but too narrow once
  list, revoke and rotate are added; `manage-service-clients` is explicit but longer and unlike
  `public-read`.
- First-party client identity — whether the built-in pod UI can be represented as an ordinary
  `did:web` client under the existing redirect coverage, or needs a documented first-party client
  identity. Either way, the UI must obtain a pod access token; no cookie-only install bypass.
- Existing PoC refresh tokens — before item 5 lands, decide from real rows and users whether to
  preserve connections until reconnect, migrate connection metadata, or invalidate and require
  reconnect. The repository is still PoC, but the decision belongs in the item, not as an accidental
  side effect.
- Grant API shape — protected DCR plus a separate grant operation is the cleaner standard boundary.
  A single UI may still call both in one submit. If a combined HTTP endpoint is chosen instead, it
  must be documented as a sempods extension rather than as plain RFC 7591 DCR.
- Sandbox convenience — creating `apps/<clientId>#manage` is useful but no longer mandatory. Decide
  whether the first UI defaults to "no grants until selected", "read existing contexts", or "create
  private app sandbox".

## Acceptance

One focused command should cover the milestone once code exists:

```bash
./gradlew :sempods-server:test --tests "org.sempods.api.pod.system.auth.*" :sempods-mcp:test :sempods-client:test
```

Before each implementation item starts, inspect existing stored rows or code paths named in the item
and record whether migration, compatibility, or intentional PoC breakage is the chosen path.
