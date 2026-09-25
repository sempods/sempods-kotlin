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
- **`https://schema.sempods.org/claims/equivalent-identities`** lists the
  person's other WebIDs, such as one an identity merge linked
  (`SPS-OIDC-005`). HTTP and HTTPS WebIDs only; the pod derives the
  Layer-0 `urn:sempods:e:*` twin itself. It is applied where a grant or
  ownership is *decided* — at consent — not on every later request; see
  the trust model below.
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
sign-in at another, and a [sign-out](oauth.md#signing-out) at one is
not a sign-out at another.

Ownership follows from it directly. `podDbo.owner` is compared against
the person the session names — not against a grant — so the owner may
grant every context on their pod with nothing granted at all, and can
create contexts in the consent dialog. A bearer is an app: its `sub`
alone makes it no owner. The owner's authority reaches a program only
through a scope approved for one operation, such as
[`contexts:manage`](oauth.md#managing-contexts). Grants are resolved
server-side per request from the grant store, keyed by the subject.

### Equivalent identities

The pod trusts its identity issuer to say which WebIDs name the same
person (`SPS-OIDC-018`), because it derives its people's WebIDs under
that same `ID_BASE_URL`. The hosted MCP service trusts its issuer for
login alone and uses no equivalent identity. The setting is
`OidcRelyingParty.trustsEquivalentIdentities`.

The relying party reads the claim once, at the login callback, after the
token has validated:

| Claim | Outcome |
|---|---|
| absent, or `[]` | no other identity in this sign-in |
| `["https://id.sempods.org/e/<hash>", …]` | a set: order, duplicates and `sub` itself change nothing (`SPS-OIDC-017`) |
| `null`, a string, an object, or an array with any other entry — `""`, `/alice`, `urn:example:alice` | the whole sign-in is refused (`SPS-OIDC-016`) |

The session then carries these WebIDs plus the URN twin of each one and
of `sub` (`PodIdentityProvider.aliasesOf`). Nothing reads OIDC's
registered `also_known_as` claim, a human pseudonym, as an identity.

Equivalent identity URIs are applied when a grant is *written*, at
consent, not when it is read: a request carries one identity URI. See
`PodContextPermissionResolver.resolveFromGrants`. A later sign-in whose
token names fewer of them revokes nothing: an app's grants are checked
against the URIs its consent recorded as well (`SPS-OIDC-017`).

## Anonymous identity for public reads

For public-read access without an active identity session, the pod mints
a synthetic, opaque, per-request subject:

```
urn:sempods:anon:<random-uuid>
```

These subjects are **not** stable across requests. They exist so that
the resource layer always has a `sub` for rate-limiting and audit logs;
they grant nothing beyond the pod's `public-read` scope. See
`oauth.md` for how this is requested and sempods-spec `spec/core/grants.md` for what
it can access.

## Current authentication boundary

Users authenticate through the OIDC provider flow. Pod access tokens are bearer tokens;
this implementation does not bind them to a client-held key.
[DPoP work](https://github.com/sempods/sempods-kotlin/issues/112) is tracked separately.
Proof of possession would complement authentication; it does not
by itself replace the identity provider.

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
- Scope grammar, grants, enforcement → see sempods-spec `spec/core/grants.md`.
