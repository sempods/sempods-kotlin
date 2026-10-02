# Identity service details

[Module guide](../README.md) · [Auth overview](../../docs/auth/README.md) · [Pod trust](../../sempods-server/docs/auth/identity.md)

For example, Alice signs in through Google. The service maps the verified claims to a WebID,
creates its profile if needed, and returns that identity to the pod. It never grants access to
Alice's pod data.

## WebID registry

[LoginService](../src/main/kotlin/org/sempods/auth/login/LoginService.kt) derives a profile URI
from verified provider claims. With an email it uses `{ID_BASE_URL}/e/<hash>`; otherwise it uses
`{ID_BASE_URL}/oidc/<hash>` from the provider issuer and subject.
[WebIdUriDeriver](../../sempods-commons/src/main/kotlin/org/sempods/commons/identity/WebIdUriDeriver.kt)
owns normalization and hashing.

SHA-256 is deliberate: a pod can derive an email's identifier without contacting an identity
service or sharing a secret. Hashing provides a stable identifier, not email secrecy against
someone guessing addresses. The profile document itself includes no email field.

A profile is created on first login. A derived URI may return `404` before then.
[WebIdEndpoint](../src/main/kotlin/org/sempods/auth/api/webid/WebIdEndpoint.kt) serves existing
profiles as Turtle, JSON-LD or HTML. [WebIdDocument](../src/main/kotlin/org/sempods/auth/webid/WebIdDocument.kt)
owns those representations.

## OIDC bridge

The service is an OpenID Provider toward pods and hosted MCP, and a relying party toward Google
and Apple. Each direction has its own code exchange and client identity.

| Endpoint | Purpose |
|---|---|
| `GET /.well-known/openid-configuration` | Discover the provider |
| `GET /.well-known/jwks.json` | Read its public signing keys |
| `GET /authorize` | Start Authorization Code + PKCE |
| `POST /token` | Redeem the code for tokens |
| `GET` or `POST /login/oidc/{provider}/callback` | Receive the upstream provider's answer |
| `GET /e/{hash}`, `GET /oidc/{hash}` | Read a WebID profile |

The callback path is registered in Apple's and Google's developer consoles, so it cannot move.
`/oidc/{hash}` is a person's identity document; no protocol endpoint sits under `/oidc/`.

### The provider flow

For example, a pod on `pods.example` identifies itself as `did:web:pods.example` and sends the
browser to `/authorize` with `scope=openid`, an S256 challenge, `state`, `nonce` and its callback.
After upstream login, the service redirects a one-time code to that callback. The pod exchanges
it with the verifier and validates the resulting `id_token`.

Every answer at the callback, code or error, carries `iss`: this service's URL, the `issuer` of its
discovery document (RFC 9207). When the upstream provider refuses, the pod gets an OAuth error
in RFC 6749's codes. Apple's `user_cancelled_authorize`, for example, arrives as `access_denied`
with `user_cancelled_authorize` in `error_description`; a code that is no refusal arrives as
`server_error`. [`OAuthErrors.fromUpstream`](../../sempods-auth-core/src/main/kotlin/org/sempods/auth/core/OAuthError.kt)
owns the mapping, which pods apply to their own provider the same way.

