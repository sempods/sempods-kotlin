# Shared auth library

[Auth overview](../docs/auth/README.md) · [Identity service](../sempods-auth/README.md)

`sempods-auth-core` provides OAuth and OIDC components for the pod server, identity service and
hosted MCP service. It has no HTTP routes or executable server. An app calling a pod normally
uses [`sempods-client`](../sempods-client/README.md). The coordinate is
`org.sempods:sempods-auth-core`, versioned by the [platform](../README.md#using-it-as-a-library).

## What to use

| Need | Component |
|---|---|
| Generate and verify an S256 PKCE challenge | [Pkce](src/main/kotlin/org/sempods/auth/core/Pkce.kt) |
| Validate a client identifier and its redirect | [ClientRedirectPolicy](src/main/kotlin/org/sempods/auth/core/ClientRedirectPolicy.kt), [DidWeb](src/main/kotlin/org/sempods/auth/core/DidWeb.kt) |
| Redeem a one-time authorization code | [AuthorizationCodeStore](src/main/kotlin/org/sempods/auth/core/AuthorizationCodeStore.kt) |
| Rotate refresh tokens and detect reuse | [RefreshTokenStore](src/main/kotlin/org/sempods/auth/core/RefreshTokenStore.kt) |
| Sign and verify JWTs | [SigningKeys](src/main/kotlin/org/sempods/auth/core/SigningKeys.kt), [JwtVerifier](src/main/kotlin/org/sempods/auth/core/JwtVerifier.kt) |
| Sign in against an OIDC provider | [OidcRelyingParty](src/main/kotlin/org/sempods/auth/core/OidcRelyingParty.kt), [IdTokenVerifier](src/main/kotlin/org/sempods/auth/core/IdTokenVerifier.kt) |

For example, both the identity service and the pod redeem authorization codes. They use the same
store implementation, each with its own database. A code from the identity service cannot be
redeemed at the pod's token endpoint. The pod alone decides the resulting context grants.

Construct the components directly or install
[SempodsAuthCoreModule](src/main/kotlin/org/sempods/auth/core/SempodsAuthCoreModule.kt) in a Guice
application. Guice is optional; Mongo-backed stores require the caller's database. Each service
supplies configuration, persistence and transport. Exact contracts belong to the linked KDoc.

[Tests](src/test/kotlin/org/sempods/auth/core/) cover redirect policy, PKCE, token validation,
one-time redemption and refresh rotation. The services' HTTP tests cover their endpoint wiring.
