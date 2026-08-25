// Both plugins in one block, and the Kotlin one without applying itself, because
// `com.autonomousapps.build-health` reads Kotlin metadata: it needs KGP in the *same* classloader,
// which a settings script and a build script do not share. The root build script therefore names
// `org.jetbrains.kotlin.jvm` without a version — this is where the version lives now.
//
// 3.18.0 rather than the 2.19.0 the plugin's own README quotes: that release's ASM cannot read
// Java 25 bytecode and dies with `Unsupported class file major version 69`. ASM 9.9 arrived in
// 3.1.0. 3.18.0 wants Gradle 8.11 or newer; the wrapper here is 9.7.1.
plugins {
  id("com.autonomousapps.build-health") version "3.18.0"
  id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
}

// The api/implementation boundary, checked. `./gradlew buildHealth` writes its report to
// `build/reports/dependency-analysis/`; see `docs/modularity.md` §"Open-source readiness" for what
// this guards and what it cannot.
//
// Configured here rather than in the root build script for one reason and against one cost: the
// settings script is where the plugin already lives, and the root build script is long enough. The
// cost is that version-catalog accessors do not exist in a settings script, so coordinates below
// are strings.
dependencyAnalysis {

  structure {

    // A `bundle` tells the plugin that several artifacts are one library, so declaring the one a
    // reader would name covers a type that technically lives in a sibling of it. The rule for what
    // belongs here: an artifact split that a *consumer* cannot see. Where the split is visible —
    // where the artifact carrying the type is one a consumer would have to declare for themselves
    // — there is no bundle, and the build file names the real coordinate instead.
    //
    // Which is why there is deliberately no bundle over `org.eclipse.rdf4j`, and none pairing
    // `rdf4j-model` with `rdf4j-model-api` or `jackson-datatype-jsr310` with `jackson-databind`.
    // Those three are exactly the declarations this plugin was adopted to correct.

    // Jackson's own three-way split. `jackson-databind` is the one a build file names; `core` and
    // `annotations` are how it is packaged.
    bundle("jackson") {
      primary("com.fasterxml.jackson.core:jackson-databind")
      includeDependency("com.fasterxml.jackson.core:jackson-core")
      includeDependency("com.fasterxml.jackson.core:jackson-annotations")
    }

    // The sync driver and the core it is built on. `bson` is *not* here: `ObjectId` and `Document`
    // are in public signatures across this repository, so that artifact is one a consumer declares.
    bundle("mongodb-driver") {
      primary("org.mongodb:mongodb-driver-sync")
      includeDependency("org.mongodb:mongodb-driver-core")
    }

    bundle("logback") {
      primary("ch.qos.logback:logback-classic")
      includeDependency("ch.qos.logback:logback-core")
    }

    bundle("mockk") {
      primary("io.mockk:mockk")
      includeDependency("io.mockk:mockk-core")
      includeDependency("io.mockk:mockk-dsl")
    }

    bundle("okhttp") {
      primary("com.squareup.okhttp3:okhttp")
      includeDependency("com.squareup.okio:okio")
    }

    // The AWS SDK ships one artifact per concern, and `s3` is the only one a build file names.
    // Listed one by one rather than by group: `apache-client` is declared here on purpose
    // (`libs.awsApacheClient` pins the sync HTTP layer) and must stay visible to the analysis.
    bundle("aws-sdk") {
      primary("software.amazon.awssdk:s3")
      includeDependency("software.amazon.awssdk:auth")
      includeDependency("software.amazon.awssdk:aws-core")
      includeDependency("software.amazon.awssdk:regions")
      includeDependency("software.amazon.awssdk:sdk-core")
    }

    // Nimbus' JSON reader, an implementation detail of both Nimbus artifacts.
    bundle("nimbus") {
      primary("com.nimbusds:oauth2-oidc-sdk")
      includeDependency("net.minidev:json-smart")
    }

    // Ktor's plumbing. `ktor-utils` is *not* here — `commons-ktor` puts its types in a public
    // signature, so it is declared.
    bundle("ktor-internals") {
      primary("io.ktor:ktor-server-core")
      includeDependency("io.ktor:ktor-http")
      includeDependency("io.ktor:ktor-io")
    }
  }

  issues {
    all {
      onAny {
        severity("fail")
      }

      onUnusedDependencies {
        // `org.junit.jupiter:junit-jupiter` is an aggregator, and the only part of it this
        // repository compiles against — `junit-jupiter-api` — the plugin already credits to
        // `kotlin-test-junit5` through its own built-in bundle. What the aggregator additionally
        // brings is the *engine*, which nothing names and `useJUnitPlatform()` cannot run without.
        // Taking this advice would leave every module compiling and no test executing.
        exclude("org.junit.jupiter:junit-jupiter")
      }

      onDuplicateClassWarnings {
        // `mockserver-netty-no-dependencies` shades slf4j, which is the point of that artifact and
        // the whole of this warning. Test-only, and not fixable without changing mock servers.
        severity("warn")
      }
    }

    // The two published services, and the one thing the plugin gets wrong about them.
    //
    // It decides a project is an application from its plugins — `application`, Jib and a few
    // others — and an application has no consumers, so it computes no ABI for one and offers no
    // `api` advice. These two are both: an application *and* a published library an embedder
    // installs a Guice module from. With no ABI to compare against, every `api` declaration looks
    // unnecessary to it, and it advises demoting the one that #11 established is required.
    //
    // The exclusion is narrow on purpose: `:commons` carries `BaseModule`, the supertype of
    // `SempodsAuthModule` and `SempodsMcpModule`, so an embedder cannot name either class without
    // it. That is not an opinion — `:consumer-probe:auth` and `:consumer-probe:mcp` fail to
    // compile if the declaration goes.
    listOf(":sempods-auth", ":sempods-mcp").forEach { service ->
      project(service) {
        onIncorrectConfiguration {
          exclude(":commons")
        }
      }
    }
  }
}

include(
  "commons",
  "commons-jaxrs",
  "commons-json",
  "commons-ktor",
  "commons-mongo",
  "commons-okhttp",
  "consumer-probe:auth",
  "consumer-probe:mcp",
  "deployments:sempods:image",
  "sempods-auth",
  "sempods-auth-core",
  "sempods-bom",
  "sempods-client",
  "sempods-control-plane-client",
  "sempods-mcp",
  "sempods-mcp-core",
  "sempods-media-s3",
  "sempods-model",
  "sempods-server",
)
