plugins {
  `java-library`
}

dependencies {
  // `api`: the driver's own types — `MongoDatabase`, `Document`, `MongoWriteException` — are what
  // this module's helpers take and return, so a consumer necessarily compiles against them.
  api(project(":commons"))
  api(libs.mongodb)

  // The ObjectId Jackson codecs. `api` for the same reason: `JsonMappers.withMongo()` hands back
  // an `ObjectMapper`.
  api(project(":commons-json"))

  // Same reasoning as `commons`: `MongoModule` is the only class here that needs Guice, and a
  // consumer wiring the driver by hand must not inherit a DI container to get a document helper.
  compileOnly(libs.guice)

  implementation(libs.bundles.logging)

  testImplementation(libs.guice)
  testImplementation(libs.bundles.test)
}
