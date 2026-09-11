// A build of its own, and that is the whole claim. Everything here resolves `org.sempods:*` as a
// Maven coordinate out of a file repository the main build publishes into, the way a stranger's
// build does. `:consumer-probe:auth` and `:consumer-probe:mcp` ask the neighbouring question —
// they compile against `project(":sempods-auth")`, which checks the `api` boundary and nothing that
// only exists once a module is published: no POM, no module metadata, no jar.
//
// Deliberately not a project of the main build. As a subproject the coordinate would still resolve,
// but the root `build.gradle.kts` would hand it repositories, a toolchain and a test convention,
// `buildHealth` would analyse it, and one `dependencySubstitution` rule added later would quietly
// turn the artifact back into a project dependency with this build still green. No
// `pluginManagement`, no `dependencyResolutionManagement`, no `includeBuild`: a consumer has none
// of them, so neither has this.

rootProject.name = "sempods-consumer-harness"

include(
  "gradle-metadata",
  "pom-only",
)
