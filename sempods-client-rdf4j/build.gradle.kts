plugins {
  `java-library`
}

description = "RDF4J models over the sempods pod client core."

dependencies {
  // `api`: every signature here names the core's session, options and answers, and RDF4J's `Model`,
  // `Value`, `BindingSet`, `RDFHandler` and `RDFFormat`.
  api(project(":sempods-client-core"))
  api(libs.rdf4jModelApi)
  api(libs.rdf4jQuery)
  api(libs.rdf4jRioApi)

  // The parsers a pod answers with and the writer are named in code, so a consumer's classpath cannot
  // take away the format this module reads.
  implementation(libs.rdf4jModel)
  // `SD.NAMED_GRAPH_PROPERTY`, which a catalogue names its members with.
  implementation(libs.rdf4jModelVocabulary)
  implementation(libs.rdf4jRioNquads)
  implementation(libs.rdf4jRioNtriples)
  implementation(libs.rdf4jRioJsonld)
  implementation(libs.hasmacJsonLd)
  // Found through `ServiceLoader` for a foreign URI that answers Turtle.
  runtimeOnly(libs.rdf4jRioTurtle)

  implementation(libs.jackson3Databind)

  testImplementation(libs.okhttp)
  testImplementation(libs.slf4jApi)
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
  testImplementation(libs.junitJupiterParams)
}
