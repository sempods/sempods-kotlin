plugins {
  `java-library`
  id("java-test-fixtures")
}

dependencies {
  implementation(libs.bundles.logging)

  // The `java.util.logging` bridge, declared here rather than left to each application: it is
  // `LoggingInitializer`'s own dependency — that class references `SLF4JBridgeHandler` directly —
  // and it is not a binding, so carrying it costs a consumer 5 KB and decides nothing for them.
  implementation(libs.slf4jJul)

  // `org.sempods.commons.guice.BaseModule` is the only class here that needs Guice, and a consumer that
  // does not use it — `sempods-client` runs from a plain `main` — must not inherit a DI container
  // to get a URL helper. Anyone extending the base module already has Guice on its own classpath.
  compileOnly(libs.guice)

  // Same reasoning for the test proxy: it needs Guice and MockK to compile, both of which every
  // consumer of these fixtures already has.
  testFixturesCompileOnly(libs.guice)
  testFixturesImplementation(libs.bundles.logging)
  testFixturesImplementation(libs.bundles.test)

  // `LogbackBaseConfigTest` configures a throwaway LoggerContext from the shared base, so it
  // names logback types directly. The binding itself comes from the root build script.
  testImplementation(libs.logbackClassic)
  testImplementation(libs.guice)
  testImplementation(libs.bundles.test)
}
