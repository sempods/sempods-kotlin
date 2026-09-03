// Both plugins in one block: `com.autonomousapps.build-health` reads Kotlin metadata and needs KGP
// in the same classloader, which a settings script and a build script do not share. The Kotlin
// version therefore lives here rather than in the root build script.
//
// Not 2.19.0, the version the plugin's README quotes: its ASM cannot read Java 25 bytecode and dies
// with `Unsupported class file major version 69`. Needs Gradle 8.11 or newer.
plugins {
  id("com.autonomousapps.build-health") version "3.19.1"
  id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
}

// The api/implementation boundary, checked. `./gradlew buildHealth` writes its report to
// `build/reports/dependency-analysis/`; `docs/concepts/modularity.md` §"Open-source readiness" says what it
// guards and what it cannot. Coordinates are strings because a settings script has no
// version-catalog accessors.
dependencyAnalysis {

  structure {

    // A `bundle` declares that several artifacts are one library, so using a type from any of them
    // justifies declaring the one a reader would name. It belongs here when the split is one a
    // *consumer* cannot see; where the artifact carrying the type is one a consumer would have to
    // declare themselves, the build file names it instead.
    //
    // Hence no bundle over `org.eclipse.rdf4j`, and none pairing `rdf4j-model` with
    // `rdf4j-model-api` or `jackson-datatype-jsr310` with `jackson-databind`: those three splits
    // are visible, and correcting them is why this plugin is here.

    // `core` and `annotations` are how `jackson-databind` is packaged.
    bundle("jackson") {
      primary("com.fasterxml.jackson.core:jackson-databind")
      includeDependency("com.fasterxml.jackson.core:jackson-core")
      includeDependency("com.fasterxml.jackson.core:jackson-annotations")
    }

    // The sync driver and the core under it. Not `bson`: `ObjectId` and `Document` sit in the
    // public signatures of `:sempods-commons-mongo` and `:sempods-auth-core`, so that artifact is
    // one a consumer declares — and bundling it would hide the modules that need the driver
    // without exporting its types.
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

    // The AWS SDK ships one artifact per concern; `s3` is the only one a build file names. Listed
    // one by one rather than by group, so that `apache-client` — pinned on purpose — stays visible
    // to the analysis.
    bundle("aws-sdk") {
      primary("software.amazon.awssdk:s3")
      includeDependency("software.amazon.awssdk:auth")
      includeDependency("software.amazon.awssdk:aws-core")
      includeDependency("software.amazon.awssdk:regions")
      includeDependency("software.amazon.awssdk:sdk-core")
    }

    // Nimbus' JSON reader.
    bundle("nimbus") {
      primary("com.nimbusds:oauth2-oidc-sdk")
      includeDependency("net.minidev:json-smart")
    }

    // Ktor's plumbing. Not `ktor-utils`: `sempods-commons-ktor` puts `AttributeKey` in a public signature.
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
        // `junit-jupiter` is an aggregator. The part this repository compiles against,
        // `junit-jupiter-api`, the plugin credits to `kotlin-test-junit5` through a built-in bundle;
        // what is left is the engine, which nothing names and `useJUnitPlatform()` cannot run
        // without. Taking this advice leaves every module compiling and no test executing.
        exclude("org.junit.jupiter:junit-jupiter")
      }

      onDuplicateClassWarnings {
        // `mockserver-netty-no-dependencies` shades slf4j, which is the point of that artifact and
        // the whole of this warning. Test-only, and not fixable without changing mock servers.
        // Left visible rather than silenced: it is true, it is just not actionable here.
        severity("warn")
      }
    }

    // The two published services, and the one thing the plugin gets wrong about them.
    //
    // It reads `application` or Jib as "this is an application", and an application has no
    // consumers, so it computes no ABI and every `api` declaration looks unnecessary to it. These
    // two are both: an application, and a library an embedder installs a Guice module from.
    //
    // The exclusion is narrow: `:sempods-commons` carries `BaseModule`, the supertype of
    // `SempodsAuthModule` and `SempodsMcpModule`, so an embedder cannot name either class without
    // it. `:consumer-probe:auth` and `:consumer-probe:mcp` fail to compile if it goes.
    listOf(":sempods-auth", ":sempods-mcp").forEach { service ->
      project(service) {
        onIncorrectConfiguration {
          exclude(":sempods-commons")
        }
      }
    }
  }
}

include(
  "sempods-commons",
  "sempods-commons-jaxrs",
  "sempods-commons-json",
  "sempods-commons-ktor",
  "sempods-commons-mongo",
  "sempods-commons-okhttp",
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
