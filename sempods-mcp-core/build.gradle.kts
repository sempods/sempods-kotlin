plugins {
  `java-library`
}

dependencies {

  // No framework. The MCP tool schemas and JSON-RPC envelope are data structures with
  // serialization annotations on them, and `PodToolExecutor` is a mapping onto an HTTP client that
  // brings its own: two HTTP *stacks* (JAX-RS in `sempods-server`, Ktor in `sempods-mcp`) both
  // depend on this module precisely because it imposes neither. Same shape as `sempods-auth-core`.
  //
  // `api` because Jackson is in this module's surface, not behind it: `ToolCatalog.validate` takes
  // a `JsonNode`, and a consumer serializing these types needs the annotations to mean something.
  // The same reasoning `sempods-commons-mongo` gives for re-exporting the driver. The `java.time` codecs
  // stay for the mapper that reads these envelopes; nothing names them.
  api(libs.jacksonDatabind)
  runtimeOnly(libs.jackson)

  // `PodToolExecutor` runs the thirteen tools against one pod over the client core's endpoint
  // groups, and `PodToolPlan.Call.execute` takes a `SempodsPod` — this module's surface, so `api`
  // rather than `implementation`. Declaring it here rather than defining a second port interface is
  // deliberate: a port would be the `PodApi` facade the consolidation deleted, rebuilt one module
  // over.
  api(project(":sempods-client-core"))

  // `api` because OkHttp is in this module's surface: `podAt` takes the `Call.Factory` a pod handle
  // runs on. It arrives through the core's `api` either way — declaring it is the repository's rule
  // that a type you compile against is one you say you have.
  api(libs.okhttp)

  // `ReauthorizeChallengeStore` is Mongo-backed, so the driver arrives — `api`, because a
  // `MongoDatabase` is a constructor parameter and therefore this module's surface. It is the
  // driver, not a framework: it constrains neither surface's HTTP stack, which is the whole reason
  // both can depend on this module. `sempods-auth-core` makes the same declaration for the same
  // reason. `bson` stays behind the wall, though: the constructor parameter is a `MongoDatabase`,
  // and the `Document` the store reads is its own business — `:sempods-auth-core` exports it
  // because its stores hand one back.
  api(libs.mongodb)
  implementation(libs.bson)
  implementation(project(":sempods-commons-mongo"))

  // `:sempods-commons` for `BaseModule`, which `SempodsMcpCoreModule` extends — `api`, because a supertype
  // is part of a class's surface.
  api(project(":sempods-commons"))

  // Only `SempodsMcpCoreModule` needs Guice, and a consumer wiring the store by hand must not
  // inherit a container to get a tool catalog. Same trade `sempods-commons` and `sempods-auth-core` make.
  compileOnly(libs.guice)

  // No `libs.bundles.logging`: no class in here logs — the two surfaces log, because what a failed
  // tool call means is theirs to decide. A dependency this module cannot justify in a comment is a
  // dependency it does not have.

  testImplementation(libs.jacksonKotlin)
  // `PodToolExecutorTest` serves a pod rather than stubbing one: the mapping it checks is the
  // request that leaves, which only a server can be asked about.
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
