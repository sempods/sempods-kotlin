# Service access

[Auth overview](../../../docs/auth/README.md) · [Delegated access](user-access.md) · [Client guide](../../../sempods-client/README.md)

Use **Client Credentials** for a backend or scheduled worker acting as itself. Alice connects
Notes Sync to her pod and allows it to update her notes. Once set up, the service gets tokens
with its own client ID and secret; Alice does not need to be online. Use [delegated access](user-access.md)
when an operation needs to act as a person instead.

## Pod URL and endpoints

`podUrl` is the full pod base URL, for example `https://pods.example/alice`.
All paths below are relative to that URL. For example, `/_system/auth/token` means
`https://pods.example/alice/_system/auth/token`. Use the URLs from the pod's OAuth metadata
when available.

## Registration and consent

Notes Sync registers itself on Alice's pod, then asks Alice to choose its contexts. For her context
`https://pods.example/alice/_system/contexts/notes`, setup is:

| Step | Pod-relative call | What happens |
|---|---|---|
| 1. Register Notes Sync | `POST /_system/auth/register`, without a bearer | Returns a provisional `svc:…` ID, a secret and an activation deadline. Save them securely. |
| 2. Ask Alice | `GET /_system/auth/service-consent?client_id=svc%3Aabc&state=…` | Alice signs in and selects `notes` with write permission. Confirming activates the service. |
| 3. Get a token | `POST /_system/auth/token` with Client Credentials | The service authenticates with its ID and secret. |
| 4. Check access | `GET /_system/contexts` with that bearer | Notes is visible. Private contacts are not. The service can now attempt its notes operations. |

Use the returned client ID in step 2. Registration sends `client_name`,
`grant_types: ["client_credentials"]` and `token_endpoint_auth_method: "client_secret_basic"`.
The secret is returned once. Without activation within 24 hours, the registration expires;
use the response's `activation_expires_at` deadline. Repeating registration creates another service.

The [JVM setup example](../../../sempods-client/docs/client.md#registering-a-service-client)
shows registration and a bounded wait. [ServiceConsent.java](../../src/test/java/org/sempods/example/ServiceConsent.java)
provides a complete program, including credential storage and an optional loopback callback;
its [HTTP test](../../src/test/kotlin/org/sempods/example/ServiceConsentExampleHttpTest.kt) runs against a pod.

### Consent

The owner selects the complete explicit grant set. **Confirming replaces it; confirming an empty
selection removes every grant and keeps the registration active. Cancelling changes nothing.**
Existing grants are preselected. Alice can choose existing contexts or create a private context.
For a worker that creates its own child contexts, she can grant `sempods-syncer#manage` on a
context with that name; it reaches that subtree, not her entire pod.

The consent URL proposes no contexts. A service name is self-declared: Alice should compare the
client ID on the consent screen with the one her program shows.

An optional `redirect_uri` must have been registered by the service. The callback carries `state`
and, on cancellation, `error=access_denied`; it carries no code, secret or approved grants. Check
`state`. Without a callback, the pod tells Alice to return to the program.

### Check the result

A token alone does not prove that the requested access exists. A service already reading contacts
can mint one before Alice approves notes. Check the context catalogue, then the operations the
service actually needs; catalogue visibility alone does not prove write or manage permission.

| Result | Action |
|---|---|
| `400 invalid_scope` when minting | No grants yet, or an active registration with an empty grant set. Wait with a time limit. |
| Token obtained, needed context missing | Back off and check again within that limit. |
| `401 invalid_client` | Stop: registration expired or was removed, or the credential is wrong. |
| `429 slow_down` | Respect the rate limit before retrying. |

A headless service cannot distinguish cancellation, an empty confirmation and an unfinished dialog.
There is no per-consent status endpoint. [SempodsServiceAccessWait](../../../sempods-client/src/main/kotlin/org/sempods/client/SempodsServiceAccessWait.kt)
checks catalogue visibility with bounded waiting and cancellation.

This is an experimental 0.2 extension. The [registration profile](oauth.md#registering-a-service-client)
and [proposed consent profile](https://github.com/sempods/sempods-spec/issues/123) track the specification deviations.

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
it does not renew proactively by expiry. `attempt.calls(http)` keeps token acquisition within
the original call's admission and cancellation boundary. The
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
`svc%3A…`. The JVM client handles this. Tokens last 10 minutes and have no refresh token;
request a new token with the same service credential. Do not send `scope` in this request:
token down-scoping is unsupported and returns `invalid_scope`.

Context grants stay on the pod and are checked on each request. They never travel in the token.

## Managing service clients

The owner's tool requests `service-clients:manage` through Authorization Code + PKCE. This
one-hour authority can register services and assign their access. **It can create lasting data
access:** the tool can obtain a service secret, grant contexts and use that service after its own
authority expires. See [management consent](oauth.md#managing-service-clients).

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

A grant replacement takes effect on the next request, including for existing tokens. If another
change won first, replacement returns `412`; read and review the current grants before retrying.
Removing the last grant keeps the registration but prevents new tokens (`invalid_scope`).
Rotating a secret stops the old secret immediately; existing tokens remain usable until expiry,
with the grants the service currently holds.

The owner can replace grants for **every service**, including operator-provisioned services.
Only the operator can rotate or delete an operator-provisioned registration; the owner can remove
its access with an empty grant set. [Host provisioning](../host-provisioning.md) never restores
grants the owner removed.

## Further reading

- [Client management API](../../../sempods-client/src/main/kotlin/org/sempods/client/SempodsPodServiceClients.kt): fields, errors and retry rules.
- [Service credential store](../../src/main/kotlin/org/sempods/pods/oauth/serviceclients/PodServiceClientStore.kt): validation, secret storage and revocation contracts.
- [OAuth operations](operations.md): rate limits, discovery and audit retention.
- [Host provisioning](../host-provisioning.md): deployment-specific initial setup by the operator.

<!-- doc-examples: checked -->
