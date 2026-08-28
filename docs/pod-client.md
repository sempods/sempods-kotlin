# Pod client — the JVM client for the pod surface (IST)

What a consumer reaches for when it wants to talk to a pod it does not run: `:sempods-client`
(`SempodsClient`, `SempodsPodClient`, `PodWireClient`, `SempodsHttpTransport`),
and the sibling that speaks the host-level admin surface beside it, `:sempods-control-plane-client`
(`SempodsControlPlaneClient`).

This document is the *shape* of those clients — what tiers they have, how a caller supplies a
credential, what they are built on, and the rules that decide what may be added. The **routes** they
speak belong to whoever owns the surface: the pod surface to the specification
(the specification's [CRUD](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md) and [media](https://github.com/sempods/sempods-spec/blob/main/spec/modules/media.md)
chapters, [`auth`](auth)), the admin surface to the
reference implementation. Exact per-method contracts are KDoc on the classes.

Why there are two modules rather than two classes is the authority boundary, and it is stated once
in [`concepts/modularity.md`](concepts/modularity.md) §"The authority boundary outlived the types": a pod offers a
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

Preserving the pod's framing and `@context` is not fussiness: parsing to RDF and re-serialising is
lossy for a forwarding consumer even when it is semantically faithful, and it spends a parser round
trip on an answer nobody is going to query. Equally, a consumer that wants meaning should not be
handed JSON to walk. So the wire layer is the floor and the semantic tiers are the storey above it
— one client, two answers.

**The wire layer is what `sempods-mcp` used to keep its own copy of.** It carried its own routes,
its own error shape, its own SSRF guard and its own JSON handling, and the two clients drifted; what
is left in that service is a `suspend` facade over this one plus the bridge that runs it.

## The tiers

The semantic side has two, and they differ only in what is fixed:

| Tier | Fixes | Bound by |
|---|---|---|
| `SempodsClient` | nothing — base URL and token per call | callers that hold a URI and no pod: an aggregator dereferencing a foreign event, an outbound guard vetting an address before it connects — and the token mint, which cannot go through a client that needs a token |
| `SempodsPodClient` | one pod, one `SempodsAuth` | everything that reaches *a* pod: an application gateway building one per pod per request, and the reference implementation's own suite, which seeds through it like any other client |

The "Bound by" column is deliberately not "For": both entries used to describe who *might* take a
tier, and a tier with no consumer at all read as a live option for two years because of it. What
survives here has a caller behind it.

