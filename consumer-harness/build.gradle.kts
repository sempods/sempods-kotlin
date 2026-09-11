import java.util.concurrent.TimeUnit

// `java-base` and nothing else: the root project compiles nothing, but `JavaToolchainService` is
// registered by it, and `requireConsumerJdk` below is what turns a missing JDK into a sentence
// rather than a toolchain trace.
plugins {
  `java-base`
}

// Which JDK compiles and runs the consumers — one value per invocation, because that is what a CI
// matrix entry is. `checkPublishedArtifacts` in the repository root publishes the current version
// and then runs this build once per entry of `-PconsumerJdks`, whose default is both.
val consumerJdk = JavaLanguageVersion.of((findProperty("consumerJdk") as String? ?: "21").toInt())

// No default. The version is the one value that goes stale silently: bump `version` in the
// producer's `gradle.properties`, forget this, and the harness keeps resolving the previous
// coordinate out of a directory that still holds it — green, and about nothing.
val sempodsVersion = findProperty("sempodsVersion") as String?
  ?: throw GradleException(
    "This build resolves the sempods modules by Maven coordinate, so it has to be told which " +
      "version to ask for. `./gradlew checkPublishedArtifacts` in the repository root publishes " +
      "the current version and then runs this build; by hand it is " +
      "`./gradlew -p consumer-harness check -PsempodsVersion=<version>`.",
  )

// A wrong path fails loudly at resolution, so this one may default.
val publishedRepository = file(
  findProperty("sempodsRepository") as String?
    ?: rootDir.resolve("../build/consumer-harness-repo").path,
)

val toolchains = extensions.getByType<JavaToolchainService>()

// The toolchain failure Gradle produces on its own names a JVM specification and a machine, and
// tells a reader nothing about what to install or where this build looked. It also arrives while a
// task's inputs are being snapshotted, which is before any `doFirst` could say anything better —
// hence a task of its own, ahead of every compile and every run.
val requireConsumerJdk = tasks.register("requireConsumerJdk") {
  group = "verification"
  description = "Fails with an actionable message when the JDK this invocation needs is not installed."
  doLast {
    val compiler = runCatching { toolchains.compilerFor { languageVersion = consumerJdk }.get() }
    val launcher = runCatching { toolchains.launcherFor { languageVersion = consumerJdk }.get() }

    if (compiler.isFailure || launcher.isFailure) {
      // Without a cause. Gradle prints a failure's cause chain under `What went wrong` and this
      // message would sit above it unread — and the cause here says only that no installation
      // matched, which is the part a reader already knows.
      throw GradleException(
        "The consumer harness compiles and runs on JDK ${consumerJdk.asInt()}, and Gradle found no " +
          "such installation.\n\n" +
          "Install one — Temurin ${consumerJdk.asInt()} — or point Gradle at one that is already " +
          "there:\n" +
          "  ./gradlew -p consumer-harness check -PsempodsVersion=… " +
          "-Porg.gradle.java.installations.paths=/path/to/jdk-21,/path/to/jdk-25\n\n" +
          "That is a gradle property: `-D` of the same name is read by nothing. " +
          "`./gradlew -q -p consumer-harness javaToolchains` lists what Gradle can see.\n\n" +
          "This build never downloads a JDK. There is no toolchain resolver plugin here on " +
          "purpose: the versions under test have to be the versions installed, or the matrix is " +
          "testing whatever a resolver happened to serve.",
      )
    }

    // The evidence the acceptance asks for, in the log of every run.
    logger.lifecycle("consumer javac: ${compiler.getOrThrow().metadata.javaRuntimeVersion}")
    logger.lifecycle("consumer java:  ${launcher.getOrThrow().metadata.javaRuntimeVersion}")
  }
}

