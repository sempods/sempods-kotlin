# Owner-installed service clients (SOLL)

> Progress is tracked in place. Completed items stay in this file, marked done, until the whole
> milestone is consolidated. Do not prune them individually — the roadmap documents progress, not
> only remaining work.

Concept: [`../concepts/app-installation.md`](../concepts/app-installation.md) — what this is and
why. Not repeated here.

When this is done, a pod owner can install a durable service client without host-admin credentials:
an owner-facing installer obtains an ordinary pod access token, protected DCR registers a
confidential client with a server-assigned `client_id`, sempods records explicit service-client
grants, and the service later uses Client Credentials. Each step checks whether PoC state can be
migrated or deliberately broken.

## Work

- [ ] 1 — Settle and implement the installer feature scope. `service-clients` is the working name:
  it matches the existing credential class and the repository's noun-style feature scopes
  (`public-read`). Decide this before persistence, because the literal reaches consent screens,
  stored grants and client requests. `PodScopeValidator.featureScopes`, authorization-code issuance,
  refresh-token narrowing and token sanitization all learn the new feature scope.
- [ ] 2 — Build the owner-facing installer as an OAuth caller rather than a UI-only session bypass.
  The first implementation may be the existing consent page, a built-in installer, `my.sempods.org`,
  or a later CLI, but it still runs Authorization Code + PKCE against the pod, requests
  `service-clients`, receives a short-lived pod access token, calls the protected install surface,
  and discards the token. `my.sempods.org` remains an implementation-agnostic owner surface, not a
  control-plane-only shortcut. Covered by an HTTP flow test; no service-client registration route
  accepts a browser cookie alone.
- [ ] 3 — Carry requested feature scopes through consent. Today `runAuthorize` renders no requested
  feature-scope set and the auto-grant branch re-issues stored feature scopes without intersecting
  the request. The new path must render privileged feature scopes only when requested, never
  preselect `service-clients`, persist `requested ∩ granted`, and auto-grant only the same
  intersection. `public-read` keeps its existing preselect and public-context rule; neither
  generalizes.
- [ ] 4 — Make token lifetime visible in the install UI. The consent page distinguishes the
  short-lived installer access token from the durable service-client registration and once-returned
  secret. `offline_access` and refresh-token lifetime are handled by the related refresh-token
  milestone, but the UI vocabulary must be shared so the two flows do not describe lifetime
  differently.
- [ ] 5 — Split `/register` into its two profiles without changing the public one. Unauthenticated
  RFC 7591 DCR continues to register public `dyn:` clients for Authorization Code. The protected
  profile is selected by an owner bearer plus `grant_types=["client_credentials"]` and
  `token_endpoint_auth_method="client_secret_basic"`; it requires `sub == pod.owner` and, after item
  1, the `service-clients` feature scope. It returns a confidential client registration with the
  secret exactly once and rejects public-client metadata on the protected profile. The protected
  profile must write the service-client row consumed by Client Credentials token exchange; standard
  conformance is at the HTTP surface, not in a separate DCR-only collection.
- [ ] 6 — Preserve server-assigned identifiers as an invariant. The protected registration body must
  not accept `client_id`, `clientId`, `contextRoot`, or a caller-named app root. Every private sandbox
  path is derived from the server-assigned service-client ID. This is the main security reason for
  using DCR here: the caller loses the ability to name another app or existing context into the
  installation.
- [ ] 7 — Keep sempods grants out of OAuth client registration state. Protected DCR creates the
  service client with no data authority by default. A separate owner-authorized grant operation, used
  by the same UI flow, assigns read/write/manage grants to that service client. `service-clients`
  permits service-client lifecycle; it does not itself confer authority over every context owned by
  the pod owner. A grant write is valid only when bound to an owner consent transaction for this
  service client and exact grant set, or when the caller independently holds covering context
  authority. The UI may offer "create a private `apps/<serverAssignedClientId>` sandbox and grant
  manage" as a convenience, but a permanent reader with only `#read` on existing contexts is equally
  valid. Depends on 5 and 6.
- [ ] 8 — Add list, revoke and rotate surfaces for owner-installed service clients. Revoking a
  service client removes the registration and stops future Client Credentials tokens without
  deleting the data context. Secret rotation follows the same once-returned-secret rule as
  registration and must not require deleting the context. Depends on 5 and 7.
- [ ] 9 — Budget the registration surfaces. `/register` is unauthenticated for public DCR today and
  unbounded; the protected service-client profile also mints bcrypt-cost secrets. Add rate limits
  that preserve real MCP reconnect behaviour but prevent unbounded DCR rows and secret-minting
  bursts. Decide whether this item also starts a sweep for orphaned public DCR rows. Depends on 5.
- [ ] 10 — Update clients and examples. `SempodsPodClient` can drive the protected DCR and grant
  operations for owner-facing UI tests and future CLI installers.
- [ ] 11 — End-to-end acceptance test: owner-facing installer flow, owner token with
  `service-clients`, protected DCR, explicit grant assignment, Client Credentials token, allowed read
  or write inside the selected grants, refused outside them, revoke, and token mint refused.

## Open decisions

- Scope name — `service-clients` is probably right because it names the capability surface, not one
  verb. Alternatives such as `install-service-client` are clearer for item 7 but too narrow once
  list, revoke and rotate are added; `manage-service-clients` is explicit but longer and unlike
  `public-read`.
- Installer identity — whether the first implementation is the existing consent page, a built-in
  first-party installer, `my.sempods.org`, or a CLI path. Either way, the installer must obtain a pod
  access token; no cookie-only install bypass.
- Grant API shape — protected DCR plus a separate grant operation is the cleaner standard boundary.
  A single UI may still call both in one submit. If a combined HTTP endpoint is chosen instead, it
  must be documented as a sempods extension rather than as plain RFC 7591 DCR.
- Grant authority model — the initial implementation may stay owner-only, but `service-clients`
  alone must never mean "grant this service client any context authority the owner could have
  granted manually". Generalizing beyond owner-bound consent needs a distinct context-management
  authority rule.
- Sandbox convenience — creating `apps/<serverAssignedClientId>#manage` is useful but no longer
  mandatory. Decide whether the first UI defaults to "no grants until selected", "read existing
  contexts", or "create private app sandbox".
- Owner catch-all — today an app carrying an owner token can create and delete contexts more broadly
  than this feature needs. That is an existing authorization defect, not part of the DCR shape; track
  it as a separate hardening milestone before relying on owner tokens for non-interactive installers.

## Acceptance

One focused command should cover the milestone once code exists:

```bash
./gradlew :sempods-server:test --tests "org.sempods.api.pod.system.auth.*" :sempods-client:test
```

Before each implementation item starts, inspect existing stored rows or code paths named in the item
and record whether migration, compatibility, or intentional PoC breakage is the chosen path.

Related hardening lives in
[`offline-access-refresh-tokens.md`](offline-access-refresh-tokens.md), because refresh-token
issuance and hosted MCP migration can ship independently from owner-installed service clients.
