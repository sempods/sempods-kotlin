# sempods Identity Service

## Purpose

This document describes the external person-identity layer for sempods:

- `id.sempods.org` — WebID registry, OIDC bridge, JWT issuance (all roles in one service)

The service is implemented by `sempods-auth` and is **optional**. sempods
works without it — but with reduced identity capabilities.

This document complements:
- [sempods-spec `spec/core/grants.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md) (pod-side enforcement, grants and scopes)
- `docs/auth/identity.md` (pod trust model for identity JWTs)

---

## Identity Layers

sempods person identity is composed of three optional layers. Each layer adds
capability without breaking the layer below.

### Layer 0 — Always, no external dependencies

Every pod can address persons by a deterministic URI derived from their email:

```
urn:sempods:e:<sha256(normalize(email))>       ← person URI (deterministic)
```

The `urn:` URI is globally unique and deterministic — any system that knows
the email and the open formula can produce the same URI.

No server needs to be running. No LOD outside the pod, but a complete and
consistent internal graph.

### Layer 1 — opt-in: sempods-auth connected

When a sempods deployment is configured with a sempods-auth instance, person
URIs become dereferenceable WebIDs:

```
id.sempods.org/e/<sha256(normalize(email))>    ← WebID URI (dereferenceable)
```

The pod graph gains a `sameAs` link to the WebID URI, bridging the local
anchor to the external identity document.
Multi-identity support via `also_known_as` in the JWT becomes available.

Any pod can independently compute the WebID URI from an email — no server
call needed for URI derivation. The document at that URI may not exist yet
(404 before first login) — this is expected and only affects LOD enrichment,
not grant matching.

### Layer 2 — opt-in: federation between sempods-auth instances

Multiple sempods-auth deployments can federate via `owl:sameAs` links between
their WebID documents:

```
id.sempods.org/e/<hash>  owl:sameAs  id.alice.org/e/<hash>
```

This enables cross-deployment identity matching without a central registry.
Each deployment is independently authoritative for its own namespace.

---

## WebID Registry

### URI namespaces

```
id.sempods.org/e/<sha256(normalize(email))>            ← EMAIL namespace
id.sempods.org/oidc/<sha256(normalize(iss+":"+sub))>   ← OIDC namespace (no email)
```

**Why SHA-256 and not HMAC:** stateless, decentralized — any pod or connector
can compute `id.sempods.org/e/<sha256(email)>` independently. HMAC would
require a shared secret, coupling all deployments to a specific service instance.

### WebID document

Served with content negotiation:

```
GET https://id.sempods.org/e/<hash>
Accept: text/turtle          → RDF/Turtle
Accept: application/ld+json  → JSON-LD
Accept: text/html            → HTML profile page
```

Turtle representation:

```turtle
@prefix foaf: <http://xmlns.com/foaf/0.1/> .
@prefix owl:  <http://www.w3.org/2002/07/owl#> .

<https://id.sempods.org/e/<hash>>
    a foaf:Person ;
    foaf:name "Alice" ;
    owl:sameAs <https://id.alice.org/e/<hash>> .   # federation link (opt-in)
```

The email address is never included — only opaque hashes in the URI path.
Federation links (`owl:sameAs`) to other sempods-auth instances are added
opt-in when cross-deployment linking is configured.

---

## OIDC Bridge

### Role

`id.sempods.org` is an identity broker:

1. Accepts OIDC login from any supported provider (Google, Apple, ...)
2. Verifies the OIDC token
3. Derives the canonical WebID URI:
   - Email present → `id.sempods.org/e/<sha256(normalize(email))>`
   - No email → `id.sempods.org/oidc/<sha256(normalize(iss+":"+sub))>`
4. Looks up or creates the WebID profile; collects all linked identities
5. Issues a sempods JWT

It is a broker in both directions at once, which is worth naming because the two legs point
opposite ways and both are OIDC:

- Toward a pod it is an **OpenID Provider** — it authenticates the person and says who they are.
- Toward Google or Apple it is a **relying party** — it is the client asking them the same thing.

The paths keep the two apart. `/authorize` and `/token` are the provider role;
`/login/oidc/{provider}/callback` is where an upstream answer comes back. The third meaning of
`oidc` in this service — `id.sempods.org/oidc/<hash>` — is neither: it is a person's identity
document, which is why no protocol endpoint lives under that prefix.

### Endpoints

| Path | Role |
|---|---|
| `GET /.well-known/openid-configuration` | Provider metadata (OIDC Discovery 1.0 §3) |
| `GET /.well-known/jwks.json` | The keys that verify what this service signs |
| `GET /authorize` | Authorization Code + PKCE; starts the upstream login |
| `POST /token` | Exchanges the code for an `id_token` |
| `GET|POST /login/oidc/{provider}/callback` | Where Google or Apple answers |
| `GET /e/{hash}`, `GET /oidc/{hash}` | The WebID documents |

Every caller is on the provider surface: the pod server's `{pod}/_system/auth/authorize` and the
hosted MCP service's AI-client and browser flows all begin an authorization request here and
exchange a code at `/token`.

`GET /login` used to sit alongside them — an implicit grant that appended an `aud`-less identity
token to any `return_to` it was given. It is gone, together with
`JwtIssuer.issueLegacyIdentityToken`. Tokens it already issued outlive it — this service persists
its signing keys, so invalidating them means clearing the key rows — an operator step against
the `oauth.signingKeys` collection. The `/login` prefix survives on the upstream callback
alone, and only because Apple and Google hold that address in their consoles.

### The provider flow

```
pod ──GET /authorize?client_id=did:web:<pod-host>&redirect_uri=…&response_type=code
                    &scope=openid&state=…&nonce=…&code_challenge=…&code_challenge_method=S256
                                                    │
                                    (provider chooser, then Google or Apple)
                                                    │
pod ◀─302 <redirect_uri>?code=…&state=… ────────────┘        through the browser

pod ──POST /token  grant_type=authorization_code&code=…&code_verifier=… ──▶   back channel
pod ◀─{ "access_token": …, "id_token": …, "token_type": "Bearer", … } ─────┘
```

The `access_token` is there because the response must carry one (RFC 6749 §5.1; OIDC adds the
`id_token` to that response rather than replacing it, and libraries validate the shape). It
authorizes nothing at this service — a WebID document is public Linked Data, so there is no
protected resource here. It is marked `typ: at+jwt` (RFC 9068) and audienced to this issuer rather
than to the client, so it cannot be mistaken for the identity token beside it.

What travels through the browser is a single-use code, not a credential. Redeeming it needs the
PKCE verifier, which only the client that started the flow holds.

Clients are **`did:web:` static identities** — an origin, no secret, nothing registered. The
`redirect_uri` must sit on the origin the identifier names, and no document is fetched to
establish that: the host match is the whole check, so there is no SSRF surface and no third party
in the login path. PKCE is required with no exemption.

The `id_token` carries `aud`, which is the difference that matters against the token below: a copy
is worth nothing anywhere except at the client it was issued to.

### Token format (v0)

```json
{
  "iss": "https://id.sempods.org",
  "sub": "https://id.sempods.org/e/<hash>",
  "webid": "https://id.sempods.org/e/<hash>",
  "also_known_as": [
    "urn:sempods:e:<hash>",
    "urn:sempods:oidc:<hash2>",
    "https://id.sempods.org/oidc/<hash2>"
  ],
  "exp": 1744300800,
  "iat": 1744197200
}
```

**`sub`** — the WebID URI; dereferenceable, LOD-compatible, globally unique.
Doubles as `webid` in v0 (no distinction needed until profile management
becomes richer in later phases).

**`also_known_as`** — all equivalent identity URIs known for this person,
collected from the WebID profile's linked identities. Always includes the
`urn:sempods:e:` form so pods without sempods-auth config can match Layer 0
grants. A standard JWT consumer that does not know this claim ignores it safely
— backward compatible.

**No `aud` claim** — the token is valid across all pods that trust
`id.sempods.org` as issuer. One login, multiple pods.

Signed with `id.sempods.org`'s private key (RS256).

### Token format — OIDC login without email

```json
{
  "iss": "https://id.sempods.org",
  "sub": "https://id.sempods.org/oidc/<hash>",
  "webid": "https://id.sempods.org/oidc/<hash>",
  "also_known_as": [
    "urn:sempods:oidc:<hash>"
  ],
  "exp": 1744300800,
  "iat": 1744197200
}
```

Note: grant-before-login is not possible for no-email subjects — the pod
owner must wait for first login, or the user must link an email via identity
merge.

### Pod trust configuration

Each pod configures 0..n trusted sempods-auth issuers:

```
sempods_auth_issuers:
  - https://id.sempods.org
  - https://id.alice.org
```

For each request with a Bearer token, the pod:
1. Verifies `iss` is in the configured issuers list
2. Verifies signature via JWKS from `<iss>/.well-known/jwks.json` (cached)
3. Verifies `exp`
4. Grant check: `grant.subject IN ([sub] + also_known_as)`
   — `also_known_as` only evaluated when `iss` is in the trusted list

With zero configured issuers, the pod accepts any standard OIDC JWT but
performs only single-sub grant matching.

---

## Email → Grant Flow (no pre-registration)

```
1. Alice enters: bob@example.com in the grant UI
2. Grant stored: <family#read> → <urn:sempods:e:<sha256("bob@example.com")>>
   (or WebID URI if sempods-auth connected — same sha256 formula)
3. Bob logs in with Google (email: bob@example.com)
4. sempods-auth derives: sub = id.sempods.org/e/<sha256("bob@example.com")>
5. JWT also_known_as includes: urn:sempods:e:<sha256("bob@example.com")>
6. Grant matches via also_known_as — no identity linking needed
```

No pre-registration. No placeholder. URI is deterministic from the open formula.

### Limitation: provider-side relay addresses

The flow rests on the provider handing over the address the grant was made against. Apple's
"Hide My Email" breaks that assumption: it supplies a per-service relay alias
(`<opaque>@privaterelay.appleid.com`, flagged by `is_private_email`), so step 4 derives a different
hash and the grant in step 2 does not match.

This is the formula working as specified, not a defect in it — the person genuinely did not
present the address they were invited under. Resolving it belongs to
[identity merge](#identity-merge): once the user links their real address, the grant matches
through `also_known_as` without anything being regranted. Until that is available to users, an
Apple login with a hidden address needs a grant against the WebID it actually produces.

The relay case is logged at login so that "the invitation did nothing" has a visible cause.

---

## Identity Merge

The WebID profile at `id.sempods.org` stores all verified identity links for
a person. On each login, `id.sempods.org` collects all linked identities
and puts them in `also_known_as`.

```
Bob logs in without email
  → sub = id.sempods.org/oidc/<hash>
  → also_known_as = ["urn:sempods:oidc:<hash>"]

Bob links bob@example.com (email verification)
  → linked identity added to profile
  → next login: also_known_as = [
      "urn:sempods:oidc:<hash>",
      "urn:sempods:e:<sha256(email)>",
      "id.sempods.org/e/<sha256(email)>"
    ]
  → grants against any of these URIs now match
```

Verification requirements:

| Identity type | Verification method |
|---|---|
| Email address | Email confirmation link |
| OIDC provider | Successful OIDC login redirect |
| External WebID | Challenge signed with WebID private key (future) |

---

## External WebID Support

Any valid WebID URI can be used as a grant target:

```turtle
<https://sempods.org/alice/family#read>
    sempods:grantedTo <https://bob.solidcommunity.net/profile/card#me> .
```

External WebIDs can be linked via identity merge if the user authenticates
through sempods-auth and verifies ownership.

---

## Auth Phases

### v0 — OIDC proxy (current)

- User authenticates via OIDC provider
- `id.sempods.org` derives WebID URI, issues JWT
- No private key management required for users

### v1 — DPoP (future, opt-in)

- User generates a key pair in the browser (Web Crypto API)
- Public key registered in WebID document at `id.sempods.org`
- DPoP auth: no OIDC provider needed — private key in browser IndexedDB

---

## Self-Hosted Deployment

All auth services can run within a single pod deployment:

```
pod.alice.org/          ← pod data plane
pod.alice.org/auth/     ← login role
pod.alice.org/id/       ← WebID registry role
```

No dependency on sempods.org infrastructure. Self-hosted instances federate
with `id.sempods.org` via opt-in `owl:sameAs` links if cross-deployment
identity matching is desired.

---

## Open Questions

- Key recovery for v1 (DPoP): lost device = lost key
- Token revocation: short `exp` sufficient for v0, no server-side revocation needed
- Multi-identity standards: track DIF and Solid OIDC; align when stable
- Profile management UI: users managing name, avatar, keys in sempods-auth
