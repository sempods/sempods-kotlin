# Owner-installed apps (SOLL)

> Progress is tracked in place. Completed items stay in this file, marked done, until the whole
> milestone is consolidated. Do not prune them individually — the roadmap documents progress, not
> only remaining work.

Concept: [`../concepts/app-installation.md`](../concepts/app-installation.md) — what this is and
why. Not repeated here.

When this is done, a pod owner installs an unattended app on a pod they do not host: they authorize
a local tool once through the ordinary browser flow, it registers a service client sandboxed to one
context, and it runs on 2-leg tokens from then on. An app reaches the owner's catch-all only where
the owner granted it.

## Work

- [ ] 1 — Feature scopes become a set instead of one boolean, and `contexts` and `service-clients`
  join `public-read` in `PodScopeValidator.featureScopes`. **The requested set has to reach the
  decision**, which today it does not: `runAuthorize` hands `renderConsentUi` no requested scopes at
  all, and the auto-grant branch re-issues a stored feature grant off `existingGrants` without
  intersecting the request. So a privileged scope is offered only when the client asked for it,
  never preselected, and granted as `requested ∩ granted` on both paths — otherwise extending the
  set alone would put `service-clients` in front of an owner authorizing an app that never wanted
  it. `public-read` keeps its preselect and its "needs at least one public context" rule; neither
  generalizes.
- [ ] 2 — Owner recognition answers the same question everywhere. `PodGrantsFacade.isPodOwner` reads
  `identity.allUris`, `SempodsBaseEndpoint.resolvePodOwnerPrincipal` reads
  `WebIdUriDeriver.derivableAliases(sub)`, and the second does not bridge `e/` to `oidc/`. Decide it
  with evidence rather than by preference: an identity whose stored owner URI appears only in
  `alsoKnownAs` is recognized at consent and refused at the endpoint, so if that identity can be
  produced the endpoint path learns the aliases. Recording the narrower check as deliberate is
  allowed only on a demonstration that it cannot. Covered either way by an HTTP test that walks the
  real browser path — DCR, `/authorize`, the OIDC callback, consent, `/token` — with a linked-alias
  identity, instead of minting an owner token directly.
- [ ] 3 — Context management asks for the `contexts` scope beside the owner principal.
  `PodContextsEndpoint.authorizeContextManageOrThrow`; the bootstrap case (owner, fresh pod, no
  grants) keeps working. Depends on 1, 2.
- [ ] 4 — `POST {pod}/_system/auth/service-clients/{clientId}` registers a service client for the
  owner, with the `expectedRegistrationId` contract and the 409 the admin route already defines.
  **The rule that decides every case: the route may confer authority only over ground it created
  in that same call.** A `clientId` carrying no registration, whose `apps/{clientId}` does not
  exist, is that case — derived, privatized, scoped, on `service-clients` alone. Everything already
  standing is a replacement and needs authority the caller already holds over that root, a covering
  `#manage` grant or the `contexts` capability: a supplied `contextRoot`, a registration under this
  `clientId`, an `apps/{clientId}` that is already there. State it as the rule rather than as three
  checks, because each of the three is the same escalation — `service-clients` alone minting
  `X#manage` over a context the owner never delegated, by naming it outright or by re-registering
  another app's `clientId`, which an omitted `expectedRegistrationId` re-mints by contract. New
  `PodServiceClientsEndpoint`; the request and response types move out of `AdminPodsEndpoint` so
  both routes answer one shape. Depends on 1, 2.
- [ ] 5 — The same route lists registrations and deletes one, so revoking an app does not mean
  deleting the context its data lives in. Depends on 4.
- [ ] 6 — A budget on the new route, from `TokenBucketRateLimiter`, keyed the two tiers
  `PodTokenRateLimiter` uses. Minting a secret costs one bcrypt at cost 12. Depends on 4.
- [ ] 7 — `SempodsPodClient` speaks the route: register, list, unregister, with the route constants
  in `SempodsPodRoutes`. Depends on 4, 5.
- [ ] 8 — `ServiceClientAuth` — the caching `SempodsAuth` over `client_credentials` that every
  2-leg consumer writes by hand today. `SempodsAuth`'s own KDoc says this module supplies no
  such adapter; that sentence is about a multi-pod registry and has to say so afterwards.
- [ ] 9 — A loopback login in the client (RFC 8252). Two calls on `SempodsClient`, and they are
  DCR at `_system/auth/register` and the `authorization_code` exchange at `_system/auth/token` —
  not authorize and token. A local tool has no stable origin, so it cannot assert a `did:web`
  identity and has to hold a `dyn:` client id of its own before `/authorize` will look at its
  loopback redirect; a client API that left that step out would send its caller to raw HTTP for the
  first move. Over the two, an orchestrator that binds an ephemeral port, carries the `client_id`
  into the authorize URL, keeps the PKCE verifier and hands back a `SempodsAuth`. It surrenders the
  authorize URL rather than opening a browser. Depends on 1, 2.
- [ ] 10 — One test walks the whole path, starting where a real tool starts — no client id in hand:
  DCR, consent, owner token, register, mint, write inside the sandbox, refused outside it,
  unregister, mint refused. Plus the two refusals that are the point of item 4: `service-clients`
  alone may not name an existing context, and may not re-register another app's `clientId`.

## Open decisions

- The two scope names — `contexts` and `service-clients` read as the surfaces they open, which is
  what `public-read` does not do. Settled by item 1 and hard to change afterwards: a scope name
  reaches consent dialogs and stored grants.
- Whether a caller-supplied `contextRoot` that is public should be demoted the way the admin route
  demotes a derived one. Not a question about descendants, whatever the admin route's wording
  suggests: `isPublic` is a flag on one context row and `getPublicContexts` matches it exactly, so
  nothing is inherited down the slash. What demotion changes is anonymous read of that root's own
  contents — and the owner published it on purpose. Item 4 has to answer it either way.
- What a provisioning that failed halfway leaves behind. The root is created before the
  registration is written, so a failure in between leaves `apps/{clientId}` standing with nothing in
  it, and item 4's rule then reads it as a replacement — the caller cannot finish what it started.
  The likely answer is an exception for a root that is *empty*: no registration, no statements, no
  sub-contexts. It has to be exactly that narrow, because a root whose registration was deleted
  while its data survived is a different thing and must stay protected. Item 4 cannot ship without
  deciding this.
- Whether `resolvePodOwnerPrincipal` should read the `alsoKnownAs` aliases. Item 2 decides it; until
  then it is open whether the two checks disagreeing is a defect or a boundary.
- Whether `:sempods-client` and `:sempods-control-plane-client` should share one result type for a
  registration. They describe the same response and sit in modules whose dependency runs one way;
  `existsPod` is the precedent for leaving a duplicate alone.
- Registration is authenticated, but DCR at `{pod}/_system/auth/register` is not and carries no
  budget at all (`docs/auth/README.md` §"Known limitations"). Item 6 could cover both.
- The Device Authorization Grant (RFC 8628) is the acquisition path for a client with no loopback —
  over SSH, in a container. Nothing here implements it, and `slow_down` already exists in the token
  endpoint's error set.

## Acceptance

Item 10 is the criterion: one run that installs an app and proves the sandbox holds, with no admin
credential anywhere in it.

```bash
./gradlew :sempods-server:test --tests "org.sempods.api.pod.system.auth.*" :sempods-client:test
```

The infrastructure step in [`../../AGENTS.md`](../../AGENTS.md) §"Quick reference" comes first;
without it the Mongo-backed suites fail in ways that read like product bugs.
