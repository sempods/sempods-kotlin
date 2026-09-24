# Pod client — the JVM client for the pod surface (IST)

What a consumer reaches for when it wants to talk to a pod it does not run: the HTTP core
`:sempods-client`, its RDF4J adapter `:sempods-client-rdf4j`, the media routes
`:sempods-client-media`, and the sibling that speaks the host-level admin surface,
`:sempods-control-plane-client`.

This document is the *shape* of those clients — how a caller supplies a credential, what they are
built on, and the rules that decide what may be added. A consumer moving from 0.1.0 reads
[`migration/0.2.md`](migration/0.2.md) first. The **routes** they
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

## Two representations

The same routes answer in two shapes, and neither is a degraded version of the other:

| Layer | Answers with | For |
|---|---|---|
| The core's own text methods — `getText`, `getJson`, `graphText`, `listText` | the pod's JSON-LD as it arrived, plus the `ETag` on every read and `If-Match` / `If-None-Match` on every write | a consumer that **forwards** what the pod said — `:sempods-mcp-core`'s `PodToolExecutor` hands it to a model, for both MCP surfaces — or that needs read-modify-write to be safe against a concurrent editor |
| `SempodsRdf4jPod` (`:sempods-client-rdf4j`) | a parsed RDF4J `Model`; a resource, subject, slot or registry read keeps every statement's context, while a `CONSTRUCT` or `DESCRIBE` answers triples, as SPARQL does | a consumer that **reasons** over the graph and does not want to know that a slot is two base64url segments |

A forwarding consumer needs the pod's framing and `@context`: parsing to RDF and re-serialising is
lossy for it even when semantically faithful, and spends a parser round trip on an answer nobody
queries. A consumer that wants meaning should equally not be handed JSON to walk. Under both is the
core, which answers the bytes the pod sent and reads none of them as RDF.

**A pod is addressed by its base URL, and nothing here addresses one by name.** A consumer serving
many pods resolves its own names and builds one session per pod; where the names come from is a
question only that consumer can answer. The busiest consumer, the hosted MCP service, keys pods by
base URL — `PodConnection.pod` *is* the pod base URL.

**Nothing here projects a resource onto a typed view either**, for the same reason one step further
in. A closed, compile-time predicate list belongs to whoever publishes that vocabulary, not to a
library about pods in general — so what this client offers is the pod's own terms (a resource, a
slot, a context, a query) and a consumer that wants views builds them on top.

## What may be added, and where

The rule that keeps three artifacts from becoming three copies of one method list:

- **A route is implemented once, in the core.** An endpoint group speaks it and answers the bytes;
  everything above changes the representation, not the request.
- **An adapter adds a body, never a route.** `SempodsRdf4jPod` runs the core's groups and decodes
  what they return; a module with a route of its own — `:sempods-client-media` — builds its request
  through the session and runs it through `SempodsExchange`, which is the same execution.
- **The raw path is one generic method, never a twin per route.** `sparql()` knows nothing about the
  question it carries; it does not, and must not, grow a `findRaw` or a `describeRaw` beside it.
- **A typed method arrives with the caller that needs it**, and replaces the raw one at its layer
  rather than joining it.

*The test:* a new pod route can be added without forcing a method on the adapters.

The System-layer resource route is what that looks like applied. `resources()` reaches an IRI under
the pod by its own path and `subjects()` reaches any IRI through `{pod}/_system/resources/{b64url(iri)}`
— two groups, because they are two routes, and the RDF4J adapter has exactly the two to match.

The raw passthrough is not a concession. [`concepts/modularity.md`](concepts/modularity.md) §"The service contract is
semantic, not a facade over RDF" forbids a method whose *name* encodes an app's question — a
`findReferencingMedia` would move one app's rule into every pod — and a generic passthrough is that
rule's positive form: the app's rules stay in its query text, and what the client offers is the
endpoint. What a pod will accept is bounded on the server rather than by convention:
`SparqlQueryService` rejects every Update form and refuses `SERVICE` anywhere in the algebra, which
is also what makes a query safe to re-run after a refused credential and after a lost connection.

