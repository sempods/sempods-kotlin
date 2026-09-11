# Additional deployment profiles and seams

> **Disposition: proposed — not implemented.** [Issue #139](https://github.com/sempods/sempods-kotlin/issues/139) owns review,
> decisions and adoption links. Current composition is documented in
> [modularity](../concepts/modularity.md); accepting a profile does not implement it.

## Proposed seams

Each row names the place that hardwires the behavior today, so the cost of introducing the
seam is visible.

| Seam | Purpose | Hardwired today in |
|---|---|---|
| **Pod resolution** | Decide which pod a request addresses: path segment (multi-pod), fixed pod (single-pod deployment), or host header. | The routing itself — `@Path("{pod}…")` plus `@PathParam("pod") pod: String` on every endpoint, carried on through `SempodsBaseEndpoint.authenticate(pod)` into `PodFacade`. The most invasive seam of the set. **The service's own name falls with it**: today it hosts pods, and a single-pod deployment *is* one. The names disagree about which: the database says `sempods-server`, the docker service and its env file say `sempods`. Settling them means knowing what the service is ([`../naming.md`](../naming.md) §3, "One name is unsettled"). Renaming a docker service is cheap next to this seam, so it is not worth doing before it. |
| **Query rewriting** | Let a deployment (or an individual pod) enforce additional constraints on SPARQL before execution. | Nothing exists; the sandbox is applied directly on the query path. |
| **Store selection per pod** | Choose the store backend per pod (in-memory, file-based, remote SPARQL). The interface is there; the per-pod choice is not — and the write path still reaches through it to an RDF4J Sail for change capture, which is the actual blocker. | `PodRepositoryCache.initialize()` constructs `InMemoryPodRepository` unconditionally; `InMemoryPodRepository.doWork` casts to `NotifyingSailConnection`. Tracked in [issue #139](https://github.com/sempods/sempods-kotlin/issues/139). |
| **`_system` extensions** | Let a deployment add endpoint sets under `_system/…` without patching the module. | The endpoint list in `SempodsModule.bindEndpoints(...)` is static. Partial precedent: `SempodsMediaModule` contributes a set from the deployment composition — the Multibinder behind `JaxRsApplicationModule.bindEndpoints` already allows it. |
| **Transport without RDF** | Let a consumer of the host-level admin surface take the HTTP plumbing without the pod client — an operator console with no RDF anywhere. | `:sempods-control-plane-client` declares `api(project(":sempods-client"))`, so `SempodsHttpTransport` and `SempodsClientException` arrive with RDF4J attached. The control-plane and hosted MCP consumers therefore carry RDF4J for HTTP operations. HTTP/RDF separation is owned by [#116](https://github.com/sempods/sempods-kotlin/issues/116). See [`../pod-client.md`](../pod-client.md). |

## Target profiles

The target profiles would use different selections; the missing seams above
prevent treating these as deployment recipes:

- **Single pod, self-hosted.** Fixed pod resolution, a single-operator admin credential,
  file-based store, no multi-tenant concerns.
- **Multi-tenant hosting.** Path-segment pod resolution and credential-checked admin
  authority exist. Per-pod store selection is proposed; app backends, operator UI and owner console are all HTTP clients of the same
  surface.
- **Embedded in an application.** The application owns the pod in its own process and the
  server is not reachable from outside it. Pod resolution is fixed. **Not shipped**: the
  server always binds an HTTP connector.

The last profile is the one that invites a mistake, which is why it has no "skip the
authority" implementation waiting for it. Dropping the credential check would be a statement
about *reachability*, not about trust, and the two look identical from inside the calling
code: an app backend that reaches the server over HTTP — including one in the same JVM — is **not** embedded and needs a credential like any
other client. If the
profile ever becomes real, the honest form of it is a server that binds no connector at all;
until something enforces that, an authority that authorizes everyone is a footgun with no
legitimate user.

## Owner and operator interfaces

An owner interface would use ordinary pod OAuth and the pod client across implementations.
A "create a pod here" action needs a separate, explicitly paired host authority because the
new pod cannot authorize its own creation. An operator interface is deployment-specific and
uses the control-plane client. A WebID plus operator allowlist is a proposed alternative to
sharing a static admin credential; `SempodsBaseEndpoint.resolvePodOwnerPrincipal` is an
existing identity-resolution example, not an implementation of that admin policy.

Owner grant CRUD over the existing grant/replace/revoke facade methods also needs an HTTP
contract and owner or covering manage authorization. Review it with this interface boundary;
service-client lifecycle and installer consent remain owned by [#35](https://github.com/sempods/sempods-kotlin/issues/35).
