plugins {
  `java-library`
}

dependencies {
  // `api`: this module exists to hand its consumers a configured `ObjectMapper`, so Jackson is part
  // of its surface rather than an implementation detail. The artifact is `jackson-databind` — where
  // `ObjectMapper` and `JsonNode` actually live — rather than either of the two registrations
  // below, which merely depend on it.
  api(libs.jacksonDatabind)

  // `implementation`, not `api`: `JsonMappers` names `JavaTimeModule` and `registerKotlinModule`
  // when it builds the mapper, and a consumer holding the built `ObjectMapper` never does.
  implementation(libs.jackson)
  implementation(libs.jacksonKotlin)

  // No `:commons`, and no logging: neither is reached from here. This module is `JsonMappers` and
  // its codecs, and a dependency it cannot justify is one it does not have — the same line
  // `:sempods-mcp-core` draws.

  testImplementation(libs.bundles.test)
}
