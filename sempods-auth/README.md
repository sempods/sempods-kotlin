# Identity service

[Auth overview](../docs/auth/README.md) · [Delegated pod access](../sempods-server/docs/auth/user-access.md)

`sempods-auth` is a standalone Ktor service for person identities. It provides WebID profiles and
an OpenID Connect provider. Its default HTTP port is **8091**.

For example, Alice opens an app connected to her pod. The pod sends her to this service to sign
in with Google or Apple. The service returns an authorization code to the pod. The pod redeems
that code for an `id_token` naming Alice's WebID, verifies it, then asks which contexts she wants
to share with the app. The pod issues the app's access token.

## Two OIDC roles

| Direction | Role | Endpoints |
|---|---|---|
| Pod or hosted MCP → identity service | OpenID Provider | `/.well-known/openid-configuration`, `/authorize`, `/token` |
| Identity service → Google or Apple | Relying party | `/login/oidc/{provider}/callback` |

The service does not grant access to pod data. A backend using
[Client Credentials](../sempods-server/docs/auth/service-clients.md) calls the pod's token endpoint.

## Run or embed it

Configure providers in [SempodsAuthConfig](src/main/kotlin/org/sempods/auth/SempodsAuthConfig.kt).
Only configured providers appear in the login flow. The service uses its own MongoDB database;
[identity-service.md](docs/identity-service.md#self-hosted-deployment) lists deployment settings.

[SempodsAuthMain](src/main/kotlin/org/sempods/auth/SempodsAuthMain.kt) starts the service.
[SempodsAuthModule](src/main/kotlin/org/sempods/auth/SempodsAuthModule.kt) wires its components.
Shared code comes from [`sempods-auth-core`](../sempods-auth-core/README.md).

## Read further

- [Identity service details](docs/identity-service.md): WebID derivation, provider flow, token claims and identity linking.
- [Pod trust model](../sempods-server/docs/auth/identity.md): how the pod validates and uses identity.
- [Provider HTTP tests](src/test/kotlin/org/sempods/auth/api/provider/): discovery and authorization/token behavior.
