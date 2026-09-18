plugins {
  `java-library`
}

description = "The pod's JSON-LD wire layer, on the transport the services still share."

dependencies {
  // For the W3C trace binding only. Deliberately framework-free and Guice-free: this is a library
  // a consumer binds itself, and the module it is published as must not hand a stranger an
  // application framework along with an HTTP client.
  implementation(project(":sempods-commons"))

  // For `TraceparentInterceptor`, which puts the caller's trace on every request this module's
  // client sends. Its Guice dependency is `compileOnly` there, so nothing of a DI container
  // reaches a consumer through this edge.
  implementation(project(":sempods-commons-okhttp"))

  // `api`, because `PodWireClient.listContexts`, `sparqlSelect` and `sparqlGraph` answer with a
  // `JsonNode`, which a caller has to name. The `java.time` codecs are a registration on the
  // mapper and nothing names them.
  api(libs.jacksonDatabind)
  runtimeOnly(libs.jackson)

  // `api`, because this module's signatures name the core's types: `SempodsHttpTransport` takes a
  // `SempodsOutboundGuard`, and this module's `SempodsClientException` extends the core's.
  api(project(":sempods-client-core"))

  // Named directly because this module names it directly: the legacy surface translates its own
  // `SempodsRequest` onto an `okhttp3.Request` and reads an `okhttp3.Response` back. It arrives
  // through the core's `api` either way — declaring it is the repository's rule that a type you
  // compile against is one you say you have.
  implementation(libs.okhttp)

  // No logging: nothing in this module logs. A failed request is handed back rather than written
  // down — see `SempodsClientException`.

  testImplementation(libs.slf4jApi)
  // The mock HTTP server this module's suites drive — one of the three that do, which is why this
  // is not in `libs.bundles.test`.
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
