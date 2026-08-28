import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.w3c.dom.Element
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  // Version in `settings.gradle.kts`, which is where the dependency-analysis plugin needs to see
  // it. See the comment there.
  id("org.jetbrains.kotlin.jvm")
}

// The root project builds nothing, but `com.autonomousapps.dependency-analysis` resolves
// `kotlin-metadata-jvm` against it, and the repositories below are declared inside
// `subprojects { }` only.
repositories {
  mavenCentral()
}

// The version catalog, resolved once here: the generated `libs` accessor exists only in a build
// script's own scope, not on the `Project` receiver inside `subprojects { }`.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// The modules published to Maven Central, and what `sempods-bom` builds its constraints from.
// Listed rather than derived from `java-library`: a platform is configured before the modules it
// would inspect, so deriving it silently yields a short list.
val publishedModules = listOf(
  "sempods-commons",
  "sempods-commons-jaxrs",
  "sempods-commons-json",
  "sempods-commons-ktor",
  "sempods-commons-mongo",
  "sempods-commons-okhttp",
  "sempods-auth",
  "sempods-auth-core",
  "sempods-client",
  "sempods-control-plane-client",
  "sempods-mcp",
  "sempods-mcp-core",
  "sempods-media-s3",
  "sempods-model",
  "sempods-server",
)
extra["publishedModules"] = publishedModules

