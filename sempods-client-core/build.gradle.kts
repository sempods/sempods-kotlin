plugins {
  `java-library`
  `java-test-fixtures`
}

dependencies {
  // For the W3C trace binding only. Deliberately framework-free and Guice-free: this is a library
  // a consumer binds itself, and the module it is published as must not hand a stranger an
  // application framework along with an HTTP client.
  implementation(project(":sempods-commons"))

  // `implementation`, never `api`: the engine stops at `SempodsTransport`. Callers speak
  // `SempodsRequest`/`SempodsResponse`/`SempodsBody` and a consumer of the published artifact never
  // compiles against an OkHttp type — see `SempodsBody` for why that boundary is drawn here.
  implementation(libs.okhttp)

  // No RDF4J, no Jackson, no Jena, and that is this module's reason to exist. `consumer-harness/`
  // resolves the published artifact and fails if any of the three arrives transitively.

  // No logging: nothing in this module logs. A failed request is handed back rather than written
  // down — see `SempodsClientException`.

  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
