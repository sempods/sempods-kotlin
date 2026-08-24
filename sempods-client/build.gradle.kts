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
  implementation(project(":commons"))

  // `api` for the same reason `:sempods-model` declares it so, and declared here rather than
  // inherited: this module's own methods return `Model` (`dereference`, `sparqlConstruct`, `load`)
  // and take `Value` (`putSlot`), so a foreign build compiling against them needs RDF4J on its
  // compile classpath without being told to add it. Before this, in-repo consumers compensated by
  // redeclaring RDF4J themselves — a stranger consuming the published artifact could not.
  api(libs.rdf4jModel)
  implementation(libs.bundles.rdf4j)

  // `api`, because the wire tier returns it. `PodWireClient.listContexts`, `sparqlSelect` and
  // `sparqlGraph` are public and answer with a `JsonNode`, so a foreign build calling them has to
  // name that type — and could not, while this was reached only transitively through
  // `implementation`. The published artifact failed on the first such call with "Cannot access
  // class com.fasterxml.jackson.databind.JsonNode", which is the shape every `api`/`implementation`
  // mistake takes: invisible in this repository, where every module has Jackson anyway.
  api(libs.jackson)

  // `implementation`, never `api`: the engine stops at `SempodsHttpTransport`. Callers speak
  // `SempodsRequest`/`SempodsResponse` and a consumer of the published artifact never compiles
  // against an OkHttp type — see `SempodsBody` for why that boundary is drawn here.
  implementation(libs.okhttp)
  implementation(libs.bundles.logging)

  testImplementation(libs.bundles.test)
}
