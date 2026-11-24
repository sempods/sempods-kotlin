package org.sempods.commons.logging

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import org.slf4j.ILoggerFactory
import org.slf4j.LoggerFactory

/**
 * Binds SLF4J once, on the launcher's thread, before JUnit starts the first test class — so that
 * no logger in this test JVM is ever one of SLF4J's substitutes.
 *
 * While SLF4J binds its provider, every other thread that asks for a logger gets a
 * `SubstituteLogger`, and binding hands them over afterwards. In SLF4J 2.0 that handover is not
 * atomic. A logger created as the provider finishes can miss it and stay detached for the rest of
 * the JVM: its lines go to a queue that is cleared, or to NOP. The suites start all their classes
 * at once, and an `object` that initializes its `KotlinLogging.logger {}` in that moment is
 * silent from then on. `CapturedLog` then sees nothing, though the code logged, and the
 * console does not show the line either.
 *
 * Registered in `META-INF/services`, so it runs in every test JVM that has these fixtures on its
 * classpath.
 */
internal class BindSlf4jBeforeTests : LauncherSessionListener {

  override fun launcherSessionOpened(session: LauncherSession) {
    boundLoggerFactory()
  }
}

/**
 * SLF4J's factory once binding has finished, binding it on this thread if nobody has yet.
 *
 * `LoggerFactory` binds while it holds its own class monitor, and it hands the substitutes over
 * before it releases it. Taking that monitor therefore waits out a binding in progress. Without
 * it, a caller could see the binding as done while the handover is still running.
 */
internal fun boundLoggerFactory(): ILoggerFactory =
  synchronized(LoggerFactory::class.java) { LoggerFactory.getILoggerFactory() }
