plugins {
  `java-library`
}

description = "The host-level admin surface of a sempods server, over the sempods pod client core."

dependencies {
  // `api`: every signature here names the core's session, its answers and its request builder. The
  // direction of the edge is the point — the proprietary plane runs on the pod client's execution,
  // never the other way round, and a consumer of the pod specification adds no dependency on this
  // module at all.
  api(project(":sempods-client"))

  // `api` rather than `implementation`: the constructor names `okhttp3.Call.Factory`, so a consumer
  // compiles against OkHttp. It also arrives through the core's `api` edge.
  api(libs.okhttp)

  // The admin routes exchange plain JSON, and no public signature names the library that reads it.
  implementation(libs.jackson3Databind)

  // No logging: nothing in this module logs, for the reason `:sempods-client` gives.

  testImplementation(libs.slf4jApi)
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
