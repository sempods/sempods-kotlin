plugins {
  `java-library`
}

dependencies {
  // `api` rather than `implementation`: this client throws `SempodsClientException` and takes a
  // `SempodsHttpTransport`, so a consumer compiles against both. The direction of the edge is the
  // point — the proprietary plane borrows the pod client's plumbing, never the other way round,
  // and a consumer of the pod specification adds no dependency on this module at all.
  api(project(":sempods-client"))

  // Declared rather than inherited. The admin surface is plain JSON, and Jackson reaches
  // `:sempods-client` only as a transitive of `rdf4j-rio-jsonld` — an undeclared compile
  // dependency that should not spread by imitation.
  implementation(libs.jackson)

  implementation(libs.bundles.logging)

  testImplementation(libs.bundles.test)
}
