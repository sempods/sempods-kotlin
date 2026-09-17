// The RDF4J adapter as a Java consumer compiles and runs it. A module of its own because RDF4J is what
// the client-core probe must not resolve. Its Java 25 JVM and the forbidden-dependency check are
// configured in the root build, as the client-core probe's are.

dependencies {
  // `testImplementation` for the `implementation` a foreign build would write.
  testImplementation(project(":sempods-client-rdf4j"))

  // Named as a consumer names what it compiles against, although the adapter's `api` brings them.
  testImplementation(project(":sempods-client-core"))
  testImplementation(libs.okhttp)
  testImplementation(libs.rdf4jModelApi)
  testImplementation(libs.rdf4jModel)
  testImplementation(libs.rdf4jQuery)

  // JUnit alone, as in `:consumer-probe:client-core`.
  testImplementation(libs.junitJupiterApi)
  testImplementation(libs.junitJupiterParams)
  testRuntimeOnly(libs.junit)
  testRuntimeOnly(libs.junitPlatformLauncher)
}
