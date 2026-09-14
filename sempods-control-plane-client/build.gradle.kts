plugins {
  `java-library`
}

dependencies {
  // `api` rather than `implementation`: this client throws `SempodsClientException` and takes a
  // `SempodsHttpTransport`, so a consumer compiles against both. The direction of the edge is the
  // point — the proprietary plane borrows the pod client's plumbing, never the other way round,
  // and a consumer of the pod specification adds no dependency on this module at all.
  api(project(":sempods-client"))

  // Declared because compiling against `:sempods-client`'s transport reads the core's types: its
  // constructor takes the core's guard, and its exceptions extend the core's. A type you compile
  // against is one you say you have.
  implementation(project(":sempods-client-core"))

  // Declared rather than inherited: the admin surface is plain JSON and names `JsonNode` and the
  // mapper itself. The `java.time` codecs are a registration and nothing names them.
  implementation(libs.jacksonDatabind)
  runtimeOnly(libs.jackson)

  // No logging: nothing in this module logs, for the reason `:sempods-client` gives.

  testImplementation(libs.slf4jApi)
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
