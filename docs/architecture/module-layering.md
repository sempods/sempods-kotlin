# Module Layering

This document defines the standard layering within each module of the mono-repo.
All modules follow these conventions.

## Layers (top to bottom)

### Endpoint (JAX-RS)

HTTP interface. Responsibilities:

- Parse and validate HTTP input (path params, query params, headers, body)
- Authenticate and authorize the request
- Delegate to a **Facade**
- Transform the result into an HTTP response

Endpoints must not contain business logic.

### Service (a seam, where one earns its place)

A `Service` interface is an **abstraction over a capability** whose implementation is chosen by
the composition rather than fixed by the caller — `AiService` is the one that exists today, with
the Ollama and OpenAI implementations behind it.

It is deliberately **not** the mandatory shape of every cross-module call. An interface with one
implementation and one caller documents nothing and costs indirection, which is why the
`SempodsService` abstraction was retired — see
[`../modularity.md`](../modularity.md) §"The authority boundary
outlived the types". The authority boundary it was thought to carry — who may do what — lives in
the authorization model, not in a type.

Introduce a `Service` when a second implementation is real, or when a deployment must be able to
select one. Otherwise call the facade.

Service implementation responsibilities:

- Validate parameters and permissions
- Delegate to a **Facade**
- Optionally transform results for the calling module

Service implementations must not contain business logic.

### Facade (business logic orchestration)

A Facade orchestrates domain logic within a thematic area of a module.
It is the single entry point for a use case that may involve multiple domain
objects or repositories.

Facade responsibilities:

- Orchestrate domain operations (read, transform, write)
- Enforce business rules and invariants
- Coordinate across multiple repositories or domain services within the module

Both Endpoints and Service implementations delegate to Facades.

Example: `PodFacade` orchestrates operations on pod resources — reading from the
repository, applying business rules, writing back, and handling side effects.

### Repository / DAO (data access)

Encapsulates all data access for a specific storage concern.

Repository responsibilities:

- Read and write data to/from the underlying store
- Handle storage-specific concerns (serialization, indexing, backup)
- Provide a clean domain-oriented API (not storage-specific)

Repositories must not contain business logic. Storage implementation details
(e.g., backup strategy, caching) are internal to the repository.

## Dependency Direction

```
Endpoint ──→ Facade ──→ Repository
                ↑
Service Impl ──┘
```

- Endpoints and Service implementations depend on Facades
- Facades depend on Repositories (and other Facades, including across modules — see below)
- Repositories have no upward dependencies

## Module Boundaries

- The `sempods-commons` family is the foundation: `sempods-commons` plus the siblings that add one dependency
  each — `sempods-commons-json`, `sempods-commons-mongo`, `sempods-commons-jaxrs`, `sempods-commons-ktor`, `sempods-commons-okhttp`. A consumer
  takes what it needs and inherits nothing else
- **An application framework is a consumer of `sempods-commons`, never its base.** A framework that
  bundles sessions, users, mails and tasks may sit on top of the family and take what it needs;
  nothing in `sempods-commons` may depend back on it. `sempods-server`, `sempods-auth`, `sempods-mcp`,
  `sempods-model`, `sempods-client`, `sempods-control-plane-client` and the pod server's deployment
  image take `sempods-commons` and no framework at all. See
  [`../modularity.md`](../modularity.md) §"Open-source readiness"
- **A Facade is a legitimate reuse surface.** sempods is a *reference implementation* built from
  selectable seams, and a toolkit whose parts cannot be called from outside is not a toolkit. A
  consuming module depends on a facade directly; it does not need an interface in between to make
  the call respectable. What a facade owes its callers is a stable signature and enforced
  invariants — not module privacy
- **Repositories and DAOs stay module-internal.** Data access is where the document format lives,
  and that is precisely what must not become a shared surface: a second module reading the same
  collection turns a storage detail into a contract nobody declared. Reach the data through the
  owning module's facade
- Which modules may depend on which is the harder boundary, and it is not a matter of visibility
  modifiers — see the layering rules above and the module list in the root `AGENTS.md`
