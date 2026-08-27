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

1. a first-party pod UI, or another installer client, obtains a pod access token through
   Authorization Code + PKCE;
2. the token names the owner in `sub`, the installer in `client_id`, and the `service-clients`
   feature scope;
3. the installer calls the pod's protected Dynamic Client Registration endpoint to create a
   confidential service client;
4. sempods records the service client's context grants as resource-server policy; and
5. the service client later uses Client Credentials with its own `client_id` and secret.

The first-party UI does not bypass OAuth. It is simply the built-in installer client of that pod
deployment, so it can use the same token path as any later CLI or hosted installer. The authorization
check starts as owner recognition and settles on `sub == pod.owner` plus the `service-clients`
feature scope before the route is exposed as a general contract.

## The standard line (SOLL)

The standard-shaped pieces stay standard-shaped:

- Authorization Code + PKCE obtains the installer token.
- Dynamic Client Registration creates the service client's OAuth client record.
- Client Credentials obtains short-lived service tokens.
- `offline_access` is the opt-in signal for issuing refresh tokens to interactive clients.

The sempods-specific piece is grant assignment. A service client may be registered with no data
authority at all, then granted read, write or manage on selected contexts. Creating a fresh
`apps/<clientId>` sandbox and granting `<root>#manage` is a convenience choice in the installation
UI, not the definition of installing a service client. A permanent reader is therefore a first-class
case rather than a write-capable app forced through a sandbox it does not need.

## Token lifetime is part of consent (SOLL)

Consent must show not only what the installer can do, but how long the credential shape lasts:

- an installer access token is short-lived and used during installation;
- `offline_access` means a refresh token can keep an interactive connection alive until it is
  revoked or unused beyond its rolling lifetime; and
- a service client secret lives until the registration is removed, while its access tokens stay
  short-lived.

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
