// One service per module: a stranger embedding `sempods-auth` does not also have `sempods-mcp` on
// their classpath, and a single probe holding both hid a missing `api` in each behind the other's
// declaration.

dependencies {
  // `implementation`, as a foreign build would write it: Gradle propagates only `api` across a
  // project boundary, so this module's compile classpath is a consumer's.
  implementation(project(":sempods-auth"))

  // As an embedder writes it too — whoever calls `Guice.createInjector` brings their own container,
  // which is why `:sempods-auth` keeps Guice on `implementation`.
  implementation(libs.guice)
}
