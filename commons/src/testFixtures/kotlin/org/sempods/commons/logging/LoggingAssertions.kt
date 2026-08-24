package org.sempods.commons.logging

import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The check every artifact with a `main` runs on itself: **does this process ship a logging
 * configuration at all?**
 *
 * It asserts on the packaged resources rather than on the live `LoggerContext`, and that is the
 * point: a test JVM is configured by `gradle/logback-test.xml` through
 * `-Dlogback.configurationFile`, so inspecting what Logback happens to have loaded would say
 * nothing about what the image will do. What the image does is decided by whether `logback.xml` is
 * on its classpath — and when it is not, Logback falls back to root DEBUG with no MDC in the
 * pattern and says so in one line nobody reads. Two deployments ran that way for months.
 */
object LoggingAssertions {

  private const val APP_CONFIG = "/logback.xml"
  private const val SHARED_BASE = "/org/sempods/commons/logging/logback-base.xml"

  fun assertAppLoggingConfigured() {
    val appConfig = assertNotNull(
      LoggingAssertions::class.java.getResource(APP_CONFIG),
      "no `logback.xml` on this artifact's classpath — the process would run on Logback's " +
        "DEBUG fallback with no MDC. See `docs/logging.md`.",
    ).readText()

    assertNotNull(
      LoggingAssertions::class.java.getResource(SHARED_BASE),
      "`$SHARED_BASE` is missing — `:commons` is not on this artifact's runtime classpath, so the " +
        "`<include>` in its `logback.xml` would resolve to nothing.",
    )

    assertTrue(
      SHARED_BASE.removePrefix("/") in appConfig,
      "this artifact's `logback.xml` does not include the shared base. Levels, the `[%X]` trace " +
        "label and the `LOG_LEVEL` / `LOG_FORMAT` knobs come from there; a private copy drifts.",
    )
  }
}
