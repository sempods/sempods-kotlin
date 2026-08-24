plugins {
  `java-library`
}

dependencies {
  // `api` throughout: an endpoint extending `BaseEndpoint` handles `jakarta.ws.rs` types
  // directly, and `ApiException` builds a JAX-RS `Response`. Jersey is this module's surface,
  // not an implementation detail — which is exactly why it is a sibling of `commons` and not
  // part of it.
  api(project(":commons"))
  api(project(":commons-json"))
  api(libs.bundles.jerseyServer)

  // Same reasoning as `commons` and `commons-mongo`: only `JaxRsServerModule` /
  // `JaxRsApplicationModule` need Guice, and a consumer wiring Jersey by hand must not inherit a
  // DI container to get a filter or an exception mapper.
  compileOnly(libs.guice)

  // The Jetty container behind `JaxRsServerModule`. `implementation`: a consumer installs the
  // module and never names a Jetty type, but the server cannot start without it on the runtime
  // classpath. It belongs here rather than with any one application composition.
  implementation(libs.jerseyJettyHttp)

  implementation(libs.bundles.logging)

  testImplementation(libs.guice)
  testImplementation(libs.bundles.test)

  // `ApiExceptionMapperTest` asserts what reaches a log line, so it attaches an appender and names
  // logback types directly. The binding itself comes from the root build script.
  testImplementation(libs.logbackClassic)
}
