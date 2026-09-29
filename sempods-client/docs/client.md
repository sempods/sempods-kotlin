# Client design and API guide

[Quick start](../README.md) · [Transport](transport.md) · [Migration from 0.1](../../docs/migration/0.2.md)

The core speaks a pod's HTTP surface. Adapters change the representation or add an optional
protocol surface. Exact method contracts are in [KDoc](../src/main/kotlin/org/sempods/client/).

## Two representations

| Representation | Use it when |
|---|---|
| Core text and bytes | Forward the pod's JSON-LD, preserve framing and `@context`, or avoid an RDF dependency |
| [RDF4J adapter](../../sempods-client-rdf4j/README.md) | Inspect and change RDF models, values and bindings |

Parsing and serializing RDF can preserve meaning while changing JSON-LD structure. A consumer
forwarding a response should keep the original body. A consumer interpreting its graph can use
the adapter on the same session.

A pod is identified by its full base URL. Applications own any mapping from a user-facing name
to that URL, and any vocabulary-specific typed views or domain queries.

An internal caller can use `base.reachedAt(address)` while keeping the canonical pod URL for
resource IRIs, token issuers and browser URLs. Plain HTTP to a non-loopback address requires the
explicit `reachedOverPlaintextAt(address)` choice: credentials travel to that address.
[SempodsPodBase](../src/main/kotlin/org/sempods/client/SempodsPodBase.kt) owns validation.

## The core: a pod, a credential, and OkHttp

| Component | Responsibility |
|---|---|
| `SempodsPodBase` | Validate the canonical pod URL and connection address; check containment |
| `SempodsSession` | Bind one pod to one authentication mechanism; build requests |
| `SempodsOkHttp` | Install confinement, authentication, resend, admission and optional address checks |
| `SempodsRequestAuth` | Supply headers per attempt and react to responses |
| `SempodsAdmission` | Bound running and waiting calls separately |
| `SempodsExchange` | Execute extension requests with the core's result and failure handling |
| `SempodsPod` | Expose the endpoint groups |

`newRequest` is the extension point for an application-specific route. Pass caller-controlled
path values as separate segments so they are encoded and `..` is refused. Execute on the
installed client, directly or through `SempodsExchange`. A session request carries a placeholder
host until the policy binds it, so a plain OkHttp client cannot accidentally send it anonymously.

A session keeps requests inside its pod. Authentication may change headers, never the target,
method or body. Retries share the original call's deadline and cancellation. The exact rules are in
[SempodsOkHttp](../src/main/kotlin/org/sempods/client/SempodsOkHttp.kt).

### Endpoint groups

