plugins {
  `java-library`
}

dependencies {
  // `api` throughout: this module exists to hand its consumers a configured `ObjectMapper`, so
  // Jackson is part of its surface rather than an implementation detail.
  api(project(":commons"))
  api(libs.jackson)
  api(libs.jacksonKotlin)

  implementation(libs.bundles.logging)

  testImplementation(libs.bundles.test)
}
