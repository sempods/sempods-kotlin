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
  // The artifact is `rdf4j-model-api`, where those interfaces actually live. `rdf4j-model` carries
  // their implementations (`SimpleValueFactory`, `Values`), which this module uses and a consumer
  // does not; the parsers under it are found by `ServiceLoader` when a request is read or written,
  // so they are needed at runtime and never on anyone's compile classpath.
  api(libs.rdf4jModelApi)
  implementation(libs.rdf4jModel)
  implementation(libs.rdf4jRioApi)
  runtimeOnly(libs.bundles.rdf4j)

  // `api`, because `PodWireClient.listContexts`, `sparqlSelect` and `sparqlGraph` answer with a
  // `JsonNode` — which a caller has to name, and which lives in `jackson-databind`. The `java.time`
  // codecs are a registration on a mapper, so they are `runtimeOnly`: nothing here names a type
  // from that artifact, and a consumer compiling against this client never will either.
  api(libs.jacksonDatabind)
  runtimeOnly(libs.jackson)

  // `implementation`, never `api`: the engine stops at `SempodsHttpTransport`. Callers speak
  // `SempodsRequest`/`SempodsResponse` and a consumer of the published artifact never compiles
  // against an OkHttp type — see `SempodsBody` for why that boundary is drawn here.
  implementation(libs.okhttp)

  // No logging: nothing in this module logs. A library that has no opinion about a failed request
  // hands it back rather than writing it down — see `SempodsClientException`.

  testImplementation(libs.slf4jApi)
  // The mock HTTP server five of this module's suites drive. Declared here rather than carried by
  // `libs.bundles.test`: three modules use it, and the other thirteen were taking it along.
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
