import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
  id("org.jetbrains.kotlin.jvm") version "2.4.10"
}

// The version catalog, resolved once here: the generated `libs` accessor exists only in a build
// script's own scope, not on the `Project` receiver inside `subprojects { }`.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

subprojects {

  // Only the Kotlin plugin: it applies `java` itself, which is what the `java-library` and
  // `java-test-fixtures` plugins in the module scripts build on. There are no Java sources —
  // `compileJava` exists but is always NO-SOURCE.
  apply {
    plugin("org.jetbrains.kotlin.jvm")
  }

  repositories {
    mavenCentral()
  }

  configurations.configureEach {
    // Prefer Angus mail artifacts and avoid conflicting legacy Jakarta mail/activation transitive deps.
    exclude(group = "com.sun.mail", module = "jakarta.mail")
    exclude(group = "com.sun.activation", module = "jakarta.activation")
  }

  java {
    toolchain {
      languageVersion = JavaLanguageVersion.of(25)
    }
  }

  dependencies {
    // The logback binding, for tests only. Production code gets it from whichever artifact owns
    // the `main`; a test JVM has no such artifact, and without a binding every module would log
    // to slf4j's NOP and `logback-test.xml` would be inert. One place beats twelve.
    "testRuntimeOnly"(catalog.findBundle("loggingBinding").get())
  }

  // The other half of `LoggingAssertions`: a library must not carry the logback binding into a
  // consumer's runtime. `implementation` is transitive at runtime, so this is easy to reintroduce by
  // adding one bundle to one build file, and impossible to notice — the consumer just silently gets
  // logback. Applications are exempt: choosing a binding is exactly their job. See `docs/logging.md`.
  val checkNoLoggingBinding = tasks.register("checkNoLoggingBinding") {
    group = "verification"
    description = "Fails if a library module carries the logback binding on its runtime classpath."
    doLast {
      if (plugins.hasPlugin("application")) return@doLast
      val runtimeClasspath = configurations.findByName("runtimeClasspath") ?: return@doLast
      val offenders = runtimeClasspath.incoming.artifacts.artifacts
        .map { it.id.componentIdentifier.displayName }
        .filter { it.startsWith("ch.qos.logback:") }
        .distinct()
      if (offenders.isNotEmpty()) {
        throw GradleException(
          "$path is a library and must declare `libs.bundles.logging` (the facade) only, but its " +
            "runtime classpath carries the binding: ${offenders.joinToString()}. " +
            "The binding belongs to `runtimeOnly(libs.bundles.loggingBinding)` in an artifact that " +
            "owns a `main`. See `docs/logging.md`.",
        )
      }
    }
  }
  tasks.matching { it.name == "check" }.configureEach { dependsOn(checkNoLoggingBinding) }

  tasks.withType<JavaExec>().configureEach {
    // kotlin-logging prints `kotlin-logging: initializing... active logger factory: …` to
    // stdout on first use unless this is off. It fires from a static initializer, before any
    // `main` body runs, so a system property set in code is always too late — it has to be a
    // JVM flag. Documented in `docs/logging.md`.
    systemProperty("kotlin-logging.logStartupMessage", "false")
  }

  tasks.test {
    useJUnitPlatform()

    // One fork per module, deliberately, and `forkEvery` stays unset for the same reason. Each
    // module's suite shares one lazily built injector and one Jetty server
    // (`SempodsIntegrationTest.sempodsInjector` and its siblings), so a second fork would pay for a
    // second boot and then collide with the first on the connector — and the per-class DAO
    // collections (`test.pod.dao` and friends, dropped in `@BeforeEach`) are only distinct *within*
    // a JVM. Concurrency inside a module comes from JUnit, across modules from `org.gradle.parallel`.
    maxParallelForks = 1

    // The test JVM's heap, not the daemon's. Gradle defaults to 512m, which is not much for a JVM
    // running Jetty, an RDF4J store, a Mongo driver pool and up to four Netty mock servers.
    maxHeapSize = "2g"

    testLogging {
      events("passed", "skipped", "failed")
      exceptionFormat = TestExceptionFormat.FULL
      // Off by default. A full run writes ~1,700 tests' worth of log through Gradle's output
      // machinery — 23 MB of console for one run — and once classes run concurrently the lines
      // interleave, so the volume buys nothing back. Failures stay readable through
      // `exceptionFormat = FULL`, and the trace id in `gradle/logback-test.xml`'s pattern is what
      // makes an interleaved log readable at all. `-PtestStdout` brings it back for one run.
      showStandardStreams = project.hasProperty("testStdout")
    }
    // Enable experimental ByteBuddy support for Java 25 (required for MockK)
    jvmArgs("-Dnet.bytebuddy.experimental=true")

    // Test classes run side by side; the methods inside one class do not.
    //
    // That combination is what makes it safe here rather than merely faster. The unit of isolation
    // in this repository is the class: it owns its DAO collection (`test.pod.dao` and friends,
    // dropped in `@BeforeEach`), its pods carry names from `randomId()`, and the mechanism the
    // suites are built on — `GuiceAppTestProxy` — keys its state on
    // `TraceContextHolder.getTraceId()`, a `ThreadLocal`. Methods within a class share all of that,
    // and `same_thread` is what keeps it true.
    //
    // Sizing is deliberately absent: JUnit's default strategy (`dynamic`, one worker per available
    // processor) is the answer until a measurement says otherwise. Set here rather than in a
    // `junit-platform.properties` because this repository has no `buildSrc` and already keeps its
    // shared test configuration in this one block — and because a module that is not ready opts out
    // where a reader looks for it, in its own build file:
    //
    //     tasks.test { systemProperty("junit.jupiter.execution.parallel.enabled", "false") }
    //
    // See `docs/testing.md` §"Running in parallel" for what to do when a test is not safe.
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")

    // Methods stay on one thread, unless `-PtestMethodsConcurrent` asks for the harsher setting —
    // that is how a coupling *inside* a class is forced into the open, and it has to be a project
    // property rather than a `-D` on the command line: `-D` lands on the Gradle daemon, while what
    // the forked test JVM sees is this map. The same distinction the `environment(..)` call below
    // turns on, from the other side.
    systemProperty(
      "junit.jupiter.execution.parallel.mode.default",
      if (project.hasProperty("testMethodsConcurrent")) "concurrent" else "same_thread",
    )

    // One logging configuration for every test JVM — see `gradle/logback-test.xml` for why it is
    // one file and not a `logback-test.xml` per module. Without it the suites run on Logback's
    // DEBUG fallback.
    systemProperty("kotlin-logging.logStartupMessage", "false")
    systemProperty(
      "logback.configurationFile",
      rootProject.layout.projectDirectory.file("gradle/logback-test.xml").asFile.absolutePath,
    )

    // Shared infrastructure, one namespace per module.
    //
    // Every suite reaches the same MongoDB on 27018 and the same connector ports, because
    // `SempodsModule.config` and friends all fall back to the same development defaults. Serially
    // that only cost accumulated garbage: a shared database keeps every previous run's rows, and a
    // suite that walks a collection then spends its time reading other runs' leftovers.
    // Concurrently it would also be wrong — two module suites would race for database and port.
    //
    // `environment(..)` rather than `systemProperty(..)`: `Env.get` resolves the environment
    // before system properties, so a suite that mutates properties (`EnvTest`,
    // `SempodsMediaModuleTest`) cannot shadow these.
    environment(
      "MONGODB_DB_NAME",
      "test-" + project.path.removePrefix(":").replace(':', '-'),
    )

    // Connector ports, one range per module. Fixed rather than picked from an ephemeral socket:
    // `SempodsModule.config` is a companion `by lazy` value read at class initialization, so the
    // port has to exist before any of our code runs. A deterministic value also keeps a failure
    // reproducible. `-PtestPortBase=` moves the range when two builds share a machine.
    //
    // 8090 — the development default — is deliberately not in the table: a locally running dev
    // server does not collide with a test run.
    val testPortBase = (findProperty("testPortBase") as String?)?.toInt() ?: 19000
    when (project.path) {
      ":sempods-server" -> {
        environment("SEMPODS_HTTP_PORT", testPortBase + 10)
        // The token endpoint's per-client budget, which defaults to off in development — and
        // `PodAuthEndpointRateLimitHttpTest` is the only thing that can show the limiter is
        // actually reached on a real connector rather than merely constructed. A small number,
        // and safe to be one: the bucket is keyed by the caller's `X-Forwarded-For`, that test
        // sends a fresh address per case, and no other test in the suite sends the header at all,
        // so nothing else has a bucket to exhaust.
        environment("SEMPODS_TOKEN_RATE_LIMIT_PER_MINUTE", 5)
        // Both halves, because the production defaults differ: the burst there is sized for a
        // provisioning sweep, and inheriting it here would mean 300 requests per case.
        environment("SEMPODS_TOKEN_RATE_LIMIT_BURST", 5)
        // The address tier sits in front of that one, so it has to stay out of its way here: each
        // case brings a fresh address and makes a handful of requests on it. Its own behaviour is
        // `PodTokenRateLimiterTest`'s subject, where the budget is stated per case.
        environment("SEMPODS_TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE", 60)
        environment("SEMPODS_TOKEN_RATE_LIMIT_ADDRESS_BURST", 60)
      }
    }
  }
}
