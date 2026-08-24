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
  // `api`: `S3PodMediaStore` is public and implements `PodMediaStore`, which lives there. A
  // consumer that never names the supertype does not need this, but one that does could not.
  api(project(":sempods-server"))

  // implementation libs
  // `org.bson.types.ObjectId` is `PodMediaRef`'s own vocabulary and reaches this module through the
  // seam's signatures. `:sempods-server` holds the driver as `implementation`, so it is not
  // inherited.
  implementation(libs.mongodb)
  implementation(libs.bundles.logging)
  implementation(libs.bundles.awsS3)

  // The conformance suite is a test fixture of `:sempods-server`: the same assertions, run here
  // against the other implementation. That "same assertions, two backends" is the whole point of
  // the seam, and a copied test file would have stopped being the same one within a month.
  testImplementation(testFixtures(project(":sempods-server")))
  testImplementation(libs.bundles.test)
}
