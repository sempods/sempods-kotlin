package org.sempods.mcp.oauth

import com.mongodb.client.MongoDatabase
import org.sempods.auth.core.RefreshTokenStore
import org.sempods.mcp.SempodsMcpCollections
import java.time.Duration
import java.time.Instant

/** A refresh token this service issued, with the owner already resolved. */
typealias McpRefreshToken = RefreshTokenStore.Token<McpRefreshTokenStore.Owner>

/**
 * The refresh tokens of this service's own authorization server: [RefreshTokenStore] with an owner
 * of `(user, profile, clientId)`.
 *
 * Nothing else — the pod server's three bulk revocations have no counterpart here, because a
 * connection is dropped by deleting it rather than by sweeping the tokens that mention it.
 *
 * @param collectionName the production name is the default; a test points an instance at its own.
 */
class McpRefreshTokenStore(db: MongoDatabase, collectionName: String = SempodsMcpCollections.OAUTH_REFRESH_TOKENS) {

  /**
   * @param user the person, as this service knows them.
   * @param profile the tenant — a token belongs to its profile's AS and is refused at another's.
   */
  data class Owner(
    val user: String,
    val profile: String,
    val clientId: String,
  )

  private val store = RefreshTokenStore<Owner>(
    db = db,
    collectionName = collectionName,
    ownerIndexFields = listOf(FIELD_USER, FIELD_PROFILE, FIELD_CLIENT_ID),
    writeOwner = {
      put(FIELD_USER, it.user)
      put(FIELD_PROFILE, it.profile)
      put(FIELD_CLIENT_ID, it.clientId)
    },
    readOwner = {
      Owner(
        user = getString(FIELD_USER),
        profile = getString(FIELD_PROFILE),
        clientId = getString(FIELD_CLIENT_ID),
      )
    },
  )

  /**
   * First token of a new family (from the `authorization_code` exchange), on this service's terms:
   * [IDLE] as the row's window and [ABSOLUTE] from now as the family's deadline.
   *
   * Every family is minted under [KIND], because this service has one lifetime class and no consent
   * control to choose between two.
   */
  fun issueNewFamily(
    user: String,
    profile: String,
    clientId: String,
    scopes: Set<String>,
  ): RefreshTokenStore.Issued<Owner> = store.issueNewFamily(
    owner = Owner(user = user, profile = profile, clientId = clientId),
    scopes = scopes,
    kind = KIND,
    endsAt = Instant.now().plus(ABSOLUTE),
    ttlSeconds = IDLE.seconds,
  )

  /**
   * Successor token in an existing family (rotation), on [IDLE]'s window and under the family's
   * deadline.
   *
   * **A family reaching this without a deadline takes its predecessor's expiry as one.** That is
   * every family minted before this service had [ABSOLUTE], with or without a [KIND]. Taking that
   * expiry extends nothing, because the store's clamp hands the successor the same instant. It is
   * the rule `PodRefreshTokenStore.issueInFamily` applies, and wider than the shared store's own,
   * which keys on a missing [RefreshTokenStore.Token.kind] alone.
   */
  fun issueInFamily(
    previous: McpRefreshToken,
    scopes: Set<String>,
  ): RefreshTokenStore.Issued<Owner> = store.issueInFamily(
    previous = previous.copy(endsAt = previous.endsAt ?: previous.expiresAt),
    scopes = scopes,
    ttlSeconds = IDLE.seconds,
  )

  fun lookup(plaintext: String): RefreshTokenStore.Lookup<Owner> = store.lookup(plaintext)

  /** Marks rotated; `false` means a concurrent caller already did — treat as reuse. */
  fun markRotated(tokenHash: String): Boolean = store.markRotated(tokenHash)

  fun revokeFamily(familyId: String): Long = store.revokeFamily(familyId)

  /** Diagnostics and tests. */
  fun findByFamily(familyId: String): List<McpRefreshToken> = store.findByFamily(familyId)

  internal companion object {

    /** The one lifetime class this service mints — see [issueNewFamily]. */
    private const val KIND = "durable"

    /** How long a family survives unused. Every rotation renews it. */
    internal val IDLE: Duration = Duration.ofSeconds(RefreshTokenStore.DEFAULT_TTL_SECONDS)

    /**
     * How long a family lives at most, from its code exchange, however often it rotates.
     *
     * Rotation with reuse detection (RFC 9700 §4.14.2) ends a stolen family. A family its client
     * keeps using renews itself, so without this an AI client that refreshes weekly stays connected
     * forever. RFC 10017 §6.3.2.3 requires such a bound of browser-based applications, which these
     * RFC 7591 clients are not. Here it is hygiene.
     *
     * Longer than a pod's default of 180 days, because at its end a person reconnects the AI client through a
     * consent dialog in a desktop application. A ceiling that arrives too often is one somebody
     * raises. The pod connections behind this service end on their own pods' terms either way.
     */
    internal val ABSOLUTE: Duration = Duration.ofDays(365)

    // The owner's field names, in the order a row on disk carries them.
    private const val FIELD_USER = "user"
    private const val FIELD_PROFILE = "profile"
    private const val FIELD_CLIENT_ID = "clientId"
  }
}
