package org.sempods.mcp

import org.sempods.mcp.oauth.IdentityProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Guice
import com.google.inject.Injector
import org.sempods.commons.ktor.trace.installTraceContext
import org.sempods.commons.logging.LoggingInitializer
import org.sempods.mcp.api.mcp.ReadTools
import org.sempods.mcp.api.mcp.UserRateLimiter
import org.sempods.mcp.api.mcp.WriteTools
import org.sempods.mcp.api.mcp.mcpEndpoint
import org.sempods.mcp.api.oauth.authEndpoint
import org.sempods.mcp.api.oauth.oauthMetadataEndpoint
import org.sempods.mcp.api.web.webUiEndpoint
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.auth.ServiceBearerVerifier
import org.sempods.mcp.auth.WebLoginStateStore
import org.sempods.mcp.auth.WebSession
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.mcp.core.ReauthorizeChallengeStore
import org.sempods.mcp.oauth.ConsentTransactionStore
import org.sempods.mcp.oauth.LoginStateStore
import org.sempods.mcp.oauth.McpRefreshTokenStore
import org.sempods.mcp.oauth.TokenIssuer
import org.sempods.mcp.persist.ConnectionRegistryDao
import org.sempods.mcp.persist.ProfileDao
import org.sempods.mcp.persist.TokenVaultDao
import org.sempods.mcp.persist.oauth.DcrClientDao
import org.sempods.mcp.pods.PodConnectStateStore
import org.sempods.mcp.pods.PodOAuthClient
import org.sempods.mcp.pods.PodUrlPolicy
import org.sempods.mcp.pods.TokenRefreshScheduler
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.respondText
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Hosted MCP — a standalone service that fronts many pods over one MCP connection, treating
 * MCP as an LLM-tooling layer over the pod's primitives (external-first). Toward each pod it
 * is an ordinary OAuth client.
 *
 * Concept (why / trade-offs / direction): docs/concepts/hosted-mcp.md
 * As-built phase status:                  sempods-mcp/AGENTS.md
 *
 * Live on mcp.sempods.org (M1–M6 done): service login / identity (own MCP-OAuth resource server /
 * AS, OIDC RP to id.sempods.org, user = stable WebID), pod-connect, the read + write tool surface
 * across connected pods, named profiles with hard isolation, and the full hosting hardening
 * (secrets-at-rest, SSRF defense, durable multi-instance state, multi-tenancy + audit + quotas).
 */
private val logger = KotlinLogging.logger("org.sempods.mcp")

fun main() {
  LoggingInitializer.initialize()
  startSempodsMcp(SempodsMcpConfig.fromEnv())
}

fun startSempodsMcp(config: SempodsMcpConfig) {
  val injector = Guice.createInjector(SempodsMcpModule(config))

  // PoC operating stance (see AGENTS.md, "Deployment stance"): NO data-migration support —
  // schema/crypto changes assume a fresh setup (drop the DB, re-connect pods). There is therefore
  // deliberately no startup-migration step here.
  // TODO: once the service carries real user state, breaking schema/crypto changes need proper
  //  migrations again (a startup pass must then be conditional on the old values, so it cannot
  //  clobber rows concurrently rewritten by another replica).

  // Eagerly construct the token issuer so the signing key is loaded/persisted at boot.
  injector.getInstance(TokenIssuer::class.java)

  logger.info { "Starting sempods-mcp on port ${config.port}" }
  logger.info { "service base URL: ${config.mcpBaseUrl}" }
  logger.info { "trusted auth issuers: ${config.authIssuers.ifEmpty { listOf("none") }.joinToString()}" }

  // Start the headless pod-token refresh loop (M2): keeps connected pods reachable.
  injector.getInstance(TokenRefreshScheduler::class.java).start()

  embeddedServer(Netty, port = config.port) {
    // Before `CallLogging`, so its request line already carries the trace id in the MDC.
    installTraceContext()
    install(CallLogging)
    // Treat `…/private` and `…/private/` as the same route: an AI client that appends a trailing
    // slash to a copied MCP URL must still hit `POST /{profile}` (and its per-profile challenge),
    // not a bare 404 that breaks the OAuth-upgrade discovery loop.
    install(IgnoreTrailingSlash)
    healthRoutes()
    wireEndpoints(injector)
  }.start(wait = true)
}

private fun Application.healthRoutes() {
  routing {
    get("/healthz") { call.respondText("ok") }
  }
}

/** Wires the M1 front-door surface: OAuth discovery, the AS endpoints, and the MCP skeleton. */
private fun Application.wireEndpoints(injector: Injector) {
  val config = injector.getInstance(SempodsMcpConfig::class.java)
  // One instance for both endpoints: the discovery round trip is shared, the redirect address is
  // per flow.
  val identityProvider = injector.getInstance(IdentityProvider::class.java)
  val objectMapper = injector.getInstance(ObjectMapper::class.java)
  val auditLog = injector.getInstance(AuditLog::class.java)

  oauthMetadataEndpoint(config, objectMapper)
  authEndpoint(
    config = config,
    dcrClientDao = injector.getInstance(DcrClientDao::class.java),
    authorizationCodeStore = injector.getInstance(AuthorizationCodeStore::class.java),
    loginStateStore = injector.getInstance(LoginStateStore::class.java),
    consentTransactionStore = injector.getInstance(ConsentTransactionStore::class.java),
    refreshTokenStore = injector.getInstance(McpRefreshTokenStore::class.java),
    tokenIssuer = injector.getInstance(TokenIssuer::class.java),
    profileDao = injector.getInstance(ProfileDao::class.java),
    webSession = injector.getInstance(WebSession::class.java),
    connectionRegistryDao = injector.getInstance(ConnectionRegistryDao::class.java),
    tokenVaultDao = injector.getInstance(TokenVaultDao::class.java),
    objectMapper = objectMapper,
    auditLog = auditLog,
    identityProvider = identityProvider,
  )
  mcpEndpoint(
    config = config,
    bearerVerifier = injector.getInstance(ServiceBearerVerifier::class.java),
    reauthorizeChallengeStore = injector.getInstance(ReauthorizeChallengeStore::class.java),
    readToolDispatch = injector.getInstance(ReadTools::class.java)::dispatch,
    writeToolDispatch = injector.getInstance(WriteTools::class.java)::dispatch,
    objectMapper = objectMapper,
    userRateLimiter = injector.getInstance(UserRateLimiter::class.java),
    auditLog = auditLog,
  )
  webUiEndpoint(
    config = config,
    webSession = injector.getInstance(WebSession::class.java),
    webLoginStateStore = injector.getInstance(WebLoginStateStore::class.java),
    podOAuthClient = injector.getInstance(PodOAuthClient::class.java),
    podConnectStateStore = injector.getInstance(PodConnectStateStore::class.java),
    podUrlPolicy = injector.getInstance(PodUrlPolicy::class.java),
    connectionRegistryDao = injector.getInstance(ConnectionRegistryDao::class.java),
    tokenVaultDao = injector.getInstance(TokenVaultDao::class.java),
    profileDao = injector.getInstance(ProfileDao::class.java),
    auditLog = auditLog,
    identityProvider = identityProvider,
  )
}
