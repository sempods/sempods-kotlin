# Pod media — how this implementation holds the bytes (IST)

**The contract is [`spec/modules/media.md`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/media.md) in sempods-spec** — the
routes, the content-addressed identifier, the authorization rule, delivery, and the collection
order. This document does not repeat it. What is here is what the specification deliberately does
not say: which store is behind the seam, how a deployment selects one, and what it takes on by
enabling the module.

## The seam — storage backends

`org.sempods.pods.media.PodMediaStore` is five methods (`put`, `open`, `delete`, `exists`,
`iterate`) and stays five. Stores are addressed by `PodMediaRef(podId, mediaId)`, **never** by a
storage key: an implementation owns how a ref becomes a physical location. Both implementations
*document* their layout as `{podId}/{mediaId}` in their own KDoc, because with no copy operation
inside sempods that layout is what an external backup and a backend swap operate on — but nothing in
the server computes a location.

`podId` is a **`PodId`**: the tenant key the store partitions by, and **not** a location. It
promises nothing about its own form — a store that needs a path or an object key derives one and
owns that mapping, the same way it owns its layout. Both shipped stores happen to use the token
verbatim as one path segment or one key prefix, and each says so in its own KDoc — which is also why
each refuses a token containing `/` from `put` rather than storing an object its own walk could
never hand back. A deployment minting tokens like that wants a store that encodes them.

Which tenants are this deployment's is therefore nothing a store can answer, and `iterate` does not
try: it hands back everything its layout can read, and `PodMediaFacade.reconcile` drops the ids this
deployment did not mint before the report. That last check is by shape, so one case survives it — a
second sempods deployment sharing the backend mints the same shape, its objects have no row here,
and they are reported as leaks. **Two deployments must not share one media backend unpartitioned.**

**Selection lives in the deployment, and that is forced rather than stylistic.** `:sempods-media-s3`
depends on `:sempods-server`, so `:sempods-server` cannot import `S3PodMediaStore` to choose it. `:sempods-server` owns
`PodMediaStore`, `FilesystemPodMediaStore` and `PodMediaEndpoint` and binds none of them;
`SempodsMediaModule` in `deployments/sempods/image` reads **`SEMPODS_MEDIA_BACKEND`** and binds the
matching store plus the endpoint.

Three configuration states, and "no backend" is an ordinary one:

| `SEMPODS_MEDIA_BACKEND` | Store | Routes | Registry + cascades |
|---|---|---|---|
| unset / `none` | none | none — the routes do not exist | bound, run against an empty collection |
| `filesystem` | `FilesystemPodMediaStore` (`:sempods-server`) | yes | bound |
| `s3` | `S3PodMediaStore` (`:sempods-media-s3`) | yes | bound |

An unrecognised value fails at boot. **The registry is not optional; only the store is** —
`PodMediaDao` needs a `MongoDatabase` and nothing else and is bound in `SempodsModule`, so the
lifecycle cascades keep working where no store exists. `PodMediaFacade`, which does touch bytes, is
bound by the media module alone.

**The way out of a backend is to copy the bytes across first, then repoint the variables.**
Switching a deployment that already holds media to `none` is deliberately unsupported: it leaves
rows and objects with no route that can reach them.

sempods.org runs `filesystem`. The layout on disk *is* the interchange format — one plain file per
object, nothing packed, no index to keep in step — so `restic` is a complete backup and what it
restores is directly readable. `s3` answers a different question: when the bytes should leave the
box, or when more than one pod server has to reach the same objects.

## Lifecycle

A media row carries only what the bytes settle (`mediaId`, `size`); everything descriptive —
content type, filename, who and when — hangs off the **assignment** to a context. That split is a
confidentiality decision: with content addressing the second upload of identical bytes finds the
first one's row, and a shared description would let that second party read back values they never
supplied.

- `unreferencedSince` is stamped when the assignment set empties and cleared when it refills.
- **Cascades**: `PodFacade.removeContext` pulls the context out of every assignment;
  `SempodsFacade.deletePod` stamps the pod's rows unreferenced rather than deleting them, so the
  grace period cushions an accidental pod deletion. Both are pure registry work and never touch a
  byte. Both stamp with `$ifNull`, so an unrelated deletion does not restart a running grace period.
