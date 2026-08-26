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
  join `public-read`. `PodScopeValidator.featureScopes`, the consent render and submit paths in
  `PodAuthEndpoint`, and one checkbox per available scope in `templates/consent.html`, preselected
  for the owner. The "needs at least one public context" rule stays specific to `public-read`.
- [ ] 2 — Owner recognition answers the same question everywhere. `PodGrantsFacade.isPodOwner` reads
  `identity.allUris`, `SempodsBaseEndpoint.resolvePodOwnerPrincipal` reads
  `WebIdUriDeriver.derivableAliases(sub)`; either the endpoint path learns the `alsoKnownAs` aliases
  or the narrower check is recorded as deliberate. Covered by an HTTP test that walks the real
  browser path — DCR, `/authorize`, the OIDC callback, consent, `/token` — instead of minting an
  owner token directly.
- [ ] 3 — Context management asks for the `contexts` scope beside the owner principal.
  `PodContextsEndpoint.authorizeContextManageOrThrow`; the bootstrap case (owner, fresh pod, no
  grants) keeps working. Depends on 1, 2.
- [ ] 4 — `POST {pod}/_system/auth/service-clients/{clientId}` registers a service client for the
  owner, with the `expectedRegistrationId` contract and the 409 the admin route already defines. An
  omitted `contextRoot` derives and privatizes `apps/{clientId}`; a supplied one must already be a
  registered context and keeps its visibility. New `PodServiceClientsEndpoint`; the request and
  response types move out of `AdminPodsEndpoint` so both routes answer one shape. Depends on 1, 2.
- [ ] 5 — The same route lists registrations and deletes one, so revoking an app does not mean
  deleting the context its data lives in. Depends on 4.
- [ ] 6 — A budget on the new route, from `TokenBucketRateLimiter`, keyed the two tiers
  `PodTokenRateLimiter` uses. Minting a secret costs one bcrypt at cost 12. Depends on 4.
- [ ] 7 — `SempodsPodClient` speaks the route: register, list, unregister, with the route constants
  in `SempodsPodRoutes`. Depends on 4, 5.
- [ ] 8 — `ServiceClientAuth` — the caching `SempodsAuth` over `client_credentials` that every
  2-leg consumer writes by hand today. `SempodsAuth`'s own KDoc says this module supplies no
  such adapter; that sentence is about a multi-pod registry and has to say so afterwards.
- [ ] 9 — A loopback login in the client (RFC 8252): the two protocol calls on `SempodsClient`, and
  a small orchestrator that binds an ephemeral port, carries the PKCE verifier and hands back a
  `SempodsAuth`. It surrenders the authorize URL rather than opening a browser. Depends on 1, 2.
- [ ] 10 — One test walks the whole path: owner token, register, mint, write inside the sandbox,
  refused outside it, unregister, mint refused.

## Open decisions

- The two scope names — `contexts` and `service-clients` read as the surfaces they open, which is
  what `public-read` does not do. Settled by item 1 and hard to change afterwards: a scope name
  reaches consent dialogs and stored grants.
- Whether a caller-supplied `contextRoot` that is public should be demoted the way the admin route
  demotes a derived one. Demoting is safer for descendants; not demoting respects that the owner
  published that context on purpose. Item 4 has to answer it either way.
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
