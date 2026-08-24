package org.sempods.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.inject.Provides
import com.google.inject.Singleton
import com.mongodb.client.MongoDatabase
import org.sempods.commons.guice.BaseModule
import org.sempods.commons.mongo.MongoModule
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.oauth.IdentityProvider
import org.sempods.mcp.oauth.SempodsClientHttpTransport
import org.sempods.mcp.auth.ServiceBearerVerifier
import org.sempods.mcp.auth.WebLoginStateStore
import org.sempods.mcp.auth.WebSession
import org.sempods.mcp.crypto.SecretCipher
import org.sempods.auth.core.SempodsAuthCoreModule
import org.sempods.auth.core.SigningKeyStore
import org.sempods.auth.core.SigningKeys
import org.sempods.mcp.oauth.ConsentTransactionStore
import org.sempods.mcp.oauth.LoginStateStore
import org.sempods.mcp.oauth.McpRefreshTokenStore
import org.sempods.mcp.oauth.TokenIssuer
import org.sempods.mcp.persist.AuditLogDao
import org.sempods.mcp.persist.ConnectionRegistryDao
import org.sempods.mcp.persist.InstanceId
import org.sempods.mcp.persist.LeaseDao
import org.sempods.mcp.persist.ProfileDao
import org.sempods.mcp.persist.TokenVaultDao
import org.sempods.mcp.persist.oauth.DcrClientDao
import org.sempods.mcp.persist.oauth.McpSigningKeyStore
import org.sempods.mcp.persist.oauth.SigningKeyDao
import org.sempods.mcp.api.mcp.ReadTools
import org.sempods.mcp.api.mcp.hostedToolCatalog
import org.sempods.mcp.api.mcp.UserRateLimiter
import org.sempods.mcp.api.mcp.WriteTools
import org.sempods.mcp.pods.PodConnectStateStore
import org.sempods.mcp.pods.PodOAuthClient
import org.sempods.mcp.pods.PodTokenProvider
import org.sempods.client.SempodsHttpTimeouts
import org.sempods.client.SempodsHttpTransport
import org.sempods.client.net.OutboundRateLimiter
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.client.wire.PodWireClient
import org.sempods.mcp.core.PodToolExecutor
import org.sempods.mcp.core.SempodsMcpCoreModule
import org.sempods.mcp.pods.PodUrlPolicy
import java.time.Duration
import org.sempods.mcp.pods.TokenRefreshScheduler
import org.sempods.commons.ratelimit.TokenBucketRateLimiter
import java.net.URI

class SempodsMcpModule(private val config: SempodsMcpConfig) : BaseModule() {

  /**
   * The deadlines every outbound request runs under, pod fetch and issuer fetch alike.
   *
   * **One definition on purpose.** These were spelled twice — once per transport — and one copy
   * silently lost its whole-call bound, which is the bound that matters: a server dripping bytes
   * just inside the read timeout keeps a request open forever, and only [SempodsHttpTimeouts.call]
   * stops it. On the login path that is a request a person is waiting on.
   *
   * Tight because the caller is waiting in both cases: a hanging pod must not stall a tool call,
   * and a hanging issuer must not stall a sign-in. `dumpContext`-style streaming, the one thing a
   * whole-call bound would cut off wrongly, is not something this service does.
   */
  private val outboundTimeouts = SempodsHttpTimeouts(
    connect = Duration.ofSeconds(5),
    read = Duration.ofSeconds(10),
    write = Duration.ofSeconds(10),
    call = Duration.ofSeconds(10),
  )

  override fun configure() {
    // MongoDB (plain sync driver — no Morphia; standalone like sempods-auth). The address comes
    // from this service's own config, not from a lookup inside the module (see `MongoModule`).
    install(MongoModule(connectionString = config.mongoUrl, databaseName = config.mongoDbName))
    install(SempodsAuthCoreModule())
    // The `authorize(reauthorize=true)` challenge, shared with the pod-immanent MCP since M5 — this
    // service's Mongo version is the one that moved.
    install(SempodsMcpCoreModule())

    bind<SempodsMcpConfig>().toInstance(config)

    // This service's composition root, so the setting goes here: `requireExplicitBindings` is
    // injector-global rather than module-local, and an implicit just-in-time binding is a
    // dependency nobody declared.
    binder().requireExplicitBindings()
  }

  @Provides @Singleton
  fun objectMapper(): ObjectMapper = jacksonObjectMapper()

  // --- Persistence spine, keyed (user, profile, pod) ---

  @Provides @Singleton
  fun connectionRegistryDao(db: MongoDatabase): ConnectionRegistryDao = ConnectionRegistryDao(db)

  // Encryption-at-rest for the secrets the service holds recoverable: pod tokens + the private
  // signing-key JWK. Key from MCP_SECRET_KEY (mandatory on a public/https deployment).
  @Provides @Singleton
  fun secretCipher(): SecretCipher = SecretCipher.fromEnv(config.isSecure)

  @Provides @Singleton
  fun tokenVaultDao(db: MongoDatabase, cipher: SecretCipher): TokenVaultDao = TokenVaultDao(db, cipher)

