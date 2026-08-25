plugins {
  `java-library`
}

dependencies {
  // `api`: the driver's own types are what this module's helpers take and return, and they come
  // from two artifacts. The sync driver carries `MongoDatabase` and `MongoWriteException`;
  // `Document`, `ObjectId` and the BSON codecs are in `bson`.
  api(project(":commons"))
  api(libs.mongodb)
  api(libs.bson)

  // The ObjectId Jackson codecs. `api` for the same reason: `JsonMappers.withMongo()` hands back
  // an `ObjectMapper`.
  api(project(":commons-json"))
  api(libs.jacksonDatabind)

  // Same reasoning as `commons`: `MongoModule` is the only class here that needs Guice, and a
  // consumer wiring the driver by hand must not inherit a DI container to get a document helper.
  compileOnly(libs.guice)

  // No logging: nothing in this module logs. The driver does, and brings its own slf4j-api.

  testImplementation(libs.bundles.test)
}
