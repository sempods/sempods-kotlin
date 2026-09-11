val publishedRepository = rootProject.extra["publishedRepository"] as File

// The consumption path nothing else in this repository exercises: a build that reads the POM and
// never the Gradle module metadata — every Maven consumer, and every Gradle consumer behind a proxy
// that does not carry `.module` files.
//
// `mavenPom()` on its own is not that build. A POM published beside Gradle metadata carries a
// marker comment, and Gradle follows it back to the `.module` file — so a repository declared with
// `mavenPom()` alone would still resolve the variants and this project would be a second copy of
// its sibling. `ignoreGradleMetadataRedirection()` is the line that makes the POM the whole answer.
repositories {
  exclusiveContent {
    forRepository {
      maven {
        name = "publishedPomOnly"
        url = publishedRepository.toURI()
        metadataSources {
          mavenPom()
          ignoreGradleMetadataRedirection()
        }
      }
    }
    filter { includeGroup("org.sempods") }
  }
  mavenCentral()
}
