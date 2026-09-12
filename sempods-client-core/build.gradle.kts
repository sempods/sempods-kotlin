plugins {
  `java-library`
  `java-test-fixtures`
}

dependencies {
  // For the W3C trace binding only. Deliberately framework-free and Guice-free: this is a library
  // a consumer binds itself, and the module it is published as must not hand a stranger an
  // application framework along with an HTTP client.
  implementation(project(":sempods-commons"))

  // `api`, and that is this module's shape rather than an oversight. `SempodsSession` hands back
  // an `okhttp3.Response` and takes an `okhttp3.Request`; a consumer compiles against both, adds
  // its own interceptor, shares the connection pool. Hiding the engine behind a second vocabulary
  // cost about five hundred lines and bought a consumer nothing they could not already do — while
  // costing them everything OkHttp offers that this library would have had to re-expose one method
  // at a time. What this module adds is what OkHttp has no opinion about: the pod base URL rules,
  // the SSRF guard, pod confinement and replaceable request authentication.
  //
  // The price is stated rather than hidden: OkHttp's major version is part of this module's ABI.
  // `docs/pod-client.md` §"The core" carries the trade.
  api(libs.okhttp)

  // No RDF4J, no Jackson, no Jena, and that is this module's reason to exist.
  // `:consumer-probe:client-core` resolves this module the way a consumer does and fails if any of
  // the three arrives transitively.

  // No logging: nothing in this module logs. A failed request is handed back rather than written
  // down — see `SempodsClientException`.

  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