- **The sweep** order is [`SPS-MEDIA-024`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/media.md#SPS-MEDIA-024) — object
  first, row second, so an interruption leaves a state the next run repairs. Here it runs
  conditionally on `unreferencedSince < cutoff`, and the grace period defaults to 30 days.
- **No in-process scheduler.** An operator cron against the admin route is the answer; sempods runs
  no tasks.
- **Reconcile corrects nothing**, in either direction. The remedy is an operator's: delete the stray
  file, or restore the missing object from the backup. Automating it would mean a route that deletes
  bytes on the strength of a query.

## Named limitations

The module chapter names four ([`spec/modules/media.md`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/media.md) §7): no range
requests, two surviving check-then-act races, no reconciliation of the graph against the registry,
and checksums that are never re-verified. What that section cannot say is where they land in this
code:

- The **upload-versus-sweep** race is logged, counted as `brokenMedia`, listed by reconcile under
  `rowsWithoutObject`, and healed by the next upload of the same content.
- The **context-deleted-mid-upload** race is narrowed by
  `PodContextWriteAuthorizer.requireContextStillRegisteredOrThrow`, which repeats the check where
  the decision is taken and answers `409`. The residual gap is one statement wide.
- **Checksums**: an upload is covered by construction — the identifier *is* the digest of the
  staged file — and nothing re-reads the bytes afterwards. That is the backup's and the store's job
  (`restic check --read-data`, an object store's own scrub).

## Deliberately outside

- **Backup, and any copy operation between two stores.** Backup runs *outside* sempods: `restic` or
  `rclone` over the filesystem store's directory, an object store's own versioning and lifecycle
  rules over a bucket. A move between backends is `rclone sync` plus two environment variables. This
  was built once and taken back out — what it bought over `restic` was a restore without the
  registry; what it cost was a second copy of the registry's schema inside a backup format. The
  registry lives in MongoDB, MongoDB is backed up as MongoDB, and the two are restored as a pair.
  If a sempods-side backup is ever genuinely needed, that argument is what it has to beat.
- **Replication, a read-through cache in front of a remote store.** The seam makes a store
  swappable, which is a different and much cheaper property than making sempods a storage system.
- **Image variants, resizing, CDN and signed storage URLs**, per the first decision above.
- Range requests, MCP tools for media, per-pod quota.

## Contract source (code)

- `sempods-server/src/main/kotlin/org/sempods/pods/media/PodMediaStore.kt` — the seam, `PodMediaRef`
- `sempods-server/src/main/kotlin/org/sempods/pods/media/PodMediaFacade.kt` — store/open/assign/sweep/reconcile
- `sempods-server/src/main/kotlin/org/sempods/pods/media/persist/PodMediaDao.kt` — the registry
- `sempods-server/src/main/kotlin/org/sempods/pods/media/MediaSourceAddressGuard.kt` — the SSRF deny table
- `sempods-server/src/main/kotlin/org/sempods/api/pod/system/media/PodMediaEndpoint.kt` — the HTTP contract
- `sempods-server/src/main/kotlin/org/sempods/api/system/admin/media/AdminMediaEndpoint.kt` — sweep, reconcile
- `deployments/sempods/image/src/main/kotlin/org/sempods/deployment/SempodsMediaModule.kt` — backend
  selection and the full configuration surface

## The obligation a deployment takes on

A deployment that enables a media backend takes on one thing this documentation cannot discharge
for it: the store holds the **only** copy of every byte, so it needs a backup of its own. Nothing
in the server can restore one, and the reconcile route reports the divergence rather than repairing
it.

## Related docs

- [sempods-spec `spec/modules/media.md`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/media.md) — the contract this
  implements.
- [sempods-spec `spec/core/lod-crud.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md) — the RDF CRUD layers. Media is
  **not** RDF CRUD; it shares the base64url identifier convention and nothing else. Why
  control-plane state lives in MongoDB rather than in the graph is
  [`SPS-CTX-025`](https://github.com/sempods/sempods-spec/blob/main/spec/core/contexts.md#SPS-CTX-025); the media registry is the same kind of
  thing.
- [`concepts/modularity.md`](concepts/modularity.md) — the seam table this store is a row of.
