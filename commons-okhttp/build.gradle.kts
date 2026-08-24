plugins {
  `java-library`
  id("java-test-fixtures")
}

dependencies {
  // `api` throughout, because this module is a thin wrapper and its surface is the wrapped
  // library's. `CommonsHttpClient` takes `Request.Builder`, hands
  // out a `JsonUtil`, and `OkHttpClientModule` provides an `OkHttpClient` a consumer varies with
  // `newBuilder()` — all three are types a consumer necessarily compiles against.
  api(project(":commons"))
  api(project(":commons-json"))
  api(libs.okhttp)

  // Same reasoning as `commons` and `commons-mongo`: `OkHttpClientModule` is the only class here
  // that needs Guice, and a consumer building an `OkHttpClient` by hand must not inherit a DI
  // container to get the wrapper.
  compileOnly(libs.guice)

  implementation(libs.bundles.logging)

  testImplementation(libs.guice)
  testImplementation(libs.bundles.test)
}