  @Provides @Singleton
  fun profileDao(db: MongoDatabase): ProfileDao = ProfileDao(db)

  // This replica's identity for leases and per-token refresh claims (M6.3). Fresh per boot; a
  // crashed holder's lease/claim just expires.
  @Provides @Singleton
  fun instanceId(): InstanceId =
    InstanceId(
      runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("mcp") +
        "-" + java.util.UUID.randomUUID().toString().take(8),
    )

  @Provides @Singleton
  fun leaseDao(db: MongoDatabase): LeaseDao = LeaseDao(db)

  // The persistent audit trail (M6.4): typed emitter over mcp.auditLog, retention-bounded,
  // synchronous-but-swallowing (an audit failure never fails the request path).
  @Provides @Singleton
  fun auditLog(db: MongoDatabase): AuditLog = AuditLog(AuditLogDao(db), config.auditRetentionDays)

  // Per-user tools/call quota (M6.4) — in-memory per replica, like the pod limiter above.
  @Provides @Singleton
  fun userRateLimiter(): UserRateLimiter = UserRateLimiter(config.userRateLimitPerMinute)

  // --- One-time OAuth flow state (Mongo-backed, TTL-indexed, atomic single-use consume — M6.3) ---

  @Provides @Singleton
  fun loginStateStore(db: MongoDatabase): LoginStateStore = LoginStateStore(db)

  @Provides @Singleton
  fun consentTransactionStore(db: MongoDatabase): ConsentTransactionStore = ConsentTransactionStore(db)

  @Provides @Singleton
  fun webLoginStateStore(db: MongoDatabase): WebLoginStateStore = WebLoginStateStore(db)

  @Provides @Singleton
  fun podConnectStateStore(db: MongoDatabase, cipher: SecretCipher): PodConnectStateStore =
    PodConnectStateStore(db, cipher)

  // --- Service-as-AS OAuth stores ---

  @Provides @Singleton
  fun signingKeyDao(db: MongoDatabase, cipher: SecretCipher): SigningKeyDao = SigningKeyDao(db, cipher)

  @Provides @Singleton
  fun dcrClientDao(db: MongoDatabase): DcrClientDao = DcrClientDao(db)

  @Provides @Singleton
  fun refreshTokenStore(db: MongoDatabase): McpRefreshTokenStore = McpRefreshTokenStore(db)

  @Provides @Singleton
  fun signingKeyStore(signingKeyDao: SigningKeyDao): SigningKeyStore = McpSigningKeyStore(signingKeyDao)

  /**
   * The service's signing keys, read (or minted) once at construction.
   *
   * A singleton, and the only place the key set is read: the issuer below and the verifier beside
   * it must agree on which keys exist, and on a first boot two independent reads do not — whichever
   * ran first found the collection empty and would then reject every token the other minted.
   */
  @Provides @Singleton
  fun signingKeys(store: SigningKeyStore): SigningKeys = SigningKeys(store)

  @Provides @Singleton
  fun tokenIssuer(signingKeys: SigningKeys): TokenIssuer =
    TokenIssuer(mcpBaseUrl = config.mcpBaseUrl, signingKeys = signingKeys)

  @Provides @Singleton
  fun serviceBearerVerifier(signingKeys: SigningKeys): ServiceBearerVerifier =
    ServiceBearerVerifier.using(mcpBaseUrl = config.mcpBaseUrl, signingKeys = signingKeys)

  /**
   * The id-server this service signs people in against, as an OIDC relying party.
   *
   * A singleton because it caches what discovery found: one round trip at the first sign-in, not
   * one per sign-in.
   */
  @Provides @Singleton
  fun identityProvider(podUrlPolicy: PodUrlPolicy): IdentityProvider =
    IdentityProvider(
      issuer = config.authIssuers.firstOrNull(),
      serviceBaseUrl = config.mcpBaseUrl,
      // Own hardened client with ONLY the configured issuer hosts exempt from DNS vetting, so a
      // private-network issuer works on a strict deployment. The exemption must not live on the
      // shared pod client: there a user-supplied pod URL naming the issuer host would ride it
      // (SSRF into the internal issuer). This client never leaves the provider — it only ever
      // talks to the configured issuer's discovery, JWKS and token endpoints.
      transport = SempodsClientHttpTransport(
        SempodsHttpTransport(
          timeouts = outboundTimeouts,
          guard = SempodsOutboundGuard(
            policy = podUrlPolicy.rules,
            trustedHosts = issuerHosts(config.authIssuers),
          ),
        ),
      ),
    )

  /**
   * The bare hostnames of the configured issuers, as OkHttp/Ktor present them to the trust check:
   * `URI.getHost()` keeps the brackets on an IPv6 literal (`[fd00::1]`) but the DNS hook and
   * `request.url.host` deliver it without (`fd00::1`), so strip them or an IPv6-literal issuer's
   * exemption would silently never match. [SempodsOutboundGuard] normalises both sides — it lowercases and strips the brackets — so
   * either spelling matches; this strips them too rather than relying on that from a distance.
   */
  private fun issuerHosts(issuers: List<String>): Set<String> =
    issuers.mapNotNull { runCatching { URI(it).host }.getOrNull() }
      .map { it.removeSurrounding("[", "]") }
      .toSet()

