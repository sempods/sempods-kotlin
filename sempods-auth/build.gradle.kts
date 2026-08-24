plugins {
  `java-library`
  application
  id("com.google.cloud.tools.jib") version "3.5.4"
}

dependencies {

  // dependent projects
  // `api` because `BaseModule` is this class's supertype, so an embedder cannot name it without.
  // Guice itself stays `implementation`, as it is in `:commons`: whoever installs a module calls
  // `Guice.createInjector` and has declared it. The rest — Ktor, Mongo, Jackson — Guice reaches by
  // reflection at wiring time (#15).
  api(project(":commons"))
  implementation(project(":sempods-auth-core"))

  // The W3C trace binding for Ktor: the inbound interceptor and the outbound client plugin.
  implementation(project(":commons-ktor"))
  implementation(project(":commons-mongo"))

  // HTTP server — standalone, no application framework
  implementation(libs.ktorServerCore)
  implementation(libs.ktorServerNetty)
  implementation(libs.ktorServerCallLogging)

  // HTTP client — for OIDC token exchange
  implementation(libs.ktorClientCore)
  implementation(libs.ktorClientCio)

  // Logging
  implementation(libs.bundles.logging)
  // The logback binding, `runtimeOnly` because no source here names it. This artifact owns a
  // `main`, so it is the one that gets to choose a binding — and its `logback.xml` is the
  // configuration that ships with that choice.
  runtimeOnly(libs.bundles.loggingBinding)

  // DI
  implementation(libs.guice)

  // JWT / JWKS
  implementation(libs.jwt)

  // The OAuth/OIDC message layer. Declared here rather than inherited: `sempods-auth-core` keeps
  // it as `implementation` on purpose, so a service that wires OAuth by hand is not handed a
  // protocol library it did not ask for. This service uses its types directly, so it says so.
  implementation(libs.oidcSdk)

  // MongoDB (raw driver — no Morphia)
  implementation(libs.mongodb)

  // Tests
  // `LoggingAssertions`, for the configuration test every artifact with a `main` runs.
  testImplementation(testFixtures(project(":commons")))
  testImplementation(libs.ktorServerTestHost)
  testImplementation(libs.bundles.test)
}

application {
  mainClass = "org.sempods.auth.SempodsAuthMainKt"
}

jib {
  from {
    image = "ghcr.io/haed/java-base:latest"
  }
  to {
    image = "ghcr.io/haed/sempods-auth"
    tags = setOf("latest")
  }
  container {
    mainClass = "org.sempods.auth.SempodsAuthMainKt"
    jvmFlags = listOf(
      "-server",
      "-XX:+UseG1GC",
      "-XX:+CrashOnOutOfMemoryError",
      "-XX:+PrintCommandLineFlags",
      "-XX:+DisableExplicitGC",
      "-XX:+ShowCodeDetailsInExceptionMessages",
      // Silences kotlin-logging's stdout startup line; see `docs/logging.md`.
      "-Dkotlin-logging.logStartupMessage=false",
    )
    ports = listOf("8091")
  }
}
