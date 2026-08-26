# The collection layer (IST)

How the pod server stores what is not RDF. The RDF itself lives in the MemoryStore and is mirrored
into `resources` by the write-through path — see
[`../sempods-server/src/main/kotlin/org/sempods/pods/AGENTS.md`](../sempods-server/src/main/kotlin/org/sempods/pods/AGENTS.md) and
`write-through.md`. This document is about the other fourteen: pods, contexts, grants, media,
authorization codes, tokens, registrations, service clients, audit rows, the reauthorize challenge
the MCP surface parks between its deliberate 401 and the client's confirmation
(`oauth.reauthChallenges`), and the two that carry a sign-in across the browser round trip
(`oauth.loginStates`) and one consent screen (`oauth.consentTransactions`).

All fifteen are declared in one place — `SempodsCollections` — and pinned by
`SempodsCollectionsTest`. They used to sit in thirteen DAO companions, where the set they formed
was visible nowhere.

## Hand-written DAOs on the MongoDB driver

There is no ODM. Each collection has a DAO that maps `Document` ↔ DBO by hand, using the helpers in
`sempods-commons-mongo`, and issues `com.mongodb.client.model.*` filters and updates directly.

That is a deliberate cost. It buys the property this module is published for: `:sempods-server` carries no
persistence framework a third party would have to inherit, and the feature surface it actually needs
turned out to be small — `eq`, `in`, `set`, `multi`, sort, projection, upsert, `setOnInsert`,
`pullAll`, `size`, `or`. No aggregation, no transactions, no GridFS, no references, no lifecycle
hooks. `sempods-auth` and `sempods-mcp` are written the same way.

`oauth.authCodes`, `oauth.reauthChallenges` and `oauth.refreshTokens` are the collections none of
them owns a DAO for: the stores belong to `sempods-auth-core` and `sempods-mcp-core`, shared with
the hosted MCP service. The first two are named in the shared module itself and the default is
what every service gets; the third is supplied by the wrapper, because the two services store
different fields in it. The contract below still applies to all three — they are written through
the same `sempods-commons-mongo` helpers.

The refresh tokens are the one of the three where the two services store **different fields**, and
they are shared anyway. `RefreshTokenStore` takes the owner as a type parameter and a pair of codec
lambdas: the pod server's `(podId, podName, clientId, webId)` against the hosted service's
`(user, profile, clientId)`. The lambdas write into the same document between `familyId` and
`scopes`, which is where both collections already carried their owner, so one writer reproduces both
layouts and neither had a row rewritten. The compound index over the owner is a third parameter and
not derived from the codec — the pod server stores `podName` without ever querying on it.

`oauth.loginStates` is the short-lived one, and it does not own its mechanism either: it is `sempods-auth-core`'s `OneTimeStore` with a payload codec,
as are the identity service's `oauth.loginStates` and the hosted MCP service's `oauth.loginStates`,
`oauth.webLoginStates` and `oauth.consentTransactions`. `oauth.consentTransactions` is the pod's counterpart
to the last of those: the session says who is submitting a consent form, and this says *which
screen* it is and that it has not been submitted before — two questions one value cannot answer,
as one commit that tried found out. One-time (`findOneAndDelete`), `_id` is the SHA-256 of
the value the browser holds rather than the value, TTL index plus a check on read because the
reaper runs on its own schedule. The document contract below is the store's to keep, which is why
it now holds for all six — one of the hand-written copies wrote BSON nulls.

They exist because nothing that authenticates a person travels through the browser any more: the
parked `/authorize` request used to ride in a `return_to` parameter, the verified identity in a
hidden form field.

The collections were on Morphia until the driver migration, and other collections in the same
database still are. That is why the document contract below is stated as a contract rather than as a
preference:
rows written before the migration are still on disk, and a row this code writes has to be
indistinguishable from one written then.

## The document contract

The `sempods-commons-mongo` helpers (`putInstant` / `getInstant`, `putStrings` / `getStringSet`,
`putNotNull`) implement it, and a wire-format test on the mapping side — where Morphia is — pins it
without needing a database:

- **A null field is omitted**, not written as BSON `null`.
- **An empty collection is omitted too**, not written as `[]`. This changes what `Filters.exists`
  and `Filters.size` see, and both are used.
- **An `Instant` is stored at millisecond precision.** A round trip is not the identity function:
  `10:15:30.123456789Z` returns as `10:15:30.123Z`.
- **The id field is `_id`.** Morphia translated a field named `id` internally; the driver does not,
  so a filter naming `id` matches nothing — quietly. Every `*DboFields` object states
  `const val id = "_id"` with the reason on the line above it.

Two consequences worth knowing before writing a query:

- **`{field: null}` matches a missing field as well as an explicit BSON null**, and is not
  interchangeable with `exists(false)`. `RefreshTokenStore.markRotated` depends on this: no live token
  carries `rotatedAt` or `revokedAt`, so the rotation filter only works because the equality matches
  an absent field. `RefreshTokenStoreTest` pins it, including why it must not be tidied.
- **`{field: false}` does *not* match a missing field.** `PodContextsDao`'s visibility listing
  therefore misses contexts written before `isPublic` existed, even though the decoder defaults them
  to private. Unchanged behaviour rather than a regression — Morphia's filter missed the same rows —
  and putting them back is a `$ne: true` change and a product decision about pre-backfill data. See
  `PodContextsDaoTest`.

