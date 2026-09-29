# Pod identity and trust

[Auth overview](../../../docs/auth/README.md) · [Delegated access](user-access.md) · [Identity service](../../../sempods-auth/README.md)

The pod identifies people by WebID URIs. For example, Alice signs in through an identity provider;
the pod verifies her identity, then asks which contexts her app may use. The identity provider
does not decide those grants.

## How the pod signs someone in

1. The pod opens its configured provider's authorization endpoint with PKCE, `state` and `nonce`.
2. The provider returns a code to `{podUrl}/_system/auth/oidc/callback`.
3. The pod exchanges it for an `id_token` and verifies signature, issuer, audience, nonce and expiry.
4. The pod creates its own browser session and resumes the app's authorization request.

The pod presents `did:web:<its host>` to the identity service. That service checks the callback
against the identifier's origin and optional path.
[Identity-service details](../../../sempods-auth/docs/identity-service.md) describe the provider side.

## Pod trust model

Configure the pod's identity provider with `ID_BASE_URL`. It can be self-hosted; using
`id.sempods.org` is optional. The pod consumes the provider's identity token only during login.

| Credential | Used for |
|---|---|
| Provider `id_token` | Proving who signed in to the pod |
| Pod session cookie | Continuing browser authorization on that pod |
| Pod access token | Calling that pod's API as an authorized app or service |

An identity-service token cannot be used as a pod access token. Sessions are isolated per pod,
even when two pods share one host. [Sign-out](connections.md#signing-out) also applies per pod.

The signed-in owner can approve all contexts. An app does not inherit owner authority just
because its token names the owner: it needs grants or an explicitly approved capability such as
[`contexts:manage`](oauth.md#managing-contexts).

### Equivalent identities

A configured issuer can assert that several WebIDs name the same person. For example, a pod
owned under Alice's email-derived WebID can recognize her provider-derived WebID after the issuer
has linked them. The pod applies these verified identities during consent; public `owl:sameAs`
statements alone establish no login trust.

The equivalent-identities claim must contain HTTP(S) WebIDs. An absent or empty claim supplies
no aliases; malformed values reject the sign-in. Later login with fewer aliases does not revoke
grants written by earlier consent. The hosted MCP service uses its issuer for login only and
does not use equivalent identities.

[OidcRelyingParty](../../../sempods-auth-core/src/main/kotlin/org/sempods/auth/core/OidcRelyingParty.kt)
owns claim validation and the trust switch;
[PodIdentityProvider](../../src/main/kotlin/org/sempods/auth/PodIdentityProvider.kt) owns the pod's
identity mapping. See `SPS-OIDC-016`, `SPS-OIDC-017` and `SPS-OIDC-018` for the normative contracts.

## Anonymous identity for public reads

Ordinary public reads need no OAuth token. An app using
[anonymous public-read authorization](oauth.md#public-read-flow) gets a synthetic subject for
that token's lifetime. It identifies no person and grants access only to public contexts.

## Self-hosted deployments

Set `ID_BASE_URL` to the trusted issuer and configure its relying clients accordingly.
Bearer tokens are currently used throughout; DPoP is
[proposed](https://github.com/sempods/sempods-kotlin/issues/112).
