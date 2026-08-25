# Authorization Model

What a caller may do once authenticated. The model is intentionally
small: contexts as the single permission boundary, URI-based scopes,
set-intersection delegation, server-enforced sandboxes.

## Contexts as the permission boundary

A **context** is a named graph in a pod, identified by its full
canonical IRI. Every permission attaches to a context — there are no
project-, app-, or role-level abstractions.

### Where context IRIs live

A context is control-plane state, not data: it is created by a control
API, it carries permissions, and its identity appears in every grant
string and in the named-graph position of every quad. So it lives inside
the pod's reserved area, where it inherits the control-plane protection
instead of needing a rule of its own:

```
/{pod}/_system/contexts/contacts                      ← the owner's own area
/{pod}/_system/contexts/projects/alpha
/{pod}/_system/contexts/apps/notes/public           ← delegated to an app
/{pod}/_system/contexts/apps/did:web:…:focus/default
```

Two shapes, one namespace. A context **delegated** to someone carries a
type — `apps` today, `users` reserved for guests — followed by the
delegate's identifier. A context the owner **keeps** carries no type and
is named freely: nothing is delegated there, so there is nothing to name.

A type means delegation, not ownership: `apps/notes` does not say
"belongs to the notes app", it says "the area it may work in". That is
also why owner identity never appears in a path. Pod ownership is
transferable and may be held by an organisation — put the owner's WebID
in every path and a transfer turns each of those IRIs into a lie. For a
*guest* the same reasoning runs the other way: their access is bound to
them personally, so `users/<webid>/…` will say something true.

The IRI and the route that manages it are the same string: `PUT
{pod}/_system/contexts/apps/notes/public` creates exactly the context
`{pod}/_system/contexts/apps/notes/public`. There is no decomposition
on either side that could drift.

### What a context may be called

The rules ([`ContextPathRules`](../../sempods-server/src/main/kotlin/org/sempods/pods/contexts/ContextPathRules.kt))
are permissive about *names* and strict about *structure*, and they are
shared by both producers — the management route and the consent dialog.

- **Free naming is the norm.** `privat`, `2026-sommer`, `projects/alpha`.
- **Type names are reserved** as first segment, and a type root is created
  by the control plane, never through these routes. A service client
  needs `<root>#manage` to work below its root, so provisioning creates
  it; a 3-leg app holds no `manage`, so its root is created when an app
  first needs one.
- **`_system` is reserved anywhere in the path**, so a context IRI can
  later carry `<context-iri>/_system/<operation>` for per-context
  operations. Context names and operation names are both open sets;
  without a reserved separator they eventually collide, and a name banned
  after the fact would break pods already using it.
- **A path that could not be addressed is refused**: an empty segment, a
  relative segment, percent-encoding, a fragment, a query, or anything
  `java.net.URI` cannot parse. All of these would produce a registry row
  no route can reach.

Naming rules apply on *creation* only. Reading and deleting keep working
for everything that exists, including shapes that predate a rule — a rule
that made an existing context unreadable would be a one-way door.

Apps discover the contexts available to them at runtime from
`GET /{pod}/_system/contexts` (or the MCP `list_contexts` tool) — not
from token scopes, which no longer enumerate contexts (see `oauth.md`).
No app constructs a context IRI; that is the server's job, and it is why
moving the namespace needed no client change.

### `_system` is protected, not undescribable

`/_system/*` is reserved for control-plane state, and no RDF write can
change it — SPARQL Update is not offered at all, and RDF CRUD does not
reach it: the context registry, grants and registrations live in MongoDB,
so a write against a control-plane IRI produces triples, never a
permission change.

Statements *about* a `_system` IRI are therefore ordinary data. A pod may
hold `<{pod}/_system/contexts/contacts> rdfs:label "Privat"` the same way it
holds statements about `did:web:bob.example` or another pod's resources —
what a statement is about is independent of where it is stored, and
`?context=` plus its write grant decides where. Only the control plane
itself is off limits, and it is not reachable through the data path at
all.

## Terminology: scope vs. grant

Two different things are easy to conflate, and the code still calls both "scope" in
places:

