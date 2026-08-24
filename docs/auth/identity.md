# Identity & Authentication

How a pod knows *who* is calling. This file covers identities, the
identity service that issues identity tokens, and how pods verify them.

For implementation details of the identity service itself (URI
namespaces, OIDC provider integrations, identity merge, federation),
see `../../sempods-auth/docs/identity-service.md`.

## Identities are external WebID URIs

Pods don't store person identity — they store **WebID URIs** in grants.
A WebID URI is a stable, dereferenceable identifier for a person or
agent.

Three optional layers, each adds capability without breaking the layer
below:

1. **Layer 0 — deterministic local URN.** Any pod can address persons
   by `urn:sempods:e:<sha256(normalize(email))>`. No server needed.
2. **Layer 1 — sempods-auth WebIDs.** When an `id.sempods.org`-style
   identity service is connected, those URNs become dereferenceable
   WebID URIs at `id.sempods.org/e/<hash>` (or `id.sempods.org/oidc/<hash>`
   when the OIDC subject has no email).
3. **Layer 2 — federation.** Multiple sempods-auth deployments can
   federate via `owl:sameAs` links between WebID documents. No central
   registry.

The pod owner is identified by a single WebID URI in `PodDbo.owner`.

## Identity tokens (issued by the id-server)

In the v0 deployment, persons authenticate via OIDC (Google, Apple, ...)
through `id.sempods.org`, which issues an `id_token`. It is **not** an
access token — it only says who someone is. A relying party exchanges a
code for it at the end of a sign-in and reads it once; a pod-scoped
access token is minted from what it said (see `oauth.md`).

Standard OIDC shape, with three sempods-specific points:

- **`sub`** is the canonical WebID URI.
- **`also_known_as`** lists every equivalent identity URI known for this
  person (e.g. the Layer-0 `urn:sempods:e:*` form, alternative WebIDs
  linked by identity merge). It is applied where a grant or ownership is
  *decided* — at consent — not on every later request; see the trust
  model below.
- **`aud` names the client it was issued to**, so a token minted for one
  relying party is refused by another.

There used to be a second, older token with no `aud` at all, handed out
by `GET /login` — valid at every pod that trusts the issuer, which is
what made it worth stealing. Both the endpoint and the token are gone.

Standards: OIDC Core 1.0 for the JWT, RS256 signing, JWKS publishing
under `/.well-known/jwks.json`.

### How the pod gets one

As an ordinary OpenID Connect relying party. The pod discovers the
id-server through `/.well-known/openid-configuration`, sends the
browser to its `/authorize` with PKCE, a `state` and a `nonce`, and
fetches the token from `/token` over a back channel using a verifier
that never travelled through the browser. The request the user was
making is parked server-side under that `state`, and the callback at
`{pod}/_system/auth/oidc/callback` resumes it.

The pod identifies itself as `did:web:<its host>` and registers
nothing: the id-server permits a redirect address only on the origin
the identifier names, which is what stands in for a client secret.

It used to redirect to `id.sempods.org/login?return_to=<its own URL>`
and have the token appended to that address — which the id-server
accepted from anyone, so any site could collect a visitor's identity.
The same flow now carries a single-use code instead, and the
`id_token` names the client it was issued to.

The consent screen carries no identity either. It used to hold the
token in a hidden form field; it now carries a single-use token for
that one screen, and submitting it also requires the session cookie —
see `oauth.md`.

## Pod trust model

Each pod is configured with the identity issuer it federates logins to
(`ID_BASE_URL`, plus `SEMPODS_AUTH_ISSUERS` for the hosted MCP
service). The `id_token` that comes back is validated by the relying
party — issuer, audience, nonce, expiry and signature together, against
the provider's JWKS.

**An identity token is not a pod credential.** It is consumed at the
login callback and never travels again. What the browser keeps is a
session cookie the pod signed itself, scoped to that pod and marked
`token_use=session` so nothing can present it as an access token; what
an app carries is a pod-issued access token. The pod recognises a
person from the session on a browser request, and from the token's
`sub` on an API request.

The session is per pod on purpose: pods are isolated tenants and share
a host on a path-scoped deployment, so a sign-in at one is not a
sign-in at another.

Ownership follows from it directly. `podDbo.owner` is compared against
the request's subject (`SempodsBaseEndpoint.resolvePodOwnerPrincipal`)
— not against a grant and not against a scope, so an owner has manage
authority over every context on their pod with nothing granted at all,
which is what lets a pod with no contexts get its first one. Grants for
everyone else are resolved server-side per request from the grant
store, keyed by that same subject.

Equivalent identity URIs (`also_known_as`) are applied when a grant is
*written*, at consent, not when it is read: a request carries one
identity URI. See `PodContextPermissionResolver.resolveFromGrants`.

## Anonymous identity for public reads

For public-read access without an active identity session, the pod mints
a synthetic, opaque, per-request subject:

```
urn:sempods:anon:<random-uuid>
```

These subjects are **not** stable across requests. They exist so that
the resource layer always has a `sub` for rate-limiting and audit logs;
they grant nothing beyond the pod's `public-read` scope. See
`oauth.md` for how this is requested and `authorization.md` for what
it can access.

## Identity phases

- **v0 — OIDC bridge (current).** Users authenticate via an upstream
  OIDC provider; the id-server derives the WebID URI and issues a
  sempods JWT. No client-side key management.
- **v1 — DPoP (planned, opt-in).** Users hold a private key in the
  browser; every request proves possession via a signed DPoP header.
  Removes the OIDC dependency and improves token-theft resistance.

DPoP is open work — see [`README.md`](README.md) ("Known limitations").

## Self-hosted deployments

Identity-service roles can run inside a single deployment alongside the
pod data plane (e.g., `pod.alice.org/auth/`, `pod.alice.org/id/`).
sempods has no hard dependency on `sempods.org` infrastructure. Federation
across deployments is opt-in via `owl:sameAs` in WebID documents.

## What lives elsewhere

- URI namespaces, OIDC provider integrations, identity merge,
  email-to-WebID flow, deterministic-hash rationale → see
  `../../sempods-auth/docs/identity-service.md`.
- Pod-issued access tokens, refresh tokens, OAuth flows → see
  `oauth.md`.
- Scope grammar, grants, enforcement → see `authorization.md`.