subprojects {
  apply(plugin = "java")

  val sourceSets = extensions.getByType<SourceSetContainer>()

  extensions.configure<JavaPluginExtension> {
    toolchain { languageVersion = consumerJdk }

    // One consumer, compiled twice. The two projects differ in how their repository is read and in
    // nothing else, so a difference in outcome is a difference in metadata rather than in source.
    sourceSets["main"].java.setSrcDirs(listOf(rootProject.file("src/main/java")))
  }

  dependencies {
    // The whole of the producer, as a consumer states it: one coordinate, no version catalog, no
    // project path. What arrives with it is what the acceptance is about.
    "implementation"("org.sempods:sempods-client-core:$sempodsVersion")
  }

  configurations.configureEach {
    resolutionStrategy {
      // A snapshot is a changing module, and Gradle caches one for 24 hours. Without this the
      // second run of the day resolves the jar it cached this morning — out of a directory that has
      // since been deleted and rewritten — and the harness passes against an artifact this build
      // did not produce. It is the one failure mode here that looks exactly like success.
      cacheChangingModulesFor(0, TimeUnit.SECONDS)
      cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
    }
  }

  tasks.withType<JavaCompile>().configureEach {
    dependsOn(requireConsumerJdk)
    javaCompiler = toolchains.compilerFor { languageVersion = consumerJdk }

    // 21 whichever JDK compiles: it is the floor the published modules promise, and a consumer
    // compiled only by the JDK that also runs it never tests that promise from either side.
    options.release = 21
  }

  val runConsumer = tasks.register<JavaExec>("runConsumer") {
    group = "verification"
    description = "Runs the consumer as a real JDK ${consumerJdk.asInt()} process."
    dependsOn(requireConsumerJdk)
    mainClass = "org.sempods.harness.PublishedArtifactConsumer"
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher = toolchains.launcherFor { languageVersion = consumerJdk }
    args(consumerJdk.asInt().toString())
  }

  // What a stranger actually downloads. The resolved graph rather than the declared dependencies,
  // because the mistake being watched for is transitive: a module naming one of these in a
  // signature hands it to every consumer, and the graph resolved here is the graph they resolve.
  val runtimeClasspath = configurations.named("runtimeClasspath")
  val modulePath = project.path

  val checkNoForbiddenDependencies = tasks.register("checkNoForbiddenDependencies") {
    group = "verification"
    description = "Fails if the consumer's runtime classpath carries RDF4J, Jena, Jackson or a media DTO."
    doLast {
      val forbidden = mapOf(
        "org.eclipse.rdf4j" to "RDF4J",
        "org.apache.jena" to "Jena",
        "com.fasterxml.jackson" to "Jackson",
        "org.sempods:sempods-model" to "the legacy media and RDF DTOs",
      )
      val offenders = runtimeClasspath.get().incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? ModuleComponentIdentifier }
        .map { "${it.group}:${it.module}:${it.version}" }
        .filter { coordinate -> forbidden.keys.any { coordinate.startsWith(it) } }
        .distinct().sorted()

      if (offenders.isNotEmpty()) {
        throw GradleException(
          "$modulePath resolves ${offenders.joinToString()}. A consumer of the published HTTP core " +
            "would take an RDF store, a triple parser or an object mapper with it, which is the " +
            "one thing that core exists not to do. `./gradlew -p consumer-harness " +
            "$modulePath:dependencies --configuration runtimeClasspath` shows which edge brings it.",
        )
      }
    }
  }

  // Isolation, asked rather than asserted. Two things the source alone cannot show: an init script
  // in a Gradle user home — `~/.gradle/init.d/*.gradle` adding `mavenLocal()` is a common developer
  // setup — and a substitution that turned a coordinate back into a project.
  val checkResolutionIsolated = tasks.register("checkResolutionIsolated") {
    group = "verification"
    description = "Fails if anything but the published file repository and Central could answer here."
    val declaredNames = provider { repositories.map { it.name } }
    doLast {
      val expected = setOf("published", "publishedPomOnly", ArtifactRepositoryContainer.DEFAULT_MAVEN_CENTRAL_REPO_NAME)
      val unexpected = declaredNames.get().toSet() - expected
      // Every graph has this project at its root; what would be wrong is another one below it.
      val result = runtimeClasspath.get().incoming.resolutionResult
      val projects = (result.allComponents - result.root)
        .map { it.id }.filterIsInstance<ProjectComponentIdentifier>().map { it.projectPath }

      val problems = buildList {
        if (unexpected.isNotEmpty()) add("repositories this build did not declare: ${unexpected.joinToString()}")
        if (projects.isNotEmpty()) add("project components instead of modules: ${projects.joinToString()}")
      }
      if (problems.isNotEmpty()) {
        throw GradleException(
          "$modulePath is no longer resolving only published artifacts — ${problems.joinToString("; ")}. " +
            "A harness that can reach a project or a populated `~/.m2` tests the build it is part of.",
        )
      }
    }
  }

  // The engine's own half of the claim. It is `implementation` in the core, so it is on this
  // consumer's runtime classpath and has to be — what it must not be is something a consumer can
  // name. Checking the compile classpath is the only way to say that from out here: inside the
  // repository every module has its own dependencies on its own compile classpath, so a leak
  // compiles perfectly there.
  val compileClasspath = configurations.named("compileClasspath")

  val checkEngineIsNotCompilable = tasks.register("checkEngineIsNotCompilable") {
    group = "verification"
    description = "Fails if a consumer could compile against the HTTP engine the core hides."
    doLast {
      val reachable = compileClasspath.get().incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? ModuleComponentIdentifier }
        .map { "${it.group}:${it.module}" }
        .filter { it.startsWith("com.squareup.okhttp3") || it.startsWith("com.squareup.okio") }
        .distinct().sorted()

      if (reachable.isNotEmpty()) {
        throw GradleException(
          "$modulePath can compile against ${reachable.joinToString()}. The engine stops at the " +
            "core's own types: a consumer speaks SempodsRequest and SempodsResponse, and putting " +
            "the engine on their compile classpath ties this library's ABI to its major version.",
        )
      }
    }
  }

  tasks.named("check") {
    dependsOn(runConsumer, checkNoForbiddenDependencies, checkResolutionIsolated, checkEngineIsNotCompilable)
  }
}

// Read by both subprojects; declared here so each names the directory once.
extra["publishedRepository"] = publishedRepository
