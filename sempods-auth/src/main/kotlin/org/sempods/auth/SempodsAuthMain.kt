package org.sempods.auth

import org.sempods.auth.core.ClientRedirectPolicy
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.api.provider.openIdProviderEndpoint
import com.google.inject.Guice
import org.sempods.commons.ktor.trace.installTraceContext
import org.sempods.commons.logging.LoggingInitializer
import org.sempods.auth.api.jwks.jwksEndpoint
import org.sempods.auth.api.login.appleDomainAssociationEndpoint
import org.sempods.auth.api.login.providerCallbackEndpoint
import org.sempods.auth.api.webid.webIdEndpoint
import org.sempods.auth.login.JwtIssuer
import org.sempods.auth.login.LoginService
import org.sempods.auth.login.StateStore
import org.sempods.auth.oidc.OidcRegistry
import org.sempods.auth.persist.WebIdProfileDao
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger("org.sempods.auth")

fun main() {
  LoggingInitializer.initialize()
  startSempodsAuth(SempodsAuthConfig.fromEnv())
}

fun startSempodsAuth(config: SempodsAuthConfig) {
  val injector = Guice.createInjector(SempodsAuthModule(config))

  val webIdProfileDao = injector.getInstance(WebIdProfileDao::class.java)
  val stateStore = injector.getInstance(StateStore::class.java)
  val jwtIssuer = injector.getInstance(JwtIssuer::class.java)
  val loginService = injector.getInstance(LoginService::class.java)
  val oidcRegistry = injector.getInstance(OidcRegistry::class.java)
  val authorizationCodeStore = injector.getInstance(AuthorizationCodeStore::class.java)

  logger.info { "Starting sempods-auth on port ${config.port}" }
  logger.info { "id base URL:    ${config.idBaseUrl}" }
  logger.info { "OIDC providers: ${oidcRegistry.providers.keys.ifEmpty { listOf("none") }.joinToString()}" }

  embeddedServer(Netty, port = config.port) {
    // Before `CallLogging`, so its request line already carries the trace id in the MDC.
    installTraceContext()
    install(CallLogging)
    webIdEndpoint(webIdProfileDao, config)
    providerCallbackEndpoint(
      stateStore = stateStore,
      providers = oidcRegistry.providers,
      loginService = loginService,
      authorizationCodeStore = authorizationCodeStore,
      issuer = config.idBaseUrl,
    )
    openIdProviderEndpoint(
      issuer = config.idBaseUrl,
      providers = oidcRegistry.providers,
      clientRedirectPolicy = injector.getInstance(ClientRedirectPolicy::class.java),
      stateStore = stateStore,
      authorizationCodeStore = authorizationCodeStore,
      jwtIssuer = jwtIssuer,
      loginService = loginService,
    )
    jwksEndpoint(jwtIssuer)
    appleDomainAssociationEndpoint(config.appleDomainAssociation)
  }.start(wait = true)
}