subprojects {

  // `sempods-bom` is a `java-platform`, and a platform is a POM and nothing else: it has no source
  // set, no classpath and no tests. Everything below this line configures a JVM module — the Kotlin
  // plugin, a toolchain, a test task — and `java-platform` refuses to coexist with the `java`
  // plugin those rest on, so applying any of it fails the build rather than being merely useless.
  if (name == "sempods-bom") return@subprojects

  // Only the Kotlin plugin: it applies `java` itself, which is what the `java-library` and
  // `java-test-fixtures` plugins in the module scripts build on. There are no Java sources —
  // `compileJava` exists but is always NO-SOURCE.
  apply {
    plugin("org.jetbrains.kotlin.jvm")
  }

  repositories {
    mavenCentral()
  }

  configurations.configureEach {
    // Prefer Angus mail artifacts and avoid conflicting legacy Jakarta mail/activation transitive deps.
    exclude(group = "com.sun.mail", module = "jakarta.mail")
    exclude(group = "com.sun.activation", module = "jakarta.activation")
  }

  java {
    toolchain {
      languageVersion = JavaLanguageVersion.of(25)
    }
  }

  dependencies {
    // The logback binding, for tests only. Production code gets it from whichever artifact owns
    // the `main`; a test JVM has no such artifact, and without a binding every module would log
    // to slf4j's NOP and `logback-test.xml` would be inert. One place beats twelve.
    "testRuntimeOnly"(catalog.findBundle("loggingBinding").get())
  }

  // The other half of `LoggingAssertions`: a library must not carry the logback binding into a
  // consumer's runtime. `implementation` is transitive at runtime, so this is easy to reintroduce by
  // adding one bundle to one build file, and impossible to notice — the consumer just silently gets
  // logback. Applications are exempt: choosing a binding is exactly their job. See `docs/logging.md`.
  //
  // Out here on the `Project` receiver, not inside `doLast`: the receiver in there is the task,
  // whose `name` is in no module list, so the guard would skip every module and still report green.
  val mustCheckLoggingBinding = name in publishedModules

  val checkNoLoggingBinding = tasks.register("checkNoLoggingBinding") {
    group = "verification"
    description = "Fails if a library module carries the logback binding on its runtime classpath."
    // A module nobody publishes has no consumer to impose a binding on, and the `:consumer-probe`
    // modules inherit a service's anyway. `onlyIf` rather than a `return@doLast`, so an exempt
    // module reports `SKIPPED` instead of looking like one that ran and found nothing.
    onlyIf { mustCheckLoggingBinding && !it.project.plugins.hasPlugin("application") }
    doLast {
      // Not `?: return@doLast`: every published module applies the Kotlin plugin and so has this
      // configuration. Its absence is a build that changed shape, not a module to wave through.
      val runtimeClasspath = configurations.findByName("runtimeClasspath")
        ?: throw GradleException(
          "${project.path} is published but has no `runtimeClasspath` configuration, so " +
            "`checkNoLoggingBinding` cannot inspect it. See `docs/logging.md`.",
        )
      val offenders = runtimeClasspath.incoming.artifacts.artifacts
        .map { it.id.componentIdentifier.displayName }
        .filter { it.startsWith("ch.qos.logback:") }
        .distinct()
      if (offenders.isNotEmpty()) {
        throw GradleException(
          "${project.path} is a library and must declare `libs.bundles.logging` (the facade) " +
            "only, but its runtime classpath carries the binding: ${offenders.joinToString()}. " +
            "The binding belongs to `runtimeOnly(libs.bundles.loggingBinding)` in an artifact that " +
            "owns a `main`. See `docs/logging.md`.",
        )
      }
    }
  }
  tasks.matching { it.name == "check" }.configureEach { dependsOn(checkNoLoggingBinding) }

  tasks.withType<JavaExec>().configureEach {
    // kotlin-logging prints `kotlin-logging: initializing... active logger factory: …` to
    // stdout on first use unless this is off. It fires from a static initializer, before any
    // `main` body runs, so a system property set in code is always too late — it has to be a
    // JVM flag. Documented in `docs/logging.md`.
    systemProperty("kotlin-logging.logStartupMessage", "false")
  }

  tasks.test {
    useJUnitPlatform()

    // One fork per module, deliberately, and `forkEvery` stays unset for the same reason. Each
    // module's suite shares one lazily built injector and one Jetty server
    // (`SempodsIntegrationTest.sempodsInjector` and its siblings), so a second fork would pay for a
    // second boot and then collide with the first on the connector — and the per-class DAO
    // collections (`test.pod.dao` and friends, dropped in `@BeforeEach`) are only distinct *within*
    // a JVM. Concurrency inside a module comes from JUnit, across modules from `org.gradle.parallel`.
    maxParallelForks = 1

    // The test JVM's heap, not the daemon's. Gradle defaults to 512m, which is not much for a JVM
    // running Jetty, an RDF4J store, a Mongo driver pool and up to four Netty mock servers.
    maxHeapSize = "2g"

    testLogging {
      events("passed", "skipped", "failed")
      exceptionFormat = TestExceptionFormat.FULL
      // Off by default. A full run writes ~1,700 tests' worth of log through Gradle's output
      // machinery — 23 MB of console for one run — and once classes run concurrently the lines
      // interleave, so the volume buys nothing back. Failures stay readable through
      // `exceptionFormat = FULL`, and the trace id in `gradle/logback-test.xml`'s pattern is what
      // makes an interleaved log readable at all. `-PtestStdout` brings it back for one run.
      showStandardStreams = project.hasProperty("testStdout")
    }
    // Enable experimental ByteBuddy support for Java 25 (required for MockK)
    jvmArgs("-Dnet.bytebuddy.experimental=true")

    // Test classes run side by side; the methods inside one class do not.
    //
    // That combination is what makes it safe here rather than merely faster. The unit of isolation
    // in this repository is the class: it owns its DAO collection (`test.pod.dao` and friends,
    // dropped in `@BeforeEach`), its pods carry names from `randomId()`, and the mechanism the
    // suites are built on — `GuiceAppTestProxy` — keys its state on
    // `TraceContextHolder.getTraceId()`, a `ThreadLocal`. Methods within a class share all of that,
    // and `same_thread` is what keeps it true.
    //
    // Sizing is deliberately absent: JUnit's default strategy (`dynamic`, one worker per available
    // processor) is the answer until a measurement says otherwise. Set here rather than in a
    // `junit-platform.properties` because this repository has no `buildSrc` and already keeps its
    // shared test configuration in this one block — and because a module that is not ready opts out
    // where a reader looks for it, in its own build file:
    //
    //     tasks.test { systemProperty("junit.jupiter.execution.parallel.enabled", "false") }
    //
    // See `docs/testing.md` §"Running in parallel" for what to do when a test is not safe.
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")

    // Methods stay on one thread, unless `-PtestMethodsConcurrent` asks for the harsher setting —
    // that is how a coupling *inside* a class is forced into the open, and it has to be a project
    // property rather than a `-D` on the command line: `-D` lands on the Gradle daemon, while what
    // the forked test JVM sees is this map. The same distinction the `environment(..)` call below
    // turns on, from the other side.
    systemProperty(
      "junit.jupiter.execution.parallel.mode.default",
      if (project.hasProperty("testMethodsConcurrent")) "concurrent" else "same_thread",
    )

    // One logging configuration for every test JVM — see `gradle/logback-test.xml` for why it is
    // one file and not a `logback-test.xml` per module. Without it the suites run on Logback's
    // DEBUG fallback.
    systemProperty("kotlin-logging.logStartupMessage", "false")
    systemProperty(
      "logback.configurationFile",
      rootProject.layout.projectDirectory.file("gradle/logback-test.xml").asFile.absolutePath,
    )

    // Shared infrastructure, one namespace per module.
    //
    // Every suite reaches the same MongoDB on 27018 and the same connector ports, because
    // `SempodsModule.config` and friends all fall back to the same development defaults. Serially
    // that only cost accumulated garbage: a shared database keeps every previous run's rows, and a
    // suite that walks a collection then spends its time reading other runs' leftovers.
    // Concurrently it would also be wrong — two module suites would race for database and port.
    //
    // `environment(..)` rather than `systemProperty(..)`: `Env.get` resolves the environment
    // before system properties, so a suite that mutates properties (`EnvTest`,
    // `SempodsMediaModuleTest`) cannot shadow these.
    environment(
      "MONGODB_DB_NAME",
      "test-" + project.path.removePrefix(":").replace(':', '-'),
    )

    // Connector ports, one range per module. Fixed rather than picked from an ephemeral socket:
    // `SempodsModule.config` is a companion `by lazy` value read at class initialization, so the
    // port has to exist before any of our code runs. A deterministic value also keeps a failure
    // reproducible. `-PtestPortBase=` moves the range when two builds share a machine.
    //
    // 8090 — the development default — is deliberately not in the table: a locally running dev
    // server does not collide with a test run.
    val testPortBase = (findProperty("testPortBase") as String?)?.toInt() ?: 19000
    when (project.path) {
      ":sempods-server" -> {
        environment("SEMPODS_HTTP_PORT", testPortBase + 10)
        // The token endpoint's per-client budget, which defaults to off in development — and
        // `PodAuthEndpointRateLimitHttpTest` is the only thing that can show the limiter is
        // actually reached on a real connector rather than merely constructed. A small number,
        // and safe to be one: the bucket is keyed by the caller's `X-Forwarded-For`, that test
        // sends a fresh address per case, and no other test in the suite sends the header at all,
        // so nothing else has a bucket to exhaust.
        environment("SEMPODS_TOKEN_RATE_LIMIT_PER_MINUTE", 5)
        // Both halves, because the production defaults differ: the burst there is sized for a
        // provisioning sweep, and inheriting it here would mean 300 requests per case.
        environment("SEMPODS_TOKEN_RATE_LIMIT_BURST", 5)
        // The address tier sits in front of that one, so it has to stay out of its way here: each
        // case brings a fresh address and makes a handful of requests on it. Its own behaviour is
        // `PodTokenRateLimiterTest`'s subject, where the budget is stated per case.
        environment("SEMPODS_TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE", 60)
        environment("SEMPODS_TOKEN_RATE_LIMIT_ADDRESS_BURST", 60)
      }
    }
  }

  if (name !in publishedModules) return@subprojects

  apply(plugin = "maven-publish")

  // Java 21 for the artifacts; the build still compiles and tests on the toolchain 25 above.
  // Raising this floor later breaks every consumer already on it. Both halves, because Gradle
  // fails a Kotlin target that disagrees with `compileJava`'s.
  tasks.withType<JavaCompile>().configureEach { options.release = 21 }
  tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21

      // `jvmTarget` sets the class-file version but not which JDK classes are visible: without
      // this, a call to a Java 22 method compiles here and dies with `NoSuchMethodError` on 21.
      freeCompilerArgs.add("-Xjdk-release=21")
    }
  }

  // Central requires both. Kotlin has no javadoc, so that jar is empty; it must merely exist.
  extensions.configure<JavaPluginExtension> {
    withSourcesJar()
    withJavadocJar()
  }

  // What this repository calls a test library, for the check further down. `libs.bundles.test` is
  // the stack every module takes; `awaitility` and `mockServer` sit beside it because only some
  // source sets use them, so naming the bundle alone would leave two of the five unwatched.
  //
  // The logback binding is deliberately not here. It is the one thing on this list a *published*
  // module may legitimately declare — `sempods-auth` and `sempods-mcp` own a `main` and choose
  // one — and `checkNoLoggingBinding` above already asks that question with the exemption that
  // makes it answerable. A binding arriving through the fixtures instead is removed by the
  // `pom.withXml` block below like anything else the fixtures bring alone.
  val testLibraries = (
    catalog.findBundle("test").get().get() +
      listOf("awaitility", "mockServer").map { catalog.findLibrary(it).get().get() }
    )
    .map { "${it.module.group}:${it.module.name}" }
    .toSet()

  // What a configuration *declares*, transitively through the ones it extends, by coordinates.
  // `apiElements` and `runtimeElements` are the two Gradle maps onto a POM scope, so asking them
  // is asking the same question `maven-publish` asks — including whatever the Kotlin plugin adds
  // on its own, which naming `api` and `implementation` by hand would miss.
  fun declaredIn(vararg configurationNames: String) = configurationNames
    .mapNotNull { configurations.findByName(it) }
    .flatMap { it.allDependencies }
    .map { "${it.group}:${it.name}" }
    .toSet()

  extensions.configure<PublishingExtension> {
    publications {
      // This also carries the test-fixtures variant where `java-test-fixtures` is applied, so a
      // consumer can ask for `testFixtures("org.sempods:sempods-server")`. The fixtures *jar*
      // travels in Gradle module metadata, and a plain Maven consumer never resolves it.
      //
      // Its *dependencies* are a different matter, and the trap this repository fell into: a POM
      // has one flat dependency list and no notion of a variant, so `maven-publish` folds every
      // variant of the component into it and a `testFixturesImplementation` reads as a `runtime`
      // dependency of the module itself. `org.sempods:sempods-server` handed every plain Maven
      // consumer JUnit, kotlin-test and MockK that way, and `org.sempods:commons` did the same.
      create<MavenPublication>("maven") {
        from(components["java"])

        // So they come back out here, at the one place that is only the POM.
        //
        // What goes is what the fixtures bring and the module itself does not — a rule about where
        // a dependency comes from rather than a list of libraries, so a library moving in or out
        // of `libs.bundles.test` cannot quietly widen the hole again. `sempods-commons` is why that
        // matters: its `TestUtil` takes Awaitility, which is no longer in the bundle.
        //
        // The alternative — declaring the test libraries `testFixturesCompileOnly` in the module
        // files — is the wrong half of the problem: it also takes them out of
        // `testFixturesRuntimeElements`, and the fixtures genuinely need them there.
        // `PodMediaStoreConformanceTest` calls `kotlin.test` assertions from its own bytecode in
        // the test JVM of whoever extends it, so an implementer of the media seam resolving
        // `testFixtures("org.sempods:sempods-server")` would get a `NoClassDefFoundError` the
        // first time the suite ran. Gradle module metadata is where that consumer resolves from,
        // and it stays whole; only the POM loses them.
        //
        // Computed inside `withXml` rather than captured above: this block is configured while the
        // root project is evaluated, which is before the module's own build file has declared
        // anything at all.
        //
        // `asElement()` rather than `asNode()`: the DOM is the same tree either way, and `Node`'s
        // name is a `QName` here, which reads worse than it works.
        pom.withXml {
          val fixtureOnly =
            declaredIn("testFixturesApiElements", "testFixturesRuntimeElements") -
              declaredIn("apiElements", "runtimeElements")
          val dependencies = asElement().getElementsByTagName("dependency")
          (0 until dependencies.length)
            .map { dependencies.item(it) as Element }
            .filter { dependency ->
              fun tag(name: String) =
                dependency.getElementsByTagName(name).item(0)?.textContent
              "${tag("groupId")}:${tag("artifactId")}" in fixtureOnly
            }
            // After the walk, not during it: `getElementsByTagName` hands back a live `NodeList`.
            .forEach { it.parentNode.removeChild(it) }
        }
      }
    }
  }

  // The published POM is the one artifact nothing in this build reads, so a mistake in it survives
  // a green run and surfaces at a consumer. This reads the file the block above wrote and asks the
  // question that has actually gone wrong here: does a module hand a test library to whoever
  // depends on it? Independent of the removal rather than a restatement of it — drop the
  // `pom.withXml` and this goes red.

  val generatePom = tasks.named<GenerateMavenPom>("generatePomFileForMavenPublication")

  val checkNoTestLibrariesInPom = tasks.register("checkNoTestLibrariesInPom") {
    group = "verification"
    description = "Fails if the published POM puts a test library on a consumer's classpath."
    // Explicitly: the provider below is read in `doLast` rather than declared as a task input, so
    // it carries no dependency of its own and the file would simply not be there.
    dependsOn(generatePom)
    val pomFile = generatePom.map { it.destination }
    // `project.path`, not `path`: the receiver in here is the task, whose path would name this
    // check rather than the module whose POM is wrong.
    val modulePath = project.path
    doLast {
      // The POM is generated, so its shape is fixed and a reader beats a parser here.
      val offenders = Regex("<dependency>(.*?)</dependency>", RegexOption.DOT_MATCHES_ALL)
        .findAll(pomFile.get().readText())
        .mapNotNull { dependency ->
          val block = dependency.groupValues[1]
          fun tag(name: String) = Regex("<$name>(.*?)</$name>").find(block)?.groupValues?.get(1)
          val coordinates = "${tag("groupId")}:${tag("artifactId")}"
          // No `<scope>` means `compile`, which is Maven's default and the worse of the two.
          if (coordinates in testLibraries) "$coordinates (${tag("scope") ?: "compile"})" else null
        }
        .toList()

      if (offenders.isNotEmpty()) {
        throw GradleException(
          "$modulePath publishes a POM that puts test libraries on a consumer's classpath: " +
            offenders.joinToString() + ". They reach it through the test-fixtures variant, which " +
            "`from(components[\"java\"])` folds into the POM's single dependency list. The " +
            "`pom.withXml` block in the root build file is what takes back out whatever the " +
            "fixtures bring and the module itself does not — check that it is still there, and " +
            "that this library really is one the module does not declare on its own.",
        )
      }
    }
  }
  tasks.matching { it.name == "check" }.configureEach { dependsOn(checkNoTestLibrariesInPom) }
}