**These are alternatives, not a stack a consumer assembles.** The bound tier is the stateless one
with a coordinate fixed, so taking it removes an argument rather than adding a layer.
`SempodsHttpTransport` sits under both and holds what every request shares: the W3C trace binding,
`followRedirects(NEVER)`, the request timeout, the one error shape (`SempodsClientException`,
carrying the server's own body).

**A pod is addressed by its base URL, and nothing here addresses one by name.** There used to be a
third tier that did — resolving pod *names* through a registry interface — on a product whose
leading idea is a pod addressed by its own URL. It went, and the finding that decided it is worth
keeping: the busiest consumer of this client, the hosted MCP service, keyed pods by base URL all
along (`PodConnection.pod` *is* the pod base URL), while the name-keyed tier had exactly one
consumer, an application that is not published. **A consumer serving many pods resolves its own
names** and builds one bound client per pod; that resolution is a dozen lines, and where the names
come from is a question only that consumer can answer.

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

The raw passthrough is not a concession. [`concepts/modularity.md`](concepts/modularity.md) §"The service contract is
semantic, not a facade over RDF" forbids a method whose *name* encodes an app's question — a
`findReferencingMedia` would move one app's rule into every pod — and a generic passthrough is that
rule's positive form: the app's rules stay in its query text, and what the client offers is the
endpoint. What a pod will accept is bounded on the server rather than by convention:
`SparqlQueryService` rejects every Update form and refuses `SERVICE` anywhere in the algebra, which
is also what makes a query safe to re-run under the 401 retry.

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

## The transport: OkHttp, blocking

Recorded as a criterion rather than an opinion, so it does not get re-argued from taste. The
criterion named two conditions that would move this off the JDK client. **Both arrived**, and the
move happened; what follows is what still holds and what the change cost.

**Blocking stays**, because:

- a Kotlin library exposing `suspend` functions exposes `Continuation` to Java callers — and a
  specification client is precisely the artifact a foreign JVM implementation consumes;
- on Java 25 a blocking send on a virtual thread is what an async client used to buy, so there is
  no thread-per-request cost left to pay a concurrency framework with. `sempods-mcp` is the proof
  rather than the counterexample: it is `suspend` throughout, fans out over every connected pod at
  once, and bridges in about forty lines (`PodIo`) — one virtual thread per in-flight request, no
  carrier thread held.

**The engine is OkHttp**, because SSRF **resolve-and-pin** turned out to be a requirement of the
shared client and not of `sempods-mcp` alone. A consumer that dereferences URIs arriving in a
request vetted the host *above* the client, which is TOCTOU-weaker because the client then resolves
again —
its own comment admitted the rebinding hole and left it open. Closing it needs a hook at the moment
the address is produced, and the JDK client has none: the only injection point is
`InetAddressResolverProvider`, which replaces the resolver for the whole JVM. That is not something
a library may do to its consumer. OkHttp's `Dns` hook is, and it is one line.

What the engine costs, stated rather than hidden:

- **A third-party dependency in a Maven Central artifact**, which the JDK choice existed to avoid
  (the maintainer's internal roadmap). It is `implementation`, never `api` — the engine stops at
  `SempodsHttpTransport`, callers speak `SempodsRequest` / `SempodsResponse` / `SempodsBody`, and a
  consumer never compiles against an OkHttp type. The dependency is real; the coupling is not.
- **A version to keep**, pinned explicitly in the catalog rather than inherited from a Ktor BOM in a
  module that has no Ktor.

### Two OkHttp clients in one process, on purpose

A JVM running both this client and `sempods-commons-okhttp`'s holds two `OkHttpClient` instances. They are
**not** merged, and the reason is the bullet above rather than tidiness: `implementation` is what
makes the third-party dependency acceptable, and a constructor or factory taking an engine would
put `okhttp3` on the compile classpath of every consumer of the published artifact and tie this
library's ABI to an engine major version. Sharing a pool is not worth that.

Nor would it buy much. A second client costs **no threads** — `TaskRunner.INSTANCE` is a JVM-wide
daemon singleton every `ConnectionPool` shares, and the dispatcher's executor has `corePoolSize = 0`
and is only ever fed by `enqueue`, which neither client uses. It saves **no sockets** either: the
two dial disjoint hosts, pods here and the id-server, the model provider and caller-chosen media
sources there, so a shared pool would have nothing to reuse. What is left is a builder graph and a
connection pool object, in the low kilobytes.

If injecting one is ever genuinely needed, the note in `SempodsHttpTransport` still holds: it
arrives as an engine-neutral seam, not as the engine.

### The guard

`SempodsOutboundGuard` is opt-in: a transport without one dials whatever it is given, which is what
a consumer reaching only pods it configured itself already did. A guarded one gets **two address
layers, and neither is redundant**:

- `SempodsUrlPolicy.rejectTarget`, per request, before the call. This is the layer that catches an
  IP literal — an engine handed `http://169.254.169.254/` has nothing to resolve and never asks a
  `Dns` hook at all, so resolve-and-pin alone would let it straight through.
- `VettingDns`, inside the connection path, vetting every resolved address. This is the layer that
  closes rebinding, because resolving and connecting become one event.

Plus `Proxy.NO_PROXY`, which is not a detail: with a proxy configured the *proxy* resolves the
hostname and the DNS hook is never consulted, so a JVM system property would otherwise switch the
whole defense off.

The range table is one table (`SempodsUrlPolicy`), and it is the **union** of the two that existed
before — the dereference guard knew about the 6to4 relay anycast and the discard prefix,
`sempods-mcp` knew that
`::a.b.c.d` carries a routable IPv4 no prefix table sees. Where they disagreed, the stricter reading
won: the NAT64 prefixes are refused outright rather than by payload.

## What the client is not

- **Not two clients.** `sempods-mcp` used to keep its own — `PodApiClient`, with its own routes,
  error shape, SSRF guard and JSON handling — on the reasoning that the gap was wider than two HTTP
  libraries: JSON-LD against `Model`, ETags and preconditions against neither, merge-patch and slot
  operations against a replace-only `putSlot`, `_system/find` against nothing, `suspend` against
  blocking. That reasoning named what would reopen it: *a consumer that needs the ETag and
  merge-patch semantics and the `Model` view.* The answer was to stop treating those as competing
  and make them two layers of one client — see §"Two representations, three bindings". What the
  service kept of its own was a `suspend` facade and the bridge that runs it; the MCP consolidation
  took the facade too, so what is left there is `PodIo` — the bridge, and nothing else. The thirteen
  calls it used to wrap now live in `:sempods-mcp-core`, where both MCP surfaces read them.

  Route knowledge stays shared through `org.sempods.commons.net.SempodsPodRoutes` in `sempods-commons`,
  which is now one of several things both layers read rather than the only thing they could.

- **The stateless `dereference` does not become pod-bound.** It takes an arbitrary foreign URI with no
  pod base and no token. That is the stateless tier, permanently.
- **No coroutine or async surface.** A `suspend` consumer bridges at its own edge, and
  `sempods-mcp`'s `PodIo` is what that costs: a virtual-thread executor, a cancel handle, and the
  caller's trace carried across the hop. Note the two things a bridge must get right, both of which
  cost a test to find — `Thread.interrupt()` does **not** unblock an OkHttp read (Okio clears the
  flag), so cancellation must go through the call handle; and `Job.invokeOnCompletion` fires when
  the job *finishes*, which for a blocking body is after the wait it was meant to cut short.
  `invokeOnCancellation` is the one that fires in time.
- **No in-process client.** A consumer inside the server takes `PodFacade` / `SempodsFacade`; a
  second path into a pod is one no client could take.

  The pod server's MCP endpoint is the one consumer that takes the *client* instead, and it is the
  rule rather than the exception to it: it dials the pod's public base URL, over the reverse proxy,
  with the caller's own bearer — the path an external client takes. It used to call the services in
  process, which is exactly the second path this line refuses, and which let the pod-immanent and
  hosted MCP surfaces drift while only one of them was exercised from outside. What it costs is in
  [`mcp/endpoint.md`](mcp/endpoint.md#how-a-tool-call-reaches-the-pod).

## Consumable as an artifact

RDF4J's model artifact is declared `api` by `:sempods-model` and `:sempods-client`, so a build that
depends on `:sempods-client` alone can name the `Model`, `IRI`, `Resource` and `Value` its public
methods return and accept. Rio, Sail and the SPARQL-results readers stay `implementation` — how the
clients are written, not what they expose. The in-repo consumers therefore declare no RDF4J of
their own, which is the check that the export is real: a stranger cannot be told to compensate.

**Open (SOLL, tracked elsewhere):** `explicitApi()` for the library-shaped modules
(the maintainer's internal roadmap) —
which is also what would let `SempodsHttpTransport` say in the type system what its KDoc says in
prose, that it is public only because Kotlin's `internal` stops at the module boundary — and
published coordinates (the maintainer's internal roadmap).

## Target deployments

What each planned surface talks to, so the module boundary has an address:

| Surface | Speaks |
|---|---|
| `sempods.org/:pod` — the pod itself | the specification → `:sempods-client` |
| `sempods.org/_system/admin` — pod hosting | reference implementation → `:sempods-control-plane-client` |
| `my.sempods.org` — pod-owner self-service, federated, any conformant pod | mostly `:sempods-client`; `:sempods-control-plane-client` only for the "create a pod here" affordance against a host it is paired with |
| `admin.sempods.org` — operator panel for a deployment | `:sempods-control-plane-client` |

The asymmetry in that table is the point: the owner surface is implementation-agnostic and reaches
any pod, the operator surface is bound to one deployment. A client that mixed both would have made
the second column unwritable. Sequencing for the two planned surfaces — the owner console and
the operator panel — lives in the maintainer's internal roadmap.

## Contract source

- `sempods-client/src/main/kotlin/org/sempods/client/` — `SempodsClient`, `SempodsPodClient`,
  `SempodsAuth`, `SempodsHttpTransport`
- `sempods-control-plane-client/src/main/kotlin/org/sempods/controlplane/SempodsControlPlaneClient.kt`
