# Service access and connection lifetime

A service client reaches a pod by host-operator provisioning, by registering itself, or through
the owner's own tool. A self-registered service holds nothing until the owner confirms its consent,
which also activates it: [OAuth reference](../../sempods-server/docs/auth/oauth.md#registering-a-service-client) has
the registration and [Service access](../../sempods-server/docs/auth/service-clients.md#consent) the consent.
Whichever way it arrived, the owner decides its grants afterwards:
[Service access](../../sempods-server/docs/auth/service-clients.md#managing-service-clients).

## Provisioning by the operator

[Host provisioning](../../sempods-server/docs/host-provisioning.md) is the deployment-specific
setup used by sempods.org. It creates the service's initial private app root and grant. Afterwards
the owner controls the grants; repeating provisioning preserves their decisions.

## The durable connection is the person's

Consent carries a control for how long the app stays connected, beside the context grants, and it
names a lifetime class rather than a scope — two classes, one measured in days and one in months.
Both are issued a refresh token; what the answer picks is the family's terms.
`offline_access` in the request preselects that control and settles nothing else;
[OAuth reference](../../sempods-server/docs/auth/oauth.md#offline_access) owns the rule and the numbers.

The request cannot be the decision. OAuth defines refresh tokens but no way to ask for one, and
`offline_access` is an OpenID Connect scope borrowed for an OAuth surface — a resource server may
advertise it in `scopes_supported`, while the MCP authorization specification defines no scope of its
own and requires none, so whether a client asks is that client's choice. Making the request decide
would hand the lifetime of a person's credential to whichever clients happen to implement the lever,
while the person who should be deciding is standing in front of the dialog. So the decision is
resolved from the stored consent whenever a token is issued, the way context permissions already
are, so an authorization code from an earlier and more generous consent does not outlive it: the
code carries the consent it was issued under, and the exchange compares that before it hands
anything back.

The comparison is per identity URI, and that is where the guarantee stops. A person is a set of
equivalent URIs, each with a stored answer of its own, and an answer given under one is written to
the others one document at a time — so a code naming the URI written last can be exchanged in the
gap and be answered. Keeping one answer per URI is deliberate, because a code issued while an alias
was the session identity has to be able to go stale on its own; a single answer for the person
would need the identity resolver the `sub` question is parked with. `PodTokenExchange` marks the
window where the exchange reads.

Ending an app's access is an action of its own: named, and confirmed before it takes effect. It
removes the grants and the durability at once, and what the app can read stops with them, because
that is decided per request. An access token already in its hands is the exception: it is
self-contained, removing access does not recall it, and it keeps its feature scopes until it
expires. Nobody should disconnect an app by accident while dismissing a dialog, be told they
disconnected when nothing happened, or be promised an instant the mechanism cannot deliver.

An anonymous public-read token has no person to grant anything, so it stays short-lived and
refresh-token-free.

## Related

- [OAuth reference](../../sempods-server/docs/auth/oauth.md) — Authorization Code + PKCE, DCR, refresh tokens.
- [Service access](../../sempods-server/docs/auth/service-clients.md) — current service-client registration,
  token exchange and audit.
- [sempods-spec `spec/core/grants.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md) — scope versus grant and context
  permissions.
