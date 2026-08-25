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

  // The same reasoning again, with a second reason that is not about taste. A POM has one
  // dependency list and no variants, and `from(components["java"])` in the root build feeds the
  // test-fixtures variants into it too, so an `implementation` here is a `runtime` dependency of
  // `org.sempods:commons` in the published POM — a plain Maven consumer would inherit JUnit,
  // kotlin-test, MockK and a mock HTTP server. Nothing needs them at runtime *here*: these
  // fixtures are compiled against the types and then executed by a consumer's test JVM, which
  // brings its own `libs.bundles.test`. `checkNoTestLibrariesInPom` in the root build is what
  // notices if this goes back.
  testFixturesCompileOnly(libs.bundles.test)

  // `LogbackBaseConfigTest` configures a throwaway LoggerContext from the shared base, so it
  // names logback types directly. The binding itself comes from the root build script.
  testImplementation(libs.logbackClassic)
  testImplementation(libs.guice)
  testImplementation(libs.bundles.test)
}
