# Installing an app into a pod (Concept)

## Purpose

An app that works on a pod needs a credential, and something has to decide that it may have one.
Today that decision belongs to whoever runs the host: service-client registration sits on the admin
surface, and [`../auth/service-clients.md`](../auth/service-clients.md) states that as intent rather
than as an accident of where the route landed.

This document is about who else may make that decision. Not about how an app authenticates —
that is [`../auth/`](../auth/), and the protocol is ordinary OAuth with nothing sempods-specific at
the protocol level. It is about an asymmetry inside the permission model: a pod owner already
creates and deletes every context of their pod, and still cannot point a client at one. Whether
that closes is a question about authority, not about a route.

Sections are marked **IST** (implemented, verifiable in code) or **SOLL** (target state).

## Provisioning by the operator (IST)

`POST /_system/admin/pods/{pod}/service-clients/{clientId}` performs the pod-side setup for an app:
it ensures the app root context `<pod>/_system/contexts/apps/{clientId}` exists and is private,
registers the client with the single scope `<app-root>#manage`, and returns the minted secret
exactly once. The caller asserts what it already holds through `expectedRegistrationId`, which is
what makes the call idempotent without the server having to know a credential only the caller can
verify. The contract is in [`../auth/service-clients.md`](../auth/service-clients.md); the code is
`api/system/admin/pods/AdminPodsEndpoint`.

Authorization is the admin-authority seam — a host-level secret, no pod identity, no pod scopes.
That is also why this route creates the app root through `PodFacade` directly instead of over the
context management route: an admin bearer is neither the pod owner nor the holder of a covering
`#manage` scope, so `PodContextsEndpoint` would refuse it.

## Why the authority is split (IST)

Creating and deleting a pod cannot be expressed as a pod-scoped permission. At `createPod` the pod
does not exist, so there is no context for a `<context-iri>#permission` scope to name. That is the
line the admin surface exists for, and it is stated once in
[`modularity.md`](modularity.md) §"The authority boundary outlived the types".

Registering an app is not on that side of the line. It names a pod that exists and a context that
exists or is about to, and both are things a pod-scoped credential can already talk about. The route
sits on the admin surface because that is where the first caller for it was, not because pod
authority is unable to express it.

## What an owner's token already does (IST)

An owner who authorizes an app through Authorization Code already leaves it with a durable
credential: the pod issues a rotating refresh token on that path, valid for 90 days from each
rotation, with reuse detection that revokes the whole family. A tool that runs at least that often
never needs anything else to keep working.

What such a token does not carry is a boundary. Its `sub` is the owner's WebID, and
`SempodsBaseEndpoint.resolvePodOwnerPrincipal` reads nothing else, so the app holds the owner's
catch-all over context management: it may create and delete any context of the pod, whatever the
owner selected at consent. Data reads and writes stay inside the grants the owner delegated, because
those resolve per request from the grant store — but the context registry does not.

The two credential shapes therefore answer different questions, and the difference is the reason an
owner-installed service client is worth having at all:

| | The owner's refresh token | A service client |
|---|---|---|
| Acquisition | 3-leg once, through a browser | registration, then 2-leg with no browser |
| Lifetime | 90 days rolling; dies when unused | until the registration is removed |
| Data access | the grants the owner delegated at consent | `<root>#manage`, resolved per request |
| Context management | the owner's catch-all, over the whole pod | below its root only |
| Revocation | revoke the family; live tokens ≤1 h | delete the registration; live tokens ≤10 min |

## Self-service by the owner (SOLL)

An owner-authenticated request may register a service client on their own pod, over the pod surface
rather than the admin surface. The scope it registers is `<root>#manage` for a root the owner may
already manage — derived as `apps/<clientId>` when the caller names none, or a context of their own
when they do.

This introduces no new scope type and no parallel policy language. `PodScopeValidator` already
refuses a `manage` root at or above `<pod>/_system/contexts`, on registration and again when
sanitizing every token, so no root reachable this way can become a pod-wide wildcard. The sandbox
promise is unchanged in wording and in effect: a service client is confined to the subtree its
`manage` root names.

The host-level acts stay where they are. Creating and deleting pods is not expressible here for the
reason in §"Why the authority is split", and nothing about owner self-service moves that line.

## Delegated authority is granted authority (SOLL)

Owner catch-all is not a property of a person; it reaches an endpoint as a token issued to some app.
Requiring only `sub == pod.owner` therefore hands every app the owner ever signs into the authority
to manage the whole pod, including the authority to remove another app's sandbox root — which
cascades into revoking that app.

An app holds owner authority only when the owner granted it: a coarse feature scope, requested at
`/authorize`, shown in the consent dialog, persisted as a grant and revocable where every other
grant is. Registering a service client and managing contexts are separate scopes, because a
registered secret outlives the token that created it and a context write does not.

A *grant* would be the wrong condition here, for a reason that is easy to rediscover the hard way: a
context that does not exist yet cannot be covered by a grant on it, and the first context of a fresh
pod is exactly that case. The scope says what the owner decided; the grant store says what exists.

## Delegation without a type segment (SOLL)

A first path segment from `ContextPathRules.DELEGATION_TYPES` marks a subtree handed to somebody
else — `apps/<id>` today, `users/` reserved. Once an owner may point a client at a context they
named themselves, that segment stops being the only mark of a delegation, and the registration in
`oauth.serviceClients` becomes the record that always holds.

The convention survives as the default and as the shape the control plane produces. What changes is
what may be inferred from its absence: an untyped context is one the owner named, not necessarily
one nobody else may write.

## Not in scope

- **Creating and deleting pods.** Host-level, for the reason above.
- **An operator console or an owner console.** This is about the authority a route carries, not
  about a surface that calls it. Which client module such a surface would take is
  [`../pod-client.md`](../pod-client.md) §"Target deployments".
- **A personal access token.** A long-lived credential minted by a person for themselves has no
  standard behind it and no way to be withdrawn here: pod access tokens are self-contained JWTs
  validated against `iss`, and the pod offers neither token revocation nor introspection. Adding one
  is adding a third credential class *and* the machinery to take it back.

## Related

- [`../auth/service-clients.md`](../auth/service-clients.md) — the 2-leg profile: registration,
  sandbox, token exchange, auditing.
- [`../auth/authorization.md`](../auth/authorization.md) — scope versus grant, `manage` semantics,
  where context IRIs live.
- [`../auth/oauth.md`](../auth/oauth.md) — the user-facing flows a 3-leg acquisition uses.
- [`modularity.md`](modularity.md) §"The authority boundary outlived the types" — why the split is
  by credential rather than by topic.
- [`../roadmaps/owner-app-installation.md`](../roadmaps/owner-app-installation.md) — the breakdown
  of the SOLL sections above, while it is being implemented.
