// The third probe, and the one that asks a wider question than its two neighbours.
//
// `auth` and `mcp` exist because `buildHealth` is blind to them — a project carrying `application`
// or Jib reads as an application, which has no consumers, so no ABI is computed. `:sempods-client-core`
// has neither plugin and is analysed like any other module, so the `api` boundary is already
// checked there. What is checked nowhere else is whether the result is *usable from Java*: this
// module's source is Java, and it compiles across a project boundary, where Gradle propagates only
// `api`. A Kotlin function type, a missing `@Throws` or an engine type on the surface is a compile
// error here rather than a finding in someone else's build.
//
// Its two tasks — the forbidden-dependency check and the Java 21 run — are registered in the root
// build beside `checkNoLoggingBinding`, because the Kotlin plugin that gives this project a source
// set is applied there and this file is evaluated first.

dependencies {
  // `implementation`, as a foreign build would write it: Gradle propagates only `api` across a
  // project boundary, so this module's compile classpath is a consumer's.
  implementation(project(":sempods-client-core"))
}
