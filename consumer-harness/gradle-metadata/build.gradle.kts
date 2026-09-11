val publishedRepository = rootProject.extra["publishedRepository"] as File

repositories {
  exclusiveContent {
    forRepository {
      maven {
        name = "published"
        url = publishedRepository.toURI()
      }
    }
    // Both halves matter. Nothing but this repository may answer for `org.sempods`, so a module
    // that failed to publish is a resolution failure here instead of a fall-through to Central;
    // and this repository is asked for nothing else, so every third-party lookup goes straight out.
    filter { includeGroup("org.sempods") }
  }
  mavenCentral()
}
