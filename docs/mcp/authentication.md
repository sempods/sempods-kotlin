# MCP authentication — how this implementation realises it (IST)

**The contract is [`spec/modules/mcp.md`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md)**: the three authentication modes
([`SPS-MCP-005`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-005) to [`SPS-MCP-008`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-008)), the bearer challenge
([`SPS-MCP-009`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-009)), the `authorize` tool and its `reauthorize` argument
([`SPS-MCP-010`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-010) to [`SPS-MCP-014`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-014),
[`SPS-MCP-029`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-029)), and registration dedup
([`SPS-MCP-016`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md#SPS-MCP-016)). Not repeated here.

What follows is the machinery underneath: which clients made the `authorize` tool necessary, how a
replay is told apart from a fresh request, and what this implementation digests to dedup a
registration.

## The `authorize` tool

Three of the four major MCP clients we test against (Claude, Copilot,
Open-Code) are **defensive**: they call `list_contexts`, see no
writable contexts, and tell the user to reconnect manually instead of
calling a write tool that would have produced the standard 401.
ChatGPT is the exception — it triggers OAuth proactively when a pod is
added.

The synthetic `authorize` tool covers the defensive case: it is always
visible in `tools/list`, so the model can call it the moment the user
asks for something that needs more than public-read.

### Server-side decision matrix

```
                                 │  reauthorize=false   │  reauthorize=true
─────────────────────────────────┼──────────────────────┼──────────────────────────
 anonymous / public-read-only    │  401 + WWW-Auth      │  401 + WWW-Auth
 context-scoped bearer           │  no-op JSON ack      │  401 + WWW-Auth
                                 │                      │  (refresh tokens revoked,
                                 │                      │   replay challenge recorded)
```

The 401 carries `WWW-Authenticate: Bearer
resource_metadata="<…/.well-known/oauth-protected-resource>"`; the MCP
client follows the link, runs the standard OAuth flow advertised by the
PRM, and replays the same `tools/call` with the new bearer.

`outcome=auth_trigger` is logged for the deliberate 401, distinct from
`outcome=error error=invalid_bearer` which only fires for actually
broken bearers. This separation matters when grepping audit logs to
verify a client picked up the trigger.

### Reauthorize replay

`reauthorize=true` is used to *extend* an existing grant (request more
contexts) without losing the current session. Two near-indistinguishable
calls share the same arguments:

1. The genuine extension request — must answer 401.
2. The MCP client's automatic replay after the OAuth roundtrip,
   carrying the brand-new bearer with the same body — must answer with
   the idempotent ack, not a second 401.

[`ReauthorizeChallengeStore`](../../sempods-mcp-core/src/main/kotlin/org/sempods/mcp/core/ReauthorizeChallengeStore.kt)
disambiguates them, shared with the hosted service. On the original 401
it records `(pod, clientId, sub, jti, recordedAt)` with a 5-minute TTL.
The replay is recognised when:

- The same `(pod, clientId, sub)` arrives,
- with a *different* `jti`,
- whose `iat` (token-issuance time) is at-or-after `recordedAt`.

Iat is checked at second precision (JWT serialization rounds `iat` to
seconds; recording is sub-second). A token issued in an earlier second
is rejected — that excludes parallel-session bearers on the same
client_id. Anonymous challenges have no client/sub yet and are keyed by
pod only; the first fresh authenticated token on that pod consumes them.
Anonymous entries are last-write-wins per pod; concurrent anonymous flows
against the same pod may need to retry.

What the client already holds for the affected `(podId, clientId, person)`
is ended on the original 401 — explicit reauthorize means *review current
consent*, and two things would otherwise answer it from stock. Its
**refresh tokens**, so parallel sessions cannot rotate around the consent
UI. And any **authorization code it has not yet exchanged**: a code stays
redeemable for five minutes and the client keeps its verifier, so one
issued just before the call would still mint the bearer and seed the
family the challenge exists to make it ask for. The two are ended by
different means, and the split is the third state: where a decision
stands, raising its generation is already enough to spend the outstanding
codes, because that is what the exchange compares them against; where
nothing is recorded there is no generation, and deleting the rows is what
reaches them.

The person is every URI derivable from the bearer's `sub`, not that one
URI: a pod stores whichever WebID authenticated, and a family recorded
under the twin would keep rotating around the same dialog.

The family sweep names what it will end before it ends it, so a consent
completing beside the call keeps the family it just produced — that one
carries the generation this raise wrote, and taking it would hand the
person a refresh token that is dead on arrival.

**The generation rises before that sweep, and that ordering is the whole
argument.** An exchange already in flight can consume its code before the
sweep runs and mint its family after it, out of the sweep's reach. What
catches it is the re-read the token endpoint already does after minting:
the raise landed first, so the exchange finds a generation its code does
not carry and gives the family up. Whichever of the two lands second sees
the first. It is a database `$inc` rather than a timestamp comparison,
because the code and the reauthorize call can be served by different
replicas and their clocks are not the same clock.

Nothing is raised where the authorization has no decision recorded —
creating one here would turn a forced review into an answer nobody gave,
and an absent document is the third state. Such an authorization mints no
family either, so the sweep and the deleted codes cover it in the ordinary
case.

**What it does not cover is one bearer, and that is the known limit.** A
code consumed in the instant before the sweep is beyond the delete's
reach, and with no generation on either side the exchange has nothing to
compare — so it returns an access token, whose fresh `jti` and `iat`
satisfy the challenge above. The client's replay is then answered "already
authorized" and the consent screen is not rendered. It is one short-lived
token of the feature scopes the client already held, and context access is
resolved per request as always; closing it needs an ordering that exists
without an answer recorded, which the consent store deliberately has not
got.

The store is Mongo-backed and its rows are TTL-indexed, so a deploy
inside the five-minute window does not cost the caller its consent
roundtrip, and the confirmation call may land on a different replica
than the one that issued the challenge. The replay predicate is one
conditional `findOneAndDelete`, so exactly one of N concurrent
confirmations consumes the challenge; a non-matching call leaves it in
place for the proper replay still to come.

## Durable connections

A client that has to stay connected past the access token's hour asks for
`scope=offline_access` at the pod's `/authorize`. It is listed in
`scopes_supported` in the protected-resource metadata the 401 points at,
which is where a client with no sempods documentation in front of it
finds the extension. Asking is not what decides the outcome: the person
answers a control in the consent dialog, and
[`../auth/oauth.md`](../auth/oauth.md#offline_access) owns that rule.

The re-authorize path above ends what the client holds, which is not the
same as asking again. Whether the next `/authorize` renders a dialog is
the ordinary auto-grant question: a `dyn:` client — which is how the
clients in [`clients.md`](clients.md) register — always gets the consent
screen, while a static `did:web:` client whose grants survive is
auto-granted and the recorded answer stands, a durable one minting a
replacement family and a short-lived one leaving the client with an
access token and nothing else. A static client that wants the review it
just triggered sends `prompt=consent`;
[`../auth/oauth.md`](../auth/oauth.md#the-prompt-parameter) has the rules.

## Bearer challenge format

The `WWW-Authenticate` header on every 401 carries:

```
Bearer realm="<pod-base-url>", resource_metadata="<resource-metadata-url>"
```

The `resource_metadata` URL is the pod-level PRM
(`…/{pod}/.well-known/oauth-protected-resource`) for every caller, MCP
or REST: the pod is the protected resource in both cases.

## DCR fingerprint

MCP clients with no persistent client-state (Claude Code / Desktop and
similar) re-register on every reconnect. Without dedup, each call would
mint a fresh `dyn:<random>` the user has never consented to, orphaning
the previous consent. sempods therefore digests the stable parts of the
registration —
[`DynamicClientFingerprint`](../../sempods-auth-core/src/main/kotlin/org/sempods/auth/core/DynamicClientFingerprint.kt):

```
fingerprint = SHA-256( clientName · userAgent · normalized-redirect-uris )
```

Loopback redirect URIs are matched with port-stripping (RFC 8252 §7.3),
which is what makes the digest survive the ephemeral port a desktop
client picks per launch.

Consequence: a client that re-registers with the same `clientName` /
`userAgent` and redirect-URI shape reuses its existing `dyn:` clientId,
so consent and grants stay anchored to one row.

**One row per digest, by constraint**, not by the lookup alone:
`(registeredForPodId, fingerprint)` is a unique index, partial on the
fingerprint existing. Two registrations arriving together both miss the
lookup, the index refuses the second, and the loser answers the winner's
`client_id`. Unsetting the field is how a duplicate written before the
constraint leaves the lookup without losing the id its grants hang off.

The digest has a fourth slot — a realm — that the pod leaves empty. It
used to carry the MCP path, which forced one OAuth client per MCP URL on
cloud connectors that otherwise collapse several UI entries onto one; the
hosted MCP service fills the same slot with its profile. What the pod
gives up with it is in [`clients.md`](clients.md#chatgpt).

## Setup for clients

For per-client setup snippets and observed quirks (Claude Desktop /
Code / Web, ChatGPT, Copilot / VS Code, Open-Code) see
[`clients.md`](clients.md).

## Related

- [`endpoint.md`](endpoint.md) — discovery routes and 401 challenge
  embedding in JSON-RPC error envelopes.
- [`tools.md`](tools.md) — the `authorize` tool description and
  per-tool scope checks.
- [sempods-spec `spec/core/auth.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md) — the OAuth flows, refresh rotation
  and the public-read flow.
- [sempods-spec `spec/core/grants.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md) — the grant grammar, the
  slash-delimited `manage` rule ([`SPS-GRANT-007`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md#SPS-GRANT-007)) and
  server-side enforcement.
