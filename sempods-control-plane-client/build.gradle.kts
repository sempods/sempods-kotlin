plugins {
  `java-library`
}

dependencies {
  // `api` rather than `implementation`: this client throws `SempodsClientException` and takes a
  // `SempodsHttpTransport`, so a consumer compiles against both. The direction of the edge is the
  // point — the proprietary plane borrows the pod client's plumbing, never the other way round,
  // and a consumer of the pod specification adds no dependency on this module at all.
  api(project(":sempods-client"))

  // Declared rather than inherited. The admin surface is plain JSON, and the types it names —
  // `JsonNode` and the mapper — are `jackson-databind`'s. The `java.time` codecs are a
  // registration, needed when a mapper is built and not when this module is compiled.
  implementation(libs.jacksonDatabind)
  runtimeOnly(libs.jackson)

  // No logging: nothing in this module logs, for the reason `:sempods-client` gives.

  testImplementation(libs.slf4jApi)
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
