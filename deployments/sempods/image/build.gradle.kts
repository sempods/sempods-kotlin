plugins {
  application
  id("com.google.cloud.tools.jib") version "3.5.4"
}

dependencies {

  // dependent projects — the pod server alone. The applications that consume a pod are
  // deliberately absent: they ship as their own artifacts and reach this server over HTTP.
  // No application framework either, and that is the whole decoupling: this image ships the pod server
  // without an application framework behind it.
  implementation(project(":commons"))
  implementation(project(":sempods-server"))
  implementation(libs.guice)
  implementation(libs.bundles.logging)
  // The logback binding, `runtimeOnly` because no source here names it. This artifact owns a
  // `main`, so it is the one that gets to choose a binding — and its `logback.xml` is the
  // configuration that ships with that choice.
  runtimeOnly(libs.bundles.loggingBinding)

  // The S3-backed media store. On the classpath *here* and nowhere else, which is what lets
  // `SempodsMediaModule` select it while `:sempods-server` still has no idea it exists — the
  // dependency runs sibling→seam, so the choice can only be made by whoever holds both.
  implementation(project(":sempods-media-s3"))

  // implementation libs
  // `SempodsServerStarter` builds and starts the Jetty `Server` itself; Jersey rides on that
  // connector without this composition naming a Jersey type, so its container is `runtimeOnly`.
  // `:commons-jaxrs` is deliberately absent: `JaxRsServerModule` is reached through
  // `:sempods-server`, which exports it, and a line here would only say it twice.
  implementation(libs.jettyServer)
  runtimeOnly(libs.jerseyJettyHttp)

  // `LoggingAssertions`, for the configuration test every artifact with a `main` runs.
  testImplementation(testFixtures(project(":commons")))

  // The media store is selected here and nowhere else — `:sempods-server` cannot name a store
  // that ships as its own sibling module — so this is the only place a test can see that
  // selection.
  testImplementation(libs.bundles.test)
}

application {

  val defaultJvmArgs = mutableListOf(
    "-server",
    "-XX:+ShowCodeDetailsInExceptionMessages",
    // Silences kotlin-logging's stdout startup line; see `docs/logging.md`.
    "-Dkotlin-logging.logStartupMessage=false",
  )
  applicationDefaultJvmArgs = defaultJvmArgs

  mainClass = "org.sempods.deployment.SempodsServerStarter"
}

jib {
  from {
    image = "ghcr.io/haed/java-base:latest"
  }
  to {
    image = "ghcr.io/haed/sempods"
    tags = setOf("latest")
  }
  container {
    val additionalJvmFlags = application.applicationDefaultJvmArgs.toMutableList()
    additionalJvmFlags.addAll(
      listOf(
        "-XX:+UseG1GC",
        "-XX:+CrashOnOutOfMemoryError",
        "-XX:+PrintCommandLineFlags",
        "-XX:+DisableExplicitGC",
      ),
    )
    jvmFlags = additionalJvmFlags

    // SEMPODS_HTTP_PORT. The image used to declare 8888 — a second connector, which never ran
    // here alone; compose covered the mismatch with an explicit `expose`.
    ports = listOf("8090")
    environment = buildMap {
      put("PRODUCTION", "true")
    }
  }
}
