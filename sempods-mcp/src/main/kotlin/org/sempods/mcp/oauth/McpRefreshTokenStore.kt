package org.sempods.mcp.oauth

import com.mongodb.client.MongoDatabase
import org.sempods.auth.core.RefreshTokenStore
import org.sempods.mcp.SempodsMcpCollections

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

  /** First token of a new family (from the `authorization_code` exchange). */
  fun issueNewFamily(
    user: String,
    profile: String,
    clientId: String,
    scopes: Set<String>,
    ttlSeconds: Long = RefreshTokenStore.DEFAULT_TTL_SECONDS,
  ): RefreshTokenStore.Issued<Owner> =
    store.issueNewFamily(Owner(user = user, profile = profile, clientId = clientId), scopes, ttlSeconds)

  /** Successor token in an existing family (rotation). */
  fun issueInFamily(
    previous: McpRefreshToken,
    scopes: Set<String>,
    ttlSeconds: Long = RefreshTokenStore.DEFAULT_TTL_SECONDS,
  ): RefreshTokenStore.Issued<Owner> = store.issueInFamily(previous, scopes, ttlSeconds)

  fun lookup(plaintext: String): RefreshTokenStore.Lookup<Owner> = store.lookup(plaintext)

  /** Marks rotated; `false` means a concurrent caller already did — treat as reuse. */
  fun markRotated(tokenHash: String): Boolean = store.markRotated(tokenHash)

  fun revokeFamily(familyId: String): Long = store.revokeFamily(familyId)

  /** Diagnostics and tests. */
  fun findByFamily(familyId: String): List<McpRefreshToken> = store.findByFamily(familyId)

  private companion object {

    // The owner's field names, in the order a row on disk carries them.
    const val FIELD_USER = "user"
    const val FIELD_PROFILE = "profile"
    const val FIELD_CLIENT_ID = "clientId"
  }
}
