# Write-through Store + Change Dispatch (IST)

Implemented behavior of the pod write path. For what is planned on the store side — the store
contract as a replaceable seam, the journal that makes a change one durable record, restore and
implementation switch — see the maintainer's internal roadmap.

## Principle

The in-memory RDF store (`MemoryStore` behind each `PodRepository`) is the **source of truth on
write**. A committed write produces a `PodChangeSet` that the `PodChangeDispatcher` fans out to
every registered `PodChangeListener` (package `org.sempods.pods.changes`). One sink subscribes
today:

- **Backup sink** (`BackupSinkPodChangeListener`, *critical*) — mirrors each changed resource
  **per context** into the `resources` collection (`(podId, resourceUri, context)`
  unique → N-Quads). The MemoryStore is volatile, so this collection is the pod's **only durable
  persistence** and the recovery source for whole-pod *or* per-context reconstruction (see
  "Recovery" below). It is critical for exactly that reason: a write it did not record must not be
  reported as successful, because the next restart would silently drop it.

A second, best-effort sink used to sit here — `MediaCleanupPodChangeListener`, which dropped an
application-side media-store reference of a deleted `schema:ImageObject`. It went with media roadmap
M9, once nothing wrote to that store any more. The pod's *own* media are not on this path at all:
their
assignments are registry state, and the cascades that clear them live in `PodFacade.removeContext`
and `SempodsFacade.deletePod`.

Further sinks (audit/ChangeStreams — vision V4.1; pod-local hooks such as a search index — V4.2)
subscribe by contributing to the listener set binder in `SempodsModule`; the write path does not
change. Each declares a `Durability` — at most one may be `CRITICAL` (see below).

## Write flow (`InMemoryPodRepository`)

`putResource`, `removeFromContext`, `removeContext`, `deleteResource` are thin store-mutation
blocks run through one `doWork` helper, serialized per pod by a write lock. The block declares
nothing about *which* resources it touches — the captured statement delta is the single source
for the changed-resource set, the change events, and the rollback undo log:

1. **Capture** the statement-level delta: a `SailChangeCapture` (`SailConnectionListener`) is
   registered on the store connection for the duration of the transaction and buffers
   `statementAdded` / `statementRemoved`. Decoupled from any write-path bookkeeping, it will also
   see non-`doWork` writes once those open up (SPARQL UPDATE, bulk loads). Deriving the delta from
   a Sail listener is also what ties a pod to an RDF4J Sail backend — replacing it is planned in
   the maintainer's internal roadmap.
2. **Run the block** inside a `begin(SNAPSHOT)` / `commit()` transaction. Each block keeps its
   own graph-isomorphism guard so an identical-content no-op never touches the store. After the
   block, the **net delta** is read from the capture (replace-all churn netted out); an empty net
   delta ⇒ no-op write, rolled back, returns `false`.
3. **Build the `PodChangeSet`** (post-commit): blank nodes are forbidden, so every delta statement's
   subject IS the owning resource IRI — affected resources and per-resource partitioning are a direct
   group-by subject, no anchor climbing. Per resource the post-image is read from the store and the
   operation (`CREATED` / `UPDATED` / `DELETED`) is derived from post-image and delta (pre-image =
   `after − added + removed`). The full post-image is carried in the event so a sink can persist it
   without a store connection.
4. **Dispatch** the `PodChangeSet` to the listeners (synchronous, post-commit). The backup sink
   writes only the **touched** contexts (`ResourceChange.contexts`): per context it upserts the
   N-Quads if the resource still has statements there, else deletes that `(resource, context)` row —
   a context drop, or, when the whole resource was deleted, all of its rows.

### Failure semantics

Dispatch is two-phase by `Durability`. The single **critical** listener runs first; if it throws
(e.g. MongoDB fails), the store change is rolled back by a **compensating transaction** that
replays the captured delta as an undo log (remove what was added, re-add what was removed), and
the request fails. Then **best-effort** listeners run; their failures are logged and swallowed —
a lagging index or media cleanup never rolls a user's write back.

