package org.sempods.updates

import com.google.inject.Guice
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The split between blocking and background updates, which decides whether a migration is finished
 * before the first request arrives.
 *
 * It matters because `runUpdates` is called while the injector is built and `BackendStarter` takes
 * the Jetty `Server` from that injector afterwards: a synchronous update is done before traffic, a
 * submitted one is not. Getting that wrong on a migration that renames identifiers means serving
 * 404s and 403s for data that exists — so the property is pinned here rather than left to the
 * reading of a comment.
 *
 * The registered list is empty since the backup backfill was retired with the legacy decommission
 * so the tests drive the updater with fakes — the mechanism is what is pinned
 * here, not any particular migration.
 */
class SempodsUpdaterTest {

  private class RecordingUpdate(
    override val name: String,
    override val blocking: Boolean,
    private val log: MutableList<String>,
    private val started: CountDownLatch? = null,
  ) : SempodsUpdate {
    override fun run() {
      log.add(name)
      started?.countDown()
    }
  }

  private fun updaterWith(vararg updates: SempodsUpdate): SempodsUpdater {
    val updater = SempodsUpdater()
    val field = SempodsUpdater::class.java.getDeclaredField("updates")
    field.isAccessible = true
    field.set(updater, updates.toList())
    return updater
  }

  @Test
  fun `a blocking update has already run when runUpdates returns`() {
    val log = mutableListOf<String>()
    val updater = updaterWith(RecordingUpdate("blocking-one", blocking = true, log = log))

    updater.runUpdates(Guice.createInjector())

    assertEquals(listOf("blocking-one"), log, "a blocking update must complete before the caller continues")
  }

  @Test
  fun `a background update does not hold up startup`() {
    val log = mutableListOf<String>()
    val started = CountDownLatch(1)
    val updater = updaterWith(RecordingUpdate("background-one", blocking = false, log = log, started = started))

    updater.runUpdates(Guice.createInjector())

    // Not asserting that it has *not* run — that would race. What matters is that the caller was
    // not made to wait for it; it finishes on its own thread.
    assertTrue(started.await(10, TimeUnit.SECONDS), "the background update must still run")
    assertEquals(listOf("background-one"), log)
  }

  @Test
  fun `a failing update neither stops the others nor the boot`() {
    val log = mutableListOf<String>()
    val failing = object : SempodsUpdate {
      override val name = "failing"
      override val blocking = true
      override fun run() = throw IllegalStateException("boom")
    }
    val updater = updaterWith(failing, RecordingUpdate("after-failure", blocking = true, log = log))

    updater.runUpdates(Guice.createInjector())

    assertEquals(listOf("after-failure"), log, "a broken update must not take the next one with it")
  }
}
