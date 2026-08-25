package org.sempods.probe.auth

import com.google.inject.Guice
import com.google.inject.Injector
import org.sempods.auth.SempodsAuthConfig
import org.sempods.auth.SempodsAuthModule

/**
 * The embedding contract of `:sempods-auth`, compiled the way a stranger would compile it.
 *
 * `com.autonomousapps.dependency-analysis` cannot check this module: it reads `application` or Jib
 * as "this is an application", and an application has no consumers, so it computes no ABI and gives
 * no `api` advice. The mistake that leaves uncaught — a type in a public signature reachable only
 * through an `implementation` dependency — is invisible from inside the repository, where every
 * module has its own dependencies on its own compile classpath.
 *
 * So the compiler does it instead. The service is declared `implementation` here, Gradle propagates
 * only `api` across a project boundary, and the function below names what an embedder names: the
 * config, the module, `Guice.createInjector`. Drop something out of `:sempods-auth`'s `api` that
 * this needs and `:consumer-probe:auth:compileKotlin` fails here rather than in someone else's
 * build.
 *
 * **The embedding contract, not the whole public surface.** The rest is wider and not compilable
 * from outside — `OidcTokenExchange` takes a Ktor `HttpClient`, the route extensions take an
 * `Application` — because none of it was designed as API. Naming it here would turn an accident
 * into a promise; #15 holds it should become `internal` instead, and whatever survives that
 * decision as public belongs in this file.
 *
 * Only the two services with a `main` need a probe; `buildHealth` covers every other published
 * module.
 *
 * Never called, and `internal`: compiling it is the point, and an injector built here would want a
 * MongoDB.
 */
@Suppress("unused")
internal fun embedSempodsAuth(config: SempodsAuthConfig): Injector =
  Guice.createInjector(SempodsAuthModule(config))
