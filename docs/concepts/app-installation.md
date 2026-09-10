# Installing an app into a pod (Concept)

## Purpose

An app that only reads a pod's public contexts needs no credential at all. Everything else needs one,
and something has to decide that the app may have it.

This document is about the durable case: a pod owner installs a service client that will later use
OAuth Client Credentials without a browser. The goal is to keep the OAuth parts ordinary and name the
sempods-specific part honestly: OAuth registers and authenticates clients; sempods decides which
contexts a service client may reach.

Sections are marked **IST** (implemented, verifiable in code) or **SOLL** (target state).

## Provisioning by the operator (IST)

Today service clients are registered out of band by the host operator:
`POST /_system/admin/pods/{pod}/service-clients/{clientId}` creates a private app root
`<pod>/_system/contexts/apps/{clientId}`, registers `<root>#manage`, and returns a secret exactly
once. That route is host-admin authority, not pod authority; it exists for the first caller that
needed it, not because OAuth requires service clients to be installed by the host.

The service client itself is standard OAuth Client Credentials at the token endpoint. The
registration side is sempods policy: context roots, grants, revocation and audit are not defined by
OAuth.

## Owner installation over pod OAuth (SOLL)

A pod owner installs a service client through the pod's ordinary OAuth surface:

1. an owner-facing installer, such as the built-in consent page, `my.sempods.org`, or a later CLI,
   obtains a pod access token through Authorization Code + PKCE;
2. the token names the owner in `sub`, the installer in `client_id`, and the installer feature scope
   (working name `service-clients`);
3. the installer calls the pod's protected Dynamic Client Registration endpoint to create a
   confidential service client;
4. the pod assigns the service client's `client_id` and returns the secret exactly once;
5. sempods records explicitly selected context grants as resource-server policy; and
6. the service client later uses Client Credentials with its own `client_id` and secret.

The installer does not need to be a control-plane UI and does not bypass OAuth. A built-in sempods
installer may be first-party, while an owner console such as `my.sempods.org` stays
implementation-agnostic and talks to the pod surface. The authorization check starts as owner
recognition and settles on the existing alias-aware owner decision plus the installer feature scope
before the route is exposed as a general contract. It must not rely on literal `sub == pod.owner`,
because a canonical WebID can authenticate the same owner whose pod stores one of its aliases.

## The standard line (SOLL)

The standard-shaped pieces stay standard-shaped:

- Authorization Code + PKCE obtains the installer token.
- Dynamic Client Registration creates the service client's OAuth client record, with the
  authorization server assigning the `client_id`.
- Client Credentials obtains short-lived service tokens.
- `offline_access` signals that the client wants a long connection; the person answers how long it
  lasts in consent (§"The durable connection is the person's" below).

Server-assigned client IDs are a security property, not just a naming preference. The caller must not
choose the service client's `client_id` or a registration root. The escalation class in the old
self-service sketch came from caller-chosen identifiers and roots: naming another app's client ID,
naming an existing context, or relying on an empty-root corner case.

The sempods-specific piece is grant assignment. A service client may be registered with no data
authority at all, then granted read, write or manage on selected contexts. The installer feature
scope authorizes service-client lifecycle operations; it is not context data authority by itself. A
grant operation may write grants only when it is bound to an owner consent transaction for the exact
service client and selected contexts, or when the caller independently holds covering context
authority. Creating a fresh `apps/<serverAssignedClientId>` sandbox and granting `<root>#manage` is a
convenience choice in the installation UI, not the definition of installing a service client. A
permanent reader is therefore a first-class case rather than a write-capable app forced through a
sandbox it does not need.

If grants are assigned during installation, the flow has two browser-visible consent moments: first
the installer gets permission to register service clients, then the pod asks the owner for the
concrete grants after the server-assigned service-client ID exists. The second transaction is sempods
policy, not OAuth client registration metadata. Installation may also finish with no grants and let
the owner assign them later through service-client management.

## The durable connection is the person's (IST)

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

## Installer lifetime (SOLL)

Consent for the installer shows the same lifetime classes. An installer access token is short-lived
and used during installation; if the installer feature scope is one-shot, the protected registration
call consumes that authority so the same bearer cannot install again and the underlying
authorization is not auto-granted on the next login, and if it is durable the UI says so. A service
client secret lives until it is rotated or the registration is removed, while its access tokens stay
short-lived. Without the lifetime, a user cannot tell "let this UI install one service client now"
from "let this remote installer keep coming back".

An authorization carrying the installer feature scope never becomes durable, whatever is ticked: a
checkbox cannot make that escalation visible, and a durable installer is something to design. The
control is therefore absent there, or shown unavailable with the reason.

## Related

- [`../auth/oauth.md`](../auth/oauth.md) — Authorization Code + PKCE, DCR, refresh tokens.
- [`../auth/service-clients.md`](../auth/service-clients.md) — current service-client registration,
  token exchange and audit.
- [sempods-spec `spec/core/grants.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md) — scope versus grant and context
  permissions.
- [`../roadmaps/owner-app-installation.md`](../roadmaps/owner-app-installation.md) — the milestone
  that implements the target state above.
