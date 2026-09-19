plugins {
  `java-library`
}

description = "The pod's media routes over the sempods pod client core."

dependencies {
  // `api`: every signature here names the core's session and answers, and the media contract's own
  // types.
  api(project(":sempods-client"))
  api(project(":sempods-media"))

  // Named directly because this module names it directly: it builds an `okhttp3.Request` through the
  // session and hands it to the core's exchange. It arrives through the core's `api` either way.
  implementation(libs.okhttp)

  // The upload route answers JSON, and one of the two upload paths sends it.
  implementation(libs.jackson3Databind)

  // The routes both sides read from one place.
  implementation(project(":sempods-commons"))

  testImplementation(libs.mockServer)
  testImplementation(libs.slf4jApi)
  testImplementation(libs.bundles.test)
}
