# Service-client provisioning and connection consent

A service client currently needs host-operator provisioning.
[Owner installation](https://github.com/sempods/sempods-kotlin/issues/35) owns the proposed pod-OAuth flow and its
implementation iterations; it is not available yet.

## Provisioning by the operator


Today service clients are registered out of band by the host operator:
`POST /_system/admin/pods/{pod}/service-clients/{clientId}` creates a private app root
`<pod>/_system/contexts/apps/{clientId}`, registers `<root>#manage`, and returns a secret exactly
once. That route is host-admin authority, not pod authority; it exists for the first caller that
needed it, not because OAuth requires service clients to be installed by the host.

The service client itself is standard OAuth Client Credentials at the token endpoint. The
registration side is sempods policy: context roots, grants, revocation and audit are not defined by
OAuth.

## The durable connection is the person's


Consent carries a control for how long the app stays connected, beside the context grants, and it
names a lifetime class rather than a scope — two classes, one measured in days and one in months.
Both are issued a refresh token; what the answer picks is the family's terms.
`offline_access` in the request preselects that control and settles nothing else;
[`../auth/oauth.md`](../auth/oauth.md#offline_access) owns the rule and the numbers.

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
would need the identity resolver the `sub` question is parked with. `PodAuthEndpoint` marks the
window where the exchange reads.

Ending an app's access is an action of its own: named, and confirmed before it takes effect. It
removes the grants and the durability at once, and what the app can read stops with them, because
that is decided per request. An access token already in its hands is the exception: it is
self-contained, nothing recalls it, and it keeps its feature scopes until it expires. Nobody should
disconnect an app by accident while dismissing a dialog, be told they disconnected when nothing
happened, or be promised an instant the mechanism cannot deliver.

An anonymous public-read token has no person to grant anything, so it stays short-lived and
refresh-token-free.

## Related

- [`../auth/oauth.md`](../auth/oauth.md) — Authorization Code + PKCE, DCR, refresh tokens.
- [`../auth/service-clients.md`](../auth/service-clients.md) — current service-client registration,
  token exchange and audit.
- [sempods-spec `spec/core/grants.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md) — scope versus grant and context
  permissions.
