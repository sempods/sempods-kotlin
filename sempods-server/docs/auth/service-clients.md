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

An optional `redirect_uri` must have been registered by the service. The callback carries `state`
and `iss`, the pod URL, but no code, secret or approved grants. Check both.

| Case | Answer |
|---|---|
| Confirmed | Redirect with `state` and `iss`, or a page telling Alice to return to the program |
| Cancelled | Redirect with `error=access_denied`, `state` and `iss`, or that page |
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

## `did:web` services

A service that is the client of many pods can authenticate at every one of them with one key pair
and hold no secret. Its identity is a `did:web` identifier, such as `did:web:console.example`, and its public
keys are in the DID document at `https://console.example/.well-known/did.json`.

It [registers with a software statement](oauth.md#registering-a-didweb-service) and gets no secret.
Alice activates it in the [consent](#consent) as any other service. For a token, it signs a client
assertion (RFC 7523 §2.2, `private_key_jwt`) and sends it in the form, with no `Authorization` header:

<!-- doc-example: illustrative; route and form checked against DidWebServiceClientHttpTest -->
```http
POST {podUrl}/_system/auth/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer
&client_assertion=eyJhbGciOiJFUzI1NiIsImtpZCI6ImRpZDp3ZWI6Y29uc29sZS5leGFtcGxlI2tleS0xIn0…
```

| Assertion | Requirement |
|---|---|
| Header | `alg` RS256, PS256 or ES256; `kid` the DID URL of the key, such as `did:web:console.example#key-1` |
| `iss`, `sub` | The `did:web` identifier |
| `aud` | The token endpoint or the pod URL, as one value |
| `exp` | At most five minutes ahead |
| `jti` | Required; each value is accepted once |

The key has to be a `publicKeyJwk` method named under the document's `authentication`, with the
`did:web` identifier as its `controller`. An `alg`, `use` or `key_ops` it publishes holds. Any other
assertion, and one for a service not registered on the pod, gets `invalid_client`. HTTP Basic and
an assertion together are `invalid_request`.

The pod reads the document only for a registered service or a registration, and keeps it for five
minutes. At most eight reads run at once. Past that, registration and token requests that need a read answer
`429 slow_down` with `Retry-After`, and nothing about the service is decided. An assertion with a key the cached document does not name reads it again, at most once a
minute, so a new key works within a minute of publishing it. A removed key stops working when the
cached document expires.

The owner manages a `did:web` service as any other. It has no secret, so rotating one answers
`409`. Each registration's grants version starts in a range no earlier one used, so an `ETag` read before
the service was removed and registered again is `412`. Registration refuses an identifier no route could name: one with a port, such as
`did:web:console.example%3A8443`, because the server refuses the `%25` its path segment would need,
and any segment of other characters than letters, digits, `.`, `-` and `_`. Development accepts a loopback
one, such as `did:web:localhost%3A5173`, which no management route can name.

## Managing a pod as a service

A service that manages pods for their owner, such as an owner console, creates and deletes
contexts, withdraws grants and reads no data. It holds a lasting authority to manage the pod, which
the owner grants once. For Alice's pod and the console `did:web:console.example`:

| Step | Pod-relative call | What happens |
|---|---|---|
| 1. Register | [As a `did:web` service](#didweb-services) | A provisional registration with no grants |
| 2. Ask Alice | `GET /_system/auth/service-consent?client_id=did%3Aweb%3Aconsole.example&state=…&authority=manage` | The page names the authority and has no contexts to tick. Confirming grants it and activates the registration. |
| 3. Get a token | `POST /_system/auth/token` with Client Credentials, no `scope` | The token carries `contexts:manage grants:manage` |
| 4. Manage contexts | `GET`, `PUT` and `DELETE /_system/contexts…` | As with [`contexts:manage`](oauth.md#managing-contexts): every context shows as `manage` alone |
| 5. Manage grants | `GET` and `DELETE /_system/auth/grants…` | [The grants on each context](#managing-grants), and withdrawing a direct one |

Only a service registered at the pod, `svc:` or `did:web`, is offered it; for an operator-provisioned
one the page answers `403`. A host can set it up instead when it
[creates the pod](../host-provisioning.md#creating-a-pod).
The pod advertises the scopes the authority carries as `sempods_service_management_scopes` in its
authorization-server metadata. A pod without the member does not offer it.

The authority reaches no data: while the service holds it, none of its tokens reads a context,
including one the service was granted otherwise and a token it obtained before the owner granted it. `service-clients:manage` is never part of it, because that can create data access.
Every call checks the pod's current owner. After a change of owner, calls answer `403`, and the
token endpoint refuses the service `invalid_scope`, even where it also holds context grants. The new
owner sees the consent page as a fresh request, and confirming it grants the authority again. Calls
are recorded in the [service audit log](operations.md#service-audit-retention).

The authority lasts until the registration is removed:

| Who | How |
|---|---|
| Alice, in the pod's UI | The same consent URL shows "Remove service" while the service holds the authority |
| Alice's tool | `DELETE /_system/auth/service-clients/{clientId}`, [below](#managing-service-clients) |
| The service itself | `DELETE /_system/auth/register/{clientId}` with its own bearer; `204`, or `403` for another service's |

A token issued before the removal answers `401` on its next call. It names the registration it was
issued for, so it holds nothing of one the service makes again under the same identifier, and
cannot remove that one either.

## Managing grants

An owner console shows the grants on each context and withdraws them. The routes take a bearer
carrying `grants:manage`, which only the [management authority](#managing-a-pod-as-a-service)
carries; an authorization request naming it is refused `invalid_scope`. They are experimental and
specific to this implementation: [sempods-spec#137](https://github.com/sempods/sempods-spec/issues/137)
leaves an owner's grant management to implementations.

Context IRIs, WebIDs and client identifiers are path segments in base64url without padding, as on
`_system/resources`. Routes are relative to `podUrl`:

| Method and path | Result |
|---|---|
| `GET /_system/auth/grants` | For every registered context, how many `people`, `apps` and `services` reach it |
| `GET /_system/auth/grants/{context}` | Who holds grants reaching one context, grouped |
| `DELETE /_system/auth/grants/{context}/people/{webId}` | Withdraw the person's direct grants there, and the apps acting for them that those grants backed |
| `DELETE /_system/auth/grants/{context}/apps/{clientId}` | Withdraw what the owner delegated to the app there |
| `DELETE /_system/auth/grants/{context}/services/{clientId}` | Withdraw the service's direct grants there and keep its others; send `If-Match` with its `grants_version` |

The answer for one context groups its holders by person. An app acting for a person is listed under
them, because withdrawing the person withdraws it too. The owner holds no grants of their own, so
the owner's apps are a group of their own:

<!-- doc-example: illustrative; answer shape checked against PodGrantsEndpoint and PodGrantsEndpointHttpTest -->
```json
{
  "context_iri": "https://pods.example/alice/_system/contexts/notes/work",
  "people": [{
    "web_id": "https://id.example/bob",
    "permissions": ["read", "write", "manage"],
    "grants": [
      {"permission": "read", "inherited_from": null},
      {"permission": "manage", "inherited_from": "https://pods.example/alice/_system/contexts/notes"}
    ],
    "delegations": [{"client_id": "dyn:k3", "client_name": "Notes", "permissions": ["read"],
                     "grants": [{"permission": "read", "inherited_from": null}],
                     "last_used_at": "2026-11-26T09:14:00Z"}]
  }],
  "apps": [],
  "services": [{"client_id": "svc:abc", "client_name": "Notes Sync", "permissions": ["read"],
                "grants": [{"permission": "read", "inherited_from": null}],
                "last_used_at": "2026-11-25T08:00:00Z", "grants_version": 3}]
}
```

Only explicit grants are listed. A public context is readable by anyone, and withdrawing grants
leaves that as it is.

`permissions` is what the holder can do, as the [context catalogue](oauth.md#managing-contexts)
reports it. `grants` are the grants behind it. A service holding the management authority is listed
with its grants and empty `permissions`, and the counts leave it out: its tokens reach no data. A grant with `inherited_from` is a `#manage` grant
on that ancestor; here Bob's `notes#manage` reaches `notes/work`. Withdrawing Bob on `notes/work`
removes his `read` there and leaves `notes#manage` standing. Where only an inherited grant reaches
the context, the pod answers `409` with `inherited_from`: withdraw it on the ancestor, which ends it
on every context it covers.

`last_used_at` is when the holder last received a token: a service through Client Credentials, an
app a refresh token for that person. A request with a token already held does not move it. The
member is absent where the pod records nothing, such as an app that never held a refresh token.

A person known by several WebIDs is one entry: the WebIDs one sign-in recognised together, which
their delegations record. Withdrawing them by any of those WebIDs withdraws the grants under all.

A withdrawal holds on the holder's next request. A person who holds no grant reaching the context
can still be listed with delegations there, such as a former owner. Withdrawing them withdraws those
delegations on this context alone; one reaching it from an ancestor answers `409` like a grant. An
app left holding nothing loses its refresh tokens.

| Answer | When |
|---|---|
| `204` | Withdrawn |
| `400` | A segment that is not base64url, or `If-Match` other than one strong `ETag` |
| `403` | A bearer without `grants:manage`, or after a change of owner |
| `404` | No such context, or the holder reaches nothing there |
| `409` | Only an inherited grant reaches the context; `inherited_from` names the ancestors |
| `412` | The service's grants changed since that `grants_version` |
| `428` | Withdrawing a service without `If-Match` |

Withdrawing a single delegation of another person, replacing grants and adding them are not
offered. The [pod management client](../../../sempods-pod-management-client/README.md) wraps these
routes for the JVM.

## Managing service clients

The owner's tool requests `service-clients:manage` through Authorization Code + PKCE.
[Management consent](oauth.md#managing-service-clients) describes that authority, including the
lasting data access it can create.

Routes are relative to `podUrl`; encode the client ID as a path segment, such as `svc%3Aabc`.

| Method and path | Result |
|---|---|
| `POST /_system/auth/register` with the management bearer | Register an active service, initially without grants. |
| `GET /_system/auth/service-clients` | List registrations and grants, without secrets; `management_granted_at` marks one holding the [management authority](#managing-a-pod-as-a-service), while the owner who granted it still owns the pod. |
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
| `409` | Rotation or removal raced another change, or rotation for a `did:web` service, which holds no secret |

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
