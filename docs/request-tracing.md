# Request Tracing (IST)

How a single request stays identifiable across processes, threads and log lines.

The mechanism is W3C Trace Context (`traceparent`, https://www.w3.org/TR/trace-context/).
This document describes what runs today; open items live as `// TODO` at the code locations
that would have to change.

## What is carried

One header, four fields:

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
             ─┬  ──────────── trace id ───────── ───── span id ──── ─┬
        version                                                   flags
```

The **trace id** identifies the journey and never changes while a request travels — it is the
value to correlate on. The **span id** identifies one hop; every caller mints a fresh one before
it sends. The flags byte carries only the `sampled` bit, and generated traces set it: nothing
here samples, so claiming otherwise would tell a future collector to discard them.

`tracestate`, the companion header for vendor data, is not forwarded.

## Where the binding lives

`sempods-commons/src/main/kotlin/org/sempods/commons/trace/` — [`TraceContext`](../commons/src/main/kotlin/org/sempods/commons/trace/TraceContext.kt)
(parse, format, `newChild`) and [`TraceContextHolder`](../commons/src/main/kotlin/org/sempods/commons/trace/TraceContextHolder.kt)
(the per-thread binding).

It sits in `sempods-commons` rather than in any one service because `sempods-client` must read it and
depends on neither a framework nor Guice. Every module binds and reads the trace through
`TraceContextHolder` directly; a facade in front of the holder would say nothing the holder does
not.

The holder writes the trace id into the SLF4J MDC in the same call that binds it, so the log
label and the outgoing headers cannot disagree. The shared `logback-base.xml` renders the MDC via
`[%X]` (see [`logging.md`](logging.md)).

Storage is a `ThreadLocal`, deliberately — see the note in `TraceContextHolder`'s KDoc. The Ktor
services keep that same holder rather than a second, coroutine-shaped binding: `TraceContextElement`
in `sempods-commons-ktor` is a `ThreadContextElement` that re-binds the holder on whichever thread a
coroutine resumes on, so `TraceContextHolder.get()` and the MDC are both correct after a suspension
point. One mechanism, two frameworks.

## Inbound

Two entry points, one behaviour.

**Ktor** — `installTraceContext()` (`sempods-commons-ktor`) intercepts `ApplicationCallPipeline.Setup` in
`sempods-auth` and `sempods-mcp`, before `CallLogging`, so its request line already carries the
trace id. It parses or starts the trace exactly as the filter below does, parks it in the call's
attributes, echoes it on the response, and wraps the rest of the pipeline in a `TraceContextElement`.
A pipeline interception rather than a `createApplicationPlugin` hook, because the binding has to
*wrap* the call and no `onCall`-style hook can do that.

**JAX-RS** — `TraceContextFilter` (registered for every JAX-RS connector in `JaxRsServerModule`) parses the
incoming header, or starts a fresh trace when it is absent — or malformed, which the spec says
to treat identically. The received context is adopted as-is rather than opened as a child span:
nothing records spans here, so a parent chain would be written and never read.

It is `@PreMatching`, and so is `ContainerRequestHolderFilter` beside it. A 404, 405 or 406 is
decided *during* matching, so a post-matching filter never runs for one and the refusal would
reach `ApiExceptionMapper` with neither a trace nor a request to name — which is what made 29
`406`s in 25 hours of production unattributable. Both filters read nothing that matching produces,
so running earlier costs nothing.

The filter echoes the trace back on the response and releases the binding there; request threads
are pooled, so a trace left bound would leak into the next request. `PodResourceEndpointHttpTest`'s
406 case is the pin for both halves at once: the echo on a matching failure is simultaneously the
evidence that the trace was bound before matching and that the response filter still released it.

Browser clients need the header through CORS in both directions — `CorsFilter.allowedHeaders`
for the preflight, `exposedHeaders` so the echo is readable.

## Outbound

Three paths, because three HTTP clients are in use:

- **OkHttp** — `TraceparentInterceptor` (`sempods-commons-okhttp`), installed once in `OkHttpClientModule`,
  covers every call on the shared client `sempods-server` composes. An *application* interceptor,
  not a network one: the trace lives in a `ThreadLocal`, and only the application layer is
  guaranteed to run on the thread that called `execute()`.
- **Ktor client** — `TraceparentClientPlugin` (`sempods-commons-ktor`), installed on `sempods-auth`'s OIDC
  client, which is its only production installation. It reads the ambient trace from the holder,
  correct inside a call because the server-side interceptor bound a `TraceContextElement` for its
  coroutine; outside a call there is no ambient trace and no header goes out.

  **`sempods-mcp` installs no client plugin at all**, which is worth saying because the module runs
  on Ktor and the assumption goes the other way. Both of its outbound legs go through
  `SempodsHttpTransport` below — the pod calls, and the identity provider's discovery, JWKS and
  token requests (`SempodsMcpModule.identityProvider` wraps it in `SempodsClientHttpTransport`).
  Its bridge, `podIo`, carries the trace across the thread hop explicitly: it reads
  `TraceContextHolder.get()` on the caller's thread — where the coroutine's `TraceContextElement`
  has it bound — and re-binds it around the blocking call on the virtual thread, which the element
  alone does not reach. `PodIoTest` pins that, together with cancellation reaching the socket and a
  fan-out running concurrently.
- **`SempodsHttpTransport`** (`sempods-client`) — OkHttp, but its own client rather than
  `sempods-commons-okhttp`'s, so the interceptor above does not reach it; it sets the header in
  `newRequest` instead. Its `newRequest(uri)` is the single door for both clients built on it — `SempodsClient`
  and `SempodsControlPlaneClient` — and a request built any other way silently ends the trace.

All of them send `TraceContext.newChild()`, so the trace id carries and the span does not.

**An explicit `traceparent` beats the ambient one on the interceptor paths only.** The OkHttp
interceptor and the Ktor plugin both check for the header and leave a request that already carries
one alone. `SempodsHttpTransport` does not: `newRequest` sets the ambient header before a caller can
add anything and the builder *appends* rather than replaces, so an explicit `traceparent` there goes
out **beside** the ambient one and the receiver sees two. No caller in the tree does that today —
the `// TODO:` sits at `newRequest`, because the fix is replacement semantics on the builder and a
test, not a sentence here.

## Across threads

Neither a platform thread pool nor a virtual-thread executor inherits the binding. Where it
matters, the trace is captured and re-bound explicitly: `podIo` in `sempods-mcp` reads the holder
on the calling thread and re-binds it inside the virtual thread it submits to. Anything else handing
work to an executor owes the same two lines.

## Scope

Propagation only. No OpenTelemetry SDK, no collector, no spans with timestamps — so there is no
waterfall view, only a shared id across log lines. A collector added later reads the header
without any change here, which is why the standard was chosen over a bespoke one.

All three services — the pod server, the identity service and the hosted MCP service — are wired. What is still open is narrower: work that starts *outside* a request
carries no trace — `sempods-mcp`'s `TokenRefreshScheduler` sweep is the one that exists today, and
its pod calls go out without a header.

## Tests

- `TraceContextTest` — parsing and validation against the spec's edge cases.
- `TraceContextFilterTest` — adoption, fresh start, MDC, echo, release.
- `KtorTraceContextTest` — the same contract for the Ktor side, plus the two things only that
  side can get wrong: the binding surviving a dispatch to another thread, and the outbound
  plugin minting a child span rather than repeating the caller's.
- `SempodsClientHttpTest` — the header on the wire, and its absence outside a request.
