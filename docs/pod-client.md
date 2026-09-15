# Pod client — the JVM client for the pod surface (IST)

What a consumer reaches for when it wants to talk to a pod it does not run: the HTTP core
`:sempods-client-core`, `:sempods-client` above it, and the sibling that speaks the host-level admin
surface, `:sempods-control-plane-client`.

This document is the *shape* of those clients — what tiers they have, how a caller supplies a
credential, what they are built on, and the rules that decide what may be added. The **routes** they
speak belong to whoever owns the surface: the pod surface to the specification
(the specification's [CRUD](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md) and [media](https://github.com/sempods/sempods-spec/blob/main/spec/modules/media.md)
chapters, [`auth`](auth)), the admin surface to the
reference implementation. Exact per-method contracts are KDoc on the classes.

Why there are two modules rather than two classes is the authority boundary, and it is stated once
in [`concepts/modularity.md`](concepts/modularity.md) §"The authority boundary": a pod offers a
graph, an addressing scheme and a permission model, and that is what a specification can describe;
hosting many pods cannot be described the same way, because at `createPod` the pod does not exist
and no `<context>#permission` scope can authorize it. The consequence a dependency declaration can
show: a consumer of the specification never adds `:sempods-control-plane-client`.

## Two representations, three bindings

The client answers the same routes in two shapes, and neither is a degraded version of the other:

| Layer | Answers with | For |
|---|---|---|
| `PodWireClient` (`org.sempods.client.wire`) | the pod's own JSON-LD as an unparsed `JsonNode`, plus the `ETag` on every read and `If-Match` / `If-None-Match` on every write | a consumer that **forwards** what the pod said — `:sempods-mcp-core`'s `PodToolExecutor` hands it to a model, for both MCP surfaces — or that needs read-modify-write to be safe against a concurrent editor |
| `SempodsClient` and the tiers below it | a parsed RDF4J `Model` over n-quads | a consumer that **reasons** over the graph and does not want to know that a slot is two base64url segments |

A forwarding consumer needs the pod's framing and `@context`: parsing to RDF and re-serialising is
lossy for it even when semantically faithful, and spends a parser round trip on an answer nobody
queries. A consumer that wants meaning should equally not be handed JSON to walk. The wire layer is
the floor and the semantic tiers the storey above it — one client, two answers.

## The tiers

The semantic side has two, and they differ only in what is fixed:

| Tier | Fixes | Bound by |
|---|---|---|
| `SempodsClient` | nothing — base URL and token per call | callers that hold a URI and no pod: an aggregator dereferencing a foreign event, an outbound guard vetting an address before it connects — and the token mint, which cannot go through a client that needs a token |
| `SempodsPodClient` | one pod, one `SempodsAuth` | everything that reaches *a* pod: an application gateway building one per pod per request, and the reference implementation's own suite, which seeds through it like any other client |

**A consumer takes one of them.** The bound tier is the stateless one with a coordinate fixed, so
taking it removes an argument rather than adding a layer.

