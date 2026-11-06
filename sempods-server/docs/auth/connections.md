# User connections

<!-- doc-examples: checked -->

[Delegated access](user-access.md) · [OAuth reference](oauth.md) · [Operations](operations.md)

A user can let an app stay connected after the browser closes. For example, Alice approves Notes
for a durable connection. Notes renews its tokens without another login until the connection
expires, is disconnected, or is ended by sign-out.

## `offline_access`

Ordinary authenticated consent issues a refresh token. The owner of that consent chooses the
connection lifetime; `scope=offline_access` only preselects the durable option. The pod issues no
OIDC `id_token` and advertises no `openid` scope.

| Consent choice | Idle window | Absolute ceiling |
|---|---|---|
| Shorter connection | `SEMPODS_SESSION_CONNECTION_IDLE_HOURS` (96 hours) | `SEMPODS_SESSION_CONNECTION_ABSOLUTE_DAYS` (7 days) |
| Durable connection | `SEMPODS_DURABLE_CONNECTION_IDLE_DAYS` (90 days) | `SEMPODS_DURABLE_CONNECTION_ABSOLUTE_DAYS` (180 days) |

The screen shows configured values. Refresh renews the idle window but never the absolute ceiling:
even a daily sync must ask for authorization again by day 180 under the defaults. Configuration
values must be positive, and the idle window cannot exceed the ceiling.

`offline_access` is omitted from the returned `scope`. The response exposes no connection-class
or refresh-expiry field. On refresh, `offline_access` is ignored before feature-scope downscoping.
Anonymous public-read, Client Credentials and privileged management flows have no refresh token.
Authenticated public-read follows the ordinary consent lifetime.

The [JVM guide](user-access.md#stay-connected) explains the client's current refresh API limits.

## Refresh token rotation

A successful refresh replaces the refresh token. **Store the replacement and serialize refreshes
for one connection.** If its response is lost, the pod accepts one retry with the predecessor
within the [configured grace period](operations.md#refresh-rotation-grace), 60 seconds by default.
The retry gets a fresh successor in the same family and retires the original successor. Use the
retry's response and discard a late response from the original request. The retired token cannot
start a second continuing chain; presenting it fails without retiring the retry's successor.

The allowance is shared across server replicas and consumed once. It ends at the configured
deadline, when a successor starts rotating, or when the family ends. A second retry, a retry at or
after the deadline, or reuse of an older ancestor revokes the whole family. That strict detection
also applies when the request names the wrong pod, client or scopes. Within a still-eligible
window, these invalid requests are refused without consuming the retry. Two simultaneous
refreshes can receive responses, but only the retry's successor remains usable; a third ends their
connection. Families rotated before grace support, or while it was disabled, have no allowance.

Grace preserves the family's lifetime and checks the same pod, client, current grants and requested
scopes as an ordinary refresh. It cannot restore a signed-out, disconnected or limit-retired family.
The tradeoff is delayed reuse detection: somebody holding a stolen predecessor can also claim that
one retry during the window, displacing the original successor. Grace does not distinguish that
caller from a client recovering a lost response. The amendment to `SPS-AUTH-033` is proposed in
[sempods-spec#142](https://github.com/sempods/sempods-spec/issues/142); enabled grace deviates from
the current requirement to revoke on every reuse. Setting the period to zero retains strict reuse
detection.

On `invalid_grant`, reconnect through authorization; do not retry indefinitely. Deleting a context
removes its grants immediately and can also end a connection when that deletion removes its last
delegation.

A refresh token the pod does not recognise is logged with a fingerprint of the token. The log
cannot name the person: no family or WebID remains, and `client_id` names an app.

The [shared refresh store](../../../sempods-auth-core/src/main/kotlin/org/sempods/auth/core/RefreshTokenStore.kt)
owns hashing and replay detection. The
[pod refresh store](../../src/main/kotlin/org/sempods/pods/oauth/PodRefreshTokenStore.kt) owns lifetime
and family-retirement rules. Deployments predating lifetime consent need the
[delegation reset](operations.md#upgrading-old-delegations).

## New consent and multiple devices

Each code exchange starts a separate refresh family. Signing in to Notes on a phone leaves the
same app's desktop connection usable. The lifetime choice applies to the new family: an unticked
consent on the phone neither shortens nor revokes the desktop's durable family.

The pod bounds live families per pod, client ID and person, including derivable WebID aliases.
After writing a new family, cleanup protects the newest live family and retires excess families
among the others in order of their last rotation, or issuance if never rotated. MongoDB assigns
an immutable issuance order shared across replicas, so simultaneous exchanges agree on the family
to keep even when clocks tie or an older connection rotates. Expired and revoked families do not
count. At limit 1, a concurrent newer exchange can displace an earlier one; the earlier exchange
checks before returning and answers `invalid_grant` if its family has already been retired. The
[family-limit setting](operations.md#connection-family-limit) controls the bound. Refreshing a
family retired by this limit returns `invalid_grant` with
`error_description=refresh token retired: connection limit`.

Family revocations are logged with their cause, client ID and family ID. Explicit disconnects
are distinguished from withdrawals of the app's last grant. Sign-out, reuse and withdrawn grants
still end the affected families. A consent change during code exchange returns `invalid_grant`
with `authorization code superseded by a later consent`, including a later unticked lifetime choice.
The specification proposal for coexistence and
per-family lifetime choices is [sempods-spec#142](https://github.com/sempods/sempods-spec/issues/142).

## The `prompt` parameter

| Value | Behavior |
|---|---|
| Omitted | Reuse grants after the lifetime question was answered; otherwise show consent |
| `none` | Request no interaction; requires a valid pod session and reusable consent |
| `consent` | Show consent |
| `login` or `select_account` | Authenticate again; forward to the provider where supported |
| `create` | No effect |
| Anything else | `invalid_request` |

`prompt=none` answers `login_required` without a pod session and `consent_required` when consent
cannot be reused. Both require an interactive retry. A
[`dyn:*` client](oauth.md#dyn--dynamically-registered-apps) has its own consent rule. Anonymous [public-read](oauth.md#public-read-flow) has its own silent
flow. A session avoids another identity-provider login but grants no data access by itself.
Google supports forwarded prompts; Apple does not guarantee them.

The pod session lasts twelve hours from its last authorization, capped at thirty days from sign-in.
Data requests and token refresh do not renew it. Browser cookie rules can prevent silent login
in an iframe; HTTP token refresh does not depend on a shared site.

## Signing out

“Sign out everywhere” ends sessions, authorization codes, refresh families and existing user
access tokens **on that pod**, across apps and known equivalent identities. Other pods, the
identity provider, other people and service clients keep their own sessions or credentials.

Sign-out keeps grants, so an app may reuse them after another login. Removing one app's access
also removes its grants. The calling app receives `access_denied` with `error_description=signed out`.

Sign-out is available on the consent page and the service consent page, and requires the session
cookie and that page's one-time form token.
There is no separate sign-out page or per-browser option; a person with no grants or public
contexts may never reach that screen. Replicas need synchronized clocks for the time-based cutoff.
[PodSignOut](../../src/main/kotlin/org/sempods/pods/oauth/PodSignOut.kt) owns the exact boundary,
including equivalent identities and sign-ins in the same second.
