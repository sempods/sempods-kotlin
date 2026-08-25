// The `:sempods-mcp` half of the embedding probe; `:consumer-probe:auth` says why the two are
// separate modules.

dependencies {
  implementation(project(":sempods-mcp"))
  implementation(libs.guice)
}
