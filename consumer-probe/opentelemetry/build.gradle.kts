// The client core traced by OpenTelemetry, wired the way a consumer wires it.
//
// A probe of its own rather than a case in `:consumer-probe:client-core`, whose classpath is kept to
// what a consumer of the core resolves plus JUnit: the SDK and the instrumentation are exactly the
// dependencies that probe must not have. This one asks whether the published seam —
// `SempodsTransport.Builder.callFactory` — takes OpenTelemetry's OkHttp library as it ships.

dependencies {
  // `testImplementation` for the `implementation` a foreign build would write: Gradle propagates
  // only `api` across a project boundary, so this suite's compile classpath is a consumer's.
  testImplementation(project(":sempods-client-core"))
  testImplementation(libs.okhttp)

  // OpenTelemetry as an application brings it: the API it names, an SDK with an in-memory exporter
  // to read the spans back, and the OkHttp instrumentation library.
  testImplementation(libs.opentelemetryApi)
  testImplementation(libs.opentelemetryContext)
  testImplementation(libs.opentelemetrySdk)
  testImplementation(libs.opentelemetrySdkTrace)
  testImplementation(libs.opentelemetrySdkTesting)
  testImplementation(libs.opentelemetryOkhttp)

  // JUnit on its own, as in `:consumer-probe:client-core`.
  testImplementation(libs.junitJupiterApi)
  testRuntimeOnly(libs.junit)
  testRuntimeOnly(libs.junitPlatformLauncher)
}