| | Travels in the access token | Example | Called |
|---|---|---|---|
| OAuth scope | yes | `public-read`, `openid`, `offline_access` | **scope** (RFC 6749) |
| Grant string | no | `<context-iri>#read\|write\|manage` | **grant** |

A grant string is server-side policy resolved per request from a grant store — see
"Why context permissions are resolved server-side". `PodScopeValidator` keeps its
name because it validates both kinds and tells them apart
(`ScopeValidationResult.Context` vs. `Feature`/`Oidc`); anything that means to
filter for context grants must ask it rather than pattern-match the string.

## Grant-string grammar

```
<context-iri>#read        # read the context
<context-iri>#write       # write to the context
<context-iri>#manage      # manage this context root and slash-delimited descendants
public-read               # singleton pseudo-scope (see below) — a scope, not a grant
```

Rules:

- The separator is the **last** `#` in the scope string.
- The left side is a canonical IRI inside the pod base URL.
- The right side is one of `read | write | manage`.
- Unknown permissions and non-canonical IRIs are rejected.
- OIDC base scopes (`openid`, `offline_access`) are accepted as
  non-context OAuth scopes when a flow needs them.

This grammar describes **grant strings** — the durable per-context
permissions stored in `PodGrantsDao` (user grants) and
`PodServiceClientDao` (service-client registrations). They are **not**
carried in access tokens; the server resolves them per request from
those stores (see "Pod-issued access tokens"). The only scope that
travels in a token is the feature scope `public-read`.

### `manage` semantics — slash-delimited, not prefix

`manage` is the one place sempods deviates from a naive string-prefix
check. Given `R#manage`:

- Authorizes `R` itself and any context `C` where `C == R` or
  `C` starts with `R + "/"`.
- **Does not** authorize sibling-prefix contexts that happen to share
  a string prefix (e.g., `R#manage` does **not** reach `R-private`).

Implementations that use raw `startsWith` are wrong; this is
load-bearing for context-tree isolation.

## Authorization model

Two levels of intersection:

1. **User-level grants** — what a person is allowed to do on a pod.
   - Pod owner: implicit `read | write | manage` on every registered
     context.
   - Other users: explicit grants keyed by `(podId, webId, scope)`
     (`PodWebIdGrantsDao`). The store, the write path and the revocation
     cascade exist; an owner-side interface for managing them does not —
     see [`README.md`](README.md) ("Known limitations").
2. **App delegation** — what the user delegates to an app at consent
   time:

   ```
   granted = requested ∩ user_grants
   ```

No special owner check, no install requirement. If the intersection is
empty and no public contexts exist, the authorize flow returns
`consent_required` (recoverable) — never `access_denied` for a
plain "no scopes" case (`access_denied` is reserved for hard signals
like an invalid JWT or explicit user refusal).

### Revocation

The intersection above is computed once, at consent, and materialized into the
app-delegation store — which is also the only store the request path reads. A
user-level grant that is later narrowed or removed therefore has to be pushed
down, or an app that already consented would keep the revoked access.

`PodGrantsFacade` owns that push and is the single write path for both levels.
On every user-level mutation it recomputes the person's remaining grants and
deletes the app-delegated rows they no longer cover. Recompute rather than a
string match, because `<root>#manage` expands into its registered descendants:
revoking the root has to sweep derived rows like `<root>/child#write`, whose
text matches nothing. Only context grants are swept — `public-read` is an OAuth
scope, not a user-level grant, and deleting it would end the session over an
unrelated narrowing.

Deleting a context runs the same recompute, and for the same reason. Deletion
does not cascade into sub-contexts — `R/sub` survives `R` — but it does remove
an `R#manage` grant, so any delegation that rested on that manage root must go
too, including ones naming a descendant that still exists. The pod owner is
unaffected: their authority is implicit over every registered context, and the
descendant stays registered.

Consent runs the recompute too, right after it persists. Time passes between
intersecting a request against the user level and writing the result, and a
revocation landing inside that window would otherwise be invisible to both
sides — the intersection is already stale, and the revocation's cascade runs
before the app row exists. Both sides therefore write first and check second,
which makes them unable to miss each other. Consent answers
`consent_required` when nothing survives the re-check.

