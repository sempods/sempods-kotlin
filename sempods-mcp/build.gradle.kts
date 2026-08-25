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
  // The pod HTTP surface and its SSRF guard live here now, shared with every consumer rather
  // than re-spelled per service. RDF4J rides along on the runtime classpath unused — the price of
  // one client instead of two, and cheaper than the drift two guards produced.
  implementation(project(":sempods-client"))
  implementation(project(":sempods-auth-core"))

  // The MCP tool catalog and JSON-RPC envelope, shared with the pod-immanent MCP in
  // `:sempods-server`. Framework-free — Jackson and nothing else — so neither surface inherits the
  // other's HTTP stack through it.
  implementation(project(":sempods-mcp-core"))

  // The W3C trace binding for Ktor: the inbound interceptor and the outbound client plugin.
  implementation(project(":commons-ktor"))
  implementation(project(":commons-mongo"))

  // HTTP server — the single MCP surface (JSON-RPC over POST), standalone (no framework)
  implementation(libs.ktorServerCore)
  // Ktor's own vocabulary, named directly: `AttributeKey` and the URL builders, the coroutine
  // context the handlers suspend in, and the buffers a streamed response body is read from.
  implementation(libs.ktorUtils)
  implementation(libs.kotlinxCoroutines)
  implementation(libs.kotlinxIoCore)
  implementation(libs.ktorServerNetty)
  implementation(libs.ktorServerCallLogging)

  // No HTTP *client* here any more: the pod System layer and the pod OAuth surface both go through
  // `:sempods-client`'s transport, which carries the SSRF resolve-and-pin this service used to own.

  // JSON — JSON-RPC envelopes + JSON-LD payloads. `jackson-databind` is where `JsonNode` and the
  // mapper are; the `java.time` codecs are a registration on that mapper and nothing names them.
  implementation(libs.jacksonDatabind)
  implementation(libs.jacksonKotlin)
  runtimeOnly(libs.jackson)

  // Logging
  implementation(libs.bundles.logging)
  // The logback binding, `runtimeOnly` because no source here names it. This artifact owns a
  // `main`, so it is the one that gets to choose a binding — and its `logback.xml` is the
  // configuration that ships with that choice.
  runtimeOnly(libs.bundles.loggingBinding)

  // DI
  implementation(libs.guice)

  // JWT — service-as-resource-server bearer handling + OIDC relying-party WebID-JWT verification
  implementation(libs.jwt)

  // The OAuth/OIDC protocol message layer, for both roles this service plays: parsing what a pod
  // answers as its client, and building what an AI client receives as its authorization server.
  // `sempods-auth-core` keeps it `implementation`, so it rides the runtime classpath here already
  // — this line is what puts it on the compile one. Worth it for the same reason auth-core gives:
  // an authorization request cannot be built without its `response_type`, and every hand-rolled
  // half of this that review looked at had a defect in exactly what the SDK does for free.
  implementation(libs.oidcSdk)

  // MongoDB (raw sync driver — no Morphia; standalone like sempods-auth)
  // Connection registry + token vault + OAuth server stores, keyed (user, profile, pod).
  // `bson` alongside it, for the `Document` and `ObjectId` the stores name.
  implementation(libs.mongodb)
  implementation(libs.bson)

  // Tests (CIO only for test clients outside the hardened pod-fetch path)
  // `LoggingAssertions`, for the configuration test every artifact with a `main` runs.
  testImplementation(testFixtures(project(":commons")))
  testImplementation(libs.ktorServerTestHost)
  testImplementation(libs.ktorClientCore)
  testImplementation(libs.ktorClientCio)
  testImplementation(libs.kotlinxCoroutinesTest)
  // `PodTokenProviderTest` waits for the refresh scheduler, and drives a mock HTTP server — the
  // two libraries `libs.bundles.test` no longer carries for every module.
  testImplementation(libs.awaitility)
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)

  // TODO: Add rdf4j (libs.bundles.rdf4j) for M7 AST SPARQL subset rewriting; whole-pod queries only until then.
}

application {
  mainClass = "org.sempods.mcp.SempodsMcpMainKt"
}

jib {
  from {
    image = "ghcr.io/haed/java-base:latest"
  }
  to {
    image = "ghcr.io/haed/sempods-mcp"
    tags = setOf("latest")
  }
  container {
    mainClass = "org.sempods.mcp.SempodsMcpMainKt"
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
    ports = listOf("8092")
  }
}

// Not parallel-safe yet: `PodOAuthClientTest` starts and stops a `ClientAndServer` per test
// *method*, and under concurrent classes that churn intermittently leaves its client talking to a
// port the server no longer owns — a `ClientException` carrying someone else's 404. The class does
// not need a server per method; it owns the instance either way. Moving the start to `@BeforeAll`
// and re-registering expectations per method is the fix; see
// the maintainer's internal roadmap.
tasks.test { systemProperty("junit.jupiter.execution.parallel.enabled", "false") }
