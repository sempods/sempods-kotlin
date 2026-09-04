plugins {
  `java-library`
  id("java-test-fixtures")
}

dependencies {
  implementation(libs.bundles.logging)

  // slf4j-api on top of the facade bundle: `LoggingInitializer` names `LoggerFactory` and
  // `ILoggerFactory` directly, which no other module here does.
  implementation(libs.slf4jApi)

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

  // And for `CapturedLog`, which names `ListAppender` and `LoggerContext`. `compileOnly` for the
  // same reason as Guice, plus one of its own: this module is published, and the binding is what
  // `checkNoLoggingBinding` exists to keep off a library. Every test JVM has it at runtime from
  // the root build script.
  testFixturesCompileOnly(libs.logbackClassic)
  testFixturesImplementation(libs.bundles.logging)

  // `implementation` and not `compileOnly`, unlike Guice above: these fixtures *run* in a
  // consumer's test JVM, calling `assertTrue` and `verify` from their own bytecode, so a consumer
  // that resolves `testFixtures("org.sempods:commons")` has to receive them. That they must not
  // also reach a plain Maven consumer of `org.sempods:commons` is a fact about the POM, and it is
  // handled where the POM is written — see `pom.withXml` in the root build file.
  testFixturesImplementation(libs.bundles.test)

  // `TestUtil` polls for a condition — one of the two source sets here that await anything, which
  // is why this is not in `libs.bundles.test`.
  testFixturesImplementation(libs.awaitility)

  // `LogbackBaseConfigTest` configures a throwaway LoggerContext from the shared base, so it
  // names logback types directly. The binding itself comes from the root build script.
  testImplementation(libs.logbackClassic)
  testImplementation(libs.bundles.test)
}
