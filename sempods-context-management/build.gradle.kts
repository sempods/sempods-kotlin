plugins {
  `java-library`
}

description = "Context creation and deletion for the sempods pod server: the spec's optional context-management module."

dependencies {
  // `api`: the endpoint extends the server's `PodBaseEndpoint`, and the Guice module binds the
  // server's `ContextManagementInstalled`. The edge runs this way so the server never names the
  // module: only a composition holding both installs it, as `:sempods-media-s3` is selected.
  api(project(":sempods-server"))
  // `api`: `ContextManagementModule` extends its `BaseModule`.
  api(project(":sempods-commons"))
  api(libs.guice)
  api(libs.jakartaWsRsApi)
  // The endpoint registration, `JaxRsApplicationModule.bindEndpoints`, named in no public signature.
  implementation(project(":sempods-commons-jaxrs"))

  testImplementation(libs.bundles.test)
}

// What the server marks `ExperimentalSempodsServerApi` is what this module is built on, knowingly.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions.optIn.add("org.sempods.ExperimentalSempodsServerApi")
}
