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
  the installer feature scope, receives a short-lived pod access token, calls the protected install
  surface, and discards the token. `my.sempods.org` remains an implementation-agnostic owner surface,
  not a control-plane-only shortcut. If the install flow assigns grants immediately, the installer
  must also be able to send the owner through a second pod-rendered grant-consent screen after
  protected DCR succeeds. Covered by an HTTP flow test; no service-client registration route accepts
  a browser cookie alone.
- [ ] 3 — Carry requested feature scopes through consent. Today `runAuthorize` renders no requested
  feature-scope set and the auto-grant branch re-issues stored feature scopes without intersecting
  the request. The new path must render privileged feature scopes only when requested, never
  preselect the installer feature scope, and must not let a normal persistent grant make this
  authority silently reusable. If the first implementation treats installer authorization as
  one-shot, the consent result is consumed or revoked after the protected DCR call and the auto-grant
  path never re-issues it without a fresh owner screen. Protected DCR must also reject replay of the
  already-issued installer bearer after the first successful registration, because the feature scope
  is carried in the access token and is not re-resolved from the grant store on every call. If the
  installer authority is intentionally durable, the UI must say so and tests must cover repeated
  installs from the same installer grant and bearer lifetime. `public-read` keeps its existing
  preselect, persistence and public-context rule; none of those behaviours generalize.
- [ ] 4 — Make token lifetime visible in the service-client install UI. This milestone owns the
  service-client lifetime wording only: the grant-consent page distinguishes the
  short-lived installer access token from the durable service-client registration and once-returned
  secret, and describes that secret as valid until rotation or registration removal. `offline_access`
  and refresh-token lifetime are handled by the related refresh-token milestone, which must reuse
  this vocabulary rather than redefine the service-client secret text.
- [ ] 5 — Split `/register` into its two profiles without changing the public one. Unauthenticated
  RFC 7591 DCR continues to register public `dyn:` clients for Authorization Code. The protected
  profile is selected by an owner bearer plus `grant_types=["client_credentials"]` and
  `token_endpoint_auth_method="client_secret_basic"`; it requires alias-aware owner recognition
  equivalent to `PodGrantsFacade.isPodOwner(podDbo, identity.allUris)` and the installer feature
  scope. It must not require literal `sub == pod.owner`, because the access token may carry the
  canonical WebID while ownership was recognized through an alias. The mirror guard is just as
  important: an unauthenticated `/register` request that asks for `client_credentials`,
  `client_secret_basic`, or any other confidential-client metadata must be rejected, not silently
  downgraded into a public client and not upgraded into a confidential one. The protected profile
  returns a confidential client registration with the secret exactly once and rejects public-client
  metadata on the protected profile. It must write the service-client row consumed by Client
  Credentials token exchange; standard conformance is at the HTTP surface, not in a separate
  DCR-only collection. Depends on 1.
- [ ] 6 — Preserve server-assigned identifiers as an invariant. The protected registration body must
  not accept `client_id`, `clientId`, `contextRoot`, or a caller-named app root. Every private sandbox
  path is derived from the server-assigned service-client ID. This is the main security reason for
  using DCR here: the caller loses the ability to name another app or existing context into the
  installation.
- [ ] 7 — Keep sempods grants out of OAuth client registration state. Protected DCR creates the
  service client with no data authority by default. Grants can be assigned later through the
  management surfaces from item 8, or immediately through a separate owner-authorized grant
  operation. The immediate path uses a second pod-rendered consent screen after registration, because
  the service-client identity and server-assigned ID are not known during the installer-token
  authorize step. Reuse the existing `ConsentTransactionStore` shape where possible: one screen,
  once, bound to the browser session and exact grant set. Define the completion grammar for this
  second sempods transaction: where the browser returns, how a CLI installer learns success or
  refusal, and whether it may poll the list surface from item 8. The installer feature scope permits
  service-client lifecycle; it does not itself confer authority over every context owned by the pod
  owner. A grant write is valid only when bound to an owner consent transaction for this service
  client and exact grant set, or when the caller independently holds covering context authority. The
  UI may offer "create a private `apps/<serverAssignedClientId>` sandbox and grant manage" as a
  convenience, but a permanent reader with only `#read` on existing contexts is equally valid.
  Existing service-client persistence rejects empty scope sets and deletes rows emptied by context
  revocation, so this item must add tests and storage changes for zero-grant registrations that
  remain listable and rotatable while minting no useful resource authority. Depends on 5 and 6.