The reverse direction is deliberately not symmetric: widening a person's grants
never widens an app retroactively. The app only gets what the user delegates in
a fresh consent.

Effect is immediate. Access tokens carry no context permissions, so the next
request already resolves the reduced set. A refresh-token family is revoked on
top of that only when the app has nothing left at all — feature scopes counted,
so an app still holding `public-read` keeps its session. That is the same
condition the refresh exchange applies on its own, so the proactive revocation
and the lazy one cannot disagree.

## The `public-read` pseudo-scope

`public-read` is a singleton scope that grants anonymous-equivalent read
access to whatever contexts the pod marks as publicly readable, expanded
**at access time**. It is **additive** and is the one scope that travels
in the access token: when `public-read` is in the token, the resource
layer unions the pod's public contexts into the caller's effective
context set (on top of the per-context grants resolved from the store).
The only place the scope is exclusive is the **anonymous**
flow (`scope=public-read&prompt=none` with no identity session) —
that token carries `public-read` as the sole scope by construction.

Behavior summary:

- In the consent UI: rendered as a normal additive scope, default
  ticked, deselectable.
- Persisted in `PodGrants` so `prompt=none` auto-grant works across
  re-authorize.
- At resource access: when present in the bearer's scope set, the
  current public-read contexts are merged into the caller's
  `restrictedContexts` set. Without it, the bearer sees only the
  explicitly granted contexts.
- For anonymous callers (no identity session): a synthetic
  `urn:sempods:anon:*` subject (see `identity.md`) and no refresh
  token. Re-authorize when expired.

The current implementation derives public contexts from a server-side
source; making this RDF-driven and pod-owner-managed is open work — see
[`README.md`](README.md) ("Known limitations").

## Pod-issued access tokens

Three orthogonal claim dimensions; access requires all three to align:

| Claim | Value | Meaning |
|---|---|---|
| `client_id` | app DID (`did:web:*` or `dyn:*`) | which app |
| `sub` | WebID URI (or `urn:sempods:anon:*`) | which user authorized |
| `scope` | space-separated **feature scopes** (e.g. `public-read`) | which coarse capabilities are enabled |

**Access tokens are slim: they do NOT carry per-context scopes.** The
`scope` claim holds only stable feature scopes (`public-read` today).
Dynamic per-context permissions (`<context>#read|write|manage`) are
resolved server-side on every request from the durable grant store —
`PodGrantsDao` for user-delegated app tokens, `PodServiceClientDao` for
service clients — keyed by the token's verified `(client_id, sub)`. A
grant revoked in the store therefore takes effect on the next request,
not after the token's TTL. The authoritative client-visible view of
effective context permissions is `GET /{pod}/_system/contexts` (and the
MCP `list_contexts` tool); token `scope` is not a context catalog.

Other claims:

- `iss` = pod API base URL.
- `exp` = 1 hour for normal tokens; longer for public-read (TTL still
  to be finalized — see roadmap).
- Signed RS256 with pod-managed RSA keys; public keys at
  `/{pod}/_system/auth/jwks.json`.

A token issued for Alice cannot reach Bob's contexts even with a
matching `client_id` — the `sub` constrains which grants apply.

## Why context permissions are resolved server-side

Context permissions are **named-graph visibility policy**, not capability
scopes. A scope like `folder:123:read` is a *capability*: it tells the
client which API to call and shapes its behaviour. sempods is different —
the data API is uniform: the same SPARQL / LOD-CRUD calls go to the same
endpoints regardless of grants, and the **server filters** what the
caller may see or write. A `<context>#read` grant shapes nothing on the
client; it is a server-side filter. The natural analog is row-level
security / policy-based access control (PBAC), not an OAuth capability
scope — so the grant belongs in the server's policy layer, not in the
token. Carrying it in the JWT `scope` string was a category mismatch
(server-side policy state in a client-facing capability field); the
"scope explosion" on large pods was a symptom of that.

Consequences of the uniform-API model:

- **Reads need no context knowledge** — queries return whatever is
  visible; the client never has to enumerate contexts to read.
