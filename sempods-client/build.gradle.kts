plugins {
  `java-library`
  `java-test-fixtures`
}

dependencies {
  // `api`: `SempodsOkHttp` configures an `okhttp3.OkHttpClient.Builder` and `SempodsSession` hands
  // out an `okhttp3.Request.Builder`. Why, and what it costs: `docs/pod-client.md` §"The transport".
  api(libs.okhttp)

  // Jackson 3, and no RDF4J, Jena or Jackson 2: `docs/pod-client.md` §"Consumable as an artifact".
  // Nothing here logs.
  implementation(libs.jackson3Databind)

  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
  testImplementation(libs.junitJupiterParams)
}