**Why `sparql()` still answers raw results:** there is no typed result to replace them with. A
consumer's keyset pagination needs the exact lexical of its sort key out of the bindings, which no
resource-shaped result type carries. `SempodsSparqlResults` gives it the bindings without a parser of
its own, and the verbatim body stays beside it for a consumer that forwards the answer.

## The core: a pod, a credential, and OkHttp

`:sempods-client` is the pod's HTTP surface without an RDF representation (§"Consumable as an
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
| `SempodsForeignTarget` | a URI outside any pod, with a credential only when the call passes one |
| `SempodsPodTokens` | a pod's token endpoint: a service client's `client_credentials` grant, and redeeming an authorization code |
| `SempodsPodAuthorization` | a public client's registration, the authorization URL its user opens, with `SempodsPkce`, and the answer that comes back |
| `SempodsPodServiceClients` | a pod owner's service clients: installing one, the grant consent, and managing the ones that exist |

```java
OkHttpClient client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
var alice = new SempodsSession(SempodsPodBase.of("https://pods.example/alice"),
    SempodsRequestAuth.apiKeyHeader("X-Api-Key", key));

var request = alice.newRequest("GET", "_system/contexts")
    .header("Accept", "application/ld+json")
    .build();
try (Response response = client.newCall(request).execute()) {
  String contexts = response.body().string();
}
```

`newRequest` plus a call on such a client is also the **extension seam**: an endpoint group, a
protocol module or a consumer's own route gets authentication, confinement, the guard, the deadline
and admission by using it, and needs nothing private. `SempodsExchange` is its other half — it sends
such a request and turns the answer into a `SempodsResponse`, with the same statuses, the same 16 MiB
bound and the same failures an endpoint group answers with, so a module speaking its own route needs
neither a result type nor a failure hierarchy of its own. `:sempods-client-media` is built that way. A module that answers in another
representation, such as the RDF4J adapter (§"The RDF4J adapter"), runs the core's operation and
decodes its answer with `SempodsResponse.map`, which keeps status and headers and reports an
unreadable body as the core does.

Four decisions shape everything above it. Each lives in one class, whose KDoc carries the contract:

