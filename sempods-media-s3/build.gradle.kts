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
  // No MongoDB artifact: nothing here names an `org.bson` type, and `buildHealth` fails such a
  // declaration as unused. Both artifacts are still *inherited* — `mongodb` through
  // `:sempods-server`, `bson` through `:sempods-auth-core`.
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