The [quick start](../README.md#find-an-operation) maps groups to tasks. Core RDF bodies remain
text or bytes; protocol JSON, such as token responses or SPARQL SELECT results, has typed readers.

A write always names its target context. A read may select contexts using `SempodsReadOptions`.
SPARQL uses protocol dataset parameters for that selection; a different pod implementation may
ignore them under `SPS-SPARQL-011`, so a client cannot use selection as an authorization boundary.
The server's grants remain that boundary.

Buffered bodies are limited to 16 MiB. For larger exports use `contexts().exportTo`,
`sparql().graphTo`, or a streaming reader. The caller owns the output stream.

Each method documents its normal statuses; other responses become `SempodsStatusException`.
For example, a resource's `404` can be an expected absence, while a media assignment's `404`
may hide unreadable media and fails. See [result handling](../README.md#handle-results).

### The RDF4J adapter

See the [RDF4J guide](../../sempods-client-rdf4j/README.md). `SempodsResponse.map` changes the body
while preserving status and headers, so an adapter needs no separate response or error hierarchy.

### The media routes

See the [media guide](../../sempods-client-media/README.md). Its routes use the same execution
policy and credential as the pod handle.

### A foreign URI

[SempodsForeignTarget](../src/main/kotlin/org/sempods/client/SempodsForeignTarget.kt) reads a URL
outside a pod, with no borrowed session credential. The caller explicitly supplies any credential
and redirect allowance. It retains the client's admission, deadline and optional outbound guard.

The [RDF4J counterpart](../../sempods-client-rdf4j/src/main/kotlin/org/sempods/client/rdf4j/SempodsRdf4jForeignTarget.kt)
parses the returned RDF. Its KDoc defines formats, remote JSON-LD context loading and streaming limits.

### A service token

The [service example](../../sempods-server/docs/auth/service-clients.md#use-the-jvm-client)
uses separate sessions for the service credential and its bearer. A credential supplier must
fetch through `attempt.calls(client)` to share the original call's admission and cancellation.

### Registering a service client

Supply an installed OkHttp `http` client, `podUrl`, and the full IRI of the `notes` context the
service needs. A headless service starts without credentials:

<!-- doc-example: sempods-client/src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt#service-register -->
```kotlin
val base = SempodsPodBase.of(podUrl)
val registering = SempodsPodServiceClients(SempodsSession(base), http)
val service = checkNotNull(registering.register("Notes Sync").body)
val state = UUID.randomUUID().toString()
val consentUrl = registering.consentUrl(service.clientId, state)
```

Save `service.clientId` and `service.clientSecret` securely before continuing. Show the owner
`consentUrl` and the client ID to compare with the consent screen. The registration expires at
`service.activationExpiresAt` unless the owner confirms it. Then wait for catalogue visibility:

<!-- doc-example: sempods-client/src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt#service-wait -->
```kotlin
val credentials = SempodsSession(base,
  SempodsRequestAuth.clientSecretBasic(service.clientId, service.clientSecret))
val waiting = SempodsServiceAccessWait(credentials, http)
val outcome = waiting.await(listOf(notes), Duration.ofMinutes(10))
```

`REACHABLE` means the catalogue lists notes; check the actual read/write operations you need.
`TIME_LIMIT` cannot distinguish cancellation from an unfinished or empty consent. `cancel()` ends
the wait. [The service guide](../../sempods-server/docs/auth/service-clients.md#check-the-result)
explains the error cases and the bearer to use for data calls.

[ServiceConsent.java](../../sempods-server/src/test/java/org/sempods/example/ServiceConsent.java)
is a complete program with credential storage, headless operation and an optional loopback callback.
The library supplies neither a callback server nor a credential store.

### Managing service access

An owner's tool can register an active service and assign grants without a dialog per service.
Supply an owner-approved `manageToken` carrying
[`service-clients:manage`](../../sempods-server/docs/auth/oauth.md#managing-service-clients),
plus `base`, `http` and a context IRI `notes`:

<!-- doc-example: sempods-client/src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt#service-manage -->
```kotlin
val managing = SempodsPodServiceClients(
  SempodsSession(base, SempodsRequestAuth.bearer(manageToken)), http)
val service = checkNotNull(managing.register("Backup").body)
val current = checkNotNull(managing.get(service.clientId).body)
val updated = managing.replaceGrants(service.clientId, listOf("$notes#read"), current.grantsVersion)
```

Save this service's secret too. Replacement is the **whole grant set**, not an addition; pass an
empty list to remove access. A `412` means the version changed: read and review before retrying.
[SempodsPodServiceClients](../src/main/kotlin/org/sempods/client/SempodsPodServiceClients.kt)
owns the operation and retry contracts. [Host provisioning](../../sempods-server/docs/host-provisioning.md)
is a deployment-specific alternative for initial setup.

### Asynchronous use

[SempodsAsync](../src/main/kotlin/org/sempods/client/SempodsAsync.kt) runs blocking work on virtual
threads. Build calls through the factory supplied to the operation so `cancel()` reaches them.
Trace context needs an executor that carries it, such as OpenTelemetry's `Context.taskWrapping`.
The [manual load comparison](../../consumer-probe/client/docs/load.md) measures the alternatives.

## The transport: OkHttp, blocking

The [transport guide](transport.md) explains the choice of OkHttp, tracing and the outbound guard.

## What the client is not

Domain models and application-specific queries belong to the application. In-process server code
uses its facades; remote callers use the HTTP client. The server's MCP endpoint deliberately uses
HTTP so both MCP surfaces exercise the same access path; see [MCP execution](../../docs/mcp/endpoint.md#how-a-tool-call-reaches-the-pod).

Kotlin coroutine adapters must cancel the HTTP call itself. Interrupting the thread alone does
not unblock an OkHttp read. The hosted MCP service's `PodIo` shows this bridge.

## Consumable as an artifact

The [module table](../README.md#choose-modules) gives dependencies and Java requirements. The
core resolves no RDF4J, Jena or Jackson 2. Protocol JSON uses Jackson 3 internally and OAuth
helpers use Nimbus internally; neither appears in the public API. OkHttp does appear there.

The RDF4J adapter exports the model, query and Rio APIs its public signatures use. Its JSON-LD
codec brings Jackson 2's streaming core, but no Jackson 2 mapper, Jena or `sempods-model`.
The media module exports the RDF-free `sempods-media` contract.

[Consumer probes](../../consumer-probe/) compile published APIs from Java and exercise their
supported JVMs. [Modularity](../../docs/concepts/modularity.md#open-source-readiness) explains the checks.

## Authority and deployment

[Host administration](../../sempods-control-plane-client/README.md) uses a session on the server
root and a host credential. A pod credential cannot create a pod that does not yet exist.
Both clients use the same execution components; their authority remains separate.

## What may be added, and where

- Implement each core route once, in an endpoint group.
- A representation adapter calls that group and maps the body; it adds no competing route.
- An optional surface, such as media, builds on `SempodsSession` and `SempodsExchange`.
- Keep generic passthroughs generic. App-specific queries belong to their applications.
- Add a typed method when a real consumer needs it; replace redundant raw methods at that layer.

For example, `resources()` addresses an IRI under the pod by its path; `subjects()` addresses
any IRI through the system resource route. The RDF4J adapter provides a model for each.
`SempodsSparqlResults` preserves binding lexicals for tasks such as keyset pagination, while
raw query results remain available for forwarding. See the
[semantic service boundary](../../docs/concepts/modularity.md#the-service-contract-is-semantic-not-a-facade-over-rdf).

## Contract source

- [Core public API](../src/main/kotlin/org/sempods/client/)
- [RDF4J API](../../sempods-client-rdf4j/src/main/kotlin/org/sempods/client/rdf4j/)
- [Media API](../../sempods-client-media/src/main/kotlin/org/sempods/client/media/SempodsPodMedia.kt)
- [Admin API](../../sempods-control-plane-client/src/main/kotlin/org/sempods/controlplane/SempodsControlPlaneClient.kt)

<!-- doc-examples: checked -->