`SempodsHttpTransport` sits under both: the legacy surface, with a token stamped on each request and
the JSON helpers (`objectMapper`, `requiredText`) the core does without. It runs on a client
`SempodsOkHttp.install` configured, so the guard and the redirect policy have one implementation, and
it sends no session's requests, so the session's authentication, resend and admission do not apply.
It hands the tiers the failure shape they classify on (`SempodsClientException`, carrying the
server's own body). Moving the tiers onto `SempodsSession` is
[#150](https://github.com/sempods/sempods-kotlin/issues/150) and
[#152](https://github.com/sempods/sempods-kotlin/issues/152).

**A pod is addressed by its base URL, and nothing here addresses one by name.** A consumer serving
many pods resolves its own names and builds one bound client per pod; where the names come from is a
question only that consumer can answer. The busiest consumer, the hosted MCP service, keys pods by
base URL — `PodConnection.pod` *is* the pod base URL.

**Nothing here projects a resource onto a typed view either**, for the same reason one step further
in. A closed, compile-time predicate list belongs to whoever publishes that vocabulary, not to a
library about pods in general — so what this client offers is the pod's own terms (a resource, a
slot, a context, a query) and a consumer that wants views builds them on top.

**The single 401 retry is why the bound tier exists** rather than being a convenience over the
stateless one. Retry-once-after-invalidating is a property of a bound client with a *refreshable*
credential: only something that can ask its credential for a fresh token can tell a rotated one from
a refused one. A caller passing a token per call cannot, and fails a 401 that a second attempt would
have satisfied. `SempodsAuth` is that credential — a token supplier plus `invalidate`, where `null`
is anonymous and supported rather than degraded, because reading a pod's public contexts needs no
credential at all.

## Growing the surface — one tier at a time

The rule that keeps a layered client from multiplying its method count by the number of tiers:

- The **stateless tier may carry both representations of a route** — `sparqlSelect` → `String`
  (SPARQL-Results JSON verbatim) beside `sparqlConstruct` → `Model`.
- The **bound tiers keep the typed one.** A representation that exists only to be parsed by the
  caller belongs where the caller already assembles the request.
- The **raw path is one generic method, never a twin per route.** `SempodsPodClient` offers
  `sparqlSelect(query)` and `sparqlConstruct(query)` and knows nothing about either question; it
  does not, and must not, grow a `findRaw` or a `describeRaw` beside them.
- A **typed method replaces the raw call at its tier instead of joining it**, and arrives when a
  caller needs it rather than upfront.

*The test:* a new pod route can be added at one tier without forcing a method at the others.

The System-layer resource route is what that test looks like when it is applied. `putSubject`,
`getSubject` and `deleteSubject` reach `{pod}/_system/resources/{b64url(iri)}` from the stateless
tier and from nowhere else; the bound tier grew nothing, because the consumer that asked for them
mints a token per tenant and takes the stateless tier anyway. The core draws the same line between
two groups, `resources()` and `subjects()` (§"Endpoint groups"). `SempodsPodClient.delete` still
clears an external subject predicate by predicate through `putSlot`; one `subjects().delete` per
context replaces that loop when the tiers move onto the core
([#152](https://github.com/sempods/sempods-kotlin/issues/152)).

The raw passthrough is not a concession. [`concepts/modularity.md`](concepts/modularity.md) §"The service contract is
semantic, not a facade over RDF" forbids a method whose *name* encodes an app's question — a
`findReferencingMedia` would move one app's rule into every pod — and a generic passthrough is that
rule's positive form: the app's rules stay in its query text, and what the client offers is the
endpoint. What a pod will accept is bounded on the server rather than by convention:
`SparqlQueryService` rejects every Update form and refuses `SERVICE` anywhere in the algebra, which
is also what makes a query safe to re-run under the 401 retry and after a lost connection.

**Why the bound tier still carries a raw `sparqlSelect` today:** there is no typed result to replace
it with. `SparqlResult` (`org.sempods.spec`) carries matched IRIs plus the model behind them, and
a consumer's keyset pagination needs the exact lexical of the sort key `?k` out of the bindings —
a value no resource-shaped result type carries. The typed method
arrives with the caller that can use it.

Two typed forms already stand beside it and show what "arrives with the caller" looks like:
`sparqlSelectColumn` for a one-column question, and `sparqlSelectStatements` for a
`SELECT ?s ?p ?o ?g` read back as statements **with the graph each came from** — the shape a caller
projecting a context reads by, and the reason it is not `sparqlConstruct` (which drops the context).
Both arrived when a caller needed them, and neither replaced the raw method, because neither answers
the pagination question above.

## The core: a pod, a credential, and OkHttp

`:sempods-client-core` is the pod's HTTP surface without an RDF representation (§"Consumable as an
artifact"). The request, the call and the response are OkHttp's: build an `OkHttpClient`, build a
`Request`, call it, read the `Response`, close it. What this module adds is what OkHttp has no
opinion about, and it adds it to the consumer's own client.

| | |
|---|---|
| `SempodsPodBase` | the base URL rules, and containment |
| `SempodsSession` | one pod and one credential; `newRequest` builds a request only a sempods client can send |
| `SempodsOkHttp` | `install` puts the policy on an `OkHttpClient.Builder`: confinement, authentication per attempt, resend, admission, the guard |
| `SempodsRequestAuth` | how a session authenticates, replaceable and decoratable |
| `SempodsAdmission` | how many calls may run, and how many may wait |
| `SempodsUrlPolicy` / `SempodsOutboundGuard` | the two address layers |

```java
OkHttpClient client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
var alice = new SempodsSession(SempodsPodBase.of("https://pods.example/alice"),
    SempodsRequestAuth.apiKeyHeader("X-Api-Key", key));

var request = alice.newRequest("GET", "_system/contexts")
    .header("Accept", "application/json")
    .build();
try (Response response = client.newCall(request).execute()) {
  String contexts = response.body().string();
}
```

`newRequest` plus a call on such a client is also the **extension seam**: an endpoint group, a
protocol module or a consumer's own route gets authentication, confinement, the guard, the deadline
and admission by using it, and needs nothing private.

Four decisions shape everything above it. Each lives in one class, whose KDoc carries the contract:

- **A credential never leaves its pod** (`SempodsPodBase`, `SempodsSession`). The base is validated
  against [`SPS-CORE-019`](https://github.com/sempods/sempods-spec/blob/main/spec/core/index.md#SPS-CORE-019)
  and [`SPS-CORE-020`](https://github.com/sempods/sempods-spec/blob/main/spec/core/index.md#SPS-CORE-020);
  every call is confined before the first attempt and again on the request about to be written; and
  authentication may set headers only.
- **A session's request needs the policy to go out** (`SempodsSession`). It carries the placeholder
  host `sempods-session.invalid` until the client's interceptor binds it to the pod, so a plain
  `OkHttpClient` cannot resolve it and never sends it anonymously.
- **The attempts belong to one call** (`SempodsOkHttp.install`). Each is authenticated afresh. A
  lost connection earns one resend for an idempotent method or a request marked `SempodsRepeatable`
  ([RFC 9110 §9.2.2](https://www.rfc-editor.org/rfc/rfc9110#section-9.2.2)), and a 401 a refreshable
  credential can answer earns one retry. `callTimeout` and `Call.cancel()` cover them all.
- **Capacity is explicit** (`SempodsAdmission`): active and waiting calls are bounded separately, for
  every running call on the client.

### Endpoint groups

`SempodsPod` carries the endpoint groups, built on the extension seam above. Every operation returns
the raw body, and a typed result where the core reads the route's document; both run the same call:

```java
var pod = new SempodsPod(alice, client);
SempodsResponse<SempodsPodDateModified> typed = pod.metadata().dateModified();
SempodsResponse<String> raw = pod.metadata().dateModifiedJson();

SempodsSparqlResults rows = pod.sparql().select("SELECT ?s WHERE { ?s ?p ?o }").getBody();
boolean any = pod.sparql().ask("ASK { ?s ?p ?o }", SempodsContextSelection.of(tasks)).getBody();

pod.resources().put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of(jsonLd), SempodsWriteOptions.inContext(tasks));
String bob = pod.subjects().getText("did:web:bob.example").getBody();
pod.slots().add("did:web:bob.example", knows, SempodsContent.of(carolRef), SempodsWriteOptions.inContext(tasks));
```

`resources()` reaches an IRI under the pod by its own path, `subjects()` any IRI through the System
route; both read, replace, merge-patch and delete. `slots()` works on the values of one predicate of a
subject: read, replace, add, clear, and remove one IRI value through its edge.

A status the route does not list, or a body that is not the route's document, is an exception that
keeps the status and headers and never quotes the body. §"Growing the surface" is the rule for the
tiers of `:sempods-client`.

**A read can be narrowed to contexts, and a write names its context.** The selection is optional. A
query carries it as the SPARQL Protocol's dataset parameters (`SempodsPodSparql` says how), which a
pod may leave unsupported ([`SPS-SPARQL-011`](https://github.com/sempods/sempods-spec/blob/main/spec/core/sparql.md#SPS-SPARQL-011)):
one that ignores them answers from everything the session may read, and a client cannot tell which
kind it faces. A resource or slot read carries it as `context` parameters, and `SempodsPodResources`
says what an empty one does. Every write takes its target context in `SempodsWriteOptions`.

## The transport: OkHttp, blocking

**Blocking**, because:

- a Kotlin library exposing `suspend` functions exposes `Continuation` to Java callers, and a
  specification client is precisely the artifact a foreign JVM implementation consumes;
- on Java 25 a blocking send on a virtual thread costs no thread per request. `sempods-mcp` is
  `suspend` throughout, fans out over every connected pod at once, and bridges in about forty lines
  (`PodIo`) — one virtual thread per in-flight request, no carrier thread held.

**OkHttp**, because SSRF **resolve-and-pin** needs a hook at the moment an address is produced. The
JDK client's only one is `InetAddressResolverProvider`, which replaces the resolver for the whole
JVM — not something a library may do to its consumer. OkHttp's `Dns` hook is one line.

**On `api`.** `SempodsOkHttp.install` configures an `okhttp3.OkHttpClient.Builder` and
`SempodsSession` hands out an `okhttp3.Request.Builder`; a consumer compiles against both, adds its
own interceptors, shares the connection pool and keeps everything else OkHttp offers. What that
costs:

- **OkHttp's major version is part of this module's ABI.** Square keeps binary compatibility for
  non-alpha APIs, and the surface used here — `Request`, `Response`, `Call`, `Interceptor`,
  `OkHttpClient.Builder` — is its oldest and most stable part. A major can still move a coordinate:
  OkHttp 5 publishes `okhttp` as `okhttp-jvm`.
- **A version to keep**, pinned explicitly in the catalog.
- **The guard is as strong as the client it is installed on.** A consumer can always build a plain
  `OkHttpClient` and reach a host this library would refuse, but not by accident: a session's
  request does not resolve there. On an installed client the guard pins the resolver and the no-proxy
  setting for every call and refuses a client that follows redirects; `SempodsOkHttp.install` says
  where a consumer's own interceptors go.

### Tracing

The standard on the wire is [W3C Trace Context](https://www.w3.org/TR/trace-context/), and
OpenTelemetry is the standard way a JVM produces it. The core needs a dependency for neither,
because the tracer goes on the consumer's own client:

- **OpenTelemetry's OkHttp library** wraps that client: `createCallFactory` over a client
  `SempodsOkHttp.install` configured derives from it and keeps the sempods interceptors. A call
  factory over a plain client fails a session's request — the placeholder host above.
- **An instrumentation that ships as an interceptor**, or a header of the consumer's own naming, goes
  on the same builder. The services' own binding, `TraceparentInterceptor`, is one
  ([`request-tracing.md`](request-tracing.md)); the core reads no ambient trace.

Each attempt is a `Chain.proceed` inside the one call, and OpenTelemetry's span sits in a network
interceptor, so a retry is a client span of its own, as OpenTelemetry's HTTP semantic conventions
ask. `:consumer-probe:opentelemetry` checks the first path against the SDK.

### Two OkHttp clients in one process, on purpose

A JVM running both this client and `sempods-commons-okhttp`'s holds two `OkHttpClient` instances,
and they are not merged by default. A consumer that wants one pool says so with
`SempodsOkHttp.install(theirs.newBuilder())`.

Merging would buy little. A second client costs **no threads** — `TaskRunner.INSTANCE` is a JVM-wide
daemon singleton every `ConnectionPool` shares, and the dispatcher's executor has `corePoolSize = 0`
and is only fed by `enqueue`, which neither client uses. It saves **no sockets** either: the two dial
disjoint hosts, pods here and the id-server, the model provider and caller-chosen media sources
there. What is left is a builder graph and a connection pool object, in the low kilobytes.

### The guard

`SempodsOutboundGuard` is opt-in: a client installed without one dials whatever it is given. A
guarded one gets **two address layers, and neither is redundant**:

- `SempodsUrlPolicy.rejectTarget`, per request, before the call. This layer catches an IP literal —
  an engine handed `http://169.254.169.254/` has nothing to resolve and never asks a `Dns` hook.
- `VettingDns`, inside the connection path, vetting every resolved address. This layer closes
  rebinding, because resolving and connecting become one event.

Plus `Proxy.NO_PROXY`: with a proxy configured the *proxy* resolves the hostname and the DNS hook is
never consulted, so a JVM system property would otherwise switch the whole defense off.

Admission for a stored *coordinate* has entry points of its own — `rejectPodBase` for a base URL,
`rejectCredentialedTarget` for an endpoint that receives a credential but is not a base. One range
table stands behind all of them (`SempodsUrlPolicy`); where the two guards it replaced disagreed, the
stricter reading won, so the NAT64 prefixes are refused outright.

## What the client is not

- **Not two clients.** The JSON-LD wire layer and the RDF tiers are two layers of one client
  (§"Two representations, three bindings"). `sempods-mcp` keeps only `PodIo`, the bridge; the tool
  calls live in `:sempods-mcp-core`, where both MCP surfaces read them. The two layers read their
  routes from `org.sempods.commons.net.SempodsPodRoutes`, and the core's endpoint groups own theirs;
  `SempodsPodRoutesParityTest` holds the shared ones equal until #152 moves the layers onto the core
  and removes their copies.
- **The stateless `dereference` does not become pod-bound.** It takes an arbitrary foreign URI with no
  pod base and no token. That is the stateless tier, permanently.
- **No coroutine surface.** OkHttp's `enqueue` carries the core's policy as `execute` does; a
  `suspend` consumer bridges at its own edge, and `sempods-mcp`'s `PodIo` is what that costs: a
  virtual-thread executor, a cancel handle, and the caller's trace carried across the hop. Two things
  a bridge must get right: `Thread.interrupt()` does **not** unblock an OkHttp read (Okio clears the
  flag), so cancellation goes through the call; and `Job.invokeOnCompletion` fires when the job
  *finishes*, which for a blocking body is after the wait it was meant to cut short —
  `invokeOnCancellation` fires in time.
- **No in-process client.** A consumer inside the server takes `PodFacade` / `SempodsFacade`; a
  second path into a pod is one no client could take.

  The pod server's MCP endpoint takes the *client* instead, and that follows the rule: it dials the
  pod's public base URL, over the reverse proxy, with the caller's own bearer — the path an external
  client takes — so the pod-immanent and hosted MCP surfaces are exercised the same way. What it
  costs is in [`mcp/endpoint.md`](mcp/endpoint.md#how-a-tool-call-reaches-the-pod).

## Consumable as an artifact

RDF4J's model artifact is declared `api` by `:sempods-model` and `:sempods-client`, so a build that
depends on `:sempods-client` alone can name the `Model`, `IRI`, `Resource` and `Value` its public
methods return and accept. Rio, Sail and the SPARQL-results readers stay `implementation`. The in-repo
consumers declare no RDF4J of their own, which is the check that the export is real.

`:sempods-client-core` is the coordinate for a consumer that only speaks HTTP. It resolves no RDF4J,
Jena or Jackson 2, directly or transitively; the protocol's JSON it reads with Jackson 3, which no
public signature names:

```kotlin
implementation(platform("org.sempods:sempods-bom:0.2.0"))
implementation("org.sempods:sempods-client-core")
```

`:consumer-probe:client-core` checks that from outside the build, as a Java consumer on Java 21 —
[`concepts/modularity.md`](concepts/modularity.md) §"Open-source readiness".

The [client redesign](https://github.com/sempods/sempods-kotlin/issues/116) still owns the endpoint
groups beyond pod metadata, SPARQL, resources, subjects and slots ([#148](https://github.com/sempods/sempods-kotlin/issues/148)), the RDF
adapters ([#150](https://github.com/sempods/sempods-kotlin/issues/150)), Java async
consumption ([#151](https://github.com/sempods/sempods-kotlin/issues/151)) and the migration of the
tiers above ([#152](https://github.com/sempods/sempods-kotlin/issues/152)). API narrowing for the
independently embeddable services belongs to
[#15](https://github.com/sempods/sempods-kotlin/issues/15).

## Authority and deployment

Pod operations use `sempods-client` and a pod credential. Host administration uses
`sempods-control-plane-client` and a host credential. Creating a pod requires the latter,
because the pod and its context authority do not exist yet. The proposed owner and
operator interfaces preserve this split; their [deployment design](proposals/deployment-profiles.md)
and [owning issue](https://github.com/sempods/sempods-kotlin/issues/139) carry target scope.

## Contract source

- `sempods-client-core/src/main/kotlin/org/sempods/client/core/` — `SempodsSession`,
  `SempodsOkHttp`, `SempodsRequestAuth`, `SempodsPodBase`, `SempodsAdmission`, and
  `net/` for the outbound guard
- `sempods-client/src/main/kotlin/org/sempods/client/` — `SempodsClient`, `SempodsPodClient`,
  `SempodsAuth`, `SempodsHttpTransport`
- `sempods-control-plane-client/src/main/kotlin/org/sempods/controlplane/SempodsControlPlaneClient.kt`