// Central rejects a POM missing any of this. `allprojects`, because `sempods-bom` returns early
// above and still needs it.
allprojects {
  plugins.withId("maven-publish") {

    // Only when there is a key: Central requires a `.asc` on a *release* and validates nothing
    // about a snapshot, so requiring one would break `publishToMavenLocal` and CI snapshots.
    // From the environment, because a CI runner has no keyring.
    // Blank is absent. `isPresent` is true for a set-but-empty variable, and an empty string is
    // not a key. The way it goes wrong is quiet: `gpg --armor --export-secret-keys <selector>`
    // warns "nothing exported" and exits 0 when it matches nothing — a fingerprint's leading
    // characters do not select a key, only its trailing ones do — so `export SIGNING_KEY="$(…)"`
    // succeeds carrying nothing, and every `sign…Publication` task then fails inside
    // `useInMemoryPgpKeys("")` naming neither the variable nor the reason. Filtered, an empty
    // export behaves like an unset one: signing is skipped, and `checkCentralBundle` stays the
    // thing that refuses to ship an unsigned release.
    val signingKey = providers.environmentVariable("SIGNING_KEY")
      .map { it.trim() }.filter { it.isNotEmpty() }
      .orElse(providers.gradleProperty("signingKey").map { it.trim() }.filter { it.isNotEmpty() })
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
      .orElse(providers.gradleProperty("signingPassword"))

    if (signingKey.isPresent) {
      apply(plugin = "signing")
      extensions.configure<SigningExtension> {
        // Empty string, not null, for a key with no passphrase: a null password leaves Gradle
        // with no signatory, surfacing later as "No configured signatory".
        useInMemoryPgpKeys(signingKey.get(), signingPassword.getOrElse(""))
        sign(extensions.getByType<PublishingExtension>().publications)
      }
    }

    extensions.configure<PublishingExtension> {

      // Snapshots only; a release goes through the Portal's own upload (see `RELEASING.md`).
      // `credentials(PasswordCredentials::class)` lets Gradle derive
      // `centralSnapshotsUsername`/`centralSnapshotsPassword` from the repository name and ask
      // for them only when publishing here — so no credential is named in this file.
      repositories {
        maven {
          name = "centralSnapshots"
          url = uri("https://central.sonatype.com/repository/maven-snapshots/")
          credentials(PasswordCredentials::class)
        }

        // Where a *release* is staged. The Central Portal takes a release as a zip in Maven
        // repository layout rather than over the wire, so a release is published into a directory
        // first and uploaded as one file — see the `centralBundle` task below and `RELEASING.md`.
        //
        // A directory under the root build dir, shared by every module, because the layout Central
        // wants is one repository containing all of them and not sixteen zips.
        maven {
          name = "centralBundle"
          url = rootProject.layout.buildDirectory.dir("central-bundle").get().asFile.toURI()
        }
      }

      publications.withType<MavenPublication>().configureEach {
        pom {
          name.set(project.name)
          // Lazily: a module sets `description` in its own build file, which is evaluated after
          // this callback has run.
          description.set(
            provider { project.description ?: "The ${project.name} module of sempods." },
          )
          url.set("https://github.com/sempods/sempods-kotlin")
          licenses {
            license {
              name.set("The Apache License, Version 2.0")
              url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
          }
          // Central asks for an organisation and a contact. The project's, not a personal one.
          developers {
            developer {
              id.set("haed")
              name.set("Danilo Stein")
              url.set("https://github.com/haed")
              email.set("hello@sempods.org")
              organization.set("sempods")
              organizationUrl.set("https://sempods.org")
            }
          }
          scm {
            url.set("https://github.com/sempods/sempods-kotlin")
            connection.set("scm:git:https://github.com/sempods/sempods-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/sempods/sempods-kotlin.git")
          }
        }
      }
    }
  }
}

// The release bundle. The Portal accepts a zip in Maven repository layout, which `maven-publish`
// already writes into `centralBundle`, so this is that directory zipped plus one `curl` — see
// `RELEASING.md`. Sonatype publishes no official Gradle plugin.

val centralBundleDir = layout.buildDirectory.dir("central-bundle")

// Nothing else cleans the directory, so a second release would ship the previous version's files
// beside the new ones — and Central accepts that, a bundle being allowed many components.
val clearCentralBundle = tasks.register<Delete>("clearCentralBundle") {
  delete(centralBundleDir)
}

// Every task that writes here, not just the aggregate: `publishAllPublicationsTo…` *depends on*
// the per-publication writers, so ordering the delete against it leaves them free to start
// whenever — and with `org.gradle.parallel` they did, mid-delete.
allprojects {
  tasks.matching { it.name.endsWith("ToCentralBundleRepository") }
    .configureEach { dependsOn(clearCentralBundle) }
}

// Central validates after the upload, which is a slow way to learn that one sources jar went
// unsigned — and it rejects the deployment whole. Same questions, asked locally first.
val checkCentralBundle = tasks.register("checkCentralBundle") {
  group = "verification"
  description = "Fails if the staged release bundle is incomplete, unsigned, or carries a stale version."
  doLast {
    val root = centralBundleDir.get().asFile
    if (!root.isDirectory) {
      throw GradleException(
        "No staged bundle at $root — run `publishAllPublicationsToCentralBundleRepository` first.",
      )
    }

    val problems = mutableListOf<String>()

    // Central rejects the version outright, and a snapshot bundle means step one was skipped.
    if (version.toString().endsWith("-SNAPSHOT")) {
      problems += "the version is $version — a release bundle cannot be built from a snapshot"
    }

    val artifacts = root.walkTopDown()
      .filter { it.isFile && (it.extension == "jar" || it.extension == "pom" || it.extension == "module") }
      .toList()

    if (artifacts.isEmpty()) problems += "the bundle contains no artifacts at all"

    // Per file, not per module: the one that goes missing is a single classifier.
    artifacts.forEach { artifact ->
      listOf("asc", "md5", "sha1").forEach { suffix ->
        val companion = File(artifact.parentFile, "${artifact.name}.$suffix")
        if (!companion.isFile) {
          problems += "${artifact.relativeTo(root)} has no .$suffix"
        }
      }
    }

    // A directory named for a version other than this one is last release's leftovers.
    val versions = artifacts.map { it.parentFile.name }.toSortedSet()
    (versions - version.toString()).forEach {
      problems += "the bundle also carries version $it — stale output from an earlier release"
    }

    if (problems.isNotEmpty()) {
      throw GradleException(
        problems.joinToString(
          prefix = "The staged release bundle is not fit to upload:\n  - ",
          separator = "\n  - ",
          postfix = "\n\nSee RELEASING.md. Signing needs SIGNING_KEY in the environment.",
        ),
      )
    }

    logger.lifecycle("Bundle is complete: ${artifacts.size} artifacts, each signed and checksummed.")
  }
}

val centralBundle = tasks.register<Zip>("centralBundle") {
  group = "publishing"
  description = "Stages every publication and zips it into the bundle the Central Portal accepts."

  // A provider: these tasks are created while the modules are evaluated, after this line runs.
  dependsOn(provider {
    subprojects.mapNotNull { it.tasks.findByName("publishAllPublicationsToCentralBundleRepository") }
  })
  dependsOn(checkCentralBundle)
  checkCentralBundle.get().mustRunAfter(
    provider {
      subprojects.mapNotNull { it.tasks.findByName("publishAllPublicationsToCentralBundleRepository") }
    },
  )

  from(centralBundleDir) {
    // A repository's index of the versions it holds, written because the staging directory is a
    // Maven repository. Central maintains that index itself, across every release.
    exclude("**/maven-metadata.xml*")
  }
  archiveFileName = "central-bundle.zip"
  destinationDirectory = layout.buildDirectory
}

// A documentation set is a graph, and the one property of it that can be checked mechanically is
// whether its edges still point at something. Nothing else here reads the markdown, so a link that
// rots survives every green run and is found by a reader — usually an agent, which then follows
// it to nothing and invents the rest. The failure this exists for is retiring a roadmap: the
// file goes away and the pointers to it do not. See `docs/agents/documentation-strategy.md`.
//
// Only relative links: an external URL is somebody else's uptime, and checking it would make the
// build depend on the network. Anchors are not resolved either — heading text drifts for reasons
// that are not mistakes, and a check that fires on a rename teaches people to disable it.
val checkDocLinks = tasks.register("checkDocLinks") {
  group = "verification"
  description =
    "Fails if a relative markdown link points at nothing, or if a specification requirement " +
      "cited anywhere points at an identifier the vendored index does not define."

  // Read out here rather than in `doLast`: `rootDir` on the task receiver would be the project's,
  // which is the same directory today and would quietly stop being it if this ever moved.
  val repositoryRoot = rootDir

  doLast {
    // Build output holds generated copies of documents that are checked at their source, and
    // `.git` holds every version of every document that was ever wrong.
    val skipped = setOf("build", ".git", ".gradle", ".idea", "node_modules")

    // `.claude/worktrees/` holds whole checkouts of this repository — the `.gitignore` entry is the
    // other half of the same fact. Walking into one reads a different branch's documentation, so
    // this checkout would fail for a link that is not in it, and the scan would do the work twice.
    // A path rather than a name: a directory called `worktrees` elsewhere is an ordinary directory.
    val worktrees = File(repositoryRoot, ".claude/worktrees")

    // The reference form — `[label]: target "title"` on its own line, used from `[text][label]`.
    // Nothing here writes links that way today, but it is ordinary markdown and the scanner above
    // is blind to it: the definition is the only place the path appears, and the use site never
    // names one. A stale target in that form would render as a dead link and pass this check.
    //
    // Two patterns, because the angle form exists to let a destination hold spaces — a single one
    // that excluded whitespace would simply not match it, and the definition would be skipped.
    val definition = Regex("""^ {0,3}\[[^\]]+]:\s*([^\s<>]+)(\s.*)?$""")
    val angleDefinition = Regex("""^ {0,3}\[[^\]]+]:\s*<([^>]*)>(\s.*)?$""")
    val fence = Regex("""^ {0,3}(`{3,}|~{3,})""")
    // A scheme means somewhere else: `https:`, `mailto:`, and anything else with that shape. So
    // does a leading `//`, which is a URL that borrows the page's scheme — never a path in here,
    // and the branch below would otherwise resolve it against the repository root.
    val elsewhere = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*:|//)""")
    // A destination is a URL, so a renderer percent-decodes it: `design%20notes.md` opens
    // `design notes.md`. A run of escapes is decoded together, because one character may be
    // several bytes. Both spellings are then accepted — a file whose name really does contain a
    // literal `%20` keeps working, and the check only fails when neither exists.
    val escapes = Regex("""(?:%[0-9a-fA-F]{2})+""")

    // A backslash escapes the character after it, so `\[example](missing.md)` renders as text and
    // is not a link — checking it would fail the build for a line that documents syntax rather
    // than points anywhere. Odd is escaped: `\\[` is a literal backslash in front of a real
    // opener.
    fun escaped(line: String, at: Int): Boolean {
      var slashes = 0
      var i = at - 1
      while (i >= 0 && line[i] == '\\') {
        slashes++
        i--
      }
      return slashes % 2 == 1
    }

    // A destination may contain balanced parentheses — `docs/setup_(linux).md` is a legal target —
    // so it cannot be read by stopping at the first `)`, and a regex cannot count. This walks the
    // line instead. It is deliberately not a CommonMark parser — indented code blocks are the
    // known gap, because telling one from a nested list item needs block-level parsing and this
    // repository has three live links inside indented list items and no indented code block at
    // all. Where the two kinds of error are in tension the rule is false green over false red: a
    // missed check costs one stale link, while a red build for a link that is fine costs a guard
    // that people switch off.
    fun destinations(line: String): List<String> {
      val found = mutableListOf<String>()
      var cursor = line.indexOf("](")
      while (cursor >= 0) {
        // The label's own brackets have to be real ones. An escaped `]` closes nothing, and an
        // escaped `[` never opened a label — either way this is text that looks like a link.
        val opener = line.lastIndexOf('[', cursor - 1)
        if (escaped(line, cursor) || opener < 0 || escaped(line, opener)) {
          cursor = line.indexOf("](", cursor + 2)
          continue
        }

        var i = cursor + 2
        while (i < line.length && line[i].isWhitespace()) i++

        if (i < line.length && line[i] == '<') {
          // The angle form ends at the first `>`; it exists so a destination may hold spaces.
          val close = line.indexOf('>', i + 1)
          if (close > 0) {
            found += line.substring(i + 1, close)
            i = close
          }
        } else {
          val start = i
          var depth = 0
          while (i < line.length) {
            val c = line[i]
            // Whitespace ends the destination: what follows is the optional `"title"`.
            if (c.isWhitespace()) break
            if (c == '(') depth++
            if (c == ')') {
              if (depth == 0) break
              depth--
            }
            i++
          }
          if (i > start) found += line.substring(start, i)
        }
        cursor = line.indexOf("](", i)
      }
      return found
    }

    // A link inside an inline code span is a link being *shown*, not one being made — a renderer
    // prints it. Fences already cover the block form; this is the same thought for one line, and
    // it matters here because this repository now documents its own markdown conventions. The
    // span is replaced by a space rather than removed, so nothing on either side of it can be
    // joined into a `](` that was never written. An unterminated run of backticks is literal.
    fun withoutCodeSpans(line: String): String {
      if (!line.contains('`')) return line
      val out = StringBuilder()
      var i = 0
      while (i < line.length) {
        if (line[i] != '`') {
          out.append(line[i])
          i++
          continue
        }
        var run = 0
        while (i + run < line.length && line[i + run] == '`') run++

        // A span closes on a run of exactly the same length; a shorter one is content.
        var j = i + run
        var close = -1
        while (j < line.length) {
          if (line[j] != '`') {
            j++
            continue
          }
          var length = 0
          while (j + length < line.length && line[j + length] == '`') length++
          if (length == run) {
            close = j
            break
          }
          j += length
        }

        if (close < 0) {
          out.append("`".repeat(run))
          i += run
        } else {
          out.append(' ')
          i = close + run
        }
      }
      return out.toString()
    }

    val broken = mutableListOf<String>()

    repositoryRoot.walkTopDown()
      .onEnter { it.name !in skipped && it != worktrees }
      .filter { it.isFile && it.extension == "md" }
      .sortedBy { it.path }
      .forEach { file ->
        // Code fences are skipped, because the templates in `docs/roadmaps/README.md` and
        // `docs/concepts/README.md` show links with placeholder targets — the fence is what marks
        // them as an example rather than a claim. A fence opened with four backticks is closed only
        // by four, which is how a template containing a fenced block stays one block.
        var open: String? = null

        file.readLines().forEachIndexed { index, line ->
          val marker = fence.find(line)?.groupValues?.get(1)
          val current = open

          if (current != null) {
            if (marker != null && marker[0] == current[0] && marker.length >= current.length) {
              open = null
            }
            return@forEachIndexed
          }
          if (marker != null) {
            open = marker
            return@forEachIndexed
          }

          fun check(target: String) {
            if (target.startsWith("#") || elsewhere.containsMatchIn(target)) return

            // Anchors and queries are addressing inside the target, not part of the path.
            val path = target.substringBefore('#').substringBefore('?')
            if (path.isEmpty()) return

            fun resolve(candidate: String) =
              if (candidate.startsWith("/")) File(repositoryRoot, candidate.removePrefix("/"))
              else File(file.parentFile, candidate)

            val spellings = mutableListOf(path)
            if (escapes.containsMatchIn(path)) {
              spellings += escapes.replace(path) { run ->
                String(
                  run.value.chunked(3).map { it.substring(1).toInt(16).toByte() }.toByteArray(),
                  Charsets.UTF_8,
                )
              }
            }

            // A directory is a legitimate target — `docs/auth/` and `.claude/skills/` are both
            // linked as places rather than documents.
            if (spellings.none { resolve(it).exists() }) {
              broken += "${file.relativeTo(repositoryRoot)}:${index + 1} -> $target"
            }
          }

          val scannable = withoutCodeSpans(line)
          destinations(scannable).forEach { check(it) }
          // At most one per line: a definition owns its line. The angle form is tried first,
          // because the other pattern would read `<docs/a` out of it.
          val reference = angleDefinition.find(scannable) ?: definition.find(scannable)
          reference?.let { check(it.groupValues[1]) }
        }
      }

    if (broken.isNotEmpty()) {
      throw GradleException(
        broken.joinToString(
          prefix = "Markdown links that point at nothing:\n  - ",
          separator = "\n  - ",
          postfix = "\n\nFix the link, or the document it should point at now. " +
            "See `docs/agents/documentation-strategy.md`.",
        ),
      )
    }

    // ── Specification citations ────────────────────────────────────────────────────────────────
    //
    // The contract this implements is specified in `sempods/sempods-spec`, and the documents that
    // moved there are cited from here by requirement identifier — `SPS-GRANT-007` — rather than by
    // file path. That is the whole point of the identifier: it survives a chapter being renamed,
    // split or reordered, which a path does not.
    //
    // It only buys anything if a typo is distinguishable from a live identifier, so the index is
    // vendored (`gradle/spec/requirements.json`, see its README) and checked against here. Not
    // fetched: a build that reached across a network to validate a comment would fail for reasons
    // nobody in this build controls, and would fail differently depending on the day.
    //
    // Kotlin as well as markdown, because most of these citations are prose inside KDoc — which is
    // exactly the half no markdown checker has ever been able to see, and the half that rots
    // silently.
    //
    // What this cannot do, so nobody assumes more of it: it checks that an identifier *exists*,
    // never that it is the right one. Citing `SPS-CRUD-011` where `SPS-CRUD-007` was meant passes,
    // because both are live — and that mistake has already been made here once. The index carries
    // each requirement's first sentence for exactly that reading, and a reviewer is what compares
    // it against the claim beside it.
    // Absence is a failure, not a reason to skip. A guard that disables itself when its source of
    // truth is missing passes at exactly the moment it was needed — and this file is a vendored
    // build input, so it going missing is a mistake rather than a configuration.
    val index = File(repositoryRoot, "gradle/spec/requirements.json")
    if (!index.exists()) {
      throw GradleException(
        "The vendored specification index is missing: gradle/spec/requirements.json.\n" +
          "Every requirement cited in this repository is checked against it. " +
          "`gradle/spec/README.md` says where it comes from.",
      )
    }

    run {
      val text = index.readText()

      // Parsed rather than pattern-matched. The first version of this read the file with two
      // regexes and got the withdrawal one wrong: `[^}]*?` between an identifier and its
      // `withdrawn` field stops at the first `}` — and eighteen summaries contain one, because
      // they quote route templates like `{pod}`. Every citation to those would have kept passing
      // after the requirement was withdrawn, which is the single thing this check exists to catch.
      @Suppress("UNCHECKED_CAST")
      val parsed = groovy.json.JsonSlurper().parseText(text) as Map<String, Any?>
      val entries = (parsed["requirements"] as? List<Map<String, Any?>>).orEmpty()
      val known = entries.mapNotNull { it["id"] as? String }.toSet()
      val withdrawn = entries.filter { it["withdrawn"] == true }.mapNotNull { it["id"] as? String }.toSet()

      // Absence fails like a mismatch. Either value going missing leaves the claim unverified,
      // and an unverified claim that passes is the same outcome as no check at all.
      val declared = parsed["specVersion"] as? String
      val implemented = project.findProperty("specVersion")?.toString()
      if (declared.isNullOrBlank() || implemented.isNullOrBlank()) {
        throw GradleException(
          "The implemented sempods specification version is not stated on both sides: " +
            "gradle.properties says '${implemented ?: "<missing>"}', " +
            "gradle/spec/requirements.json says '${declared ?: "<missing>"}'. " +
            "Both are required — see `gradle/spec/README.md`.",
        )
      }
      if (declared != implemented) {
        throw GradleException(
          "This repository claims to implement sempods specification '$implemented' " +
            "(`specVersion` in gradle.properties), but the vendored index is '$declared' " +
            "(`gradle/spec/requirements.json`). Upgrading the specification is one change: " +
            "replace the vendored file and set that line.",
        )
      }

      // Deliberately wider than the identifier form. A pattern anchored to exactly three digits
      // matches a *prefix* of a mistyped citation — a trailing digit or letter is simply left
      // outside the match — so the typo passes as the live identifier it begins with. This
      // captures the whole SPS-like token instead, and the token as a whole is then either known
      // or reported. A guard meant to separate citations from typos must not accept a typo's
      // prefix.
      //
      // Which is also why this comment describes the shapes rather than spelling them: written
      // out, they would be citations in a scanned file, and the guard would report itself.
      // Deliberate — that is the check working, and the examples live in the commit message.
      // Consumes the whole contiguous token, separators included. Two narrower versions of this
      // were wrong in the same way: a pattern that stops after the third segment matches a *prefix*
      // of a longer malformed token and reports it as the live identifier it begins with — first
      // with a trailing character, then with a trailing separator and more text. The token ends
      // where SPS-like characters stop, and what it spells is then either known or reported.
      val citation = Regex("SPS(?:[-_][A-Za-z0-9]+)+")

      // An abbreviated range — a citation followed by a bare number stood in for the second
      // endpoint — hides that endpoint from every check above: only the first token carries the
      // `SPS-` prefix the scanner looks for, so the other one could be a typo or a withdrawn
      // requirement and stay green. Cheaper to refuse the shorthand than to teach the scanner to
      // reconstruct it, and spelling both out is what a reader wants anyway.
      val abbreviated = Regex("SPS-[A-Za-z0-9]+-[A-Za-z0-9]+`?\\s*(?:…|\\.\\.\\.|-|–)\\s*`?\\d{3}\\b")

      val unknown = mutableListOf<String>()
      val retired = mutableListOf<String>()
      val shorthand = mutableListOf<String>()

      repositoryRoot.walkTopDown()
        .onEnter { it.name !in skipped && it != worktrees }
        // `context7.json` earns its place here: it carries requirement citations and is *published*
        // to agents outside this repository, so a typo there is served rather than merely stored.
        // The index itself is excluded by path — it is what defines the identifiers.
        .filter {
          it.isFile &&
            (it.extension == "md" || it.extension == "kt" || it.extension == "kts" || it.extension == "json")
        }
        .filter { it.absolutePath != index.absolutePath }
        .sortedBy { it.path }
        .forEach { file ->
          file.readLines().forEachIndexed { no, line ->
            abbreviated.find(line)?.let {
              shorthand += "${file.relativeTo(repositoryRoot)}:${no + 1} -> ${it.value}"
            }
            citation.findAll(line).forEach { hit ->
              val id = hit.value
              val where = "${file.relativeTo(repositoryRoot)}:${no + 1} -> $id"
              when {
                id !in known -> unknown += where
                id in withdrawn -> retired += where
              }
            }
          }
        }

      if (unknown.isNotEmpty() || retired.isNotEmpty() || shorthand.isNotEmpty()) {
        val parts = mutableListOf<String>()
        if (unknown.isNotEmpty()) {
          parts += unknown.joinToString(
            prefix = "Specification requirements cited here that the index does not define:\n  - ",
            separator = "\n  - ",
          )
        }
        if (retired.isNotEmpty()) {
          parts += retired.joinToString(
            prefix = "Withdrawn requirements still cited here:\n  - ",
            separator = "\n  - ",
          )
        }
        if (shorthand.isNotEmpty()) {
          parts += shorthand.joinToString(
            prefix = "Abbreviated requirement ranges — spell both endpoints out, or the second " +
              "one is checked by nothing:\n  - ",
            separator = "\n  - ",
          )
        }
        throw GradleException(
          parts.joinToString("\n\n") +
            "\n\nEither the identifier is a typo, or the specification moved on and this " +
            "repository has not. `gradle/spec/README.md` says how to upgrade the vendored index.",
        )
      }
    }
  }
}
tasks.matching { it.name == "check" }.configureEach { dependsOn(checkDocLinks) }
