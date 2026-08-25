plugins {
  `java-library`
}

dependencies {

  // dependent projects — **the seam and nothing else**. This module depends on
  // `:sempods-server`, which is exactly why `:sempods-server` can never name `S3PodMediaStore`
  // to select it: the dependency runs this way round, so only the composition that has both on
  // its classpath can choose. See `SempodsMediaModule` in `:deployments:sempods:image`, and
  // `docs/media.md` §"The seam".
  // **No application framework here, ever** — this module was born in the target shape `:sempods-server` has
  // since reached; see `docs/modularity.md` §"Open-source readiness".
  // `api`: `S3PodMediaStore` is public and implements `PodMediaStore`, which lives there.
  api(project(":sempods-server"))

  // implementation libs
  // No `bson` here any more: `PodMediaRef` used to speak `org.bson.types.ObjectId`, so implementing
  // a seam that has nothing to do with MongoDB meant naming the driver's type. It speaks `PodId`
  // now (#12), and `buildHealth` holds the line — the declaration fails as unused the moment it
  // comes back.
  //
  // What this does *not* mean is a driver-free classpath. `api(project(":sempods-server"))` above
  // still brings `mongodb` and `bson` along, because the server exports them for its own DAO layer.
  // Ending that is the rest of #12, not this line.
  implementation(libs.bundles.logging)
  // The SDK, and the sync HTTP layer under it. `apache-client` is pinned so the client this code
  // uses is the one written down, and discovered by the SDK rather than named.
  implementation(libs.awsS3)
  runtimeOnly(libs.awsApacheClient)

  // The conformance suite is a test fixture of `:sempods-server`: the same assertions, run here
  // against the other implementation. That "same assertions, two backends" is the whole point of
  // the seam, and a copied test file would have stopped being the same one within a month.
  testImplementation(testFixtures(project(":sempods-server")))
  testImplementation(libs.bundles.test)
}
