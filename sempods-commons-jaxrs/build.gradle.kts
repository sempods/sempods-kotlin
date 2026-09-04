plugins {
  `java-library`
}

dependencies {
  // `api` for the protocol types: an endpoint extending `BaseEndpoint` handles `jakarta.ws.rs`
  // types directly, `ApiException` builds a `Response`, and an endpoint carries an `@Inject`.
  // That surface is this module's reason to exist — which is why it is a sibling of `sempods-commons` and
  // not part of it. Jersey itself serves that surface and stays behind the wall, below.
  api(project(":sempods-commons"))
  api(libs.jakartaWsRsApi)
  api(libs.jakartaInjectApi)
  // `ObjectMapperResolver` hands back an `ObjectMapper`. `:sempods-commons-json` supplies the *configured*
  // mapper and stays behind the wall: a consumer names the type, not the factory.
  api(libs.jacksonDatabind)

  // Same reasoning as `sempods-commons` and `sempods-commons-mongo`: only `JaxRsServerModule` /
  // `JaxRsApplicationModule` need Guice, and a consumer wiring Jersey by hand must not inherit a
  // DI container to get a filter or an exception mapper.
  compileOnly(libs.guice)

  implementation(project(":sempods-commons-json"))

  // The Jetty container `JaxRsServerModule` builds by hand — `Server`, `ServerConnector`,
  // `GzipHandler`, `VirtualThreadPool` — and the Jersey bridge onto it, which nothing names.
  implementation(libs.jettyServer)
  implementation(libs.jettyUtil)
  implementation(libs.jerseyServer)
  runtimeOnly(libs.jerseyJettyHttp)

  // Two Jersey features: `JaxRsServerModule` registers `JacksonFeature` by name; HK2 the
  // container discovers.
  implementation(libs.jerseyJsonJackson)
  runtimeOnly(libs.jerseyHk2)

  implementation(libs.bundles.logging)

  testImplementation(libs.slf4jApi)
  testImplementation(libs.bundles.test)

  // These suites assert what reaches a log line, so they attach an appender and name logback types
  // directly. The binding itself comes from the root build script; `CapturedLog` — which owns the
  // attaching — comes from the fixtures of the module that owns the rule.
  testImplementation(libs.logbackClassic)
  testImplementation(testFixtures(project(":sempods-commons")))

  // `BaseEndpointPreconditionTest` builds a real `ContainerRequest`, which needs the properties
  // delegate from `jersey-common` — the header parsing it asserts on is the whole point, so a mock
  // request would assert nothing.
  testImplementation(libs.jerseyCommon)
}
