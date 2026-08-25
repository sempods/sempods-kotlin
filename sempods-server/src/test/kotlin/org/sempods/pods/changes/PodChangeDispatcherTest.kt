package org.sempods.pods.changes

import org.sempods.pods.mongo.persist.toPodId
import org.bson.types.ObjectId
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Docker-free unit test of the durability contract: the single critical listener runs first and
 * its failure propagates (so `doWork` compensates the store); best-effort failures are swallowed.
 */
class PodChangeDispatcherTest {

  private class TestListener(
    override val durability: Durability,
    private val fail: Boolean = false,
  ) : PodChangeListener {
    var ran = false
    override fun onChange(changeSet: PodChangeSet) {
      ran = true
      if (fail) throw RuntimeException("boom from $durability")
    }
  }

  private fun changeSet() = PodChangeSet(
    podId = ObjectId().toPodId(),
    podName = "test-pod",
    resources = listOf(
      ResourceChange(URI("https://example.org/e1"), ChangeOperation.UPDATED, emptySet(), LinkedHashModel(), LinkedHashModel(), LinkedHashModel()),
    ),
  )

  @Test
  fun `critical listener failure propagates`() {
    val critical = TestListener(Durability.CRITICAL, fail = true)
    assertFailsWith<RuntimeException> { PodChangeDispatcher(setOf(critical)).dispatch(changeSet()) }
  }

  @Test
  fun `best-effort failure is swallowed and the critical sink still ran`() {
    val critical = TestListener(Durability.CRITICAL)
    val bestEffort = TestListener(Durability.BEST_EFFORT, fail = true)

    // Does not throw despite the best-effort failure.
    PodChangeDispatcher(setOf(critical, bestEffort)).dispatch(changeSet())

    assertTrue(critical.ran)
    assertTrue(bestEffort.ran)
  }

  @Test
  fun `a critical failure runs before and short-circuits best-effort sinks`() {
    val critical = TestListener(Durability.CRITICAL, fail = true)
    val bestEffort = TestListener(Durability.BEST_EFFORT)

    assertFailsWith<RuntimeException> { PodChangeDispatcher(setOf(critical, bestEffort)).dispatch(changeSet()) }

    assertTrue(critical.ran)
    assertFalse(bestEffort.ran, "critical runs first; its failure aborts before best-effort sinks")
  }

  @Test
  fun `more than one critical listener is rejected at construction`() {
    assertFailsWith<IllegalArgumentException> {
      PodChangeDispatcher(setOf(TestListener(Durability.CRITICAL), TestListener(Durability.CRITICAL)))
    }
  }

  @Test
  fun `an empty change set dispatches to nobody`() {
    val listener = TestListener(Durability.BEST_EFFORT)
    PodChangeDispatcher(setOf(listener)).dispatch(PodChangeSet(ObjectId().toPodId(), "test-pod", emptyList()))
    assertFalse(listener.ran)
  }
}
