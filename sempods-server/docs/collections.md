# The collection layer (IST)

What the pod server stores that is not RDF. The RDF itself lives in the MemoryStore and is mirrored
into `resources` by the write-through path — see
[`../src/main/kotlin/org/sempods/pods/AGENTS.md`](../src/main/kotlin/org/sempods/pods/AGENTS.md) and
`write-through.md`. This document is about the other fourteen.

**`SempodsCollections` is the list**, declared in one place and pinned by
`SempodsCollectionsTest`. Naming them again here would be a second source for one fact, and the
second one is the one that goes stale.

**How a document is written is not this document's subject.** The wire contract, the query
consequences that follow from it and the conventions for writing a DAO are
[`../../sempods-commons-mongo/docs/document-contract.md`](../../sempods-commons-mongo/docs/document-contract.md),
and they are shared with the other two services rather than particular to this one.

## Hand-written DAOs on the MongoDB driver

There is no ODM. Each collection has a DAO that maps `Document` ↔ DBO by hand, using the helpers in
`sempods-commons-mongo`, and issues `com.mongodb.client.model.*` filters and updates directly. The
feature surface that turned out to be needed is small — `eq`, `in`, `set`, `multi`, sort,
projection, upsert, `setOnInsert`, `pullAll`, `size`, `or`. No aggregation, no transactions, no
GridFS, no references, no lifecycle hooks. `sempods-auth` and `sempods-mcp` are written the same
way, and what it buys is in
[`../../docs/concepts/modularity.md`](../../docs/concepts/modularity.md) §"Open-source readiness".

`oauth.authCodes`, `oauth.reauthChallenges` and `oauth.refreshTokens` are the collections this
module owns no DAO for: the stores belong to `sempods-auth-core` and `sempods-mcp-core`, shared with
the hosted MCP service. The first two are named in the shared module itself and the default is what
every service gets; the third is supplied by the wrapper, because the two services store different
fields in it.

The refresh tokens are the one of the three where the two services store **different fields**, and
they are shared anyway. `RefreshTokenStore` takes the owner as a type parameter and a pair of codec
lambdas: this server's `(podId, podName, clientId, webId)` against the hosted service's
`(user, profile, clientId)`. The lambdas write into the same document between `familyId` and
`scopes`, which is where both collections already carried their owner, so one writer reproduces both
layouts and neither had a row rewritten. The compound index over the owner is a third parameter and
not derived from the codec — this server stores `podName` without ever querying on it.

`oauth.loginStates` and `oauth.consentTransactions` do not own their mechanism either: both are
`sempods-auth-core`'s `OneTimeStore` with a payload codec — one-time (`findOneAndDelete`), `_id` is
the SHA-256 of the value the browser holds rather than the value, TTL index plus a check on read
because the reaper runs on its own schedule. They are two rather than one because the session says
*who* is submitting a consent form and the transaction says *which screen* it is and that it has not
been submitted before, which one value cannot answer.

Both exist because nothing that authenticates a person travels through the browser any more.

## Two collections with no field order, and one filter that misses rows

Both are instances of a rule stated in the document contract, and both are this server's.

- **`grants` and `webIdGrants` have no layout to match.** Both are written by an upsert, so the
  order varies from row to row for one and the same command. What holds for them is the field
  *set*, which `PodGrantsDaoTest` and `PodWebIdGrantsDaoTest` assert.
- **`PodContextsDao`'s visibility listing misses contexts written before `isPublic` existed**, even
  though the decoder defaults them to private, because `{field: false}` does not match a missing
  field. Unchanged behaviour rather than a regression; putting them back is a `$ne: true` change and
  a product decision about pre-backfill data. See `PodContextsDaoTest`.

## Conventions

- **The rows, the `*DboFields` objects and the DAO functions over them are `internal`** — the
  document format is not a published surface, and `buildHealth` fails the build if one reaches a
  public signature. **The DAO classes and their constructors are not**: Guice builds them, and
  `SempodsModule`'s `@Provides` methods name their types. The shared stores in `sempods-auth-core`
  are the deliberate exception — being callable from three services is what they are for.

Everything else a DAO here follows is
[`../../sempods-commons-mongo/docs/document-contract.md`](../../sempods-commons-mongo/docs/document-contract.md)
§"Conventions".

## Which database

`MONGODB_URL` and `MONGODB_DB_NAME`, read into `SempodsConfig` at the entry point rather than looked
up where they are used. The default is `sempods-server` — this service's own, and one every
deployment can point elsewhere.

**The three services share nothing but the connection string.** `sempods-server`, `sempods-auth` and
`sempods-mcp` each have a database of their own, which is what lets the collections inside them drop
the service prefix: the signing keys are `oauth.signingKeys` in all three, because the database name
already says whose they are.

## Schema changes have no migration mechanism worth the name

Nothing here versions the stored data or records that a change was applied. What exists is
`SempodsUpdater`, an eager singleton whose `runUpdates` is called while Guice builds the injector —
before `SempodsServerStarter` obtains the Jetty server from it. The list of updates it submits is
**hardcoded**, and empty today.

**Two execution modes, and the split is the part that works.** Each update declares `blocking`. A
blocking one runs synchronously there, so it is finished before the first request is accepted; the
rest go to a daemon thread and run alongside a serving instance. That distinction is deliberate and
load-bearing: an update that changes what existing data *means* has to complete before traffic
arrives, because the code around it already assumes the new meaning — serving during such a
migration produces wrong answers, not slow ones. An update that only adds data nothing reads yet
does not need to hold up boot.

What is missing is everything around it, and it is worth knowing before you self-host across an
upgrade:

- **No history.** Nothing records that an update ran, when, or how it ended. There is nothing to
  look up and nothing to skip.
- **No "already applied" check.** Every entry runs on every boot, so idempotence is each entry's own
  responsibility. An entry that walks every pod does that walk every restart.
- **A failure does not stop anything** — including a blocking one. `runUpdate` catches, logs at
  SEVERE and continues, on purpose: a broken update must not leave the server unable to start. The
  cost is that a migration which failed looks exactly like one that succeeded unless somebody reads
  the log, and the blocking mode's guarantee is then only that the attempt finished, not that it
  worked.
- **Retiring an update erases it.** Editing the list is the only way, and afterwards nothing records
  that it ever existed.

The practical consequence for an operator: **take a backup before upgrading**, and read the startup
log rather than assuming a clean boot means a clean migration. A versioned registry with run history
is designed and not built.
