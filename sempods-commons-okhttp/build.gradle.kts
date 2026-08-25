plugins {
  `java-library`
  id("java-test-fixtures")
}

dependencies {
  // `api` throughout, because this module is a thin wrapper and its surface is the wrapped
  // library's. `CommonsHttpClient` takes `Request.Builder`, hands
  // out a `JsonUtil`, and `OkHttpClientModule` provides an `OkHttpClient` a consumer varies with
  // `newBuilder()` — all three are types a consumer necessarily compiles against.
  api(project(":sempods-commons"))
  api(project(":sempods-commons-json"))
  api(libs.okhttp)
  // `JsonUtil` hands back Jackson's own types.
  api(libs.jacksonDatabind)

  // Same reasoning as `sempods-commons` and `sempods-commons-mongo`: `OkHttpClientModule` is the only class here
  // that needs Guice, and a consumer building an `OkHttpClient` by hand must not inherit a DI
  // container to get the wrapper.
  compileOnly(libs.guice)

  // No logging: nothing in this module logs — the caller decides what a failed request means.

  testImplementation(libs.bundles.test)
}
