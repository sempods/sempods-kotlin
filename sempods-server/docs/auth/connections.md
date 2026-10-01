# User connections

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
for one connection.** Reusing an old token revokes the whole family. For example, two workers
refreshing with the same token can invalidate each other's connection.

On `invalid_grant`, reconnect through authorization; do not retry indefinitely. New consent can
retire older refresh families. Deleting a context removes its grants immediately and can also end
a connection when that deletion removes its last delegation.

A refresh token the pod does not recognise is logged with a fingerprint of the token. The log
cannot name the person: no family or WebID remains, and `client_id` names an app.

The [shared refresh store](../../../sempods-auth-core/src/main/kotlin/org/sempods/auth/core/RefreshTokenStore.kt)
owns hashing and replay detection. The
[pod refresh store](../../src/main/kotlin/org/sempods/pods/oauth/PodRefreshTokenStore.kt) owns lifetime
and family-retirement rules. Deployments predating lifetime consent need the
[delegation reset](operations.md#upgrading-old-delegations).

## The `prompt` parameter

| Value | Behavior |
|---|---|
| Omitted | Reuse grants after the lifetime question was answered; otherwise show consent |
| `none` | Request no interaction; requires a valid pod session and reusable consent |
| `consent` | Show consent |
| `login` or `select_account` | Authenticate again; forward to the provider where supported |

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
