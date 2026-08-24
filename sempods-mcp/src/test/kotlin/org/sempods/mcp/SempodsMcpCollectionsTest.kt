package org.sempods.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * What the hosted MCP service writes into, spelled out once.
 *
 * Twelve of these were string literals inside the `getCollection(...)` call until the rename —
 * the one shape the other two services had already left behind, and the reason the suites here
 * work around it with a throwaway database per class instead of a collection per test.
 *
 * **A guard against a silent change, not a restatement of the source.** A name here is a contract
 * with rows on disk; the literals are what make the migration impossible to forget.
 *
 * No database is involved — see `SempodsCollectionsTest` in the pod server for why that is the
 * point rather than a shortcut.
 */
class SempodsMcpCollectionsTest {

  @Test
  fun `the collection names are unchanged`() {
    assertEquals(
      listOf(
        "connections",
        "podTokens",
        "profiles",
        "leases",
        "auditLog",
        "oauth.signingKeys",
        "oauth.refreshTokens",
        "oauth.clientRegistrations",
        "oauth.loginStates",
        "oauth.consentTransactions",
        "oauth.webLoginStates",
        "oauth.podConnectStates",
        "oauth.authCodes",
        "oauth.reauthChallenges",
      ),
      SempodsMcpCollections.ALL,
    )
  }

  @Test
  fun `no name is used twice`() {
    assertEquals(SempodsMcpCollections.ALL.size, SempodsMcpCollections.ALL.toSet().size)
  }

  @Test
  fun `no name carries a service prefix`() {
    val offenders = SempodsMcpCollections.ALL.filter { it.startsWith("mcp.") || it.startsWith("sempods.") }
    assertEquals(emptyList(), offenders)
  }
}