- **A credential never leaves its pod** (`SempodsPodBase`, `SempodsSession`). The base is validated
  against [`SPS-CORE-019`](https://github.com/sempods/sempods-spec/blob/main/spec/core/index.md#SPS-CORE-019)
  and [`SPS-CORE-020`](https://github.com/sempods/sempods-spec/blob/main/spec/core/index.md#SPS-CORE-020);
  every call is confined before the first attempt and again on the request about to be written; and
  authentication may set headers only.
- **A session's request needs the policy to go out** (`SempodsSession`). It carries the placeholder
  host `sempods-session.invalid` until the client's interceptor binds it to the pod, so a plain
  `OkHttpClient` cannot resolve it and never sends it anonymously.
- **The attempts belong to one call** (`SempodsOkHttp.install`). Each is authenticated afresh, and
  the session's `SempodsRequestAuth` is told about every answer, a successful one included. A lost
  connection earns one resend when the request that went out is idempotent or marked
  `SempodsRepeatable` ([RFC 9110 §9.2.2](https://www.rfc-editor.org/rfc/rfc9110#section-9.2.2)), and
  a 401 the mechanism claims earns one retry. `callTimeout` and `Call.cancel()` cover them all.
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

pod.contexts().create(tasks, SempodsContextCreate.fields().withLabel("Tasks"));
String catalogue = pod.contexts().listText().getBody();
try (OutputStream dump = Files.newOutputStream(path)) { pod.contexts().exportTo(tasks, dump); }
```

`resources()` reaches an IRI under the pod by its own path, `subjects()` any IRI through the System
route; both read, replace, merge-patch and delete. `slots()` works on the values of one predicate of a
subject: read, replace, add, clear, and remove one IRI value through its edge. `contexts()` reads the
registry — the catalogue a session sees, and what the registry holds for one context — and creates or
removes a context at the IRI the pod gave. Those answers are RDF, and this module reads none of it: they arrive
as the text or the bytes the pod sent, in canonical JSON-LD or N-Quads. §"The RDF4J adapter" reads them as RDF4J values.

**An answer is read into memory, up to 16 MiB — an export is not.** `contexts().exportTo` writes everything in
one context to a stream the caller owns while it arrives, and `contexts().export` hands the body to a
`SempodsBodyReader` for the lifetime of the call. It is a `CONSTRUCT` over `sparql()`, which is where a pod's
graph comes from; `sparql().graphStream` and `graphTo` are the same read for a query of the caller's own.

A status the route does not list, or a body that is not the route's document, is an exception that
keeps the status and headers and never quotes the body.

**What gets listed is decided by what an answer would mean.** A requirement binds a pod, and a client
refusing an answer makes no pod conformant — it makes one pod's deviation its caller's failure. So a
status carrying the meaning its route promises is listed even where a requirement names another one:
[`SPS-MEDIA-011`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/media.md#SPS-MEDIA-011)
requires `201` on an upload whether or not the bytes were already stored, and a pod answering `200`
has stored the media all the same, so `SempodsPodMedia` reports that answer. A status carrying some
other meaning is refused — `SempodsPodMedia.assign` leaves `404` unlisted, because there it says the
caller may not read the media, and reading it as nothing-to-do would report something untrue. The
status is on every answer, which is where a caller that wants to notice a deviation looks.

**A read can be narrowed to contexts, and a write names its context.** The selection is optional. A
query carries it as the SPARQL Protocol's dataset parameters (`SempodsPodSparql` says how), which a
pod may leave unsupported ([`SPS-SPARQL-011`](https://github.com/sempods/sempods-spec/blob/main/spec/core/sparql.md#SPS-SPARQL-011)):
one that ignores them answers from everything the session may read, and a client cannot tell which
kind it faces. A resource or slot read carries it as `context` parameters, and `SempodsPodResources`
says what an empty one does. Every write takes its target context in `SempodsWriteOptions`.

### The RDF4J adapter

`:sempods-client-rdf4j` reads and writes a pod's RDF as RDF4J values, on a `SempodsPod` that already
exists:

```java
var rdf = new SempodsRdf4jPod(pod);
SempodsResponse<Model> read = rdf.resources().getModel(event, SempodsReadOptions.of(SempodsContextSelection.of(tasks)));
Model model = read.getBody();
rdf.resources().put(event, model, SempodsWriteOptions.inContext(tasks).withIfMatch(read.getHeaders().get("ETag")));

rdf.slots().add("did:web:bob.example", knows, Values.iri(carol), SempodsWriteOptions.inContext(tasks));
List<BindingSet> rows = rdf.sparql().select("SELECT ?s WHERE { ?s ?p ?o }").getBody().getBindingSets();
```

**Only the body changes.** Each call is the endpoint group's, so a raw call and a model call on one pod
share authentication, the resend, admission and the transport, and an answer keeps its status and
headers. It reads and writes resources, subjects and slots, reads the registry and a context's export,
and answers CONSTRUCT, DESCRIBE and SELECT queries.

| Group | Reads | Writes |
|---|---|---|
| `resources()`, `subjects()` | a `Model` from N-Quads | a `Model` as JSON-LD, each statement in its context's named graph |
| `slots()` | a `Model` from JSON-LD grouped by context, the only slot form that names contexts | `Value`s as JSON-LD value objects |
| `contexts()` | a `Model` from N-Quads, for the catalogue, a description and a creation's answer; an export into an `RDFHandler` or a `Model`, each statement in the exported context | — |
| `sparql()` | a CONSTRUCT or DESCRIBE `Model`, or into an `RDFHandler`, whose statements carry no context; SELECT solutions as `BindingSet`s | — |

Every statement keeps the context the pod put it in. An ASK needs nothing here: the core's `boolean`
is what RDF4J would answer. `SempodsRdf4jResources` says what a context other than the target leads
to. **A model holds what the pod sent:** RDF4J's defaults rewrite some values and yield to JVM system
properties, so `Rdf4jCodec` sets every such setting itself. What no setting keeps is a language tag's
case through JSON-LD, which `SempodsRdf4jSlots` explains.

**A stream sorts a failure by where it came from.** What the handler throws, and an `IOException` of the
connection, reach the caller as they are; a body that stops parsing is a `SempodsDecodingException`,
after the statements before it were handed on.

### The media routes

`:sempods-client-media` carries what a pod holds beside its graph: bytes, and which contexts reach
them.

```java
var media = new SempodsPodMedia(pod);
UploadedMedia stored = media.upload(tasks, "image/png", () -> Files.newInputStream(png), Files.size(png)).getBody();
media.assign(stored.getMediaId(), notes);
```

**It writes no triple.** Whoever wants a `schema:ImageObject` writes it themselves and points its
`schema:contentUrl` at what the upload answered — the pod knows the address it is published at, and a
client rebuilding one from the id would publish the address it dialled. What the routes are, and what
a deployment without a media backend serves, is [`media.md`](media.md).

**An upload's body is opened per attempt.** `SempodsContentSource` is what a caller passes, and the
core asks it for a fresh stream for every attempt, so an upload is resent after a lost connection like
any other write. A plain stream is the other case and is sent once.

### A foreign URI

A URI no pod serves is read through `SempodsForeignTarget`, on the same client and outside `SempodsPod`:

```java
var foreign = new SempodsForeignTarget(client);
SempodsResponse<String> card = foreign.getText("https://bob.example/profile", "text/turtle");
SempodsResponse<byte[]> doc = foreign.followingRedirects(5).getBytes(id, "application/n-quads", SempodsRequestAuth.bearer(token));
```

It keeps the client's guard, deadline and admission, and nothing a session holds; its KDoc has the
contract. It is the call most likely to get a URI from someone else's request, so install the guard
(§"The guard").

`SempodsRdf4jForeignTarget` reads the same URI as RDF4J values:

```java
var rdfForeign = new SempodsRdf4jForeignTarget(foreign.followingRedirects(5));
Model profile = rdfForeign.getModel("https://bob.example/profile", List.of(RDFFormat.TURTLE, RDFFormat.JSONLD)).getBody();
```

`Accept` lists the formats in the caller's order, and for a model the answer's `Content-Type` picks the
parser; a stream parses as the one format it was given.
Turtle, N-Quads, N-Triples and JSON-LD come with the module. **A remote JSON-LD context is loaded
through the same foreign target, anonymously and at most ten per document**, so the guard and admission
hold for it too. A stream loads none, because its call still holds the admission slot.

### A service token

A service client mints its bearer through `SempodsPodTokens`, in a session of its own that carries
the client's credential. A pod session's supplier passes `attempt.calls(client)`, so the token request
runs on the call's admission slot and never carries the bearer it supplies:

```java
var base = SempodsPodBase.of("https://pods.example/alice");
var clientSession = new SempodsSession(base, SempodsRequestAuth.clientSecretBasic("notes-app", secret));
var podBearer = SempodsRequestAuth.refreshable((forceRefresh, attempt) ->
    new SempodsPodTokens(clientSession, attempt.calls(client)).clientCredentials().getBody().getAccessToken());
var pod = new SempodsPod(new SempodsSession(base, podBearer), client);
```

### Installing a service client

A program installs a service client for a pod owner in two browser round trips, and the service then
mints its own tokens (§"A service token"). The protocol is
[`auth/oauth.md`](auth/oauth.md#installing-a-service-client)'s; the lifetimes, what may be sent again
and the refusals are `SempodsPodServiceClients`' KDoc.

```java
browser.open(authorization.authorizationUrl(installer, redirectUri, "service-clients:install", state, pkce));
String code = authorization.readRedirect(query, state).getCode();
String token = tokens.authorizationCode(installer, code, redirectUri, pkce.getVerifier()).getBody().getAccessToken();

var installing = new SempodsPodServiceClients(new SempodsSession(pod, SempodsRequestAuth.bearer(token)), client);
SempodsServiceClientRegistration service = installing.register("Notes Sync").getBody();
store.save(service.getClientId(), service.getClientSecret());

browser.open(installing.grantConsentUrl(installer, redirectUri, grantState, service.getClientId(), scopes));
SempodsGrantOutcome grants = SempodsGrantOutcome.readQuery(grantQuery, grantState);
```

The client has no HTTP server: the program serves its own loopback redirect.
[`OwnerInstallation.java`](../sempods-server/src/test/java/org/sempods/example/OwnerInstallation.java)
is the whole program, and `OwnerInstallationExampleHttpTest` runs it against a pod.

### Asynchronous use

`SempodsAsync` runs blocking work away from the caller's thread, on one virtual thread per operation.
The work makes its calls through the factory it receives, so the operation's `cancel()` reaches them:

```java
var async = new SempodsAsync(client);
SempodsAsyncOperation<SempodsResponse<Boolean>> ask =
    async.submit(calls -> new SempodsPod(session, calls).sparql().ask("ASK { ?s ?p ?o }"));
ask.result().thenAccept(answer -> ...);
ask.cancel();
```

Nothing travels to the operation's thread on its own. A trace goes along with an executor that
carries it, such as OpenTelemetry's `Context.taskWrapping`, passed as the second argument.
`SempodsAsyncOperation.result` has the table of how an operation completes.

The [manual load comparison](../consumer-probe/client/docs/load.md) measures the adapter,
direct virtual-thread calls and OkHttp callbacks on Java 21 and 25, outside ordinary PR CI.

## The transport: OkHttp, blocking

**Blocking**, because:

- a Kotlin library exposing `suspend` functions exposes `Continuation` to Java callers, and a
  specification client is precisely the artifact a foreign JVM implementation consumes;
- on Java 25 a blocking send on a virtual thread costs no thread per request. `sempods-mcp` is
  `suspend` throughout, fans out over every connected pod at once, and bridges on `SempodsAsync`
  (`PodIo`) — one virtual thread per in-flight request, no carrier thread held. A Java consumer that
  wants a `CompletionStage` takes the same class (§"Asynchronous use").

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

- **Not two clients.** The text the pod sent and the RDF4J adapter are two shapes of one answer
  (§"Two representations"). `sempods-mcp` keeps only `PodIo`, the bridge; the tool
  calls live in `:sempods-mcp-core`, where both MCP surfaces read them.
- **A foreign URI stays unbound.** `SempodsForeignTarget` and its RDF4J adapter take no pod
  base (§"A foreign URI").
- **No coroutine surface.** OkHttp's `enqueue` carries the core's policy as `execute` does; a
  `suspend` consumer bridges at its own edge, and `sempods-mcp`'s `PodIo` is what that costs: a
  `SempodsAsync` operation per call, and the caller's trace carried across the hop. Two things a
  bridge must get right: `Thread.interrupt()` does **not** unblock an OkHttp read (Okio clears the
  flag), so cancellation goes through the operation's calls; and `Job.invokeOnCompletion` fires when
  the job *finishes*, which for a blocking body is after the wait it was meant to cut short —
  `invokeOnCancellation` fires in time.
- **No in-process client.** A consumer inside the server takes `PodFacade` / `SempodsFacade`; a
  second path into a pod is one no client could take.

  The pod server's MCP endpoint takes the *client* instead, and that follows the rule: it dials the
  pod's public base URL, over the reverse proxy, with the caller's own bearer — the path an external
  client takes — so the pod-immanent and hosted MCP surfaces are exercised the same way. What it
  costs is in [`mcp/endpoint.md`](mcp/endpoint.md#how-a-tool-call-reaches-the-pod).

## Consumable as an artifact

**One artifact brings RDF4J, and it is the one named for it.** `:sempods-client-rdf4j` declares
RDF4J's model, query and Rio APIs `api`, so a build depending on it can name the `Model`, `IRI`,
`Value` and `BindingSet` its methods return and accept; its parsers and writers stay
`implementation`. The in-repo consumers declare no RDF4J of their own, which is the check that the
export is real.

`:sempods-client` is the coordinate for a consumer that only speaks HTTP. It resolves no RDF4J,
Jena or Jackson 2, directly or transitively. The protocol's JSON it reads with Jackson 3, and the
OAuth client side — PKCE, the authorization request and its answer, registration metadata — with
Nimbus's `oauth2-oidc-sdk`, the library the pod's authorization server speaks. No public signature
names either, which `checkPublishedSignatures` holds; a consumer with a Nimbus of its own resolves one
version for both:

```kotlin
implementation(platform("org.sempods:sempods-bom:0.2.0"))
implementation("org.sempods:sempods-client")
```

`:sempods-client-rdf4j` is the coordinate for a consumer that wants RDF4J values on that same session. It
brings RDF4J's model, its query types and its N-Quads, Turtle and JSON-LD codecs — and with the JSON-LD
codec Jackson 2's streaming core — but no Jena, no Jackson 2 mapper and no `:sempods-model`. Slot
values it writes with the core's Jackson 3.
**It needs Java 25**, because RDF4J 6 is built for it; the core stays on 21.

`:sempods-client-media` is the coordinate for the pod's media routes. It brings the media contract
`:sempods-media` — two types and a media type string, which the pod server reads from the same place —
and no RDF library at all, so it stays on Java 21 with the core.

`:sempods-control-plane-client` is the coordinate for the host-level admin surface, and it resolves
what the core does: the admin routes' JSON is Jackson 3 behind an internal object, and no RDF library
is involved, so it stays on Java 21 too.

A probe per artifact checks them from outside the build, as Java consumers on the lowest JVM each runs
on: `:consumer-probe:client`, `:consumer-probe:client-media` and `:consumer-probe:control-plane`
on 21, `:consumer-probe:client-rdf4j` on 25 —
[`concepts/modularity.md`](concepts/modularity.md) §"Open-source readiness".

API narrowing for the independently embeddable services belongs to
[#15](https://github.com/sempods/sempods-kotlin/issues/15).

## Authority and deployment

Pod operations use `sempods-client` — with the RDF4J or media adapter where the representation
calls for one — and a pod credential. Host administration uses
`sempods-control-plane-client` and a host credential. Creating a pod requires the latter,
because the pod and its context authority do not exist yet. The proposed owner and
operator interfaces preserve this split; their [deployment design](proposals/deployment-profiles.md)
and [owning issue](https://github.com/sempods/sempods-kotlin/issues/139) carry target scope.

**Both run on the same execution:** the control-plane client is a `SempodsSession` on the server
root, with a host credential, on the same installed OkHttp client.

## Contract source

- `sempods-client/src/main/kotlin/org/sempods/client/` — `SempodsSession`,
  `SempodsOkHttp`, `SempodsRequestAuth`, `SempodsPodBase`, `SempodsAdmission`,
  `SempodsForeignTarget`, `SempodsPodTokens`, `SempodsPodAuthorization`,
  `SempodsPodServiceClients`, and `net/` for the outbound guard
- `sempods-client-rdf4j/src/main/kotlin/org/sempods/client/rdf4j/` — `SempodsRdf4jPod` and its
  groups, `Rdf4jCodec` for the pinned parser and writer settings
- `sempods-client-media/src/main/kotlin/org/sempods/client/media/SempodsPodMedia.kt` — the media
  routes over a session
- `sempods-control-plane-client/src/main/kotlin/org/sempods/controlplane/SempodsControlPlaneClient.kt`
