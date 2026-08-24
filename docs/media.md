# Pod media — binaries a pod owns (IST)

Upload against the pod, access decided by the context permissions that already exist, and a
defensible answer to "nobody needs these bytes any more".

Exact contracts are KDoc on the classes named under "Contract source". This document is the model
and the boundaries.

## Model

- **Any binary, not images.** The pod server holds bytes and a content type. No image knowledge, no
  variant concept. Rendering and resizing belong to the application, which does its own before
  uploading.
- **The pod streams the bytes itself**, after checking the context permissions. No CDN, no redirect,
  no signed URLs; see "Delivery" for the door that stays open.
- **Registry only, no RDF written by the upload.** The upload returns a media URL; whoever wants a
  `schema:ImageObject` writes it. Registry and graph do not know each other, so there is nothing to
  synchronise and nothing to drift.
- **Opt-in.** With no backend configured the `_system/media` routes do not exist. A pod server holds
  RDF; only a deployment that means to hold binaries configures a store.

`{id}` is the **SHA-256 of the bytes**, base64url without padding — the encoding convention the
[`_system/resources`](lod-crud/README.md) routes already use. Content-addressed, so an upload dedups
within the pod and a re-upload yields the same URL. **Never across pods:** the storage key carries
the `podId`, because a shared object would make pod A's deletion depend on pod B.

## Routes

```
POST   {pod}/_system/media?context=<ctx>        upload; raw body + Content-Type, or a
                                                source descriptor. 201 + Location.
GET    {pod}/_system/media/{id}                 metadata (JSON), read check
HEAD   {pod}/_system/media/{id}
GET    {pod}/_system/media/{id}/content         the bytes, read check, ETag
HEAD   {pod}/_system/media/{id}/content
PUT    {pod}/_system/media/{id}?context=<ctx>   context-copy: add an assignment (idempotent)
DELETE {pod}/_system/media/{id}?context=<ctx>   remove an assignment (ensure-absent, always 204)
```

Host-level operator routes: `POST _system/admin/media/sweep` (grace-period collection, `dryRun`
supported) and `POST _system/admin/media/reconcile` (store↔registry drift, a report only). They are
host-level rather than pod-scoped because a deleted pod's rows outlive the pod.

`POST` also accepts `application/vnd.sempods.media-source+json` (`{"source_url", "filename"}`) and
fetches the source itself. That path is an SSRF surface and is guarded — scheme allowlist, every A
and AAAA record validated, the validated address pinned for the connection, the whole chain re-run
per redirect hop, and a size cap enforced while streaming. The policy is the IANA special-purpose
registries' *not globally reachable* column as a CIDR table, not a hand-picked list of private
ranges.

## Authorization

Media inherit the pod's existing model rather than adding one.

- **Write** (POST/PUT/DELETE) goes through `PodContextWriteAuthorizer`, manage-cascade included.
- **Read**: a media is readable exactly when its assignment set intersects the caller's
  `SempodsCredentials.restrictedContexts` (`null` = unrestricted).

So anonymous/public access, `public-read`, the manage cascade, revocation and the context-deletion
cascade all apply with no media-specific authorization logic.

**Two consequences that look like quirks and are not:**

- **`404`, never `403`,** for media the caller may not read — and the same `404` for ids that do not
  exist. The id is a content hash, so a distinguishable `403` would answer "does this pod hold
  exactly this file".
- **`POST` always answers `201`,** never `200` for a dedup hit, and metadata lists only the
  assignments the caller may read. Same reason.

**Context-copy needs two permissions:** write on the target context *and* read on a context the
media is already assigned to. Otherwise write anywhere would let anyone attach an arbitrary media id
to their own context.

## Delivery

`schema:contentUrl` is **always** `{podBase}/_system/media/{id}/content`. That is the load-bearing
property: a later CDN or signed-URL delivery changes what the server *answers* there, never what the
data says — an optimisation rather than a migration.

- ETag **is** the content hash, so it is a strong validator by construction; `304` on `If-None-Match`.
- Metadata is `private, no-store`, content `private, no-cache`, both `Vary: Authorization`. Explicit
  because the answer changes per caller and over time for the same one, and revocation is promised
  to be immediate.
- `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox` on every response.
  `Content-Disposition` is an allowlist: `inline` for png/jpeg/gif/webp/avif, `attachment` for
  everything else including unknown types. **`image/svg+xml` and `text/html` are always
  `attachment`** — SVG carries script, and inline in the pod's origin means same-origin access.

The door left open: presigned GET is standard S3, so `S3PodMediaStore` would gain
`presignedGetUrl(key, ttl)` and the endpoint would redirect instead of stream. The filesystem store
has no equivalent and keeps streaming — delivery is a property of the backend, and a deployment that
wants the redirect picks a backend that can do it.

## The seam — storage backends

`org.sempods.pods.media.PodMediaStore` is five methods (`put`, `open`, `delete`, `exists`,
`iterate`) and stays five. Stores are addressed by `PodMediaRef(podId, mediaId)`, **never** by a
storage key: an implementation owns how a ref becomes a physical location. Both implementations
*document* their layout as `{podId}/{mediaId}` in their own KDoc, because with no copy operation
inside sempods that layout is what an external backup and a backend swap operate on — but nothing in
the server computes a location.

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
- **The sweep** deletes the object first and the row second, conditionally on
  `unreferencedSince < cutoff`. That order is the one whose interrupted state is harmless (an object
  with no row is inert and reconcile reports it) and it lets a failing delete be retried by the next
  run. Grace period defaults to 30 days.
- **No in-process scheduler.** An operator cron against the admin route is the answer; sempods runs
  no tasks.
- **Reconcile corrects nothing**, in either direction. The remedy is an operator's: delete the stray
  file, or restore the missing object from the backup. Automating it would mean a route that deletes
  bytes on the strength of a query.

## Named limitations

- **No range requests.** `Accept-Ranges` is not advertised.
- **Two check-then-act races survive, narrowed rather than closed.** The registry, the context
  registry and the blob store are separate systems with no shared transaction:
  - between an upload's `exists` check and the sweep's object delete, the upload can end up with a
    row whose bytes are gone. It is logged, counted as `brokenMedia`, listed by reconcile under
    `rowsWithoutObject`, and healed by the next upload of the same content.
  - between authorizing an upload and writing its assignment, `removeContext` can run.
    `PodContextWriteAuthorizer.requireContextStillRegisteredOrThrow` repeats the check where the
    decision is taken and answers `409`; the residual gap is one statement wide.
- **No "no triple names this media any more → drop the assignment" reconciliation.** Assignments
  linger if someone deletes a `schema:ImageObject` through the LOD layer without the media DELETE.
  That is the price of keeping registry and graph apart; an application that owns both sides handles
  it itself.
- **Checksums are not re-verified.** An upload is covered by construction — the id *is* the digest
  of the staged file — but nothing re-reads the bytes afterwards. Corruption in transit or at rest
  is the backup's and the store's job (`restic check --read-data`, an object store's own scrub).

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

- [`lod-crud/README.md`](lod-crud/README.md) — the RDF CRUD layers. Media is **not** RDF CRUD; it
  shares the base64url id convention and nothing else.
- [`lod-crud/system-layer.md`](lod-crud/system-layer.md) — why control-plane state lives in MongoDB
  rather than in the graph. The media registry is the same kind of thing.
- [`modularity.md`](modularity.md) — the seam table this store is a row of.
