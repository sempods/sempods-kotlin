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

  implementation(libs.bundles.logging)

  // kotlinx-coroutines (for `ThreadContextElement`) arrives with Ktor, which pins it; declaring a
  // second version here is how the two drift apart.

  testImplementation(libs.ktorServerTestHost)
  testImplementation(libs.ktorClientCio)
  testImplementation(libs.bundles.test)
}
