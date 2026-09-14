plugins {
  `java-library`
  `java-test-fixtures`
}

dependencies {
  // For the W3C trace binding only; framework-free, so a consumer takes no framework with the client.
  implementation(project(":sempods-commons"))

  // `api`: `SempodsOkHttp` configures an `okhttp3.OkHttpClient.Builder` and `SempodsSession` hands
  // out an `okhttp3.Request.Builder`. Why, and what it costs: `docs/pod-client.md` §"The transport".
  api(libs.okhttp)

  // No RDF4J, Jackson or Jena: `docs/pod-client.md` §"Consumable as an artifact". Nothing here logs.

  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
