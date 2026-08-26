# The document contract (IST)

What a document written through `sempods-commons-mongo` looks like on the wire, and the conventions
for writing a DAO on top of it. It holds for every collection in this repository — the pod server's,
the identity service's, the hosted MCP service's — because all of them go through the same helpers,
and a DAO that diverges produces rows indistinguishable from correct ones until a filter misses
them.

`Documents.kt` carries the field-level reasoning and says why these rules and not others.
`DocumentsTest` pins the whole contract without needing a database.

## The contract

- **A null field is omitted**, not written as BSON `null`.
- **An empty collection is omitted too**, not written as `[]`. This changes what `Filters.exists`
  and `Filters.size` see, and both are used.
- **An `Instant` is stored at millisecond precision.** A round trip is not the identity function:
  `10:15:30.123456789Z` returns as `10:15:30.123Z`.
- **The id field is `_id`.** A filter naming `id` matches nothing — quietly. Every `*DboFields`
  object states `const val id = "_id"` with the reason on the line above it.

## Two consequences worth knowing before writing a query

- **`{field: null}` matches a missing field as well as an explicit BSON null**, and is not
  interchangeable with `exists(false)`. `RefreshTokenStore.markRotated` depends on this: no live
  token carries `rotatedAt` or `revokedAt`, so the rotation filter only works because the equality
  matches an absent field. `RefreshTokenStoreTest` pins it, including why it must not be tidied.
- **`{field: false}` does *not* match a missing field.** A boolean added to a row shape later is
  invisible to an equality filter on every row written before it, even where the decoder defaults
  them — so the fix is a `$ne: true` filter and a decision about the pre-backfill rows, not a
  correction to the decoder. `:sempods-server`'s context listing carries a live instance
  ([`../../sempods-server/docs/collections.md`](../../sempods-server/docs/collections.md)).

## Field order

Wherever an encoder writes the document, the field order is the DBO's declaration order, and it is
kept: a row that differs from its neighbours only in order reads differently in a dump.

**An upsert is the exception, and not a controllable one.** Mongo composes the inserted document out
of the filter's equality fields plus `$setOnInsert`, and picks an order that varies from row to row
for one and the same command. An upserted collection is laid out inconsistently among its own rows,
so there is no layout for a new row to match and what holds is the field *set*.

## Conventions

- **Indexes are created by the DAO constructor**, imperatively, with the options spelled out —
  `unique`, `partialFilterExpression`, `expireAfterSeconds`. Neither the driver nor Mongo names an
  index; the caller does, as `a_1_b_1`.
- **The collection name is a constructor parameter**, with the production name either on an
  `@Inject` secondary constructor (where Guice constructs the DAO) or as a default (where a
  `@Provides` method does). That is the whole cost of giving a test a collection of its own
  (`test.<name>.<purpose>`, outside the production names), which the suites need: they run against
  the developer's own database, so a test that cleared a real collection to know what it was looking
  at would be destructive and order-dependent.
- **An insert returns the id it stored under, wherever a caller needs one.** `insertOne` does not
  write the generated `_id` back into the document it was handed, so a caller reading the id off the
  object it passed in gets `null` and the DAO has to hand back the stored row instead. A store whose
  callers never ask for the key needs no such answer and should not invent one: `RefreshTokenStore`
  mints its `_id` at write time and its `Token` does not carry it.
