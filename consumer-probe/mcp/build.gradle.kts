// The `:sempods-mcp` half of the embedding probe; see `:consumer-probe:auth` for why the two are
// separate modules.

dependencies {
  implementation(project(":sempods-mcp"))
  implementation(libs.guice)
}
