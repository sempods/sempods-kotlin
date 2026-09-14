plugins {
  `java-library`
  `java-test-fixtures`
}

dependencies {
  // `api`: `SempodsOkHttp` configures an `okhttp3.OkHttpClient.Builder` and `SempodsSession` hands
  // out an `okhttp3.Request.Builder`. Why, and what it costs: `docs/pod-client.md` §"The transport".
  api(libs.okhttp)

  // No RDF4J, Jackson or Jena: `docs/pod-client.md` §"Consumable as an artifact". Nothing here logs.

  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
