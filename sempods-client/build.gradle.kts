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
  implementation(project(":sempods-commons"))

  // For `TraceparentInterceptor`, which puts the caller's trace on every request this module's
  // client sends. Its Guice dependency is `compileOnly` there, so nothing of a DI container
  // reaches a consumer through this edge.
  implementation(project(":sempods-commons-okhttp"))

  // `api` for the same reason `:sempods-model` declares it so, and declared here rather than
  // inherited: this module's own methods return `Model` (`dereference`, `sparqlConstruct`, `load`)
  // and take `Value` (`putSlot`), so a foreign build compiling against them needs RDF4J on its
  // compile classpath without being told to add it. Before this, in-repo consumers compensated by
  // redeclaring RDF4J themselves — a stranger consuming the published artifact could not.
  // `rdf4j-model` beside it carries the `SimpleValueFactory` and `Values` this module uses and a
  // consumer does not; the parsers are found by `ServiceLoader` when a body is read or written.
  api(libs.rdf4jModelApi)
  implementation(libs.rdf4jModel)
  implementation(libs.rdf4jQuery)
  implementation(libs.rdf4jRioApi)
  runtimeOnly(libs.bundles.rdf4j)

  // `api`, because `PodWireClient.listContexts`, `sparqlSelect` and `sparqlGraph` answer with a
  // `JsonNode`, which a caller has to name. The `java.time` codecs are a registration on the
  // mapper and nothing names them.
  api(libs.jacksonDatabind)
  runtimeOnly(libs.jackson)

  // `api`, because this module's signatures name the core's types: `SempodsHttpTransport` takes a
  // `SempodsOutboundGuard`, and this module's `SempodsClientException` extends the core's.
  api(project(":sempods-client-core"))

  // How the tiers here read and write RDF: they run the core's endpoint groups and let this adapter
  // decode the bodies. `implementation`, because no signature here names one of its types — a caller
  // gets `Model` and `Value` from `:sempods-model` above.
  implementation(project(":sempods-client-rdf4j"))

  // Named directly because this module names it directly: the legacy surface translates its own
  // `SempodsRequest` onto an `okhttp3.Request` and reads an `okhttp3.Response` back. It arrives
  // through the core's `api` either way — declaring it is the repository's rule that a type you
  // compile against is one you say you have.
  implementation(libs.okhttp)

  // No logging: nothing in this module logs. A failed request is handed back rather than written
  // down — see `SempodsClientException`.

  testImplementation(libs.slf4jApi)
  // The mock HTTP server five of this module's suites drive — one of the three that do, which is
  // why this is not in `libs.bundles.test`.
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
