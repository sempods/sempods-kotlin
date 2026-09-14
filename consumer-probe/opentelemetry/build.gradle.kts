// The client core traced by OpenTelemetry's OkHttp library, wired as a consumer wires it. A module of
// its own because the SDK and the instrumentation are what the client-core probe must not resolve.

dependencies {
  // `testImplementation` for the `implementation` a foreign build would write.
  testImplementation(project(":sempods-client-core"))
  testImplementation(libs.okhttp)

  // The API, an SDK with an in-memory exporter to read the spans back, and the instrumentation library.
  testImplementation(libs.opentelemetryApi)
  testImplementation(libs.opentelemetryContext)
  testImplementation(libs.opentelemetrySdk)
  testImplementation(libs.opentelemetrySdkTrace)
  testImplementation(libs.opentelemetrySdkTesting)
  testImplementation(libs.opentelemetryOkhttp)

  // JUnit alone, as in `:consumer-probe:client-core`.
  testImplementation(libs.junitJupiterApi)
  testRuntimeOnly(libs.junit)
  testRuntimeOnly(libs.junitPlatformLauncher)
}
