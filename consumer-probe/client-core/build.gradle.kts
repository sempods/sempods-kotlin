// The client core as a Java consumer compiles and runs it. What it checks and why:
// `docs/concepts/modularity.md` §"Open-source readiness". Its Java 21 JVM and the forbidden-dependency
// check are configured in the root build, which applies the plugin that gives this project its tasks.

dependencies {
  // `testImplementation` for the `implementation` a foreign build would write: Gradle propagates
  // only `api` across a project boundary, so this suite's compile classpath is a consumer's.
  testImplementation(project(":sempods-client-core"))

  // Named as a consumer names what it compiles against, although the core's `api` brings it.
  testImplementation(libs.okhttp)

  // JUnit alone: the Kotlin suites' bundle adds MockK and kotlin-test, which a Java suite does not use.
  testImplementation(libs.junitJupiterApi)
  testImplementation(libs.junitJupiterParams)
  testRuntimeOnly(libs.junit)
  testRuntimeOnly(libs.junitPlatformLauncher)
}

// An opt-in source set: ordinary test/check tasks never run a load comparison.
val load = sourceSets.create("load")
dependencies {
  add(load.implementationConfigurationName, project(":sempods-client-core"))
  add(load.implementationConfigurationName, libs.okhttp)
}

listOf(21, 25).forEach { release ->
  tasks.register<JavaExec>("load$release") {
    group = "verification"
    description = "Runs the manual client load comparison on Java $release."
    classpath = load.runtimeClasspath
    mainClass = "org.sempods.probe.clientcore.ClientLoad"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(release) }
    val output = providers.gradleProperty("loadOutput")
      .orElse(layout.buildDirectory.dir("load/java-$release").map { it.asFile.absolutePath })
    args("suite", output.get(), providers.gradleProperty("loadSeconds").getOrElse("5"),
      providers.gradleProperty("loadRepeats").getOrElse("3"),
      providers.gradleProperty("loadConcurrency").getOrElse("96"),
      providers.gradleProperty("loadActive").getOrElse("32"),
      providers.gradleProperty("loadWorkloads").getOrElse(""),
      providers.gradleProperty("loadWarmup").getOrElse("2"))
    systemProperty("load.revision", rootProject.extra["gitRevision"].toString())
  }
}
