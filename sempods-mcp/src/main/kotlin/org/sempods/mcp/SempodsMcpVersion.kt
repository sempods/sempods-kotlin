package org.sempods.mcp

/**
 * The version of this service — the repository's `version` at build time, `0.2.0` or
 * `0.2.1-SNAPSHOT`. Reported as the MCP `serverInfo.version` and as the `software_version` in the
 * registrations it makes at pods.
 *
 * Read from a resource the build writes (`versionResource` in `build.gradle.kts`) rather than the
 * JAR manifest, because the container image runs the classes exploded and has no manifest to read.
 */
internal object SempodsMcpVersion {
  val current: String =
    checkNotNull(SempodsMcpVersion::class.java.getResource("version")) {
      "org/sempods/mcp/version is missing from the classpath — the `versionResource` task writes it"
    }.readText().trim()
}
