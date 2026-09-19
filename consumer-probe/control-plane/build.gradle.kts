// The host-level admin surface as a Java consumer compiles and runs it, on Java 21 and without an
// RDF library. Its JVM and the forbidden-dependency check are configured in the root build, as the
// other probes' are.

dependencies {
  // `testImplementation` for the `implementation` a foreign build would write.
  testImplementation(project(":sempods-control-plane-client"))

  // Named as a consumer names what it compiles against, although the module's `api` brings them.
  testImplementation(project(":sempods-client-core"))
  testImplementation(libs.okhttp)

  // JUnit alone, as in `:consumer-probe:client-core`.
  testImplementation(libs.junitJupiterApi)
  testRuntimeOnly(libs.junit)
  testRuntimeOnly(libs.junitPlatformLauncher)
}