## Field order, and the two collections that have none

Wherever an encoder writes the document, the field order is the DBO's declaration order, and it is
kept: a row that differs from its neighbours only in order reads differently in a dump.

`grants` and `webIdGrants` are the exception, and it is a property of the server
rather than a gap here. Both are written by an upsert, so Mongo composes the inserted document out
of the filter's equality fields plus `$setOnInsert` and picks an order that varies from row to row
for one and the same command. The rows on disk are laid out inconsistently among themselves, so
there is no layout for a new row to match. What holds for them is the field *set*, which
`PodGrantsDaoTest` and `PodWebIdGrantsDaoTest` assert.

## Conventions

- **The rows, the `*DboFields` objects and the DAO functions over them are `internal`** — the
  document format is not a published surface, and `buildHealth` fails the build if one reaches a
  public signature. **The DAO classes and their constructors are not**: Guice builds them, and
  `SempodsModule`'s `@Provides` methods name their types.
- **Indexes are created by the DAO constructor**, imperatively, with the options spelled out —
  `unique`, `partialFilterExpression`, `expireAfterSeconds`. Neither the driver nor Mongo names an
  index here; the server does, as `a_1_b_1`.
- **The collection name is a constructor parameter**, with the production name either on an
  `@Inject` secondary constructor (where Guice constructs the DAO) or as a default (where a
  `@Provides` method does). That is the whole cost of giving a test a collection of its own
  (`test.<name>.<purpose>`, outside the production names), which the suites need: they run
  against the developer's own database, so a test that cleared a real collection to know what it was
  looking at would be destructive and order-dependent.
- **An insert returns the id it stored under.** `datastore.save()` used to write the generated `_id`
  back into the instance it was handed; `insertOne` does not, so a caller reading the id off the
  object it passed in gets `null`. `PodServiceClientStore.register` did exactly that, and the whole
  provisioning contract collapsed into `registrationId: null` until the DAO started returning the
  stored row.

## Which database

`MONGODB_URL` and `MONGODB_DB_NAME`, read into `SempodsConfig` at the entry point rather than
looked up where they are used. The default is `sempods-server` — this service's own, and one every
deployment can point elsewhere.

It used to be a database named after an application a self-hoster does not run — the last such
name in a module that is to be published. The collections moved in a maintenance window: a
dump/restore on the live host, not something a refactoring commit can do.

**The three services no longer share anything but the connection string.** `sempods-server`,
`sempods-auth` and `sempods-mcp` each have a database of their own, which is what lets the
collections inside them drop the service prefix: `sempods.oauthSigningKeys`,
`mcp.oauthSigningKeys` and `signing_keys` were three spellings of one thing, and each said what
its database name already said. They are all `oauth.signingKeys` now.

The database that name belonged to is untouched, and it was never genuinely shared: its owner
reaches this server over HTTP and has never read a collection here of its own accord. The two only
ever met in one test JVM, where both halves read the same `MONGODB_DB_NAME` and still do.

## Schema changes have no migration mechanism worth the name

Nothing here versions the stored data or records that a change was applied. What exists is
`SempodsUpdater`, an eager singleton whose `runUpdates` is called while Guice builds the
injector — before `SempodsServerStarter` obtains the Jetty server from it. The list of
updates it submits is **hardcoded**, and empty today.

**Two execution modes, and the split is the part that works.** Each update declares
`blocking`. A blocking one runs synchronously there, so it is finished before the first
request is accepted; the rest go to a daemon thread and run alongside a serving instance.
That distinction is deliberate and load-bearing: an update that changes what existing data
*means* has to complete before traffic arrives, because the code around it already assumes
the new meaning — serving during such a migration produces wrong answers, not slow ones. An
update that only adds data nothing reads yet does not need to hold up boot.

What is missing is everything around it, and it is worth knowing before you self-host across
an upgrade:

- **No history.** Nothing records that an update ran, when, or how it ended. There is
  nothing to look up and nothing to skip.
- **No "already applied" check.** Every entry runs on every boot, so idempotence is each
  entry's own responsibility. An entry that walks every pod does that walk every restart.
- **A failure does not stop anything** — including a blocking one. `runUpdate` catches,
  logs at SEVERE and continues, on purpose: a broken update must not leave the server unable
  to start. The cost is that a migration which failed looks exactly like one that succeeded
  unless somebody reads the log, and the blocking mode's guarantee is then only that the
  attempt finished, not that it worked.
- **Retiring an update erases it.** Editing the list is the only way, and afterwards nothing
  records that it ever existed.

The practical consequence for an operator: **take a backup before upgrading**, and read the
startup log rather than assuming a clean boot means a clean migration. A versioned registry
with run history is designed and not built.

## Related documents

- [`modularity.md`](modularity.md) — what is selectable per deployment, and open-source readiness
- [`../sempods-server/src/main/kotlin/org/sempods/pods/AGENTS.md`](../sempods-server/src/main/kotlin/org/sempods/pods/AGENTS.md) —
  the RDF side: `PodRepository`, the MemoryStore, the write-through path
- [`architecture/module-layering.md`](architecture/module-layering.md) — where
  `sempods-commons-mongo` sits
