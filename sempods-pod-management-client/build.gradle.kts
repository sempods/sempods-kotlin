plugins {
  `java-library`
}

description = "A pod owner's grant management on the sempods reference implementation, over the sempods pod client core."

dependencies {
  // `api`: every signature here names the core's session and its answers. The edge points this way
  // so that a consumer of the pod specification adds no dependency on this module at all: the routes
  // it speaks are this implementation's own, and stay so (sempods-spec#137).
  api(project(":sempods-client"))

  // `api` rather than `implementation`: the constructor names `okhttp3.Call.Factory`, so a consumer
  // compiles against OkHttp. It also arrives through the core's `api` edge.
  api(libs.okhttp)

  // The routes answer plain JSON, and no public signature names the library that reads it.
  implementation(libs.jackson3Databind)

  // No logging: nothing in this module logs, for the reason `:sempods-client` gives.

  testImplementation(libs.slf4jApi)
  testImplementation(libs.mockServer)
  testImplementation(libs.bundles.test)
}
