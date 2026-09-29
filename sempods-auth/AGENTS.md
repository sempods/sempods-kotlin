# AGENTS.md — sempods-auth

Scope: applies to `sempods-auth/**`.

## What this module is

`sempods-auth` is a standalone Kotlin/Ktor service that implements the external person-identity layer for sempods:

- `id.sempods.org` — WebID registry: issues dereferenceable RDF identity documents
- `id.sempods.org/authorize` + `/token` — OpenID Provider: Google/Apple login → `id_token`

It runs on port **8091**, deployed as a separate Docker container (`ghcr.io/haed/sempods-auth`).

## Architecture constraints

- **No application-framework dependency** — the repo's session-based framework is for prototypes; not appropriate for a production auth service
- **Ktor** for HTTP (lambda-based routing, not Jersey/Guice endpoints)
- **Guice** for DI (service objects only — DAOs, config, MongoDB)
- **MongoDB** (raw driver, no Morphia) — database `sempods-auth` in MongoDB Atlas. Its four
  collection names are declared in `SempodsAuthCollections` and pinned by
  `SempodsAuthCollectionsTest`; the `oauth.*` ones are spelled exactly as the pod server and
  the hosted MCP service spell them
- **nimbus-jose-jwt** for JWT + JWKS (already in project)
- Routing is configured as Ktor extension functions, not injectable objects

## Key files

| File | Role |
|---|---|
| `SempodsAuthMain.kt` | Entry point — starts Ktor server |
| `SempodsAuthConfig.kt` | Config from env vars |
| `SempodsAuthModule.kt` | Guice module — wires DAOs and services |
| `api/provider/OpenIdProviderEndpoint.kt` | `/authorize`, `/token` — the OpenID Provider role |
| `api/provider/OpenIdConfiguration.kt` | The discovery document, and the endpoint paths it names |
| `api/login/ProviderCallbackEndpoint.kt` | `GET|POST /login/oidc/{provider}/callback` — where Google and Apple answer |
| `api/login/LoginPage.kt` | The provider chooser `/authorize` shows when more than one is configured |
| `oidc/OidcProviderClient.kt` | What a provider has to implement — add one, no routing changes |
| `api/webid/WebIdEndpoint.kt` | `GET /e/{hash}` and `GET /oidc/{hash}` with content negotiation |
| `persist/WebIdProfileDao.kt` | MongoDB DAO for `webIdProfiles` collection |
| `persist/WebIdProfile.kt` | Document model |
| `webid/WebIdDocument.kt` | Turtle/JSON-LD/HTML serialization |

## URI namespaces

```
id.sempods.org/e/<sha256(normalize(email))>            ← EMAIL namespace
id.sempods.org/oidc/<sha256(normalize(iss+":"+sub))>   ← OIDC namespace (fallback)
```

SHA-256 without HMAC — stateless, decentralized; any pod can derive URIs independently.

## Documentation

- [Module guide](README.md) — role, login example and deployment entry points
- [Identity service details](docs/identity-service.md) — WebID registry, OIDC bridge, token format,
  URN twins, identity merge, deployment settings and current limits

`sempods-auth/docs/` follows the repository's
[documentation types and placement rules](../docs/agents/documentation-strategy.md#the-three-document-types).
Public plans follow [issue planning](../docs/agents/documentation-strategy.md#issue-planning).
This module qualifies for a `docs/vision.md` of its own: it is deployable and usable on its own,
as an identity provider, which is an audience the repository vision does not address. A module
vision refines the repository's; it does not contradict it. None is written yet.

## Two roles, pointing opposite ways

Both legs are OIDC; do not read one for the other. The OpenID Provider toward pods is in
`api/provider/`; the relying party toward Google and Apple is in `api/login/` and `oidc/`. Do not
move the callback path or put a protocol endpoint under `/oidc/`: the
[OIDC bridge](docs/identity-service.md#oidc-bridge) says why. Tokens from the removed `GET /login`
are under [current limits](docs/identity-service.md#current-limits).

## Login providers

Which providers exist is this module's knowledge — adding or removing one needs no change outside
it. Provider behaviour, including Apple's, is in
[self-hosted deployment](docs/identity-service.md#self-hosted-deployment).
