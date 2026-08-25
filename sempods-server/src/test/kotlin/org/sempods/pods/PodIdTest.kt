package org.sempods.pods

import org.sempods.pods.mongo.persist.toObjectIdOrNull
import org.sempods.pods.mongo.persist.toPodId
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A pod id is a tenant key: an identity, and no promise about its form. */
class PodIdTest {

  @Test
  fun `any non-empty token is a pod id`() {
    // Deliberately including values no path or object key would take unescaped. A store that cannot
    // use a token as-is derives something it can; that is its mapping to own, not this type's rule
    // to impose — see `FilesystemPodMediaStore.resolve`.
    for (raw in listOf("a", ObjectId().toHexString(), "pod-1", "a/b", "a b", "ü", "x".repeat(4096))) {
      assertEquals(raw, PodId(raw).value, "should be a pod id: '$raw'")
    }
  }

  @Test
  fun `an empty token names nothing and is refused`() {
    assertFailsWith<IllegalArgumentException> { PodId("") }
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
  fun `a token this deployment did not mint converts back to nothing`() {
    // What lets `PodMediaFacade.reconcile` keep a foreign tenant out of its report — no store can,
    // since the form of a pod id is not part of the contract. Shape only: a token another
    // deployment minted the same way converts back fine.
    assertNull(PodId("not-a-pod-of-ours").toObjectIdOrNull())
    assertNotNull(PodId(ObjectId().toHexString()).toObjectIdOrNull())
  }
}
