# Authentication and authorization

[Documentation](../README.md) · [JVM client](../../sempods-client/README.md)

A pod checks who calls and which contexts that caller may use. A **grant** is a permission such as
`https://pods.example/alice/_system/contexts/notes#write`. The pod stores it and resolves it on
each request. A token identifies the caller; its OAuth **scopes** carry coarse capabilities such
as `public-read`. Context grants never travel inside the token.

For example, Alice lets Notes Sync write her notes context. The service can read and write that
context, but cannot read her contacts. Removing the grant stops further access on the next request,
even if the service still holds an unexpired token. It cannot erase copies already downloaded.

## Choose a flow

| You are building | Flow | Start here |
|---|---|---|
| A backend or scheduled worker acting as a service | **Client Credentials** | [Service access](../../sempods-server/docs/auth/service-clients.md) |
| An app acting for a person | **Authorization Code + PKCE** | [Delegated access](../../sempods-server/docs/auth/user-access.md) |
| A reader of public data | Anonymous HTTP requests | [Client quick start](../../sempods-client/README.md#read-public-data) |

A service acts as itself, even when its setup needs browser approval from the owner.
A delegated app acts for a person. Public client registration (`dyn:*`) alone never
provides service credentials.

Pod OAuth calls use endpoints relative to the **full pod URL**, such as
`https://pods.example/alice/_system/auth/token`. [Host provisioning](../../sempods-server/docs/host-provisioning.md)
uses a separate address and credential.

## Which module does what?

| Module | Responsibility |
|---|---|
| [`sempods-server`](../../sempods-server/docs/auth/README.md) | The pod's authorization server: consent, grants, service registrations, access tokens and enforcement |
| [`sempods-auth`](../../sempods-auth/README.md) | Person identity: Google or Apple sign-in, WebID profiles and OIDC identity tokens |
| [`sempods-auth-core`](../../sempods-auth-core/README.md) | Shared OAuth/OIDC building blocks used by the services |
| [`sempods-client`](../../sempods-client/README.md) | App-side HTTP calls, PKCE, registration and token exchange |
| [`sempods-mcp`](../concepts/hosted-mcp.md) | Hosted MCP connections to multiple pods |

An identity token from `sempods-auth` is consumed during login. Apps call a pod with an **access
token issued by that pod**. A service using Client Credentials does not need the identity service
for its token exchange.

## Read further

- [Pod identity and trust](../../sempods-server/docs/auth/identity.md): how a verified WebID becomes a pod session.
- [OAuth reference](../../sempods-server/docs/auth/oauth.md): client identifiers, consent, refresh and feature scopes.
- [OAuth errors](oauth-errors.md): recovery for the errors linked from browser redirects.
- [Specification](https://github.com/sempods/sempods-spec): normative
  [auth](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md) and
  [grant](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md) contracts.

These pages describe the current implementation. Self-registered `svc:*` clients are an
experimental 0.2 extension; the [service guide](../../sempods-server/docs/auth/service-clients.md)
links the proposed portable profiles for registration and consent.
