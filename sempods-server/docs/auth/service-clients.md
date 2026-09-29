# Service access

[Auth overview](../../../docs/auth/README.md) · [Delegated access](user-access.md) · [Client guide](../../../sempods-client/README.md)

Use **Client Credentials** for a backend or scheduled worker acting as itself. Alice connects
Notes Sync to her pod and allows it to update her notes. Once set up, the service gets tokens
with its own client ID and secret; Alice does not need to be online. Use [delegated access](user-access.md)
when an operation needs to act as a person instead.

`podUrl` is the [full pod URL](oauth.md#endpoints), such as `https://pods.example/alice`; paths
below are relative to it.

## Registration and consent

Notes Sync registers itself on Alice's pod, then asks Alice to choose its contexts. For her context
`https://pods.example/alice/_system/contexts/notes`, setup is:

| Step | Pod-relative call | What happens |
|---|---|---|
| 1. Register Notes Sync | `POST /_system/auth/register`, without a bearer | Returns a provisional `svc:…` ID, a secret and an activation deadline. Save them securely. |
| 2. Ask Alice | `GET /_system/auth/service-consent?client_id=svc%3Aabc&state=…` | Alice signs in and selects `notes` with write permission. Confirming activates the service. |
| 3. Get a token | `POST /_system/auth/token` with Client Credentials | The service authenticates with its ID and secret. |
| 4. Check access | `GET /_system/contexts` with that bearer | Notes is visible. Private contacts are not. The service can now attempt its notes operations. |

Use the returned client ID in step 2. [Registration](oauth.md#registering-a-service-client)
describes the request body, the activation deadline and what a retry creates.

The [JVM setup example](../../../sempods-client/docs/client.md#registering-a-service-client)
shows registration and a bounded wait. [ServiceConsent.java](../../src/test/java/org/sempods/example/ServiceConsent.java)
provides a complete program, including credential storage and an optional loopback callback;
its [HTTP test](../../src/test/kotlin/org/sempods/example/ServiceConsentExampleHttpTest.kt) runs against a pod.

### Consent

The same consent serves self-registered and [operator-provisioned](../host-provisioning.md)
services. The owner selects the complete explicit grant set. **Confirming replaces it and activates
a provisional registration; confirming an empty selection removes every grant and keeps the
registration active. Cancelling changes nothing.** Existing grants are preselected. Alice can
choose existing contexts or create a private context. For a worker that creates its own child
contexts, she can create `apps/notes-sync` and grant
`https://pods.example/alice/_system/contexts/apps/notes-sync#manage`, which reaches that subtree only.

The consent URL proposes no contexts. A service name is self-declared: Alice should compare the
client ID on the consent screen with the one her program shows.

An optional `redirect_uri` must have been registered by the service. The callback carries no code,
secret or approved grants. Check `state`.

| Case | Answer |
|---|---|
| Confirmed | Redirect with `state`, or a page telling Alice to return to the program |
| Cancelled | Redirect with `error=access_denied` and `state`, or that page |
| Unknown client ID, including a `dyn:` ID or an expired registration | `400` page |
| `redirect_uri` the service did not register | `400` page |
| Signed-in person is not the owner | `403` page |
| Session ended before the form was sent | `401` page |
| Form token absent, spent or another session's | `403` page |
| Form issued for another screen | `400` page |
| Selection the dialog could not produce | `400` page; nothing changes |
| Grants changed, or registration removed, while the page was open | `409` or `404` page; contexts created stay private, without grants, and the page names them |
| Identity provider unreachable | `503` page |

Every refusal is a page for Alice; the service receives no redirect.

### Check the result

A token alone does not prove that the requested access exists. A service already reading contacts
can mint one before Alice approves notes. Check the context catalogue, then the operations the
service actually needs; catalogue visibility alone does not prove write or manage permission.
There is no per-consent status endpoint.

[SempodsServiceAccessWait](../../../sempods-client/src/main/kotlin/org/sempods/client/SempodsServiceAccessWait.kt)
waits for catalogue visibility with a time limit and defines what each token-endpoint answer means.

Registration and consent are experimental 0.2 extensions. Their portable profiles are proposed in
[sempods-spec#122](https://github.com/sempods/sempods-spec/issues/122) and
[#123](https://github.com/sempods/sempods-spec/issues/123).

## Use the JVM client

Use the `clientId` and `clientSecret` returned by registration, her `podUrl`, and an
installed OkHttp `http` client as in the [quick start](../../../sempods-client/README.md#read-public-data).

<!-- doc-example: sempods-client/src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt#service-access -->
```kotlin
val base = SempodsPodBase.of(podUrl)
val credentials = SempodsSession(base, SempodsRequestAuth.clientSecretBasic(clientId, clientSecret))
val bearer = SempodsRequestAuth.refreshable(supplier = { _, attempt ->
  val tokens = SempodsPodTokens(credentials, attempt.calls(http))
  checkNotNull(tokens.clientCredentials().body).accessToken
})
val pod = SempodsPod(SempodsSession(base, bearer), http)
val contexts = pod.contexts().listText()
```

The secret goes only to the token endpoint; data requests carry the resulting bearer.
Keep the secret on the backend. This example caches the token and renews it once after a 401;
it does not renew proactively by expiry. `attempt.calls(http)` runs token acquisition on the
original call's admission slot; cancelling that call does not cancel it. The
[example test](../../../sempods-client/src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt)
checks the credentials sent and the renewal path.

## Token exchange

For a direct HTTP call, send a form body and use `client_secret_basic`:

<!-- doc-example: illustrative; route and form checked against PodAuthEndpoint and PodAuthEndpointClientCredentialsHttpTest -->
```http
POST {podUrl}/_system/auth/token
Authorization: Basic base64(formEncode(clientId):formEncode(secret))
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

Form-encode each credential before joining them for Basic authentication: `svc:…` becomes
`svc%3A…`. The JVM client handles this. Tokens last 10 minutes; request a new one with the same
service credential. Do not send `scope` in this request: token down-scoping is unsupported and
returns `invalid_scope`.

Context grants stay on the pod and are checked on each request. They never travel in the token.

## Managing service clients

The owner's tool requests `service-clients:manage` through Authorization Code + PKCE.
[Management consent](oauth.md#managing-service-clients) describes that authority, including the
lasting data access it can create.

Routes are relative to `podUrl`; encode the client ID as a path segment, such as `svc%3Aabc`.

| Method and path | Result |
|---|---|
| `POST /_system/auth/register` with the management bearer | Register an active service, initially without grants. |
| `GET /_system/auth/service-clients` | List registrations and grants, without secrets. |
| `GET /_system/auth/service-clients/{clientId}` | Read one registration and its grants version (`ETag`). |
| `PUT /_system/auth/service-clients/{clientId}/grants` | Replace all grants with the JSON array in the body; send the read `ETag` as `If-Match`. `[]` removes access. |
| `POST /_system/auth/service-clients/{clientId}/secret` | Rotate the secret; return the new secret once. |
| `DELETE /_system/auth/service-clients/{clientId}` | Remove the registration (`204`); keep its data and contexts. |

For example, Alice removes Notes Sync (`svc:abc`) with
`DELETE https://pods.example/alice/_system/auth/service-clients/svc%3Aabc` using that bearer.
The service can no longer obtain tokens or reach contexts. Her notes remain on the pod.

A grant replacement takes effect on the next request, including for existing tokens. Removing
the last grant keeps the registration but prevents new tokens (`invalid_scope`). Rotating a secret
stops the old secret immediately; existing tokens remain usable until expiry, with the grants the
service currently holds.

| Answer | When |
|---|---|
| `428` | `PUT …/grants` without `If-Match` |
| `400` | `If-Match` other than one strong `ETag` from a read, such as `*`, a list or a weak tag |
| `400` | A body other than a JSON array of strings |
| `400` | A scope a service cannot hold: `public-read` or another feature scope, an OIDC scope, a context at or above the context namespace (`SPS-AUTH-014`), or a context not registered on the pod |
| `412` | The grants changed since that `ETag`; read and review them before retrying |
| `403` | Rotating or removing an operator-provisioned registration |
| `404` | No such registration |
| `409` | Rotation or removal raced another change |

The owner can replace grants for **every service**, including operator-provisioned services. The
owner cannot rotate or remove an operator-provisioned registration; `[]` ends its access. The
operator can issue it a new secret by [provisioning again](../host-provisioning.md#provisioning-over-the-admin-surface),
which never restores grants the owner removed. The registration goes only when its pod is deleted.

## Further reading

- [Client management API](../../../sempods-client/src/main/kotlin/org/sempods/client/SempodsPodServiceClients.kt): fields, errors and retry rules.
- [Service credential store](../../src/main/kotlin/org/sempods/pods/oauth/serviceclients/PodServiceClientStore.kt): validation, secret storage and revocation contracts.
- [OAuth operations](operations.md): rate limits, discovery and audit retention.
- [Host provisioning](../host-provisioning.md): initial setup by the host operator.

<!-- doc-examples: checked -->