PKCE is mandatory on this service, including for `did:web` clients. Redirect policy checks the
identifier's host, port and optional path locally; it fetches no DID document. The pod's own
public-client rules are [documented separately](../../sempods-server/docs/auth/oauth.md#client-identity-didweb-dyn-and-svc).

The token response also contains an `access_token`, as required by the OAuth response shape.
It authorizes no protected resource here. It has `typ: at+jwt` and the service as its audience,
so it cannot stand in for the identity token beside it.

### Token format

[JwtIssuer](../src/main/kotlin/org/sempods/auth/login/JwtIssuer.kt) issues RS256 identity tokens:
`sub` and `webid` name the canonical WebID; `aud` names the relying client; `nonce` binds the
login request. The caller validates signature, issuer, audience, nonce and expiry.

The optional `https://schema.sempods.org/claims/equivalent-identities` claim contains other
HTTP(S) WebIDs recorded on the profile. `LoginService.equivalentIdentitiesFor` converts supported
URN aliases and leaves unsupported values out. A profile with no links sends no such claim.
The [pod trust model](../../sempods-server/docs/auth/identity.md#equivalent-identities) explains
when those identities affect consent.

## Email → Grant Flow

A pod can derive Bob's WebID from his email before Bob has logged in. When a provider later
returns that same verified email, this service derives the same WebID. The pod server has no owner
UI for granting access to another person.

Each WebID has a URN twin with the same hash: `{ID_BASE_URL}/e/<hash>` ↔ `urn:sempods:e:<hash>`,
and `/oidc/<hash>` ↔ `urn:sempods:oidc:<hash>`. A grant made before Bob's first login can name the
URN. The pod derives the twin of the token's `sub` and matches the grant; the twin is never sent in
a claim. A grant before the first login needs an email, because only an email hash is derivable in
advance.

### Limitation: provider-side relay addresses

Apple's "Hide My Email" supplies a relay address, so a login can produce a different WebID from
one derived from Alice's usual email. A grant to the latter then does not match. Until a verified
link exists, grant access to the WebID the login actually produces. The service logs relay use;
it currently offers no public [identity-linking workflow](#identity-merge).

## Identity merge

[WebIdProfile](../src/main/kotlin/org/sempods/auth/persist/WebIdProfile.kt) stores linked identities;
login can emit them in the identity claim and profile documents can show `owl:sameAs` links.
The service currently has no public linking workflow, email-confirmation flow or automatic
cross-deployment federation. A public `owl:sameAs` statement alone is not proof of control;
the pod trusts its configured issuer's verified identity claims.

## Self-hosted deployment

The service can run under your own domain. Configure `ID_BASE_URL` to its public issuer URL
and configure relying services to trust that issuer. The relevant settings are:

| Setting | Purpose |
|---|---|
| `PORT` | HTTP listen port; defaults to 8091 |
| `MONGODB_URL`, `MONGODB_DB_NAME` | The service's database |
| `ID_BASE_URL` | Public issuer and profile base URL |
| `GOOGLE_OIDC_CLIENT_ID`, `GOOGLE_OIDC_CLIENT_SECRET` | Enable Google |
| `APPLE_OIDC_TEAM_ID`, `APPLE_OIDC_SERVICE_ID`, `APPLE_OIDC_KEY_ID`, `APPLE_PRIVATE_KEY_PEM` | Enable Apple |
| `APPLE_DOMAIN_ASSOCIATION` | Optional [domain-association file](../src/main/kotlin/org/sempods/auth/api/login/AppleDomainAssociationEndpoint.kt) for Apple's portal |

[SempodsAuthConfig](../src/main/kotlin/org/sempods/auth/SempodsAuthConfig.kt) is the configuration
contract. With no provider configured, authorization returns `server_error`; one skips the chooser;
several show a chooser. Apple returns a cross-site POST and may send the display name only on first
authorization, so its callback supports POST and login fills a previously empty name. Its client
secret is a signed assertion minted for each token exchange.

## Current limits

The service maintains no login session: `prompt=none` returns `login_required`. A client's
`prompt=login` or `prompt=select_account` is forwarded to the provider; without one, Google may
reuse its session. Apple documents no `prompt`. Signing keys are persisted but not
rotated automatically. Identity tokens from the removed `GET /login` carry no `aud`, so the pod and
the hosted MCP service refuse them; their signatures verify until an operator clears the rows in
`oauth.signingKeys`.
[OIDC timeout tests](../src/test/kotlin/org/sempods/auth/oidc/OidcHttpTimeoutsTest.kt) pin the upstream
HTTP budgets; [pod operations](../../sempods-server/docs/auth/operations.md) describes the chain.

### Profile management

Profile editing and identity linking have no user-facing UI.

## Verification

[Provider HTTP tests](../src/test/kotlin/org/sempods/auth/api/provider/OpenIdProviderEndpointTest.kt)
exercise discovery, PKCE, redirects and code redemption. [JwtIssuerTest](../src/test/kotlin/org/sempods/auth/login/JwtIssuerTest.kt)
covers token claims and equivalent identities, and
[WebIdUriDeriverTest](../../sempods-commons/src/test/kotlin/org/sempods/commons/identity/WebIdUriDeriverTest.kt)
covers WebID derivation. The tests use local provider fixtures and no live Google or Apple accounts.
