package org.sempods.pods

import org.sempods.pods.mongo.persist.toObjectIdOrNull
import org.sempods.pods.mongo.persist.toPodId
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What a pod id is allowed to be, and what this implementation's key becomes. */
class PodIdTest {

  @Test
  fun `a token of letters, digits, hyphen and underscore is a pod id`() {
    for (raw in listOf("a", ObjectId().toHexString(), "pod-1", "pod_1", "A-Z_0-9", "x".repeat(64))) {
      assertEquals(raw, PodId(raw).value, "should be a pod id: '$raw'")
    }
  }

  @Test
  fun `anything a path or a key would have to escape is not`() {
    // A store puts the value straight into a path segment or an object key.
    for (raw in listOf("", " ", "a b", "a/b", "a.b", "..", "lost+found", "pod:1", "ü", "x".repeat(65))) {
      assertFailsWith<IllegalArgumentException>("should not be a pod id: '$raw'") { PodId(raw) }
      assertNull(PodId.parseOrNull(raw), "should not parse: '$raw'")
    }
  }

  @Test
  fun `parseOrNull answers rather than throws, because a store reads foreign names too`() {
    assertEquals(PodId("pod-1"), PodId.parseOrNull("pod-1"))
    assertNull(PodId.parseOrNull("not a pod id"))
  }

  @Test
  fun `ids order by their token, so a report of refs is stable between runs`() {
    assertEquals(
      listOf(PodId("a"), PodId("b"), PodId("c")),
      listOf(PodId("c"), PodId("a"), PodId("b")).sorted(),
    )
  }

  @Test
  fun `toString is the token itself, so a log line reads as the id`() {
    assertEquals("pod-1", "${PodId("pod-1")}")
  }

  @Test
  fun `an ObjectId round trips through PodId unchanged`() {
    // Character for character, because the token a store writes into a path or a key is this hex.
    val objectId = ObjectId()

    val podId = objectId.toPodId()

    assertEquals(objectId.toHexString(), podId.value)
    assertEquals(objectId, podId.toObjectIdOrNull())
  }

  @Test
  fun `a well-formed pod id that is not shaped like ours converts back to nothing`() {
    // What lets `PodMediaFacade.reconcile` keep a foreign prefix out of its report. Shape only —
    // an id another deployment minted the same way converts back fine.
    assertNotNull(PodId.parseOrNull("not-a-pod-of-ours"))
    assertNull(PodId("not-a-pod-of-ours").toObjectIdOrNull())
    assertNotNull(PodId(ObjectId().toHexString()).toObjectIdOrNull())
  }
}