  // --- M2: pod-connect (service → pod OAuth) + web-session ---

  @Provides @Singleton
  fun webSession(tokenIssuer: TokenIssuer, bearerVerifier: ServiceBearerVerifier): WebSession =
    WebSession(config = config, tokenIssuer = tokenIssuer, bearerVerifier = bearerVerifier)

  @Provides @Singleton
  fun podUrlPolicy(): PodUrlPolicy = PodUrlPolicy(allowLocal = config.allowLocalPods)

  @Provides @Singleton
  fun podOAuthClient(
    transport: SempodsHttpTransport,
    objectMapper: ObjectMapper,
    podUrlPolicy: PodUrlPolicy,
  ): PodOAuthClient = PodOAuthClient(transport = transport, objectMapper = objectMapper, podUrlPolicy = podUrlPolicy)

  /** Shared "give me a usable pod token" provider — used by the read tools and the refresh sweep. */
  @Provides @Singleton
  fun podTokenProvider(
    tokenVaultDao: TokenVaultDao,
    connectionRegistryDao: ConnectionRegistryDao,
    podOAuthClient: PodOAuthClient,
    auditLog: AuditLog,
    instanceId: InstanceId,
  ): PodTokenProvider =
    PodTokenProvider(tokenVaultDao, connectionRegistryDao, podOAuthClient, auditLog, instanceId = instanceId.value)

  @Provides @Singleton
  fun tokenRefreshScheduler(
    tokenVaultDao: TokenVaultDao,
    podTokenProvider: PodTokenProvider,
    leaseDao: LeaseDao,
    instanceId: InstanceId,
  ): TokenRefreshScheduler = TokenRefreshScheduler(config, tokenVaultDao, podTokenProvider, leaseDao, instanceId)

  // --- M3: pod read surface (service → pod HTTP System layer) ---

  /**
   * The pod System-layer client — `:sempods-client`'s, with this service's hardening bolted on at
   * the transport: DNS resolve-and-pin, the per-request IP-literal check, no proxy, no redirects,
   * a per-pod budget, and timeouts tight enough that a hanging pod cannot stall a tool call.
   *
   * NO trust exemptions: every request through this transport is vetted, because pod base URLs
   * (and the endpoints their metadata advertises) are user input.
   */
  @Provides @Singleton
  fun podTransport(podUrlPolicy: PodUrlPolicy): SempodsHttpTransport = SempodsHttpTransport(
    timeouts = outboundTimeouts,
    guard = SempodsOutboundGuard(
      policy = podUrlPolicy.rules,
      rateLimiter = perPodBudget(TokenBucketRateLimiter(config.podRateLimitPerMinute)),
    ),
  )

  /**
   * The thirteen pod tools, shared with the pod-immanent MCP: this service supplies the transport
   * (SSRF guard, per-pod budget, timeouts) and its own `MULTI_POD` catalog, and gets back a
   * blocking executor the dispatchers run one pod at a time through `podIo`.
   */
  @Provides @Singleton
  fun podToolExecutor(transport: SempodsHttpTransport): PodToolExecutor =
    PodToolExecutor(hostedToolCatalog, PodWireClient(transport))

  /**
   * The per-pod outbound budget, keyed by pod base = authority + first path segment.
   *
   * The port matters — two pods differing only by port on one host (self-host multi-container) must
   * not share a bucket. The host is lowercased (an authority is case-insensitive); the path segment
   * is left as given (pod names are not). An empty first segment means a root-hosted pod, keyed by
   * authority alone.
   *
   * Two topologies this approximates loosely: a root-hosted pod fragments across its endpoint
   * segments (`_system` vs `.well-known` → separate buckets), and pods sharing one path-less
   * external authorization server pool onto that AS host.
   */
  private fun perPodBudget(limiter: TokenBucketRateLimiter) = OutboundRateLimiter { target ->
    val authority = "${target.host.lowercase()}:${target.port}"
    val firstSegment = target.path.split('/').firstOrNull { it.isNotEmpty() } ?: ""
    limiter.tryAcquire(if (firstSegment.isEmpty()) authority else "$authority/$firstSegment")
  }

  @Provides @Singleton
  fun readTools(
    connectionRegistryDao: ConnectionRegistryDao,
    podTokenProvider: PodTokenProvider,
    executor: PodToolExecutor,
    objectMapper: ObjectMapper,
    auditLog: AuditLog,
  ): ReadTools = ReadTools(connectionRegistryDao, podTokenProvider, executor, objectMapper, config.mcpBaseUrl, auditLog)

  @Provides @Singleton
  fun writeTools(
    connectionRegistryDao: ConnectionRegistryDao,
    podTokenProvider: PodTokenProvider,
    executor: PodToolExecutor,
    objectMapper: ObjectMapper,
    auditLog: AuditLog,
  ): WriteTools = WriteTools(connectionRegistryDao, podTokenProvider, executor, objectMapper, config.mcpBaseUrl, auditLog)
}
