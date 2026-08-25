plugins {
  `java-library`
}

dependencies {
  // Framework-free, and now actually so: `sempods-model` used to depend on an application
  // framework and put Jersey and an object mapper behind this client through the back door.
  api(project(":sempods-model"))

  // For the W3C trace binding only. Deliberately framework-free and Guice-free: this is a library
  // a consumer binds itself, and the module it is published as must not hand a stranger an
  // application framework along with an HTTP client.
  implementation(project(":commons"))

  // `api` for the same reason `:sempods-model` declares it so, and declared here rather than
  // inherited: this module's own methods return `Model` (`dereference`, `sparqlConstruct`, `load`)
  // and take `Value` (`putSlot`), so a foreign build compiling against them needs RDF4J on its
  // compile classpath without being told to add it. Before this, in-repo consumers compensated by
  // redeclaring RDF4J themselves — a stranger consuming the published artifact could not.
  // `rdf4j-model` beside it carries the `SimpleValueFactory` and `Values` this module uses and a
  // consumer does not; the parsers are found by `ServiceLoader` when a body is read or written.
  api(libs.rdf4jModelApi)
  implementation(libs.rdf4jModel)
  implementation(libs.rdf4jRioApi)
  runtimeOnly(libs.bundles.rdf4j)

  // `api`, because `PodWireClient.listContexts`, `sparqlSelect` and `sparqlGraph` answer with a
  // `JsonNode`, which a caller has to name. The `java.time` codecs are a registration on the
  // mapper and nothing names them.
  api(libs.jacksonDatabind)
  runtimeOnly(libs.jackson)

  // `implementation`, never `api`: the engine stops at `SempodsHttpTransport`. Callers speak
  // `SempodsRequest`/`SempodsResponse` and a consumer of the published artifact never compiles
  // against an OkHttp type — see `SempodsBody` for why that boundary is drawn here.
  implementation(libs.okhttp)

  // No logging: nothing in this module logs. A failed request is handed back rather than written
  // down — see `SempodsClientException`.

  testImplementation(libs.slf4jApi)
  // The mock HTTP server five of this module's suites drive — one of the three that do, which is
  // why this is not in `libs.bundles.test`.
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
