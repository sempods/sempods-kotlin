package org.sempods

/**
 * The version of this pod server — the repository's `version` at build time, `0.2.0` or
 * `0.2.1-SNAPSHOT`. Reported as the `serverInfo.version` of the pod-immanent MCP endpoint.
 *
 * Read from a resource the build writes (`versionResource` in `build.gradle.kts`) rather than the
 * JAR manifest, because the container image runs the classes exploded and has no manifest to read.
 */
internal object SempodsServerVersion {
  val current: String =
    checkNotNull(SempodsServerVersion::class.java.getResource("version")) {
      "org/sempods/version is missing from the classpath — the `versionResource` task writes it"
    }.readText().trim()
}
