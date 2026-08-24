plugins {
  `java-platform`
  `maven-publish`
}

// A consumer pins one coordinate and names no versions:
//
//     implementation(platform("org.sempods:sempods-bom:0.1.0"))
//     implementation("org.sempods:sempods-client")
//
// Without it every consumer repeats the version once per module and eventually gets one wrong —
// and a mix of `sempods-client` 0.2 with `sempods-model` 0.1 is a combination nothing ever ran,
// because the repository only ever releases them together.
//
// The list comes from the root build, which is also what decides that those modules are published.
// One source, so the set that ships and the set this pins cannot disagree.
@Suppress("UNCHECKED_CAST")
val publishedModules = rootProject.extra["publishedModules"] as List<String>

dependencies {
  constraints {
    publishedModules.forEach { api(project(":$it")) }
  }
}

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
