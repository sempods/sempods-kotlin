package org.sempods.api.pod.system.auth

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Persistent record of one RFC 7591 Dynamic Client Registration. Inserts are
 * fingerprint-deduped in [DynamicClientStore.register] — a repeat `/register`
 * with the same `(clientName, userAgent, canonicalised redirect URIs)` returns
 * the existing row's `clientId` instead of minting a new one. The
 * top-level fields are not subsequently mutated; only [lastAuthorizedAt] is
 * touched on each successful `/token` exchange to fuel the orphan sweep.
 *
 * Hot fields (`clientId`, `redirectUris`, `clientName`) are extracted to top-level for
 * fast lookup during `/authorize`; the verbatim `rawRequest` is the source of truth and
 * carries fields we don't explicitly model yet.
 *
 * A plain data class: the collection name, the five indexes and the mapping onto a BSON document
 * live in [DynamicClientRegistrationDao], which talks to the driver. There is no no-arg constructor
 * either — it existed only so Morphia's `PojoCodec` had an entry point, and its `MorphiaUtil`
 * sentinels were values no reader ever saw.
 *
 * **The declaration order is the wire order** and is not free: it is what a row already on disk
 * carries, and `DynamicClientRegistrationDao.toDocument` writes the fields in exactly this
 * sequence.
 */
data class DynamicClientRegistrationDbo(
  val id: ObjectId? = null,

  val clientId: String,

  // Pod that issued this clientId. Every `/register` call is pod-scoped
  // (`/{pod}/_system/auth/register`), so these fields are always known.
  val registeredForPodId: ObjectId,
  val registeredForPodName: String,
  val registeredAt: Instant = Instant.now(),

  // Extracted RFC 7591 fields. These are projections of `rawRequest` kept at top-level for
  // query ergonomics; the verbatim body remains the source of truth.
  val redirectUris: Set<String>,
  val clientName: String?,
  val clientUri: String?,
  val logoUri: String?,
  val softwareId: String?,
  val softwareVersion: String?,
  val contacts: List<String>,
  val tosUri: String?,
  val policyUri: String?,

  // Verbatim DCR body. Preserves every key the client sent, including fields not yet modeled.
  // Stage 2 mines this to decide which additional fields are worth promoting to top-level.
  // Stored as a nested document; an empty body is omitted the way an empty collection is.
  val rawRequest: Map<String, Any?>,

  // Optional request-context observations captured for Stage-2 agent fingerprinting.
  val remoteAddr: String? = null,
  val userAgent: String? = null,

  // Pod-scoped de-duplication digest of (clientName, userAgent, canonicalized
  // redirect-uri set). Nullable so rows written before the dedup change keep parsing;
  // new rows always carry a value. Lookups in [DynamicClientRegistrationDao.findByFingerprint]
  // reuse the existing clientId instead of minting a new one on re-registration.
  val fingerprint: String? = null,

  // Liveness signal: touched on every successful `/token` exchange. The row otherwise
  // stays immutable (DCR observation data), but this one field is intentionally mutable
  // so a cleanup sweep can distinguish abandoned registrations from active ones, and a
  // future Active-Connections UI has a "last seen" to render. Null for rows written
  // before the touch change or for clients that registered but never completed an
  // auth flow.
  val lastAuthorizedAt: Instant? = null,

  // Bumped when the extraction/normalization logic changes semantically, so migrations
  // know which documents need reprocessing vs. can be interpreted as-is.
  val schemaVersion: Int = 1,
)
