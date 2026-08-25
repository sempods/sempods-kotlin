plugins {
  `java-library`
}

dependencies {
  // `api` for the protocol types: an endpoint extending `BaseEndpoint` handles `jakarta.ws.rs`
  // types directly, and `ApiException` builds a JAX-RS `Response`. That surface is this module's
  // reason to exist — which is exactly why it is a sibling of `commons` and not part of it. Jersey
  // *itself* is how that surface is served and stays behind the wall, below.
  //
  // The artifacts are the ones the types are actually in: `jakarta.ws.rs-api` for `Response`,
  // `Request` and the annotations, and `jakarta.inject-api` for the `@Inject` an endpoint carries.
  // Both arrive with Jersey either way — naming them is the difference between a consumer being
  // able to compile against `BaseEndpoint` and merely happening to.
  api(project(":commons"))
  api(libs.jakartaWsRsApi)
  api(libs.jakartaInjectApi)
  // `ObjectMapperResolver` hands back an `ObjectMapper`, so `jackson-databind` is in this module's
  // surface too. `:commons-json` supplies the *configured* mapper and is an implementation detail
  // of that — a consumer names the type, not the factory.
  api(libs.jacksonDatabind)

  // Same reasoning as `commons` and `commons-mongo`: only `JaxRsServerModule` /
  // `JaxRsApplicationModule` need Guice, and a consumer wiring Jersey by hand must not inherit a
  // DI container to get a filter or an exception mapper.
  compileOnly(libs.guice)

  implementation(project(":commons-json"))

  // The Jetty container behind `JaxRsServerModule`, which builds it by hand: `Server`,
  // `ServerConnector`, `GzipHandler`, `VirtualThreadPool`. `implementation` — a consumer installs
  // the module and never names a Jetty type — and `jerseyJettyHttp` below is what puts Jersey on
  // that connector, discovered rather than named, hence `runtimeOnly`.
  implementation(libs.jettyServer)
  implementation(libs.jettyUtil)
  implementation(libs.jerseyServer)
  runtimeOnly(libs.jerseyJettyHttp)

  // The Jackson provider and the HK2 injection bridge: both are Jersey features, registered by
  // name or discovered by the container. `JaxRsServerModule` registers `JacksonFeature` itself, so
  // that one is compiled against; HK2 nothing here ever names.
  implementation(libs.jerseyJsonJackson)
  runtimeOnly(libs.jerseyHk2)

  implementation(libs.bundles.logging)

  testImplementation(libs.slf4jApi)
  testImplementation(libs.bundles.test)

  // `ApiExceptionMapperTest` asserts what reaches a log line, so it attaches an appender and names
  // logback types directly. The binding itself comes from the root build script.
  testImplementation(libs.logbackClassic)
}
