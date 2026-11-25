// `find` served by a deployment with stores and a sign-in of its own, bound type by type from the
// list in `docs/concepts/graph-retrieval.md` §"Embedding find". MongoDB is excluded: a type of it in
// anything that list reaches fails this suite rather than an embedder's.

dependencies {
  // `testImplementation` for the `implementation` a foreign build would write.
  testImplementation(project(":sempods-server")) {
    exclude(group = "org.mongodb")
  }

  // Named as a consumer names what it compiles against, although the server's `api` brings them.
  testImplementation(project(":sempods-commons"))
  testImplementation(project(":sempods-model"))

  // What the probe names itself: the container, Jersey to route a request in memory, and an RDF4J
  // repository for the deployment's store.
  testImplementation(libs.guice)
  testImplementation(libs.jakartaWsRsApi)
  testImplementation(libs.jerseyServer)
  testImplementation(libs.jerseyCommon)
  testRuntimeOnly(libs.jerseyHk2)
  testImplementation(libs.rdf4jModelApi)
  testImplementation(libs.rdf4jModel)
  testImplementation(libs.rdf4jModelVocabulary)
  testImplementation(libs.rdf4jRepositoryApi)
  testImplementation(libs.rdf4jRepoSail)
  testImplementation(libs.rdf4jSailApi)
  testImplementation(libs.rdf4jSailMemory)

  testImplementation(libs.junitJupiterApi)
  testImplementation(libs.kotlinTestJunit5)
  testRuntimeOnly(libs.junit)
  testRuntimeOnly(libs.junitPlatformLauncher)
}
