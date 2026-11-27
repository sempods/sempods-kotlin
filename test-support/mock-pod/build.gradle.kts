// The stubbed pod every client module's tests run against: `docs/testing.md` §"Stubbing an external
// API". Unpublished, and why: the same document, §"Conventions".
plugins {
  `java-library`
}

description = "The MockServer standing in for a pod in the client modules' tests."

dependencies {
  // `api`: a test class extends `MockPodTest` and reaches MockServer, OkHttp and JUnit through it.
  api(project(":sempods-client"))
  api(libs.okhttp)
  api(libs.mockServer)
  api(libs.junitJupiterApi)

  testImplementation(libs.bundles.test)
  // Its own tests launch a class on the JUnit Platform to see what the base class releases after it.
  testImplementation(libs.junitPlatformEngine)
  testImplementation(libs.junitPlatformLauncher)
}

// The client modules compile their tests for Java 21, the release they publish, and resolve this
// module for that release. The root build sets it on published modules only.
tasks.withType<JavaCompile>().configureEach { options.release = 21 }
kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    freeCompilerArgs.add("-Xjdk-release=21")
  }
}
