# Client transport

[Client guide](../README.md) · [API guide](client.md)

Install the sempods policy on an OkHttp client and reuse that client. For example, a backend
contacting several pods can share one pool while each `SempodsSession` keeps its own pod URL and
credential. The [quick start](../README.md#read-public-data) shows the setup.

## Blocking calls and OkHttp

Calls block so Kotlin and Java consumers use the same API. [Asynchronous use](client.md#asynchronous-use)
runs those calls on virtual threads and propagates cancellation to the HTTP operation.

OkHttp provides a per-client DNS hook for validating and pinning resolved addresses. The JDK
HTTP client's resolver extension changes the whole JVM, which is unsuitable for this library.

OkHttp types are part of the public API: consumers configure the builder, add interceptors and
share pools. Its version is therefore part of the module's compatibility boundary and is pinned
in the dependency catalog. [SempodsOkHttp](../src/main/kotlin/org/sempods/client/SempodsOkHttp.kt)
defines interceptor ordering, confinement, retries, deadlines and admission.

### Tracing

Attach tracing to the installed client. The core reads no ambient trace context and requires no
OpenTelemetry dependency.

- OpenTelemetry's `createCallFactory` can wrap the installed client while preserving the sempods
  interceptors. [The consumer probe](../../consumer-probe/opentelemetry/) verifies this setup.
- An interceptor can add tracing on the same builder. The services use
  [TraceparentInterceptor](../../docs/request-tracing.md).

A wrapper around a plain, uninstalled client cannot execute a session request. OpenTelemetry's
network interceptor creates a span for each network attempt, including retries.

### Two OkHttp clients in one process, on purpose

The pod client and `sempods-commons-okhttp` keep separate client instances by default. They
usually call different hosts: pods versus identity, model and media services. A caller can share
an existing pool by installing policy on `theirs.newBuilder()`.

Separate instances do not imply a thread per client: these paths use blocking calls, and OkHttp
shares its connection-pool task runner. Keeping the instances separate also keeps their request
policies explicit.

### The guard

Address filtering is **opt-in** through
[SempodsOutboundGuard](../src/main/kotlin/org/sempods/client/net/SempodsOutboundGuard.kt).
Use it when a backend accepts pod URLs from users. Without a guard, the client can connect to
any supplied address.

Two checks are required: URL policy rejects disallowed IP literals before the request; the DNS
hook vets and pins resolved addresses during connection. Either alone leaves a gap. Guarded
clients disable proxies so remote proxy resolution cannot bypass DNS checks. The installed
policy also refuses automatic redirects.

[SempodsUrlPolicy](../src/main/kotlin/org/sempods/client/net/SempodsUrlPolicy.kt) owns address
ranges and the checks for pod bases and other credential-bearing endpoints. Authentication and
pod containment apply separately from this network policy.
