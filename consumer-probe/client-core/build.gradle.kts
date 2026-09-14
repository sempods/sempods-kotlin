// The third probe, and the one that asks a wider question than its two neighbours.
//
// `auth` and `mcp` exist because `buildHealth` is blind to them — a project carrying `application`
// or Jib reads as an application, which has no consumers, so no ABI is computed. `:sempods-client-core`
// has neither plugin and is analysed like any other module, so the `api` boundary is already
// checked there. What is checked nowhere else is whether the result is *usable from Java*: this
// module's suite is Java, and it compiles across a project boundary, where Gradle propagates only
// `api`. A Kotlin function type, a missing `@Throws` or an engine type on the surface is a compile
// error here rather than a finding in someone else's build.
//
// Its Java 21 JVM and its forbidden-dependency check are configured in the root build beside
// `checkNoLoggingBinding`, because the Kotlin plugin that gives this project its tasks is applied
// there and this file is evaluated first.

dependencies {
  // `testImplementation` for the `implementation` a foreign build would write: Gradle propagates
  // only `api` across a project boundary, so this suite's compile classpath is a consumer's.
  testImplementation(project(":sempods-client-core"))

  // As a consumer writes it. The core exports OkHttp on `api`, so this arrives without the line —
  // but a consumer that names `Request`, `Response` and `Call` declares them, and a probe that
  // did not would be modelling a build nobody writes.
  testImplementation(libs.okhttp)

  // JUnit on its own: MockK and kotlin-test, the rest of `libs.bundles.test`, are for Kotlin suites.
  // Elsewhere kotlin-test is credited with `junit-jupiter-api` and brings the platform launcher;
  // without it, both are named here. The aggregator stays for the engine it carries.
  testImplementation(libs.junitJupiterApi)
  testImplementation(libs.junitJupiterParams)
  testRuntimeOnly(libs.junit)
  testRuntimeOnly(libs.junitPlatformLauncher)
}
