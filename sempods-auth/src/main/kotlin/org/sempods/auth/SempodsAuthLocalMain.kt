package org.sempods.auth

/**
 * Local development entry point for sempods-auth.
 * Hardcodes local URLs — no environment variables needed.
 *
 * Run via IntelliJ. (There is no Gradle task for it — `application.mainClass` is the
 * environment-driven [SempodsAuthMain], which is what the image runs.)
 */
fun main() = startSempodsAuth(
  SempodsAuthConfig(
    port = 8091,
    mongoUrl = "mongodb://localhost:27018",
    mongoDbName = "sempods-auth",
    idBaseUrl = "http://localhost:8091",
  )
)
