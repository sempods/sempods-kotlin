plugins {
  `java-library`
}

dependencies {
  // `api` throughout, for the same reason `sempods-commons-jaxrs` does it: Ktor is this module's surface,
  // not an implementation detail. A consumer installs `installTraceContext()` on its own
  // `Application` and the client plugin on its own `HttpClient`, so it holds those types anyway.
  //
  // Transitional in the same sense as `sempods-commons-jaxrs`: that module is the Jersey half of the same
  // job, and when `:sempods-server` moves to Ktor this one absorbs it.
  api(project(":sempods-commons"))
  api(libs.ktorServerCore)
  api(libs.ktorClientCore)

  // `AttributeKey` and `ThreadContextElement` are in the trace binding's own signatures, so a
  // consumer installing `installTraceContext()` compiles against them too.
  api(libs.ktorUtils)
  api(libs.kotlinxCoroutines)

  // No logging: nothing in this module logs.

  testImplementation(libs.ktorServerTestHost)
  testImplementation(libs.ktorClientCio)
  testImplementation(libs.kotlinxCoroutinesTest)
  testImplementation(libs.bundles.test)
}
