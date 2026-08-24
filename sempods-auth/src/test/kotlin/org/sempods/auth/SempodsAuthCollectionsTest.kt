package org.sempods.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * What the identity service writes into, spelled out once.
 *
 * This service was the odd one out: four snake_case names where the other two used
 * `prefix.camelCase`, and no reason for the difference beyond the order the files were written
 * in. What it had right was the absence of a service prefix — it already had a database of its
 * own.
 *
 * **A guard against a silent change, not a restatement of the source.** A name here is a contract
 * with rows on disk; the literals are what make the migration impossible to forget.
 *
 * No database is involved — see `SempodsCollectionsTest` in the pod server for why that is the
 * point rather than a shortcut.
 */
class SempodsAuthCollectionsTest {

  @Test
  fun `the collection names are unchanged`() {
    assertEquals(
      listOf(
        "webIdProfiles",
        "oauth.signingKeys",
        "oauth.loginStates",
        "oauth.authCodes",
      ),
      SempodsAuthCollections.ALL,
    )
  }

  @Test
  fun `no name is used twice`() {
    assertEquals(SempodsAuthCollections.ALL.size, SempodsAuthCollections.ALL.toSet().size)
  }
}
