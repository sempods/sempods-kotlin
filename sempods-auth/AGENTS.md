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
| `oidc/IdTokenVerifier.kt` | Shared `id_token` checks: JWKS signature, `iss`, `aud`, `exp` |
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

- `sempods-auth/docs/README.md` — module overview
- `sempods-auth/docs/identity-service.md` — identity layers, WebID registry, OIDC bridge, JWT format, linked identities

## Two roles, pointing opposite ways

Both legs are OIDC and it is easy to read one for the other:

- **OpenID Provider**, toward a pod — `/authorize`, `/token`, `/.well-known/openid-configuration`,
  in `api/provider/`. This service authenticates the person.
- **Relying party**, toward Google and Apple — `/login/oidc/{provider}/callback`, in `oidc/`.
  This service is the client.

`id.sempods.org/oidc/<hash>` is a third thing again — a person's identity document — which is why
no protocol endpoint sits under that prefix.

**All three callers are on the provider endpoints** — the pod server and the hosted MCP service's
two flows. `GET /login` is gone, and with it the `aud`-less identity token it handed out. What is
left under that path prefix is only the upstream callback, which cannot move: it is registered in
Apple's and Google's developer consoles. Removing it does not invalidate what it issued: this
service persists its signing keys, so that takes clearing the key rows — an operator step
against the `oauth.signingKeys` collection, not a release.

## Login providers

Each provider is registered only when its credentials are configured, so `/authorize` serves what
the deployment actually has: `server_error` to the client with none, a direct redirect with one, a
chooser with several. Which providers exist is this module's knowledge — adding or removing one
needs no change outside it.

Apple is the one with sharp edges, all of them invisible until production:

- The client secret is a **signed assertion**, minted per token exchange rather than stored.
- The callback is a **cross-site POST** (`response_mode=form_post`), which is why the route answers
  on `POST` as well as `GET`. It used to also dictate `SameSite=None` on a `return_to` cookie; that
  cookie went with `GET /login`.
- The user's **name arrives once**, in a `user` form field on the first authorization only — hence
  `handleCallback(code, callbackParams)`.
- Apple's portal **used to** verify the domain by fetching a file from it before accepting a Return
  URL, and `APPLE_DOMAIN_ASSOCIATION` still serves that file. It stopped asking: `id.sempods.org`
  was configured without one in August 2026. Leave the variable unset and the route 404s, which is
  what a deployment Apple never asks wants.

## Phase status

- **Phase 1 (WebID registry)** — implemented and deployed at `id.sempods.org`
- **Phase 2 (OIDC bridge + JWT issuance)** — implemented, live at `id.sempods.org`