The compensation only undoes the store, so it is sound exactly because at most **one** critical
listener is allowed (enforced by a guard in `PodChangeDispatcher`): a second critical sink's
already-applied side effects could not be undone. Worst-case data loss stays at one write (a crash
between store commit and backup persist). `WriteCompensationTest` pins the behaviour, including that
a failed *update* restores the previous state rather than an empty one.

**Partial application.** One logical change becomes N row writes — one per touched
`(resource, context)` — and the compensation above undoes only the store. A multi-resource change
set (`removeContext` is the big one) is therefore **not atomic against the backup collection**: if a
row write fails part-way, the request reports failure while the backup holds a mix of pre- and
post-image, and a later restart materializes that mix. Both directions occur — surviving upserts
make part of a rejected write real, surviving deletes drop data the store still holds.

Two things bound this; neither closes it. The rows are idempotent full replacements, so
`BackupSinkPodChangeListener` **retries the whole set** and a transient failure self-heals to a full
apply. A persistent failure is logged at ERROR naming the affected resources, so the divergence can
be reconciled rather than merely suspected. Single-resource, single-context writes — the common case
— are one row and unaffected either way.

The pod's row count is inside the same window. A hard kill after the up-front subtraction but before
the closing write leaves the count short by up to the set's planned deletes while the rows are still
there — the silent direction, and the one the sign rule below cannot reach, because nothing was
wrong about what the sink did, the process simply stopped. It is bounded by the set (one row for the
common write, more for `removeContext`) and it closes with everything else here: one durable record
per change set.

Closing it needs the change to become **one** durable record instead of N. A MongoDB transaction
would do it but needs a replica set (declined); the intended answer is the journal in
the maintainer's internal roadmap, which retires the retry with it.

### Resource boundary — no blank nodes

A resource is exactly its **own-subject** statements, and **blank nodes are forbidden** (see
`ResourceBoundary`): pod data is fully IRI/literal-valued, a nested value must be its own IRI-named
resource. `putResource` enforces this up front (foreign subject or any blank node ⇒ reject) — the
persistence backstop for every in-process write path, covered by `PodRepositoryBoundaryTest`; the
HTTP layer additionally rejects with 400. This is the de-facto state — the canonical JSON-LD CRUD
API cannot express anonymous nested objects — now made explicit; external RDF carrying blank nodes
would be skolemized at the import boundary (future). Because there is no blank-node closure, a
resource = `getStatements(subject)`, owner = subject, and context removal is a single
`clear(context)` (no orphan pruning).

### Context awareness

Context (named graph) is first-class in sempods (it carries authorization). Change events keep it:
the delta Models are quads (every statement keeps its graph), the backup serializes context-aware
N-Quads, and `ResourceChange.contexts` surfaces the touched graphs explicitly for context-scoped
consumers (vision V4.1 ChangeStreams are "scoped by context").

This covers context as the **data dimension** of statements. Context as a **lifecycle entity** —
creating a context, flipping its public flag, deleting a context (with the grant / refresh-token /
service-client revocations that go with it) — is **not** evented today: a context removal's *data*
side flows through the dispatch (stripped statements arrive as `removed` with their graph), but the
registry/authorization side does not. The split is deliberate (data changes are delta-derived,
lifecycle changes are intent-explicit); a `ContextChange` sibling event is planned for when audit
(V2.3) or context-scoped ChangeStreams (V4.1) need it — see the note on `PodChangeSet`.

## Reads

Resource-level reads are served from the **store**: `getResource`, `getResource(uri, context)`,
`existsResource` and `findReferencingResources` run `getStatements`/`hasStatement` over a read
connection (`withConnection`). They take **no** `writeLock` — the `MemoryStore` serves concurrent
reads while a write holds the lock, and a reader sees a consistent snapshot (SNAPSHOT isolation).
`existsResource` keeps the semantics the former MongoDB `$in` query had (resource exists ∧ optional
matching `rdf:type` ∧ optional statement in one of the contexts); `findReferencingResources` returns
the subjects of the non-`rdf:type` edges pointing at the object in a context.

