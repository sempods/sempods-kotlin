package org.sempods.probe.auth

import com.google.inject.Guice
import com.google.inject.Injector
import org.sempods.auth.SempodsAuthConfig
import org.sempods.auth.SempodsAuthModule

/**
 * The embedding contract of `:sempods-auth`, compiled the way a stranger would compile it.
 *
 * The module is published as a library — a consumer is expected to install its Guice module — and
 * it also carries the `application` and Jib plugins. That combination is a blind spot:
 * `com.autonomousapps.dependency-analysis` treats a project with either plugin as an application,
 * an application has no consumers, and so it computes no ABI and offers no `api` advice for this
 * module at all. The mistake it would otherwise catch — a type in a public signature reachable only
 * through an `implementation` dependency — is invisible from inside the repository, because every
 * module has its own dependencies on its own compile classpath.
 *
 * This file covers the *embedding contract* with the compiler instead. It depends on the service
 * the way a foreign build does and names exactly what an embedder names: the config, the module,
 * and `Guice.createInjector`. Drop something out of `:sempods-auth`'s `api` that this needs and
 * `:consumer-probe:auth:compileKotlin` fails here, rather than in someone else's build.
 *
 * **What it does not cover, and why it does not.** The rest of this module's public surface is
 * wider than that contract and is not compilable from outside — `OidcTokenExchange` takes a Ktor
 * `HttpClient`, the route extensions take an `Application`, and `ktorClientCore` is
 * `implementation`. That is not an oversight in this file: #11 exported exactly `:commons` and
 * Guice because everything else is reached by Guice through reflection at wiring time, and #15
 * holds that the answer is to make that surface `internal` rather than to export it. Naming those
 * signatures here would commit them to a supported API, which is the decision this probe is
 * deliberately not making. When #15 settles what embedding is meant to support, whatever it keeps
 * public belongs in this file.
 *
 * Only the two services with a `main` have a probe. Every other published module is covered by
 * `buildHealth`, and a probe for them would be a second, weaker copy of a check that already
 * exists.
 *
 * Never called, and `internal`: nothing consumes this module, so it has no API of its own.
 * Compiling it is the whole point — an injector built here would want a MongoDB.
 */
@Suppress("unused")
internal fun embedSempodsAuth(config: SempodsAuthConfig): Injector =
  Guice.createInjector(SempodsAuthModule(config))