- **Only writes need an explicit target.** Context discovery
  (`GET /_system/contexts`, `writable_contexts`) is therefore really the
  question *"where may I write?"* — a data question, answered by the
  endpoint, not a capability the token must advertise.

**Trade-off.** Per-request resolution gives immediate effect for grant
revocation and context deletion, no topology leak in the token, and small
JWTs — at the cost of the self-contained-token property: the grant store
is now on the hot path of every authenticated request. This is acceptable
because the authorization server and resource server are the same pod, so
nothing relied on offline, lookup-free token verification at an edge.
(A short-lived, invalidation-aware cache is the planned mitigation if
request-time resolution becomes a measured bottleneck — see the auth
roadmap.)

How this maps to OAuth standards:

- **RFC 6749** — `scope` is kept for stable, coarse feature capabilities
  (`public-read`, future `ai`/`search`), not per-resource entitlements.
- **RFC 7662 (Token Introspection)** — the conceptual match for
  resolving authorization context outside the token; sempods does this
  internally (same pod) rather than via a public introspection endpoint.
- **RFC 9396 (Rich Authorization Requests)** — the standard for
  structured per-resource permissions. Not adopted: clients do not
  request concrete context IRIs; the user selects them in the consent UI,
  and the uniform API needs no client-visible permission structure.
- **UMA 2.0** — resource-set permissions; too heavy for this profile.

## Enforcement points

The server enforces scopes at three points; apps cannot bypass any of
them by clever request shaping.

### Read sandbox (SPARQL Query)

- Queries see only the contexts in the caller's `read_contexts` set.
- Implementation parses against the RDF4J SPARQL grammar
  (`SparqlQueryService.validateReadOnly()`), rejecting `Update` forms
  and walking the parsed algebra to forbid `SERVICE` clauses anywhere
  (subqueries included). Substring keyword checks are not used —
  literals containing keyword tokens are correctly classified.
- The REST `_system/sparql/query` endpoint and the MCP
  `sparql_select` / `sparql_graph` tools dispatch through this single
  service so the surfaces cannot drift.

### Write sandbox

- Writes never arrive over SPARQL: `validateReadOnly()` rejects the
  whole Update grammar (`INSERT`, `DELETE`, `LOAD`, `CLEAR`, `CREATE`,
  `DROP`, `COPY`, `MOVE`, `ADD`), so every write below is a CRUD write.
- CRUD writes must specify the target context explicitly, and it must
  be in `write_contexts`.
- Per-context scope is checked at write time; for `manage`, the
  slash-delimited rule above is enforced.
- Writes to `/_system/*` via external endpoints are forbidden.

### Resource-level checks

- Read endpoints only return data from readable contexts.
- The model parsed from a write request must only contain statements
  for the target resource (no smuggled out-of-context triples).
- A `public-read` token on a write endpoint surfaces as
  `403 insufficient_scope` — the bearer is valid, the scope just
  isn't enough. (Bad/missing bearer is `401 invalid_token`.)

## Error semantics

| Status | When |
|---|---|
| `400` | Malformed request, invalid scope, invalid SPARQL |
| `401` | Missing or invalid authentication |
| `403` | Authenticated but lacking required scope/grant |
| `404` | Resource or context not found |
| `500` | Server error |
| OAuth errors | `redirect_uri?error=...&error_description=...&error_uri=...` |

The OAuth `error_uri` is present only where a deployment has said which
address serves [`oauth-errors.md`](oauth-errors.md)
(`SEMPODS_OAUTH_ERROR_DOC_BASE`). When it is, the error code is the fragment —
one heading per code, with what the client should do next. Clients must treat
it as optional; RFC 6749 §4.1.2.1 does.

## What lives elsewhere

- OAuth flows that produce these tokens → `oauth.md`.
- Identity layer that issues identity JWTs and synthetic anonymous
  subjects → `identity.md`.
- Open items (non-owner grant UI, RDF-driven public contexts, DPoP) →
  [`README.md`](README.md) ("Known limitations").
- Recovery guidance per OAuth error code → [`oauth-errors.md`](oauth-errors.md).
