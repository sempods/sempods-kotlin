plugins {
  `java-library`
}

description = "RDF4J models over the sempods pod client core."

dependencies {
  // `api`: every signature here names the core's session, options and answers, and RDF4J's `Model`,
  // `Value` and `BindingSet`.
  api(project(":sempods-client-core"))
  api(libs.rdf4jModelApi)
  api(libs.rdf4jQuery)

  // The parser and writer are named, not found through `ServiceLoader`: a consumer's classpath cannot
  // take away the format this module reads, and the settings pinned in `Rdf4jCodec` are these classes'.
  implementation(libs.rdf4jModel)
  implementation(libs.rdf4jRioApi)
  implementation(libs.rdf4jRioNquads)
  implementation(libs.rdf4jRioNtriples)
  implementation(libs.rdf4jRioJsonld)

  implementation(libs.jackson3Databind)

  testImplementation(libs.okhttp)
  testImplementation(libs.rdf4jModelVocabulary)
  testImplementation(libs.slf4jApi)
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
  testImplementation(libs.junitJupiterParams)
}
