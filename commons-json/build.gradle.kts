plugins {
  `java-library`
}

dependencies {
  // `api`: this module exists to hand its consumers a configured `ObjectMapper`, so Jackson is part
  // of its surface rather than an implementation detail.
  api(libs.jacksonDatabind)

  // `implementation`: `JsonMappers` names `JavaTimeModule` and `registerKotlinModule` when it
  // builds the mapper; a consumer holding the built mapper does not.
  implementation(libs.jackson)
  implementation(libs.jacksonKotlin)

  // No `:commons` and no logging: neither is reached from here. This module is `JsonMappers` and
  // its codecs — the same line `:sempods-mcp-core` draws.

  testImplementation(libs.bundles.test)
}
