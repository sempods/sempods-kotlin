# Host service-client provisioning

[Control-plane client](../../sempods-control-plane-client/README.md) · [Service access](auth/service-clients.md)

This is a **deployment-specific hosting API**. A host operator can create a pod for its owner, and
a service credential and a private app context for a hosted pod. An application connecting to an
arbitrary pod uses [service registration and consent](auth/service-clients.md#registration-and-consent).

## Address and authority

The route is `POST {hostUrl}/_system/admin/pods/{podName}/service-clients/{clientId}`.
`hostUrl` is the separately configured hosting base URL. Do not derive it by removing a path
segment from an arbitrary pod URL. Calls require a host admin credential, not a pod bearer.

For example, an operator provisions `notes-app` on Alice's hosted pod:

| Operation | URL |
|---|---|
| Operator setup | `https://pods.example/_system/admin/pods/alice/service-clients/notes-app` |
| Service token exchange | `https://pods.example/alice/_system/auth/token` |

[AdminPodsEndpoint](../src/main/kotlin/org/sempods/api/system/admin/pods/AdminPodsEndpoint.kt)
is registered in [SempodsModule](../src/main/kotlin/org/sempods/SempodsModule.kt). Without
configured admin authority, its requests return 503.

## Creating a pod

`PUT {hostUrl}/_system/admin/pods/{podName}` creates the pod. The body names the owner with exactly
one of two members:

| Member | Caller | Stored owner |
|---|---|---|
| `ownerEmail` | knows the person's email | the WebID derived from it; the email is dropped |
| `ownerWebId` | signed the person in at the identity service | the WebID as sent |

Only an email-based WebID `{idBaseUrl}/e/<sha256>` can own a pod, so a person whose provider gave
no email cannot own one yet. [`CreatePodRequest`](../src/main/kotlin/org/sempods/api/system/admin/pods/AdminPodsEndpoint.kt)
lists what is refused with a `400`.

An optional `managementClient`, a `did:web` identifier, registers that service on the new pod
holding the [authority to manage it](auth/service-clients.md#managing-a-pod-as-a-service) for the
owner, with no consent screen; `managementClientName` is what the owner sees it called. A value that
is not a `did:web` a route can name is a `400`: a blank one, or one with a port, `/` or another
character outside letters, digits, `.`, `-` and `_` in a segment. Development accepts a loopback one
with a port, as [registration does](auth/service-clients.md#didweb-services).

The management service is written before the pod, so a pod exists only with it. A creation that
fails before the pod is written leaves none, and a retry starts afresh. A call whose answer is
lost may have created the pod: its retry answers `alreadyExists`, and the pod has the service if
the first call wrote the pod. A repeated `PUT` answers `alreadyExists` and keeps
the stored owner. It sets up no management service either. `GET` on the same address
answers the pod as [listing an owner's pods](#listing-an-owners-pods) does, plus `exists`, so a
caller can confirm the owner before it records the pod for a person.

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

| Case | Result |
|---|---|
| Both identifiers match | `alreadyProvisioned`, no write and no secret in the response. |
| An identifier is missing or stale | `provisioned`, a fresh secret for the same registration; `secretId` changes. |
| No registration exists | Create it with a private app root and its `#manage` grant. |

Later calls **never rewrite grants or restore the app root**. The owner may narrow access, remove
it, delete the root or make it public; provisioning again preserves those decisions. Returned
`scopes` are the current grants, possibly empty. For an existing registration, `contextRoot` names
the initial root without checking that it still exists.

For example, a caller that lost its secret omits both expected identifiers to obtain a new one.
[Rotation](auth/service-clients.md#managing-service-clients) describes the old secret and live
tokens. A `409` means another call changed the credential or created the registration in between.

The [endpoint contract](../src/main/kotlin/org/sempods/api/system/admin/pods/AdminPodsEndpoint.kt)
owns validation, concurrency and response fields. Credential storage in the calling application
is the application's responsibility. Never use an internal user ID as a pod identity; the pod
identifies people by WebID.

## Listing an owner's pods

An owner console asks the host which pods a signed-in person owns, so it needs no registry of its
own:

```
GET https://pods.example/_system/admin/pods?owner=https://id.sempods.org/e/<hash>&owner=https://id.sempods.org/oidc/<hash>
```

Pass every identity the person's verified sign-in produced, such as the equivalent identities in
the `id_token`. The host answers the pods stored under any of them, and names the owning identity
for each pod. It keeps no mapping between identities: a link the identity service drops stops
showing pods at the next sign-in.

The [control-plane client](../../sempods-control-plane-client/README.md) calls it as
`podsOwnedBy`. How many identities one call takes, and the response fields, are on the
[endpoint](../src/main/kotlin/org/sempods/api/system/admin/pods/AdminPodsEndpoint.kt).

## Deleting a pod

`DELETE {hostUrl}/_system/admin/pods/{podName}` deletes the pod and its data. It answers `204`,
also for a pod that was never there. The pod's media stay until the sweep removes them after the
grace period; [media lifecycle](../../docs/media.md#lifecycle) has the details.

A caller deleting for an owner checks the owner first, for example through the listing above.
Between that check and the delete, the pod can be deleted and its name taken by another owner. To
delete only the pod it checked, the caller names the owner it expects:

```
DELETE https://pods.example/_system/admin/pods/alice?owner=https://id.sempods.org/e/<hash>
```

| Case | Answer |
|---|---|
| The pod is stored under that owner, or its `urn:sempods:e:` twin | `204`, deleted |
| The pod has another owner | `412`, nothing deleted |
| There is no pod of that name | `404` |

The server compares the owner and deletes the same stored pod, so a pod created again under the
name in between survives. The [control-plane client](../../sempods-control-plane-client/README.md)
calls it as `deletePod(podName, expectedOwner)`.
