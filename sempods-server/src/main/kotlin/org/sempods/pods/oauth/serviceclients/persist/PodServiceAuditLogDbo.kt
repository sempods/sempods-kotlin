package org.sempods.pods.oauth.serviceclients.persist

import org.bson.types.ObjectId
import java.time.Instant

/**
 * One row per request made by a statically-registered service client (a
 * caller authenticated via the OAuth `client_credentials` grant). Populated
 * by [org.sempods.api.SempodsBaseEndpoint] when the bearer-token resolver
 * recognises a service-client token.
 *
 * Kept deliberately lean: timestamp + caller identity + request shape,
 * plus the [expiresAt] anchor that bounds how long the row is kept.
 *
 * A plain data class: the collection name, the three indexes and the mapping onto a BSON document
 * live in [PodServiceAuditLogDao], which talks to the driver. There is no no-arg constructor
 * either — it existed only so Morphia's `PojoCodec` had an entry point, and its `MorphiaUtil`
 * sentinels were values no reader ever saw.
 *
 * **The declaration order is the wire order** and is not free: down to [statusCode] it is what a
 * row already on disk carries, and `PodServiceAuditLogDao.toDocument` writes the fields in exactly
 * this sequence. [expiresAt] is appended after it for that reason — a field placed anywhere else
 * would reorder every row written from here on against every row already stored.
 */
internal data class PodServiceAuditLogDbo(
  val id: ObjectId? = null,

  val ts: Instant = Instant.now(),

  val podId: ObjectId,
  val clientId: String,

  /** HTTP method (e.g. `GET`, `PUT`). */
  val operation: String,

  /** Request path, including query string when present. */
  val path: String,

  /**
   * HTTP response status code. Null when not known at write time — today
   * the row is written from the auth-resolution layer, which runs before
   * the response is built. Populated by a follow-up filter (TODO).
   */
  val statusCode: Int? = null,

  /**
   * When this row expires — the anchor Mongo's TTL index on the collection reaps by.
   *
   * **The DAO owns it**, exactly as it owns [id]: a caller builds the row without one and
   * [PodServiceAuditLogDao.record] stamps `ts` + the configured retention over whatever is
   * here, so no call site can write a row that never expires. What a caller passes is therefore
   * ignored; what [PodServiceAuditLogDao.record] *returns* carries the stamped value.
   *
   * **Null on a row written before the retention existed.** A TTL index only reaps rows that
   * carry the field, so those are kept for ever unless an operator backfills them — the choice,
   * and the one-off command, are in `docs/auth/service-clients.md`.
   */
  val expiresAt: Instant? = null,
)
