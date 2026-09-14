// The client core as a Java consumer compiles and runs it. What it checks and why:
// `docs/concepts/modularity.md` §"Open-source readiness". Its Java 21 JVM and the forbidden-dependency
// check are configured in the root build, which applies the plugin that gives this project its tasks.

dependencies {
  // `testImplementation` for the `implementation` a foreign build would write: Gradle propagates
  // only `api` across a project boundary, so this suite's compile classpath is a consumer's.
  testImplementation(project(":sempods-client-core"))

  // Named as a consumer names what it compiles against, although the core's `api` brings it.
  testImplementation(libs.okhttp)

  // JUnit alone: the Kotlin suites' bundle adds MockK and kotlin-test, which a Java suite does not use.
  testImplementation(libs.junitJupiterApi)
  testImplementation(libs.junitJupiterParams)
  testRuntimeOnly(libs.junit)
  testRuntimeOnly(libs.junitPlatformLauncher)
}
