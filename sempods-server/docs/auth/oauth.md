# OAuth reference

[Pod authentication](README.md) · [Delegated walkthrough](user-access.md) · [Service walkthrough](service-clients.md)

Use this page for the pod's client identities, request rules and consent behavior. Start with the
walkthroughs for complete examples. The [specification](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md)
owns the protocol; this page describes the current implementation and its extensions.

## Endpoints

All paths are relative to the **full pod URL**, such as `https://pods.example/alice`.
Use advertised endpoint URLs from discovery where available.

| Method and path | Purpose |
|---|---|
| `GET /_system/auth/authorize` | Start Authorization Code authorization |
| `POST /_system/auth/authorize/consent` | Submit the pod's consent form |
| `POST /_system/auth/token` | Exchange a code, refresh token or service credential |
| `GET /_system/auth/jwks.json` | Read the pod's public signing keys |
| `POST /_system/auth/register` | Register a public client or service |
| `GET /_system/auth/service-consent` | Let the owner decide a registered service's context grants |
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
[Host provisioning](../host-provisioning.md) is a separate deployment-specific setup option.

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

Interactive authorization always shows consent, with existing grants preselected. A silent
request (`prompt=none`) cannot reuse user consent: it returns `consent_required` with a session
or `login_required` without one. Anonymous
[public-read](#public-read-flow) is the separate exception. Token refresh itself remains silent.

### `svc:*` — registered services

The pod assigns these IDs during [registration](#registering-a-service-client). The resulting
service uses [Client Credentials](service-clients.md#token-exchange), with no browser login.

## Authorize flow (overview)

The [Delegated walkthrough](user-access.md) shows the client calls. An authorization request sends
`response_type=code`, `client_id`, `redirect_uri`, a fresh `state`, and S256 PKCE parameters.
The pod signs the person in if needed, then reuses or asks for consent according to
[`prompt`](#the-prompt-parameter).

On approval, the callback carries `code` and the supplied `state`. On refusal, it carries `error`,
`error_description` and `state`; an `error_uri` may be configured. Check the callback before
redeeming the code. An omitted or empty `state` is not returned.

The consent form requires the pod session cookie and a single-use token bound to that screen's
client, callback, PKCE parameters and offered rows. Altered requests or unoffered rows are refused.
For ordinary delegated access:

| Action | Result |
|---|---|
| Confirm selected contexts | Replace the app's explicit grants for the signed-in WebID with that selection. |
| Confirm nothing | Disconnect the app and remove its grants and refresh tokens. |
| Cancel | Leave access unchanged. |

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

### Rate limit

See [OAuth operations](operations.md#rate-limit).

### `offline_access`

See [connection lifetimes](connections.md#offline_access).

### Refresh token rotation

See [rotation and reconnects](connections.md#refresh-token-rotation).

## The `prompt` parameter

See [interactive and silent authorization](connections.md#the-prompt-parameter).

## Signing out

See [signing out of a pod](connections.md#signing-out).

## Public-read flow

Ordinary public resource reads need no token. For a client that needs a bearer, `scope=public-read`
can issue one limited to public contexts. A signed-in user keeps their WebID as subject; the
anonymous variant uses a new synthetic subject and has no refresh token.

Anonymous authorization requires `prompt=none`, only `public-read`, and no valid pod session.
This shortcut also works for a `dyn:*` client. A failed provider login is returned as an error;
it does not resume as anonymous authorization. With no public contexts, the result is
`consent_required`. An interactive public-read request signs the person in if needed and can
show consent with public-read preselected. `prompt=consent` explicitly requests that screen.

For a signed-in app with context grants, `public-read` adds public contexts to those grants
(`SPS-GRANT-020`). [PodAuthorizeFlow](../../src/main/kotlin/org/sempods/pods/oauth/flows/PodAuthorizeFlow.kt)
owns the validation order and authorization decisions.

## Registering a service client

Send this body to `POST /_system/auth/register` without a bearer for self-registration.
`redirect_uris` is optional; omit it for a headless service.

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
The registration has no grants and expires unless the owner activates it. With a valid
`service-clients:manage` bearer, registration is active immediately and has no deadline; it still
starts without grants. Another bearer is refused. The [service walkthrough](service-clients.md#registration-and-consent)
covers activation, consent callbacks and checking access.

`scope`, `jwks`, `jwks_uri` and non-empty `response_types` are refused for this profile.
Display metadata beyond the required `client_name` is ignored. The
[registration contract](../../src/main/kotlin/org/sempods/pods/oauth/flows/PodClientRegistration.kt)
owns validation. On a lost response, a retry creates another registration; a provisional one
expires automatically, while an active one needs explicit removal.

This experimental profile deviates from `SPS-AUTH-008`, `SPS-AUTH-011` and `SPS-AUTH-012`;
[sempods-spec#122](https://github.com/sempods/sempods-spec/issues/122) tracks the proposed profile.
Service consent assigns grants after registration, a deviation from `SPS-AUTH-013` tracked in
[sempods-spec#123](https://github.com/sempods/sempods-spec/issues/123).

## Managing service clients

Request `service-clients:manage` through owner consent. The bearer lasts one hour, has no refresh
token, and can register services, replace their grants, and manage their credentials. It resolves
no context permissions itself, but can obtain service credentials and assign them lasting data
access. The consent explicitly asks for that authority.

Request one privileged scope at a time; combining it with ordinary access scopes is refused.
`offline_access` is ignored. Approval is required each time; `prompt=none` cannot obtain it.
Declining this screen leaves existing access unchanged. Disconnecting the management app revokes
its authority. Each management call checks the current pod owner.

An authority approved before the consent text included registration and grant assignment keeps
its earlier powers. Registration or a replacement that broadens them returns `403 insufficient_scope`;
request fresh owner consent. The [management API](service-clients.md#managing-service-clients)
and [authority contract](../../src/main/kotlin/org/sempods/pods/oauth/flows/PodServiceClientManagement.kt)
describe operations and version checks.

## Managing contexts

Request `contexts:manage` through owner consent to create or delete any context using
`PUT` or `DELETE /_system/contexts/{path}`. It follows the same authority rules as service management.
An ordinary app, including the owner's app, can only manage contexts covered by its `#manage` grants.
For example, authority over `apps/notes` does not authorize deletion of `contacts`.

This privileged scope reads no data, but deleting a context deletes its data. The catalogue
reports management-only permission. That differs from normal `#manage` grants and is an
experimental deviation from `SPS-CTX-034` and `SPS-GRANT-009`, tracked in
[sempods-spec#114](https://github.com/sempods/sempods-spec/issues/114).

## Registration rate limit

See [OAuth operations](operations.md#registration-rate-limit).

## Protected Resource Metadata (RFC 9728)

See [OAuth operations](operations.md#protected-resource-metadata-rfc-9728).

## Sharp edges (current state)

See [OAuth operations](operations.md#sharp-edges-current-state).

## What lives elsewhere

- [Identity and trust](identity.md): WebIDs and pod login.
- [User connections](connections.md): lifetime, refresh and sign-out.
- [Operations](operations.md): rate limits, discovery, audit retention and maintenance.

<!-- doc-examples: checked -->
