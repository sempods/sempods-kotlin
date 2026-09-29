# Host service-client provisioning

[Control-plane client](../../sempods-control-plane-client/README.md) · [Service access](auth/service-clients.md)

This is the **deployment-specific hosting API used by sempods.org**. A host operator can create
a service credential and a private app context for a hosted pod. Applications connecting to an
arbitrary pod use [service registration and consent](auth/service-clients.md#registration-and-consent) instead.

## Address and authority

The route is `POST {hostUrl}/_system/admin/pods/{podName}/service-clients/{clientId}`.
`hostUrl` is the separately configured hosting base URL. Do not derive it by removing a path
segment from an arbitrary pod URL. Calls require a host admin credential, not a pod bearer.

For example, an operator provisions `notes-app` on Alice's hosted pod:

| Operation | URL |
|---|---|
| Operator setup | `https://pods.example/_system/admin/pods/alice/service-clients/notes-app` |
| Service token exchange | `https://pods.example/alice/_system/auth/token` |

**Current code placement:** [AdminPodsEndpoint](../src/main/kotlin/org/sempods/api/system/admin/pods/AdminPodsEndpoint.kt)
is registered unconditionally in [SempodsModule](../src/main/kotlin/org/sempods/SempodsModule.kt).
It is still bundled with the reference server, rather than isolated into the sempods.org deployment.
Without configured admin authority, requests return 503.

## Sandbox via manage-root

The first provisioning creates or ensures the private context
`{podUrl}/_system/contexts/apps/{clientId}` and grants the service `<contextRoot>#manage`.
An existing public root is made private so subsequent writes are not exposed anonymously.
The service can manage this subtree; it receives no host authority to create or delete pods.

## Provisioning over the admin surface

Use the [control-plane client](../../sempods-control-plane-client/README.md). Save `registrationId`,
`secretId`, `contextRoot` and the returned secret. Use the returned `contextRoot` as the initial
context name; the server owns its location.

For an existing registration, send both `expectedRegistrationId` and `expectedSecretId` from
the credential you still hold:

| Request | Result |
|---|---|
| Both identifiers match | `alreadyProvisioned`, no write and no secret in the response. |
| An identifier is missing or stale | `provisioned`, a fresh secret for the same registration; `secretId` changes. |
| No registration exists | Create it with a private app root and its `#manage` grant. |

Later calls **never rewrite grants or restore the app root**. The owner may narrow access, remove
it, delete the root or make it public; provisioning again preserves those decisions. Returned
`scopes` are the current grants, possibly empty. For an existing registration, `contextRoot` names
the initial root without checking that it still exists.

For example, a caller that lost its secret omits both expected identifiers to obtain a new one.
The old secret stops working immediately. Existing tokens keep using the service's current grants
until expiry, at most ten minutes. A `409` means another call changed the credential or created
the registration in between.

The [endpoint contract](../src/main/kotlin/org/sempods/api/system/admin/pods/AdminPodsEndpoint.kt)
owns validation, concurrency and response fields. Credential storage in the calling application
is the application's responsibility. Internal user IDs never become pod identities; the pod
identifies people by WebID.
