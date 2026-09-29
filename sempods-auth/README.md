# Identity service

[Auth overview](../docs/auth/README.md) · [Delegated pod access](../sempods-server/docs/auth/user-access.md)

`sempods-auth` is a standalone Ktor service for person identities. It provides WebID profiles and
an OpenID Connect provider. Its default HTTP port is **8091**.

For example, Alice opens an app connected to her pod. The pod sends her to this service to sign
in with Google or Apple. The service returns an authorization code to the pod. The pod redeems
that code for an `id_token` naming Alice's WebID, verifies it, then asks which contexts she wants
to share with the app. The pod issues the app's access token.

The service is an OpenID Provider toward pods and a relying party toward Google and Apple
([OIDC bridge](docs/identity-service.md#oidc-bridge)). It grants no access to pod data. A backend
using [Client Credentials](../sempods-server/docs/auth/service-clients.md) calls the pod's token
endpoint.

## Run or embed it

With the local MongoDB from the [quick start](../README.md#quick-start) running,
`ID_BASE_URL=http://localhost:8091 ./gradlew :sempods-auth:run` starts the service. Only configured
providers appear in the login flow; [identity-service.md](docs/identity-service.md#self-hosted-deployment)
lists the settings. The container image is `ghcr.io/haed/sempods-auth`, and the library
coordinate is `org.sempods:sempods-auth`.

[SempodsAuthMain](src/main/kotlin/org/sempods/auth/SempodsAuthMain.kt) starts the service.
[SempodsAuthModule](src/main/kotlin/org/sempods/auth/SempodsAuthModule.kt) wires its components.
Shared code comes from [`sempods-auth-core`](../sempods-auth-core/README.md).

## Read further

- [Identity service details](docs/identity-service.md): WebID derivation, provider flow, token claims and identity linking.
- [Pod trust model](../sempods-server/docs/auth/identity.md): how the pod validates and uses identity.
- [Provider HTTP tests](src/test/kotlin/org/sempods/auth/api/provider/): discovery and authorization/token behavior.
