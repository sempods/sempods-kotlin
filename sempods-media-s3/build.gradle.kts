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
  // `org.bson.types.ObjectId` is `PodMediaRef`'s own vocabulary and reaches this module through the
  // seam's signatures — so `api`, and `bson` rather than the sync driver, which is where the type
  // is and which is all this module needs. Declared rather than inherited: `:sempods-server`
  // exports it too, and an artifact this module's own surface names is this module's to say.
  api(libs.bson)
  implementation(libs.bundles.logging)
  // The SDK, and the sync HTTP layer under it. `apache-client` is discovered by the SDK rather
  // than named by anything here — pinned so the client this code uses is the one written down, and
  // `runtimeOnly` because that is when it is needed.
  implementation(libs.awsS3)
  runtimeOnly(libs.awsApacheClient)

  // The conformance suite is a test fixture of `:sempods-server`: the same assertions, run here
  // against the other implementation. That "same assertions, two backends" is the whole point of
  // the seam, and a copied test file would have stopped being the same one within a month.
  testImplementation(testFixtures(project(":sempods-server")))
  testImplementation(libs.bundles.test)
}
