plugins {
  `java-library`
}

dependencies {
  // `api`: the driver's own types are what this module's helpers take and return, so a consumer
  // necessarily compiles against them — and they come from two artifacts, not one. The sync driver
  // carries `MongoDatabase` and `MongoWriteException`; `Document`, `ObjectId` and the BSON codecs
  // are in `bson`. Naming only the driver held exactly as long as the driver kept a dependency it
  // never promised.
  api(project(":commons"))
  api(libs.mongodb)
  api(libs.bson)

  // The ObjectId Jackson codecs. `api` for the same reason: `JsonMappers.withMongo()` hands back
  // an `ObjectMapper`, which is `jackson-databind`'s type — declared here rather than inherited
  // through `:commons-json`, because a signature that names a type declares the artifact it is in.
  api(project(":commons-json"))
  api(libs.jacksonDatabind)

  // Same reasoning as `commons`: `MongoModule` is the only class here that needs Guice, and a
  // consumer wiring the driver by hand must not inherit a DI container to get a document helper.
  compileOnly(libs.guice)

  // No logging: nothing in this module logs. slf4j-api still reaches a deployment's runtime
  // classpath — the driver itself logs — but it does so as the driver's dependency, not as ours.

  testImplementation(libs.bundles.test)
}
