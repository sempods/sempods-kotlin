plugins {
  `java-platform`
  `maven-publish`
}

// A consumer pins this one coordinate and names no versions. The modules are only ever released
// together, so a mix of `sempods-client` 0.2 with `sempods-model` 0.1 is a combination nothing
// ran. The list comes from the root build, which is also what decides they are published.
@Suppress("UNCHECKED_CAST")
val publishedModules = rootProject.extra["publishedModules"] as List<String>

dependencies {
  constraints {
    publishedModules.forEach { api(project(":$it")) }
  }
}

// For the POM; the rest of its metadata is set once in the root build file.
description = "Dependency constraints for the sempods modules, released together."

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["javaPlatform"])
    }
  }
}
