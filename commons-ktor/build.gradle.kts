plugins {
  `java-library`
}

dependencies {
  // `api` throughout, for the same reason `commons-jaxrs` does it: Ktor is this module's surface,
  // not an implementation detail. A consumer installs `installTraceContext()` on its own
  // `Application` and the client plugin on its own `HttpClient`, so it holds those types anyway.
  //
  // Transitional in the same sense as `commons-jaxrs`: that module is the Jersey half of the same
  // job, and when `:sempods-server` moves to Ktor this one absorbs it.
  api(project(":commons"))
  api(libs.ktorServerCore)
  api(libs.ktorClientCore)

  // `AttributeKey` and `ThreadContextElement` are in the signatures of the trace binding itself, so
  // both are `api` too. Named rather than left to arrive with Ktor: the version still comes from
  // the Ktor BOM (`coroutines` in the catalog matches what Ktor pins, and the two must not drift),
  // but a consumer installing `installTraceContext()` compiles against these types and cannot be
  // expected to work out which Ktor artifact they came in.
  api(libs.ktorUtils)
  api(libs.kotlinxCoroutines)

  // No logging: nothing in this module logs.

  testImplementation(libs.ktorServerTestHost)
  testImplementation(libs.ktorClientCio)
  testImplementation(libs.kotlinxCoroutinesTest)
  testImplementation(libs.bundles.test)
}
