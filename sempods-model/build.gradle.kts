plugins {
  `java-library`
}

dependencies {

  // `api`, not `implementation`: `ResourceView`, `SparqlResult`, `Rdf4JUtil` and the `Ontologies`
  // constants all name `Model`, `IRI`, `Resource` and `Value` in their public signatures. RDF is
  // what a pod holds, so those types are part of the contract this module *is* rather than a
  // detail of how it is written — a consumer that cannot name them cannot call anything here.
  //
  // Two artifacts, because the contract is in both: the interfaces in `rdf4j-model-api`, and the
  // concrete `LinkedHashModel`, `SimpleNamespace`, `ModelBuilder` and `Values` that `Rdf4JUtil` and
  // the `Ontologies` builders hand back in `rdf4j-model`.
  api(libs.rdf4jModelApi)
  api(libs.rdf4jModel)
  implementation(libs.rdf4jModelVocabulary)

  // `Rio` and `RDFFormat`, for reading and writing. The parsers behind them are found by
  // `ServiceLoader` and needed only at runtime.
  implementation(libs.rdf4jRioApi)
  runtimeOnly(libs.bundles.rdf4j)

  // `SempodsUriBuilder` logs.
  implementation(libs.bundles.logging)

  // The media data types carry serialization annotations and name `JsonNode`; the mapper that
  // reads them comes from `:commons-json`.
  implementation(libs.jacksonDatabind)

  // No application framework. This module is the pod contract, and `sempods-client` plus every
  // consumer above it depends on it — an edge to a framework here would put Jersey, an object
  // mapper and a user model behind all of them. The last symbol was `SempodsUriBuilder`'s injected
  // application-config constructor.
  implementation(project(":commons"))
  implementation(project(":commons-json"))

  // No test fixtures, and no DI container anywhere: this module ships ontologies, view definitions,
  // a URI builder that takes its base URL as a plain argument, and the media data types. No pod
  // service to program against — the pod server binds `SempodsUriBuilder` for its own use and
  // `sempods-client` constructs its own `ViewFacade`, but nothing here is an abstraction a consumer
  // injects instead of talking to a pod. A
  // consumer that wants a real pod runs one (`:sempods-server`); one that wants the wire takes
  // `:sempods-client`.

  testImplementation(libs.bundles.test)
}
