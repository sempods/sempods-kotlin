package org.sempods.api.pod.system.auth

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Persistent RSA signing key for pod-scoped OAuth access tokens.
 *
 * Before persistence, `PodTokenIssuer` generated an ephemeral RSA key on every JVM boot,
 * which meant every deploy invalidated all outstanding access tokens (observed live:
 * `[oauth/access] Token signature verification failed` mid-session). Persisting the key
 * fixes that at the cost of one Mongo read on startup.
 *
 * The stored `jwk` is a JSON Web Key containing the *private* parameters too, so the
 * process can both sign (full JWK) and publish (public-only view via JWKS endpoint).
 * Private keys at rest are not encrypted — consistent with how the rest of the pod's
 * secrets (OAuth client secrets, etc.) are stored; encryption-at-rest is a separate
 * deployment concern.
 *
 * Rotation is not implemented yet — for now there is exactly one active key per service
 * instance. The schema already supports multiple keys + a `retiredAt` timestamp so a
 * later rotation increment is a pure logic change, not a migration.
 *
 * A plain data class: the collection name, the two indexes and the mapping onto a BSON document
 * live in [OAuthSigningKeyDao], which talks to the driver. There is no no-arg constructor either —
 * it existed only so Morphia's `PojoCodec` had an entry point, and its `MorphiaUtil` sentinels were
 * values no reader ever saw.
 */
data class OAuthSigningKeyDbo(
  val id: ObjectId? = null,

  val kid: String,
  val algorithm: String,

  /** Full JWK JSON including private parameters. */
  val jwk: String,

  val createdAt: Instant = Instant.now(),

  /** When set, the key is no longer used for signing but may still appear in JWKS for verification. */
  val retiredAt: Instant? = null,
)
