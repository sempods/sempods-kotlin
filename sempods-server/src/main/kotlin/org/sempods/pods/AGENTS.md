# pods — Pod Data Layer

This package contains the per-pod data abstraction and business logic orchestration.

## Key classes

| Class | Role |
|---|---|
| `PodRepository` | Interface — resource-level RDF store abstraction per pod. All read/write operations go through this. |
| `InMemoryPodRepository` | **Writes** go write-through against the MemoryStore (source of truth on write), then the backup sink mirrors the change into MongoDB — see `write-through.md`. **Reads** (`getResource`, `existsResource`, `findReferencingResources`, the ETag validator) come from the MemoryStore. Domain listings (an event listing, say) are not here — they live in the consuming application as SPARQL-native queries. |
| `PodRepositoryCache` | Singleton — manages `PodRepository` lifecycle: lazy init, caching, invalidation. Entry point to get a `PodRepository` for a pod name. The lazy init rebuilds a pod's volatile store from its backup rows, and checks what it loaded against the row count the write path maintains on the pod registry row — which is what tells a pod whose durable state went missing from one that was never written to. |
| `media.PodMediaFacade` | The pod's **own** media: bytes in `PodMediaStore`, everything else in `persist.PodMediaDao`, and the rules connecting them — including `sweepUnreferenced` (the grace-period garbage collection) and `reconcile` (store↔registry drift, a report only). Bound only where a deployment configures a store; the DAO is bound always, so the lifecycle cascades in `PodFacade.removeContext` and `SempodsFacade.deletePod` keep working without one. Concept doc: `docs/media.md`. |
| `PodFacade` | Business logic orchestration — resource and slot manipulation, context lifecycle. Sits between endpoints/services and `PodRepository`. |
| `grants.PodGrantsFacade` | Single entry point for reading and mutating grants. Owns the two-level model (`granted = requested ∩ user_grants`) and the revocation cascade that re-derives app-delegated grants when an owner-level WebID grant changes — without it, revocation would never reach an app that already consented. Also owns the context-deletion revocation `PodFacade.removeContext` delegates to. |
| `ResourceBoundary` | Resource boundary invariant: a resource = its **own-subject** statements, and **blank nodes are forbidden** (pod data is fully IRI/literal-valued; a nested value is its own IRI-named resource). `requireWithinBoundary` is the persistence backstop for every in-process write path (the HTTP layer additionally rejects foreign subjects / blank nodes with 400, see `PodResourceWriteService`). |

## Call flow

```
Endpoint  (or, over HTTP, sempods-client)
    │
    ▼
PodFacade  (business logic: resources, slots, context lifecycle)
    │
    ├──▶ PodRepositoryCache.get(podName) → PodRepository
    │        │
    │        ▼
    │    InMemoryPodRepository
    │        ├─ writes → MemoryStore (source of truth) → change dispatch → backup sink → MongoDB
    │        │                                                          └→ media cleanup
    │        └─ reads  → MemoryStore  (domain listings live in the consuming app as SPARQL)
    │
    └──▶ PodRepository.withConnection { ... }  (SPARQL via MemoryStore)
```

## Method groups in PodFacade

- **Resource operations** — CRUD delegates (`getResource`, `putResourceModel`, `deleteFromContext`, `removeContext`)
- **Endpoint-facing** — `patchResource` (context-scoped merge-patch), `loadResourceStatementsInContext`
- **Slot operations** — the LOD-CRUD System layer (`getSlot`, `replaceSlot`, `addSlotValue`, …)

> There is **no view orchestration here, and none anywhere in the published tree.** Typed resource
> views are a projection an application builds over the pod's own terms, and they live with the
> application that owns the vocabulary; the server sees the RDF that arrives over the wire and
> nothing narrower. Domain listings are the same story: they are SPARQL-native queries in the
> consuming application, and sempods exposes only generic SPARQL.

## The persistence layer is module-internal

The `…Dbo` rows under `*/persist/` are `internal` classes and every DAO function over them is
`internal`; the classes and their constructors stay public because Guice builds them. That is what
keeps `ObjectId` out of what `:sempods-server` publishes, and `buildHealth` fails the build if a row
drifts back into a public signature. Consequence for a change here: a facade or endpoint method that
takes or returns a row is `internal` too — the compiler says so — and giving one a public answer
means giving it a row-free type, not a wider modifier. See `docs/persistence.md` §"Conventions" and
`docs/architecture/module-layering.md` §"Module Boundaries".

## Related docs

- Authorization model, grant vs. scope, revocation (IST): `docs/auth/authorization.md`
- Write-through store, change dispatch, recovery (IST): `write-through.md`
- The MongoDB side — driver DAOs, the document contract, index and test conventions (IST):
  `docs/persistence.md`
- Architecture: `docs/architecture/module-layering.md`
- Pod store & journal roadmap (SOLL): the maintainer's internal roadmap
