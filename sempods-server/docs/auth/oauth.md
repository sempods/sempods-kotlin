# OAuth reference

[Pod authentication](README.md) · [Delegated walkthrough](user-access.md) · [Service walkthrough](service-clients.md)

Use this page for the pod's client identities, request rules and consent behavior. Start with the
walkthroughs for complete examples. The [specification](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md)
owns the protocol; this page describes the current implementation and its extensions.

## Endpoints

All paths are relative to the **full pod URL**, such as `https://pods.example/alice`: the token
endpoint is `https://pods.example/alice/_system/auth/token`. Use advertised endpoint URLs from
discovery where available.

| Method and path | Purpose |
|---|---|
| `GET /_system/auth/authorize` | Start Authorization Code authorization |
| `POST /_system/auth/authorize/consent` | Submit the pod's consent form |
| `POST /_system/auth/token` | Exchange a code, refresh token or service credential |
| `GET /_system/auth/jwks.json` | Read the pod's public signing keys |
| `POST /_system/auth/register` | Register a public client or service |
| `GET /_system/auth/service-consent` | Let the owner decide a service's context grants |
| `POST /_system/auth/service-consent` | Submit the service consent form |
| `GET /_system/auth/oidc/callback` | Receive the identity provider's login response |
| `/_system/auth/service-clients` | [List and manage service registrations](service-clients.md#managing-service-clients) |
| `GET /.well-known/oauth-protected-resource` | Discover the resource and its authorization server |
| `GET /.well-known/oauth-authorization-server` | Discover authorization endpoints and supported capabilities |

Implicit and password grants are unsupported. Browser clients use Authorization Code with
S256 PKCE; services use Client Credentials.

## Client identity: `did:web:*`, `dyn:*` and `svc:*`

| Client | Setup | Authentication |
|---|---|---|
| `did:web:*` | Use a stable application origin; no registration | Authorization Code; PKCE strongly recommended |
| `dyn:*` | Public Dynamic Client Registration (DCR) | Authorization Code; PKCE required |
| `svc:*` | Service registration, followed by owner consent | Client Credentials with `client_secret_basic` |

Public DCR never returns a service secret. Service IDs cannot use `/authorize`.
[Host provisioning](../host-provisioning.md) is a separate setup path.

### Redirect rules

Redirects must be absolute, have no fragment, and contain no `code`, `response` or `state` query
parameter. HTTPS is required except on loopback. The client-specific restrictions below also apply.
Invalid redirects receive a direct error; the pod never redirects an error to an unverified address.

Display URLs (`client_uri`, `logo_uri`, `tos_uri`, `policy_uri`) must also be absolute HTTPS URLs,
with HTTP permitted on loopback. Exact validation is owned by
[RedirectUri](../../../sempods-auth-core/src/main/kotlin/org/sempods/auth/core/RedirectUri.kt) and
[ClientMetadataUri](../../../sempods-auth-core/src/main/kotlin/org/sempods/auth/core/ClientMetadataUri.kt).

### `did:web:*` — origin-bound apps

For example, `did:web:notes.example` permits a callback on `https://notes.example` with a matching
port. `did:web:notes.example:app` also restricts callbacks to `/app` and its descendants:
`/app/callback` matches, `/app-other/callback` does not. Dot segments are refused.

The identifier is checked locally; the pod fetches no DID document. Loopback identifiers and
redirect exceptions for these clients require development configuration. See
[DidWeb](../../../sempods-auth-core/src/main/kotlin/org/sempods/auth/core/DidWeb.kt) for the contract.

### `dyn:*` — dynamically registered apps

Desktop and MCP clients register their callback at `/_system/auth/register`. Loopback ports may
vary; other callbacks remain port-strict. Repeat registrations with the same
[fingerprint](../../../docs/mcp/authentication.md#dcr-fingerprint) reuse the client ID.

Interactive authorization always shows consent, with existing grants preselected. A
[silent request](connections.md#the-prompt-parameter) cannot reuse that consent. Token refresh
stays silent.

### `svc:*` — registered services

The pod assigns these IDs during [registration](#registering-a-service-client). The resulting
service uses [Client Credentials](service-clients.md#token-exchange), with no browser login.

## Authorize flow (overview)

The [Delegated walkthrough](user-access.md) shows the client calls. An authorization request sends
`response_type=code`, `client_id`, `redirect_uri`, a fresh `state`, and S256 PKCE parameters.
The pod signs the person in if needed, then reuses or asks for consent according to
[`prompt`](connections.md#the-prompt-parameter).

On approval, the callback carries `code` and the supplied `state`. On refusal, it carries `error`,
`error_description` and `state`; an `error_uri` may be configured. Check the callback before
redeeming the code. An omitted or empty `state` is not returned.

The consent form requires the pod session cookie and a single-use token bound to that screen's
client, callback, PKCE parameters and offered rows. For ordinary delegated access:

| Submission | Result |
|---|---|
| Selected contexts | Replace the app's explicit grants for the signed-in WebID with that selection; redirect with `code`. |
| Nothing selected, or "Remove access" | Disconnect the app and remove its grants and refresh tokens; `access_denied`. |
| Cancel, or "Sign out everywhere" | Leave grants unchanged; `access_denied`. |
| A row or privileged scope the screen did not offer | `invalid_scope`; nothing is written. |
| A context to create that is refused | `invalid_request`; nothing is written. |
| No selected row the person can still delegate | `consent_required`; contexts created stay private, without grants. |
| No session, a spent, foreign or outdated form token, a form for another request, or an unknown client or redirect | A `400`, `401` or `403` page, with no redirect. |

[OAuth errors](../../../docs/auth/oauth-errors.md) lists each `error_description` and its recovery.

Existing grants are preselected. A `#manage` grant still reaches descendants even when a child has
no explicit grant of its own. Grants under linked identities survive replacement under the current
WebID; removing the app's access clears those too. Context creation is available to the owner and
creates private contexts. [Operational limits](operations.md#consent-and-grant-updates) cover
concurrent changes and rollout.

Ordinary context access is selected in the consent UI. Context IRIs in this request's `scope`
grant no access. Feature scopes select these other capabilities:

| Scope | Effect |
|---|---|
| `public-read` | [Public-context access](#public-read-flow) |
| `offline_access` | Preselect the [durable connection option](connections.md#offline_access) |
| `service-clients:manage` | Register services and decide their context access |
| `contexts:manage` | Create and delete contexts as the owner, with no data-read permission |

## Token exchange

Send a form body to `/_system/auth/token`:

| Grant | Form fields |
|---|---|
| Authorization Code | `grant_type=authorization_code`, `code`, `redirect_uri`, `client_id`, `code_verifier` |
| Refresh | `grant_type=refresh_token`, `refresh_token`, `client_id`, optional feature `scope` subset |
| Client Credentials | `grant_type=client_credentials`; authenticate with [HTTP Basic](service-clients.md#token-exchange) |

Delegated access tokens last at most one hour, capped by the remaining connection lifetime. Their
subject is the person's WebID; the issuer is the pod URL. Context grants are resolved on every
request and never appear in the token. Refresh down-scoping applies only to feature scopes.
Pod tokens are RS256-signed; public keys are at `/_system/auth/jwks.json`.
[PodTokenIssuer](../../src/main/kotlin/org/sempods/pods/oauth/PodTokenIssuer.kt) owns the claim contract.

## Public-read flow

Ordinary public resource reads need no token. For a client that needs a bearer, `scope=public-read`
can issue one limited to public contexts. For a signed-in app with context grants, `public-read`
adds the public contexts to those grants (`SPS-GRANT-020`).

An interactive public-read request signs the person in if needed and can show consent with
public-read preselected; `prompt=consent` requests that screen. The token's subject is the
person's WebID.

Anonymous authorization requires `prompt=none`, only `public-read`, and no valid pod session.
It also works for a `dyn:*` client, and its token has a new synthetic subject. A failed provider
login is returned as an error and does not resume as anonymous authorization. With no public
contexts, the result is `consent_required`.

[PodAuthorizeFlow](../../src/main/kotlin/org/sempods/pods/oauth/flows/PodAuthorizeFlow.kt) owns the
validation order and authorization decisions.

## Registering a service client

Send this body to `POST /_system/auth/register` without a bearer for self-registration:

<!-- doc-example: illustrative; metadata checked against PodClientRegistration and ServiceConsentHttpTest -->
```json
{
  "client_name": "Notes Sync",
  "grant_types": ["client_credentials"],
  "token_endpoint_auth_method": "client_secret_basic",
  "redirect_uris": ["http://127.0.0.1/callback"]
}
```

The response carries a `svc:` ID, a one-time secret and `activation_expires_at` in epoch seconds.
The registration has no grants and is removed after 24 hours unless the owner activates it. With a
valid `service-clients:manage` bearer, registration is active immediately and has no deadline; it
still starts without grants. Another bearer is refused. The [service walkthrough](service-clients.md#registration-and-consent)
covers activation, consent callbacks and checking access.

| Member | Handling |
|---|---|
| `grant_types`, `token_endpoint_auth_method` | Exactly `["client_credentials"]` and `client_secret_basic`; another secret-holding shape is `invalid_client_metadata` |
| `client_name` | Required; `invalid_client_metadata` without it |
| `redirect_uris` | Optional; omit it for a headless service. Each follows the [redirect rules](#redirect-rules), else `invalid_redirect_uri` |
| A non-empty `scope`, `jwks`, `jwks_uri` or `response_types` | `invalid_client_metadata` |
| Any other member, display metadata included | Neither stored nor echoed |

The [registration contract](../../src/main/kotlin/org/sempods/pods/oauth/flows/PodClientRegistration.kt)
owns validation. On a lost response, a retry creates another registration; a provisional one
expires, and an active one needs [removal](service-clients.md#managing-service-clients).
This profile is experimental; the [service guide](service-clients.md#consent) links the proposed
portable profiles.

## Managing service clients

Request `service-clients:manage` through owner consent. The bearer lasts one hour and can register
services, replace their grants, and manage their credentials. It resolves no context permissions
itself. **It can create lasting data access:** the tool can obtain a service secret, grant contexts
and use that service after its own authority expires. The consent asks for that authority explicitly.

Request one privileged scope at a time; combining it with ordinary access scopes is refused.
`offline_access` is ignored. Approval is required each time; `prompt=none` cannot obtain it.
Declining this screen leaves existing access unchanged. Disconnecting the management app revokes
its authority. Each management call checks the current pod owner.

An authority stored under consent version 1 may only narrow the grants of an active `svc:`
registration. Registration, and a replacement that widens grants or targets a provisional or
operator-provisioned registration, return `403 insufficient_scope`; request fresh owner consent.
The [management API](service-clients.md#managing-service-clients) and
[authority contract](../../src/main/kotlin/org/sempods/pods/oauth/flows/PodServiceClientManagement.kt)
describe operations and version checks.

## Managing contexts

Request `contexts:manage` through owner consent to create or delete any context using
`PUT` or `DELETE /_system/contexts/{path}`. It follows the same authority rules as service management.
An ordinary app, including the owner's app, can only manage contexts covered by its `#manage` grants.
For example, authority over `apps/notes` does not authorize deletion of `contacts`.

This privileged scope reads no data, but deleting a context deletes its data. The catalogue
reports management-only permission, unlike a normal `#manage` grant; see
[specification deviations](#specification-deviations).

## Specification deviations

| Behavior | Deviates from | Tracked in |
|---|---|---|
| [`contexts:manage`](#managing-contexts) reports management-only permission in the catalogue | `SPS-CTX-034`, `SPS-GRANT-009` | [sempods-spec#114](https://github.com/sempods/sempods-spec/issues/114) |
| Pod access tokens carry no `aud` claim ([sharp edge](operations.md#sharp-edges)) | `SPS-MCP-038` | — |

## What lives elsewhere

- [Identity and trust](identity.md): WebIDs and pod login.
- [User connections](connections.md): lifetime, refresh and sign-out.
- [Operations](operations.md): rate limits, discovery, sharp edges, audit retention and maintenance.

<!-- doc-examples: checked -->