- [ ] 8 — Add list, grant assignment/update, revoke and rotate surfaces for owner-installed service
  clients. Revoking a service client removes the registration and stops future Client Credentials
  tokens without deleting the data context. Removing the last grant leaves a zero-grant service
  client registration in place unless the owner explicitly revokes the client. Secret rotation
  follows the same once-returned-secret rule as registration and must not require deleting the
  context. Depends on 5 and 7.
- [ ] 9 — Budget the registration surfaces. `/register` is unauthenticated for public DCR today and
  unbounded; the protected service-client profile also mints bcrypt-cost secrets. Add rate limits
  that preserve real MCP reconnect behaviour but prevent unbounded DCR rows and secret-minting
  bursts. Decide whether this item also starts a sweep for orphaned public DCR rows. Depends on 5.
- [ ] 10 — Update clients and examples. `SempodsPodClient` can drive the protected DCR and grant
  operations for owner-facing UI tests and future CLI installers.
- [ ] 11 — End-to-end acceptance test: owner-facing installer flow, first consent for an owner token
  with the installer feature scope, protected DCR, either no initial grants plus later assignment via
  the management surface or a second pod-rendered consent for explicit grant assignment, Client
  Credentials token, allowed read or write inside the selected grants, refused outside them, revoke,
  and token mint refused. If the immediate-grant path ships, a CLI-path test must cover both browser
  hand-offs and the second transaction's completion signal explicitly. Tests also cover zero-grant
  registration, last-grant removal, later grant assignment, alias-aware owner recognition, and replay
  of the same installer bearer after its first successful protected DCR call.

## Open decisions

- Scope name — `service-clients` is probably right because it names the capability surface, not one
  verb. Alternatives such as `install-service-client` are clearer for the protected DCR step but too
  narrow once list, revoke and rotate are added; `manage-service-clients` is explicit but longer and
  unlike `public-read`.
- Installer identity — whether the first implementation is the existing consent page, a built-in
  first-party installer, `my.sempods.org`, or a CLI path. Either way, the installer must obtain a pod
  access token; no cookie-only install bypass. If immediate grant assignment ships, the installer
  also needs a second browser hand-off and a completion signal for the grant-consent transaction.
- Grant API shape — protected DCR plus a separate grant operation is the cleaner standard boundary.
  Registration without grants can finish after one browser stop, with grants assigned later through
  the management surface. Immediate grants need either the second pod-rendered consent transaction or
  independent covering context authority; if a combined HTTP endpoint is chosen instead, it must be
  documented as a sempods extension rather than as plain RFC 7591 DCR.
- Grant authority model — the initial implementation may stay owner-only, but `service-clients`
  alone must never mean "grant this service client any context authority the owner could have
  granted manually". Generalizing beyond owner-bound consent needs a distinct context-management
  authority rule.
- Refresh-token prerequisite — before protected DCR is exposed, either the related
  `offline_access` hardening has landed or this milestone suppresses refresh-token issuance for
  installer-feature-scope-only authorizations. That is necessary but not sufficient: if the
  installer feature scope is persisted like an ordinary static-client grant, the installer can start
  another authorization-code flow and recover the same authority through auto-grant. Decide whether
  installer authorization is one-shot by default or deliberately durable, then implement and display
  that lifetime honestly. For the one-shot path, protect both layers: no silent re-issuance on the
  next authorization flow, and no repeated protected-DCR calls with the already-issued bearer.
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
[`offline-access-refresh-tokens.md`](offline-access-refresh-tokens.md). Hosted MCP migration can
ship independently from owner-installed service clients, but the installer rollout still needs the
refresh-token boundary for installer-feature-scope authorizations before it is exposed.