**ETag validator.** The HTTP ETag is a strong **content hash** over the resource's own-subject
statements (`ResourceValidator`, served by `SempodsFacade.getResourceValidator` →
`PodRepository.fetchResourceValidator`). It is deterministic (blank nodes are forbidden, so sorting
the per-statement N-Quads canonicalizes), resource-snapshot grained (any change to any context bumps
it), and needs no MongoDB read. `SlotETagComputer` and the MCP `if_match` mirrors take the validator
as the anchor.

Domain listings such as `findEvents` are not a sempods concern: they live in the consuming
application as SPARQL-native queries over the pod — sempods exposes only generic SPARQL.

## Recovery

The MemoryStore is volatile, so on first access to a pod (`PodRepositoryCache.get` → lazy
`initialize`, and after `invalidate`) it is reconstructed from MongoDB. Recovery reads the
**backup collection** `resources` unconditionally: each row is self-contained N-Quads
carrying its named graph (blank nodes are forbidden, so a resource's per-context statements never
reach into another context), so the rows just get parsed and added — `reconstructModel` is the one
shared N-Quads → Model decode path (also used by pod deletion's media cleanup).

### The row count

A pod with **no** rows recovers empty, and the collection's own contents cannot say whether that is a
loss. Three different pods look identical from there: one nothing was ever written to (provisioning
writes context registry rows, not RDF), one whose owner deleted its last resource, and one whose
durable state went missing. The first two are the ordinary case, so a warning on all three warns
mostly about nothing — and reading the answer off `lastModifiedAt` does not help either, because a
legitimate delete removes the rows and bumps the registry row in the same call.

So the number is written **at the transition** instead. `PodDbo.resourceRowCount` on the pod registry
row is how many `resources` rows the write path believes the pod has, maintained by
`BackupSinkPodChangeListener`: the DAO reports every row that actually appeared or vanished, and the
sink applies them to the registry row with `$inc`. A change set that only replaces rows moves nothing
and issues no command, so the common write pays nothing. The tally runs **across retry attempts** (a
row inserted by a failed attempt comes back as a replacement in the next one) and the closing write
is in a `finally` (rows written are on disk whether or not the set completed, and the count names the
collection, not the request).

**Down before the rows go, up after they arrive.** The set's deletes are subtracted up front —
pessimistically, before a single row is dropped — and everything that adds is applied after its row
exists. Two documents always have a gap where they disagree; this ordering decides which way. The
count is never *above* the collection while a set is in flight, only below it, so a recovery reading
mid-set sees the harmless direction rather than the shape of a loss. Subtracting up front is a guess
— a delete of a row that was already gone moves nothing — so the closing write adds back whatever did
not happen, along with the inserts that did.

Recovery then compares:

| | |
|---|---|
| recorded > loaded | rows the write path recorded are gone, and not through a delete — **ERROR**, naming both numbers |
| recorded == loaded | consistent, new pod and emptied pod alike — `debug` |
| recorded < loaded | the count is behind (a bump that did not land, or a recovery that raced one) — `info`, and **left alone** |
| no count recorded | the row predates the field; this recovery establishes its baseline — `info`, once per pod |

A confirmed shortfall then writes what the recovery loaded back as the new baseline. That is
load-bearing rather than tidiness: restoring a pod's rows through the write path adds them to a count
that already claims them, so without it a repaired pod would report the same loss on every recovery
for ever. With it, one incident produces one line. The price is that a recovery — reached from read
paths as well as writes — writes one document, once per pod per process.

Rows on disk that predate the field need no backfill: `$inc` on an absent field starts from zero, so
such a pod's count sits *below* the collection, which never raises the alarm, and the first recovery
sets it right.

**Neither number is a snapshot of the other, and the recovery never raises the count.** Another
replica may be moving both while a recovery reads them. A recovery landing in that gap sees rows
whose delta has not been applied yet — so writing what it loaded would count them a second time when
the delta lands, leaving the count *above* the collection. Waiting for the two to agree does not
help: both numbers are stable throughout that gap, so a stability check has nothing to see. Three
things divide the work, and each covers what the others cannot:

- **The sink's ordering** (above) keeps a set in flight from ever putting the count above the
  collection, which is what a still, mid-set gap would otherwise produce.
- **The shortfall re-check** — count re-read, rows counted over the `(podId, …)` index — rules out
  values still in motion, a set that started or finished between the recovery's two reads.
- **The direction of the recovery's own writes.** A count left *below* the collection raises nothing
  — it costs sensitivity equal to the drift, and a loss larger than the drift still reports — while
  one left *above* is the shape of a loss. So the recovery only ever lowers: `recorded < loaded` is
  stated and left alone, and the two writes that remain are the confirmed shortfall and the first
  baseline of a row that predates the field. That baseline is a pod's one and only lift, and the
  alternative to leaving such a pod permanently blind. Both are compare-and-set on the value that was
  read, so a delta landing in between makes them *miss* rather than discard it.

What is left is what a write cannot tell the sink: past the driver's own retry, a throw means the
outcome is **unknown**, not that nothing happened. That is true of the `$inc` itself, of a delete
whose row may or may not have gone, and of an upsert that may or may not have created its row — and
a retry cannot recover any of them, because `$inc` is not idempotent and the second attempt sees only
the end state.

**An unknown outcome always resolves the same way here: toward the count being high.** The two
mistakes are not symmetric. A count above the collection is reported by the next recovery and lowered
in the same breath — one line, then gone. A count below it is what `recorded < loaded` deliberately
leaves alone, so it never repairs and quietly costs that much detection for ever.

The **sign of the delta** is what says how to get there, and it points opposite ways:

| | repeating it risks | dropping it risks | so |
|---|---|---|---|
| a delta that **adds** | applying it twice — high | losing it — low | it is retried |
| a delta that **removes** | subtracting twice — low | losing it — high | it is issued once |

The only delta that removes is the up-front subtraction; the closing write cannot be negative,
because a set never sees more deletes happen than it planned. Alongside that, every planned delete
the sink could not confirm is added back, for the same reason. (The in-flight ordering above prefers
the opposite direction and does not contradict this: a gap that closes when the set finishes is
transient, and below is what raises nothing while it lasts. This is about drift that outlives the
set.)

One shape escapes even that, and is left standing: an upsert that inserted but could not say so
leaves the count low by a row. There is nothing to key an idempotent retry on until a change set
becomes one durable record — the journal in
the maintainer's internal roadmap.

**The one lift a recovery still makes** is the first baseline of a pod that predates the field, and
it is *raised to* what was loaded rather than set or added, because two different things can have
touched the row since it was read as absent and they need opposite answers. A stray `$inc` creates
the field, leaving a small number — one against a pod holding fifty — which has to be lifted, or the
pod stays blind for good, `recorded < loaded` being exactly what is left untouched. Another replica
recovering the same pod, which a rolling deploy makes ordinary, leaves the loaded rows themselves,
which must be left alone, or the count becomes twice the collection and the next recovery reports the
pod's whole history as missing. Nothing can tell the two apart by how they arrived; the value answers
both, so the update matches only a count that is absent or below what this recovery loaded. It can
therefore never write a count above the collection, which is the shape that raises an alarm.

A **negative** value there is a third case — it can only be a delete set's up-front subtraction on a
row that never had a baseline — and it is overwritten rather than preserved, which looks like the
careless choice and is not. The sink subtracts before it removes the row, so overwriting is one too
high when the rows were read before that delete and exact when after, while preserving the
subtraction is exact in the first case and one too *low* in the second. The first pair is reported by
the next recovery and lowered; the second is never reported at all.

What this does **not** cover is a write that bypasses `doWork` entirely (SPARQL UPDATE, bulk loads —
see step 1 above): it reaches neither the sink nor the count, so the two stay consistent with each
other while the store diverges from both.

## Pod deletion

`SempodsFacade.deletePod` drops every pod-scoped collection, backup rows included.

The pod's own media are the one thing it does **not** drop: after the contexts are gone, it stamps
the pod's rows `unreferencedSince` through `PodMediaDao.markPodUnreferenced` and leaves the bytes to
the host-level sweep, so the grace period cushions an accidental pod deletion. The ordering and the
one-statement-wide gap that remains are argued at the call site.
