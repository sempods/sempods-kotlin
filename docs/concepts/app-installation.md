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
- The standard-shaped refresh-token signal is an OIDC request that includes `openid offline_access`.
  If sempods later accepts bare `offline_access` without `openid`, that profile is advertised as a
  sempods extension rather than plain OAuth.

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

## Token lifetime is part of consent (SOLL)

Consent must show not only what the installer can do, but how long the credential shape lasts:

- an installer access token is short-lived and used during installation;
- `offline_access` means a refresh token can keep an interactive connection alive until it is
  revoked or unused beyond its rolling lifetime; and
- a service client secret lives until it is rotated or the registration is removed, while its access
  tokens stay short-lived.

This is not just UI polish. Without the lifetime, a user cannot tell the difference between "let
this UI install one service client now" and "let this remote installer keep coming back".

## Related

- [`../auth/oauth.md`](../auth/oauth.md) — Authorization Code + PKCE, DCR, refresh tokens.
- [`../auth/service-clients.md`](../auth/service-clients.md) — current service-client registration,
  token exchange and audit.
- [`../auth/authorization.md`](../auth/authorization.md) — scope versus grant and context
  permissions.
- [`../roadmaps/owner-app-installation.md`](../roadmaps/owner-app-installation.md) — the milestone
  that implements this target state.
- [`../roadmaps/offline-access-refresh-tokens.md`](../roadmaps/offline-access-refresh-tokens.md) —
  the separate milestone for `offline_access`, refresh-token hardening and MCP migration.
