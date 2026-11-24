package org.sempods.commons.logging

import org.junit.platform.launcher.LauncherSessionListener
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertTrue

class BindSlf4jBeforeTestsTest {

  /**
   * The listener only helps if the launcher finds it. Without the `META-INF/services` entry
   * nothing fails, and the flake comes back.
   */
  @Test
  fun `the launcher finds the listener that binds SLF4J before the first test class`() {
    val listeners = ServiceLoader.load(LauncherSessionListener::class.java).map { it::class }

    assertTrue(BindSlf4jBeforeTests::class in listeners, "registered listeners: $listeners")
  }
}
