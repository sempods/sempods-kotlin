# Pod authentication

[Auth overview](../../../docs/auth/README.md) · [Client guide](../../../sempods-client/README.md)

The pod server issues access tokens and checks context grants. Start with the flow you need:

- [Service access](service-clients.md): register a service, obtain a token, manage access.
- [Delegated access](user-access.md): register an app, request approval, redeem the code.

[Host provisioning](../host-provisioning.md) is a separate setup path for the host operator,
with a host address and operator credentials.

For implementation and operation:

- [OAuth reference](oauth.md): endpoint and client rules, consent, refresh and management scopes.
- [User connections](connections.md): lifetime choices, refresh rotation and sign-out.
- [OAuth operations](operations.md): rate limits, discovery and timeouts.
- [Identity and trust](identity.md): pod sessions, WebIDs and linked identities.
- [Identity service](../../../sempods-auth/README.md): the separate service handling person login.
- [Shared auth library](../../../sempods-auth-core/README.md): reusable OAuth/OIDC components.
- [OAuth errors](../../../docs/auth/oauth-errors.md): browser error recovery.
