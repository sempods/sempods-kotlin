# Authentication & Authorization

**The auth model itself is not here any more.** Who may do what, the grant grammar,
the client-identity shapes and the OAuth profile are the specification:
[`spec/core/grants.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md),
[`spec/core/contexts.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/contexts.md) and
[`spec/core/auth.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md). A second implementation reads those; nothing
in this folder binds it.

What stays here is what the specification deliberately leaves to an implementation —
the numbers, the limits and the machinery this one chose. The token endpoint's rate
budget, the timeouts on the OIDC legs, service-client provisioning over the admin
surface and its audit trail, and the page every OAuth `error_uri` points at.

The old rule for this folder was "if a topic isn't here, it isn't part of the auth
model". That is now the specification's job, and the inverse holds here: if a topic is
here, it is *this pod server's* answer to something the contract left open.

## Mental model

Four primitives:

- **Identity** — a person or agent identified by a stable WebID URI
  (or a synthetic anonymous URN for public reads).
- **Context** — a named graph in a pod. Canonical IRI. Single permission
  boundary; everything is per-context.
- **Scope** — `<context-iri>#read|write|manage`, plus the `public-read`
  pseudo-scope.
- **Token** — a pod-issued JWT carrying three orthogonal dimensions:
  `client_id` (which app), `sub` (which user), `scope` (what is allowed).
  Service tokens (2-leg, no user) collapse `sub` to the `client_id` and
  are marked `client_type = "service"` (see `service-clients.md`).

Authorization is set intersection: an app gets the subset of the user's
scopes that the user explicitly delegates at consent time. The pod
enforces the result on every request — apps never decide their own access.

## Design principles

- **Server-enforced, deterministic.** No custom policy languages; access
  is decidable from `(context, scope, sub, client_id)` alone.
- **Standards-first.** OAuth 2.1, OIDC Core 1.0, RFC 7591 (DCR), RFC 9728
  (PRM), RFC 6750 (Bearer), PKCE S256. Where sempods deviates, the
  deviation is documented (see sempods-spec `spec/core/grants.md` on `manage`, and
  `oauth.md` on `dyn:` clients and `public-read`).
- **Identities are external.** WebID URIs are the canonical handle. Pods
  don't manage person identity — they store WebID URIs in grants.
- **Pod-owned tokens.** Each pod issues and signs its own access tokens
  (RS256, per-pod RSA keys). No shared OAuth server.
- **Copyable data, stable identities.** Scopes reference full IRIs so
  that resources copied between pods retain unambiguous provenance.

## Standards used

| Standard | Where it shows up |
|---|---|
| OAuth 2.1 (Authorization Code + PKCE) | App login (`oauth.md`) |
| RFC 6749 §4.4 — Client Credentials | Service clients (`service-clients.md`) |
| OIDC Core 1.0 | `prompt` parameter, identity JWT shape (`identity.md`) |
| OIDC Discovery 1.0 | The id-server's `/.well-known/openid-configuration` (`identity.md`) |
| RFC 7636 — PKCE (S256) | Required by both the pod's and the id-server's `/authorize` |
| RFC 7591 — Dynamic Client Registration | `dyn:*` clients (`oauth.md`) |
| RFC 9728 — Protected Resource Metadata | `/.well-known/oauth-protected-resource` (`oauth.md`) |
| RFC 6749 §10.4 — Refresh-token rotation | Token family + reuse detection (`oauth.md`) |
| RFC 6750 — Bearer Token usage | Resource-server requests |
| RFC 8414 — Authorization Server Metadata | JWKS endpoint shape (`oauth.md`) |

Standards are *named*, not re-explained in these docs.

## Known limitations

What the model does not do yet. Named here rather than left to be
discovered — none of it is a bug report, and none of it carries a date.

**Sign-in happens once per pod.** The identity service holds no session of
its own: every authorization runs the full provider leg, so `prompt=none`
at its `/authorize` is always `login_required`, and signing in to a second
pod re-authenticates at the upstream provider. The pod side has a session
cookie; this is the other half.

**A pod session is neither visible nor revocable to the person holding
it.** There is no "signed in as … / sign out" surface on a pod, and no way
to end a session other than waiting out its twelve hours.

**`prompt=login` cannot be guaranteed for an Apple sign-in.** The value is
parsed and forwarded, and Google honours it. Apple's authorize endpoint
does not document `prompt`, and an existing Apple session satisfies the
flow regardless — so a pod that genuinely requires fresh authentication
gets a promise the chain may not keep, and does not learn that it did not.

**No rate limiting on `/authorize` or `/register`** beyond what the
surrounding infrastructure provides. `/register` is unauthenticated by
design (RFC 7591) and unthrottled: registrations from anyone who can reach
the pod are stored, deduplicated only when the submitted metadata is
identical. `/token` is the one that has a limit — see
[`oauth.md`](oauth.md) for the key it is spent against, and note that the
budget is per process, so a deployment running several replicas hands out
one per replica.

**The HTTP timeouts on the OIDC legs are nobody's decision, bar one.** A
sign-in crosses three of them, on three different clients: the pod server's
leg to the identity service gives up after ten seconds, which somebody
chose; the identity service's token exchange with Google or Apple after
fifteen; and the JWKS fetch that verifies the resulting token after half a
second, the tightest of the three sitting on the step nobody thinks about
(cached for five minutes, so it bites on a cold cache). All are bounded, so
a slow provider does not hang a request — but only the first was picked,
none is configurable, and `sempods-client` is the only place in the tree
where these are modelled deliberately. Figures in `oauth.md`
("Sharp edges").

**A failed credential cannot be attributed to anybody.** When a refresh token is
presented that this server does not recognise, the warning names which token
missed — a short digest prefix — but not whose it was: there is no token to read a
family or a WebID off, and the submitted `client_id` names an app rather than one
person's installation of it. A pod owner reading his own logs cannot tell his own
client from another person's holding a grant on the same pod.

**Signing keys are persisted but never rotated.** The schema carries
`kid`, `algorithm` and `retiredAt`, and the JWKS endpoint publishes every
persisted key, so rotation is a change to the issuer rather than a
migration — but nothing performs it today. Revocation before expiry is
limited to refresh-family revocation; there is no `jti` blacklist, so an
issued access token stays valid for its hour.

**A connection whose grant died is marked, not pruned.** The RFC 6749 §5.2
case (`invalid_grant` on refresh) sets a flag that surfaces as "reconnect
needed". A token that is merely expired with no refresh token never
reaches that path and still reads as healthy. Reconnecting reads the flag
and re-registers a dynamic client rather than presenting the stored one;
the background refresh does not read it at all, and keeps retrying a marked
connection on every sweep until the pod is reconnected.

**Access for non-owner WebIDs has storage but no interface.** The scope
grammar and the grant store support `(pod, webId, scope)`; there is no
pod-owner UI to manage such grants, so in practice access is owner plus
whatever the owner delegates to apps, plus `public-read`.

**Public contexts are flagged in the operational store, not in RDF.** A
pod's public-read contexts come from a database flag rather than from
pod-owned metadata, so the setting is not itself data the owner can read,
copy or reason over.

**DPoP is not implemented.** Tokens are bearer tokens. Sender-constrained
tokens and browser-resident key pairs are a design in `identity.md`, not
code.

## Doc map

- **`identity.md`** — authentication: WebID identity layers, identity
  JWTs issued by `id.sempods.org`, the OIDC bridge, trust model,
  anonymous subjects.
- **`oauth.md`** — what the flows cost and where they are bounded here: the token
  endpoint's rate budget and its two tiers, the timeouts on both OIDC legs, and the
  sharp edges. The flows themselves are
  [`spec/core/auth.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md).
- **`service-clients.md`** — provisioning a service client over the admin surface,
  idempotency, the audit trail and its retention. What a service client *is* and what
  it may hold is [`SPS-AUTH-012`](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md#SPS-AUTH-012) onwards.
- **`oauth-errors.md`** — the recovery page every OAuth `error_uri`
  points at: one heading per error code a redirect can carry.
- **`../../sempods-auth/docs/identity-service.md`** — implementation
  details for the id-server (URI namespaces, OIDC bridge internals,
  identity merge, federation).
