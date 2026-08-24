package org.sempods

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * What this server writes into, spelled out once.
 *
 * The names used to live in thirteen DAO companions, where the set they formed was visible
 * nowhere — adding a collection was invisible in review, and the two oldest had drifted out of
 * the namespace the other eleven shared without anyone noticing.
 *
 * **This is a guard against a silent change, not a restatement of the source.** A name here is a
 * contract with rows on disk: changing one is a data migration, and the point of the literals
 * below is that the migration cannot be forgotten, because the edit alone turns this red.
 *
 * No database is involved. A booted server does not hold every collection anyway — most DAOs are
 * bound lazily, so the collection appears at the first request that needs it — which is exactly
 * why this reads the declaration rather than asking a running MongoDB.
 */
class SempodsCollectionsTest {

  @Test
  fun `the collection names are unchanged`() {
    assertEquals(
      listOf(
        "pods",
        "resources",
        "contexts",
        "grants",
        "webIdGrants",
        "media",
        "oauth.serviceClients",
        "oauth.serviceAuditLog",
        "oauth.clientRegistrations",
        "oauth.signingKeys",
        "oauth.refreshTokens",
        "oauth.loginStates",
        "oauth.consentTransactions",
        "oauth.authCodes",
        "oauth.reauthChallenges",
      ),
      SempodsCollections.ALL,
    )
  }

  /**
   * Two collections cannot share a name, and the way that would happen is a copy-paste in
   * [SempodsCollections] rather than a deliberate decision — one that the list assertion above
   * would report as a confusing diff rather than as the duplicate it is.
   */
  @Test
  fun `no name is used twice`() {
    assertEquals(SempodsCollections.ALL.size, SempodsCollections.ALL.toSet().size)
  }

  /**
   * The service prefix is gone, and it must not creep back.
   *
   * `sempods.` in front of every name said what the database name already said, and the database
   * is `sempods-server` now. What a prefix carries here is the *kind* of thing — `oauth.` for the
   * OAuth machinery — so a name starting with the service is a name that says nothing.
   */
  @Test
  fun `no name carries a service prefix`() {
    val offenders = SempodsCollections.ALL.filter {
      it.startsWith("sempods.") || it.startsWith("mcp.") || it.startsWith("pods.")
    }
    assertEquals(emptyList(), offenders)
  }
}
