plugins {
  `java-library`
}

dependencies {
  // `api` rather than `implementation`: this client throws `SempodsClientException` and takes a
  // `SempodsHttpTransport`, so a consumer compiles against both. The direction of the edge is the
  // point — the proprietary plane borrows the pod client's plumbing, never the other way round,
  // and a consumer of the pod specification adds no dependency on this module at all.
  api(project(":sempods-client"))

  // The request and response vocabulary is the core's, and this module builds requests with it —
  // `implementation`, because those are local values here rather than anything in its own
  // signature. Declared rather than inherited through `:sempods-client`'s `api`: a type you
  // compile against is one you say you have.
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
