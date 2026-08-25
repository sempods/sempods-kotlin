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
  // The same reasoning `commons-mongo` gives for re-exporting the driver. The `java.time` codecs
  // stay for the mapper that reads these envelopes; nothing names them.
  api(libs.jacksonDatabind)
  runtimeOnly(libs.jackson)

  // `PodToolExecutor` runs the thirteen tools against one pod over `PodWireClient`, which is
  // this module's surface — it is a constructor parameter — so `api` rather than `implementation`.
  // RDF4J rides along unused, the same price `sempods-mcp` already pays for having one pod client
  // instead of two (`docs/pod-client.md` §"What the client is not"). Declaring it here
  // rather than defining a second port interface is deliberate: a port would be the
  // `PodApi` facade the consolidation deleted, rebuilt one module over.
  //
  // Jackson stays declared above and is not inherited from here: `:sempods-client` reaches it only
  // transitively through `rdf4j-rio-jsonld` and does not export it.
  api(project(":sempods-client"))

  // `ReauthorizeChallengeStore` is Mongo-backed, so the driver arrives — `api`, because a
  // `MongoDatabase` is a constructor parameter and therefore this module's surface. It is the
  // driver, not a framework: it constrains neither surface's HTTP stack, which is the whole reason
  // both can depend on this module. `sempods-auth-core` makes the same declaration for the same
  // reason. `bson` stays behind the wall, though: the constructor parameter is a `MongoDatabase`,
  // and the `Document` the store reads is its own business — `:sempods-auth-core` exports it
  // because its stores hand one back.
  api(libs.mongodb)
  implementation(libs.bson)
  implementation(project(":commons-mongo"))

  // `:commons` for `BaseModule`, which `SempodsMcpCoreModule` extends — `api`, because a supertype
  // is part of a class's surface.
  api(project(":commons"))

  // Only `SempodsMcpCoreModule` needs Guice, and a consumer wiring the store by hand must not
  // inherit a container to get a tool catalog. Same trade `commons` and `sempods-auth-core` make.
  compileOnly(libs.guice)

  // No `libs.bundles.logging`: no class in here logs — the two surfaces log, because what a failed
  // tool call means is theirs to decide. A dependency this module cannot justify in a comment is a
  // dependency it does not have.

  testImplementation(libs.jacksonKotlin)
  testImplementation(libs.bundles.test)
}
