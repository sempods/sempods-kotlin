plugins {
  `java-platform`
  `maven-publish`
}

// A consumer pins one coordinate and gets a set that was built and tested together:
//
//     implementation(platform("org.sempods:sempods-bom:0.1.0"))
//     implementation("org.sempods:sempods-client")   // no version here, or anywhere else
//
// That is the whole point of publishing a platform beside the modules. Without it every consumer
// repeats the version once per module and eventually gets one of them wrong — and a mix of
// `sempods-client` 0.2 with `sempods-model` 0.1 is a combination nothing ever ran, because the
// repository only ever releases them together.

// Written out rather than derived from the projects that carry `java-library`. A `java-platform`
// is configured before the modules it constrains are evaluated, so asking each one which plugins
// it has would read the answer too early and quietly produce a short BOM — the failure mode being
// a missing constraint, which looks like nothing at all until a consumer resolves a stale version.
// `checkBomIsComplete` below closes that gap by asking the same question late, when the answer is
// true.
val publishedModules = listOf(
  "commons",
  "commons-jaxrs",
  "commons-json",
  "commons-ktor",
  "commons-mongo",
  "commons-okhttp",
  "sempods-auth",
  "sempods-auth-core",
  "sempods-client",
  "sempods-control-plane-client",
  "sempods-mcp",
  "sempods-mcp-core",
  "sempods-media-s3",
  "sempods-model",
  "sempods-server",
)

dependencies {
  constraints {
    publishedModules.forEach { api(project(":$it")) }
  }
}

// The guard the list above needs. It runs after every project is evaluated, which is when
// `hasPlugin` finally tells the truth, and fails on a difference in either direction: a library
// missing from the BOM (a consumer resolves whatever version they happen to have) or a name left
// behind by a rename (the BOM constrains a module that no longer exists).
val checkBomIsComplete = tasks.register("checkBomIsComplete") {
  group = "verification"
  description = "Fails if the BOM's module list and the set of published library modules differ."
  doLast {
    val libraries = rootProject.subprojects
      .filter { it.plugins.hasPlugin("java-library") }
      .map { it.name }
      .toSortedSet()
    val listed = publishedModules.toSortedSet()
    val missing = libraries - listed
    val stale = listed - libraries
    if (missing.isNotEmpty() || stale.isNotEmpty()) {
      throw GradleException(
        buildString {
          append("`sempods-bom` does not list the published modules.")
          if (missing.isNotEmpty()) {
            append("\n  Missing from the BOM (a library that nothing constrains): ")
            append(missing.joinToString())
          }
          if (stale.isNotEmpty()) {
            append("\n  Listed but not a library module (renamed or removed?): ")
            append(stale.joinToString())
          }
          append("\n  Edit `publishedModules` in sempods-bom/build.gradle.kts.")
        },
      )
    }
  }
}
tasks.matching { it.name == "check" }.configureEach { dependsOn(checkBomIsComplete) }

// What the module is, for the POM. The rest of the metadata — licence, developer, scm, url — is
// identical for every publication in this build and is set once in the root build file.
description = "Dependency constraints for the sempods modules, released together."

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["javaPlatform"])
    }
  }
}
