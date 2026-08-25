// One service per module, and that separation is the whole design: a stranger embedding
// `sempods-auth` does not also have `sempods-mcp` on their classpath. A single probe holding both
// hid a missing `api` in one of them behind the other's declaration — verified, and the reason
// this is two directories rather than one file.

dependencies {
  // `implementation`, exactly as a foreign build would write it. Gradle propagates only `api`
  // across a project boundary, so what lands on this module's compile classpath is what lands on a
  // consumer's — nothing more.
  implementation(project(":sempods-auth"))

  // Declared here for the same reason `:sempods-auth` keeps it on `implementation`: whoever calls
  // `Guice.createInjector` brings their own container. An embedder writes this line too.
  implementation(libs.guice)
}
