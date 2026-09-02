package org.sempods

import org.sempods.auth.core.HttpTransport
import org.sempods.auth.core.SempodsAuthCoreModule
import org.sempods.auth.core.SigningKeyStore
import org.sempods.auth.core.SigningKeys
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import com.google.inject.name.Named
import com.google.inject.name.Names
import com.mongodb.client.MongoDatabase
import org.sempods.commons.config.Env
import org.sempods.commons.guice.BaseModule
import org.sempods.commons.okhttp.OkHttpClientModule
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.jaxrs.JaxRsApplicationModule
import org.sempods.commons.jaxrs.errors.ApiExceptionMapper
import org.sempods.commons.jaxrs.filters.VaryHeaderFilter
import org.sempods.commons.mongo.MongoModule
import org.sempods.admin.AdminAuthorizer
import org.sempods.pods.grants.GrantStorePodAuthorizer
import org.sempods.pods.grants.PodAuthorizer
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.PodTokenAuthenticator
import org.sempods.admin.StaticCredentialAdminAuthorizer
import org.sempods.ai.AiService
import org.sempods.ai.impls.ollama.OllamaAiConfig
import org.sempods.ai.impls.ollama.OllamaAiService
import org.sempods.ai.impls.openai.OpenAiConfig
import org.sempods.ai.impls.openai.OpenAiService
import org.sempods.ai.sem.AiSemFacade
import org.sempods.ai.sem.AiSemShaclGuidanceDeriver
import org.sempods.ai.sem.prompts.SempodsPromptBuilderFactory
import org.sempods.api.pod.resources.PodContextWriteAuthorizer
import org.sempods.api.pod.resources.PodResourceEndpoint
import org.sempods.api.pod.resources.PodResourceReadService
import org.sempods.api.pod.resources.PodResourceWriteService
import org.sempods.api.pod.system.ai.semweb.PodAiSemWebEndpoint
import org.sempods.api.pod.system.auth.*
import org.sempods.api.pod.system.contexts.PodContextsEndpoint
import org.sempods.api.pod.system.find.FindEndpoint
import org.sempods.api.pod.system.mcp.McpEndpoint
import org.sempods.api.pod.system.meta.PodMetaEndpoint
import org.sempods.api.pod.system.resources.PodSlotWriteService
import org.sempods.api.pod.system.resources.PodSystemResourcesEndpoint
import org.sempods.api.system.admin.pods.AdminPodsEndpoint
import org.sempods.api.system.sparql.SparqlEndpoint
import org.sempods.auth.CommonsHttpTransport
import org.sempods.auth.ConsentTransactionStore
import org.sempods.auth.PodIdentityProvider
import org.sempods.auth.PodLoginStateStore
import org.sempods.client.SempodsHttpTimeouts
import org.sempods.client.SempodsHttpTransport
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.client.net.SempodsUrlPolicy
import org.sempods.client.wire.PodWireClient
import org.sempods.jaxrs.SempodsCorsFilter
import org.sempods.jaxrs.SempodsObjectMapperResolver
import org.sempods.mcp.core.PodToolExecutor
import org.sempods.mcp.core.SempodsMcpCoreModule
import org.sempods.mcp.core.ToolCatalog
import org.sempods.mcp.core.ToolVariant
import org.sempods.pods.PodFacade
import org.sempods.pods.PodRepositoryCache
import org.sempods.pods.changes.BackupSinkPodChangeListener
import org.sempods.pods.changes.PodChangeDispatcher
import org.sempods.pods.changes.PodChangeListener
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.PodContextPermissionResolver
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.PodScopeValidator
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.grants.persist.PodWebIdGrantsDao
import org.sempods.pods.media.persist.PodMediaDao
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.RdfResourceBackupDao
import org.sempods.pods.oauth.serviceclients.PodServiceClientFacade
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.persist.PodServiceAuditLogDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import org.sempods.query.SparqlQueryService
import org.sempods.retrieval.FindAdapter
import org.sempods.retrieval.FindService
import org.sempods.retrieval.ResourceExpander
import org.sempods.retrieval.impls.sparql.SparqlResourceExpander
import org.sempods.retrieval.impls.sparql.SparqlTextFindAdapter
import org.sempods.updates.SempodsUpdater
import java.net.URI
import java.time.Duration
import java.time.InstantSource
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The pod server's bindings.
 *
 * Binds **no in-process pod client**, and must not: the pod server *is* the pod, so its endpoints
 * take the module facades ([PodFacade], [SempodsFacade]) directly. A second, in-process way into a
 * pod is a way no client can take, and the two would drift, since only one of them is what
 * production traffic exercises.
 *
 * [podToolExecutor] is not that, and reads as the exception only until the direction is stated: it
 * binds a client that goes over the pod's **public HTTP surface**, the path every external caller
 * takes. That is the rule being honoured, not bent — the MCP surface stopped being a second way in
 * and became one more caller of the first.
 */
class SempodsModule : BaseModule() {

  override fun configure() {

    // The composition, and the whole of what the pod server takes from the shared infrastructure.
    // Deliberately no application framework: this module is published, so what it installs is what
    // a third party would have to run. See `docs/concepts/modularity.md` §"Open-source readiness".
    bind<SempodsConfig>().toInstance(config)
    install(OkHttpClientModule)
    install(MongoModule(connectionString = config.mongoUrl, databaseName = config.mongoDb))
    install(JaxRsApplicationModule(httpPort = config.httpPort))
    // Authorization codes live in this server's own collection — they used to live in a
    // `ConcurrentHashMap`, so a deploy between `/authorize` and `/token` invalidated every code in
    // flight and a second replica would never have seen them at all. Loopback redirect addresses
    // stay a development affordance: in production they would let anything on the user's machine
    // intercept an authorization code.
    install(SempodsAuthCoreModule(allowLoopbackRedirects = Env.isDevelopment))
    // The `authorize(reauthorize=true)` challenge, shared with the hosted MCP service. Mongo-backed
    // since M5: it used to be a `ConcurrentHashMap`, so a deploy inside the five-minute window
    // between the deliberate 401 and the client's post-OAuth confirmation cost the caller its
    // consent roundtrip, and a second replica never saw the challenge at all.
    install(SempodsMcpCoreModule())

    // Injected by `SempodsPromptBuilder`; it used to arrive with an application framework's module.
    // `InstantSourceTestObserver` overrides it where a suite needs to move time.
    bind<InstantSource>().toInstance(InstantSource.system())

    // The relying-party side of sign-in: the id-server this server federates logins to, and the
    // one-time store that carries the parked `/authorize` request across the browser round trip.
    // The identity itself is remembered in a session cookie on this pod's origin, so nothing that
    // authenticates a person travels through the browser.
    bind<HttpTransport>().to(CommonsHttpTransport::class.java).asSingleton()
    bind<PodIdentityProvider>().asSingleton()
    bind<PodLoginStateStore>().asSingleton()
    bind<ConsentTransactionStore>().asSingleton()

    bind<PodDao>().asSingleton()
    bind<RdfResourceBackupDao>().asSingleton()
    bind<SempodsFacade>().asSingleton()
    bind<SparqlQueryService>().asSingleton()
    bind<PodRepositoryCache>().asSingleton()
    bind<PodFacade>().asSingleton()

    // Change-event fan-out for committed pod writes. New sinks (audit, search index, …) subscribe
    // by contributing to this set binder — no change to the write path.
    bind<PodChangeDispatcher>().asSingleton()
    bind<BackupSinkPodChangeListener>().asSingleton()
    val changeListeners = Multibinder.newSetBinder(binder(), PodChangeListener::class.java)
    changeListeners.addBinding().to(BackupSinkPodChangeListener::class.java)  // critical
    bind<PodResourceWriteService>().asSingleton()
    bind<PodResourceReadService>().asSingleton()
    bind<PodContextWriteAuthorizer>().asSingleton()
    bind<PodSlotWriteService>().asSingleton()
    bind<PodContextsDao>().asSingleton()
    bind<PodGrantsDao>().asSingleton()
    // Eager (unlike the sibling DAOs): this store is seeded out-of-band (owner writes WebID
    // grants directly, ahead of the owner-facing HTTP API), so its collection + unique index
    // must exist from boot rather than only after the first request constructs the DAO.
    bind<PodWebIdGrantsDao>().asSingleton(eager = true)
    bind<PodScopeValidator>().asSingleton()
    bind<PodContextPermissionResolver>().asSingleton()
    bind<PodGrantsFacade>().asSingleton()
    bind<PodTokenAuthenticator>().asSingleton()
    bindPodAuthorizer()
    // In-memory per process, so its budget is per replica — see the class for the key.
    bind<PodTokenRateLimiter>().asSingleton()
    bind<DynamicClientRegistrationDao>().asSingleton()
    bind<DynamicClientStore>().asSingleton()
    bind<TemplateRenderer>().asSingleton()
    bind<OAuthSigningKeyDao>().asSingleton()
    bind<PodConsentDecisionStore>().asSingleton()
    bind<PodRefreshTokenStore>().asSingleton()
    bind<PodServiceClientDao>().asSingleton()
    bind<PodServiceClientFacade>().asSingleton()
    bind<PodServiceClientStore>().asSingleton()
    bind<PodServiceAuditLogDao>().asSingleton()
    bind<AiSemShaclGuidanceDeriver>().asSingleton()
    bind<SempodsPromptBuilderFactory>().asSingleton()
    bind<SempodsUpdater>().asSingleton(eager = true)
    bind<AiSemFacade>().asSingleton()

    // Graph-retrieval `find`. New search engines (vector, OpenSearch, …) subscribe by
    // contributing a FindAdapter to this set binder — no change to the endpoint or FindService.
    bind<FindService>().asSingleton()
    bind<ResourceExpander>().to(SparqlResourceExpander::class.java).asSingleton()
    bind<SparqlTextFindAdapter>().asSingleton()
    val findAdapters = Multibinder.newSetBinder(binder(), FindAdapter::class.java)
    findAdapters.addBinding().to(SparqlTextFindAdapter::class.java)

    bindAiService()
    bindAdminAuthorizer()

    bindEndpoints(
      // The JAX-RS providers this composition owns — four of the seven an application framework
      // used to register on this connector. Two do not come along: an authentication filter and an
      // admin jobs endpoint, both serving a session login no route here uses. The seventh,
      // `ContainerRequestHolderFilter`, is registered by `JaxRsServerModule` on every connector,
      // because `ApiExceptionMapper` reads it to name the request that failed.
      //
      // `SempodsObjectMapperResolver` is the one that fails *silently* if it is forgotten — see
      // its KDoc.
      ApiExceptionMapper::class.java,
      SempodsCorsFilter::class.java,
      SempodsObjectMapperResolver::class.java,
      VaryHeaderFilter::class.java,

      AdminPodsEndpoint::class.java,
      PodContextsEndpoint::class.java,
      PodAuthEndpoint::class.java,
      PodOAuthMetadataEndpoint::class.java,
      RootOAuthMetadataEndpoint::class.java,
      McpEndpoint::class.java,
      PodAiSemWebEndpoint::class.java,
      SparqlEndpoint::class.java,
      FindEndpoint::class.java,
      PodMetaEndpoint::class.java,
      PodSystemResourcesEndpoint::class.java,
      PodResourceEndpoint::class.java,
    )

    // Inherited from the framework module that used to call this at the end of its `configure()`. An
    // implicit just-in-time binding is a dependency nobody declared, and this module's whole point
    // is that what it installs is what it uses. Injector-global rather than module-local, which is
    // why it belongs in a composition root and not in a module that others install.
    binder().requireExplicitBindings()
  }

  private fun bindEndpoints(vararg endpoints: Class<out Any>) {
    JaxRsApplicationModule.bindEndpoints(binder(), httpPort = config.httpPort, *endpoints)
  }

  /**
   * The pod server's own URI base: the address it is known by, which is exactly what
   * `SEMPODS_PUBLIC_BASE_URL` means.
   *
   * Stated here rather than derived inside the builder. `SempodsUriBuilder` used to pick a base
   * out of an injected set of registered applications, which was right only because this process
   * registered the app named `sempods` — the same lookup in any other application's injector
   * answered with that app's address and persisted it into resource subjects and named graphs.
   */
  @Provides
  @Singleton
  fun provideSempodsUriBuilder(): SempodsUriBuilder = SempodsUriBuilder(config.apiBaseUrl)

  /**
   * The thirteen MCP tools, run against this pod server over its own public HTTP surface.
   *
   * `McpEndpoint` used to call the pod services in process, which made the MCP a second
   * implementation of what `_system/…` already answers. It now goes out through
   * [SempodsConfig.apiBaseUrl] and back in, so the tools authorize, sandbox and validate through
   * exactly the routes an external client hits — the equivalence is the deployment's, not a claim
   * this codebase has to keep true by hand. The costs are in `docs/mcp/endpoint.md`.
   *
   * **The transport is built here and bound nowhere.** It carries a trusted-host exemption, and an
   * injectable `SempodsHttpTransport` carrying one is the trap `SempodsMcpModule` documents on the
   * hosted side: a second consumer would receive it by type and take the exemption along to
   * wherever it dials. This one dials a single address, and the address comes from deployment
   * configuration rather than from any request, so the case the exemption is dangerous in — a
   * caller-supplied URL naming the trusted host — cannot arise.
   *
   * **The exemption is needed, not cosmetic.** Without it `SempodsUrlPolicy` refuses `localhost`
   * as a loopback name (`NON_GLOBAL_HOST`, which is precisely the refusal `trustedHosts` clears),
   * and local development and the test run would have an MCP that cannot reach its own pod.
   *
   * The whole-call bound is 60 s, deliberately not the 10 s the hosted service gives a foreign pod:
   * there a hanging stranger is the risk, here the risk is cutting off a legal query, and the pod's
   * own SPARQL guard already stops at 10 s with `find` and JSON-LD serialisation on top of it.
   */
  @Provides
  @Singleton
  fun podToolExecutor(): PodToolExecutor = PodToolExecutor(
    ToolCatalog.of(ToolVariant.SINGLE_POD),
    PodWireClient(
      SempodsHttpTransport(
        timeouts = SempodsHttpTimeouts(
          connect = Duration.ofSeconds(5),
          read = Duration.ofSeconds(30),
          write = Duration.ofSeconds(30),
          call = Duration.ofSeconds(60),
        ),
        guard = SempodsOutboundGuard(
          policy = SempodsUrlPolicy(allowPrivateAddresses = false),
          trustedHosts = setOfNotNull(runCatching { URI(config.apiBaseUrl).host }.getOrNull()),
        ),
      ),
    ),
  )

  /**
   * The media registry ([PodMediaDao]).
   *
   * **Bound unconditionally, including on a deployment that configures no blob store.** The
   * registry and the store are separable, and that is what keeps `SEMPODS_MEDIA_BACKEND=none` from
   * breaking the injector: removing a context from every assignment and marking a deleted pod's
   * rows unreferenced are pure registry work that touches no byte, so the lifecycle cascades can
   * take this DAO directly — the way `SempodsFacade` already takes `podContextsDao`, `podGrantsDao`
   * and the rest. `PodMediaFacade`, which does need bytes, is bound by `SempodsMediaModule` in the
   * deployment instead. See `docs/media.md` §"The seam".
   */
  @Provides
  @Singleton
  internal fun providePodMediaDao(db: MongoDatabase): PodMediaDao = PodMediaDao(db)

  /**
   * The pod's signing keys, read (or minted) once at construction.
   *
   * A singleton, and the only place the key set is read: an issuer and a verifier that each went
   * to the store for themselves would disagree on a first boot, where whichever ran first found it
   * empty. Reading here also makes an unreachable database a startup failure rather than a failure
   * on the first authenticated request.
   */
  @Provides
  @Singleton
  fun providePodSigningKeyStore(dao: OAuthSigningKeyDao): SigningKeyStore = PodSigningKeyStore(dao)

  @Provides
  @Singleton
  fun providePodSigningKeys(store: SigningKeyStore): SigningKeys = SigningKeys(store)

  @Provides
  @Singleton
  fun providePodTokenIssuer(signingKeys: SigningKeys): PodTokenIssuer {
    return PodTokenIssuer(
      apiBaseUrl = config.apiBaseUrl,
      signingKeys = signingKeys,
    )
  }

  @Provides
  @Singleton
  @Named("idBaseUrl")
  fun provideIdBaseUrl(): String {
    return Env.get("ID_BASE_URL")?.trimEnd('/')?.takeIf { it.isNotBlank() }
      ?: if (Env.isDevelopment) "http://localhost:8091" else "https://id.sempods.org"
  }

  @Provides
  @Singleton
  fun provideWebIdUriDeriver(@Named("idBaseUrl") idBaseUrl: String): WebIdUriDeriver {
    return WebIdUriDeriver(idBaseUrl)
  }

  /**
   * Binds the admin-authority seam ([AdminAuthorizer]) to [StaticCredentialAdminAuthorizer] over
   * the `clientId:secret` pairs in `SEMPODS_ADMIN_CLIENTS`. With no pairs configured the authorizer
   * **fails closed**: every `_system/admin/…` route answers 503. That is deliberately a request-
   * time failure, not a boot failure, so a deployment can start before its operator credential is
   * provisioned — but no admin operation runs until it is.
   *
   * The one way out of that state without a real credential is [DEV_ADMIN_FALLBACK_ENV_VARIABLE],
   * which a deployment never sets; see [resolveAdminClients].
   *
   * Deliberately *not* an env-selected switch like [bindAiService]: a credential check is right for
   * every deployment whose admin surface is reachable across a process or network boundary, which
   * is every deployment this repository ships — the server always binds an HTTP connector. The seam
   * stays an interface because a second implementation is genuinely coming (WebID plus an operator
   * allowlist for the hosted console, control-plane admin roadmap A3); when a deployment profile
   * arrives that needs a *different* authority, this method is where the selection goes back in.
   */
  /**
   * Binds the authorization seam ([PodAuthorizer]) to [GrantStorePodAuthorizer] — durable
   * per-context grants, resolved per request, with the `<root>#manage` cascade and `public-read`
   * as an additive feature scope.
   *
   * A single unconditional binding, like [bindAdminAuthorizer] and unlike [bindAiService]: every
   * deployment this repository ships runs the grant model, and an authorization switch is not
   * something an operator should be able to get wrong. What keeps it an interface rather than a
   * concrete class is that the behaviour behind it is genuinely deployment-shaped — a fixed
   * read-only view over virtual contexts, or an external policy engine, are the cases
   * `docs/concepts/modularity.md` names. This method is where such a selection would go back in.
   *
   * What no implementation may do is decide *whether* the sandbox applies; see [PodAuthorizer].
   */
  private fun bindPodAuthorizer() {
    bind<PodAuthorizer>().to(GrantStorePodAuthorizer::class.java).asSingleton()
  }

  private fun bindAdminAuthorizer() {
    val configured = StaticCredentialAdminAuthorizer.parseClients(Env.get("SEMPODS_ADMIN_CLIENTS"))
    val clients = resolveAdminClients(
      configured = configured,
      fallbackRequested = Env.get(DEV_ADMIN_FALLBACK_ENV_VARIABLE)?.toBooleanStrictOrNull() == true,
      isDevelopment = Env.isDevelopment,
    )
    bind<AdminAuthorizer>().toInstance(StaticCredentialAdminAuthorizer(clients))
    if (clients.isEmpty()) {
      logger.warn {
        "SEMPODS_ADMIN_CLIENTS is empty — every _system/admin route will answer 503 (fail closed). " +
            "Set it, or set $DEV_ADMIN_FALLBACK_ENV_VARIABLE=true for a local run."
      }
    } else {
      // Count only — the client ids are the other half of a credential pair, and the 401 path
      // deliberately keeps them unenumerable. Logging them would hand that set to anyone with log
      // access.
      logger.info { "Admin authority configured: ${clients.size} client(s)" }
    }
  }

  private fun bindAiService() {
    val provider = Env.get("AI_PROVIDER")
      ?.trim()
      ?.lowercase()
      ?.takeIf { it.isNotEmpty() }
      ?: "ollama"

    when (provider) {
      "ollama" -> bindOllamaAiService()
      "openai" -> bindOpenAiService()
      else -> throw IllegalStateException("unsupported AI_PROVIDER '$provider' (supported: ollama, openai)")
    }
  }

  private fun bindOllamaAiService() {
    val ollamaBaseUrl = Env.get("OLLAMA_BASE_URL")?.trimEnd('/') ?: "http://localhost:11434"
    val ollamaModel = Env.get("OLLAMA_MODEL")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?: "qwen2.5:7b"

    bind(String::class.java)
      .annotatedWith(Names.named(OllamaAiConfig.OLLAMA_BASE_URL))
      .toInstance(ollamaBaseUrl)
    bind(String::class.java)
      .annotatedWith(Names.named(OllamaAiConfig.OLLAMA_MODEL))
      .toInstance(ollamaModel)
    bind<AiService>().to(OllamaAiService::class.java).asSingleton()
    logger.info { "AI provider configured: ollama baseUrl='$ollamaBaseUrl', model='$ollamaModel'" }
  }

  private fun bindOpenAiService() {
    val openAiBaseUrl = Env.get("OPENAI_BASE_URL")?.trimEnd('/') ?: "https://api.openai.com"

    val openAiApiKey = Env.get("OPENAI_API_KEY")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?: throw IllegalStateException("OPENAI_API_KEY must be configured when AI_PROVIDER=openai")
    val openAiModel = Env.get("OPENAI_MODEL")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?: throw IllegalStateException("OPENAI_MODEL must be configured when AI_PROVIDER=openai")

    bind(String::class.java)
      .annotatedWith(Names.named(OpenAiConfig.OPENAI_BASE_URL))
      .toInstance(openAiBaseUrl)
    bind(String::class.java)
      .annotatedWith(Names.named(OpenAiConfig.OPENAI_API_KEY))
      .toInstance(openAiApiKey)
    bind(String::class.java)
      .annotatedWith(Names.named(OpenAiConfig.OPENAI_MODEL))
      .toInstance(openAiModel)

    bind<AiService>().to(OpenAiService::class.java).asSingleton()
    logger.info { "AI provider configured: openai baseUrl='$openAiBaseUrl', model='$openAiModel'" }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    /**
     * Deterministic development credential, so that a developer who starts the server and an
     * application against it locally gets a working pod lifecycle without hand-picking a secret.
     *
     * The client half lives with the application that dials this server and is configured there;
     * the secret is what has to match, since [StaticCredentialAdminAuthorizer] identifies the
     * caller by the secret and never by the id. The id is a log label.
     *
     * **This value is published with the source.** It is a credential only in the sense that the
     * comparison uses it; anyone reading the repository has it. That is why arming it takes
     * [DEV_ADMIN_FALLBACK_ENV_VARIABLE] and not an inference about the environment.
     */
    internal const val DEVELOPMENT_ADMIN_CLIENT_ID = "development"
    internal const val DEVELOPMENT_ADMIN_SECRET = "sc_development-admin-secret"

    /**
     * Arms the development credential above. Unset means fail closed, on a developer machine too.
     *
     * It exists because the two halves of a local setup are otherwise asymmetric: a client that
     * falls back to a deterministic secret meets a server that falls back to *no* clients, so
     * every local pod creation answers 503. What changed is where that is settled — in the env
     * template a developer copies, rather than in a rule the server infers.
     */
    internal const val DEV_ADMIN_FALLBACK_ENV_VARIABLE = "SEMPODS_DEV_ADMIN_FALLBACK"

    /**
     * What the admin authorizer is configured with.
     *
     * Configured pairs always win. With none, the published development pair is used **only** when
     * [DEV_ADMIN_FALLBACK_ENV_VARIABLE] asks for it; anything else is fail closed.
     *
     * The earlier rule was `PRODUCTION != "true"`, which reads a forgotten variable as consent.
     * Two things make that worse than it sounds: the connector binds every interface
     * (`JaxRsServerModule` never sets `connector.host`), and TLS is terminated upstream in most
     * deployments, so "not production" is exactly the state a first self-host is in. A published
     * credential must not arm itself in a state that a deployment can reach by omission.
     *
     * Asking for the fallback **in production is refused at boot** rather than ignored. Silently
     * dropping it would leave an operator with 503s and a variable that looks set, which is the
     * failure mode this function exists to remove — and it matches how the rest of the tree
     * handles a setting that contradicts its deployment ([Env.requiredInProduction]).
     */
    internal fun resolveAdminClients(
      configured: Map<String, String>,
      fallbackRequested: Boolean,
      isDevelopment: Boolean,
    ): Map<String, String> {
      check(!(fallbackRequested && !isDevelopment)) {
        "$DEV_ADMIN_FALLBACK_ENV_VARIABLE arms the published development admin credential " +
            "($DEVELOPMENT_ADMIN_SECRET) and must not be set in production — configure " +
            "SEMPODS_ADMIN_CLIENTS instead"
      }
      if (configured.isNotEmpty()) return configured
      if (!fallbackRequested) return emptyMap()
      logger.warn {
        "$DEV_ADMIN_FALLBACK_ENV_VARIABLE is set — the admin surface accepts the development " +
            "credential, which is published with the source. Development only."
      }
      return mapOf(DEVELOPMENT_ADMIN_CLIENT_ID to DEVELOPMENT_ADMIN_SECRET)
    }

    /** Where the pod server listens. Configurable so a third party can run it on its own port. */
    internal const val HTTP_PORT_ENV_VARIABLE = "SEMPODS_HTTP_PORT"

    /**
     * The address this server is **known by** — not merely reachable at.
     *
     * Pod IRIs are minted by appending to it (`SempodsBaseEndpoint`, `SempodsUriBuilder`), it is
     * the `iss` claim of every pod token (`PodTokenIssuer`), and grants are compared against it
     * byte for byte (`PodGrantsFacade`). Changing it on a live host therefore does not redirect
     * traffic — it starts writing a second set of identities beside the existing ones.
     *
     * Deliberately distinct from a client's own `SEMPODS_BASE_URL`, which says where *it* reaches
     * this server. After the deployment split those can legitimately differ: an internal
     * `http://sempods:8090/` for the container network, a public `https://sempods.org/` in the
     * data.
     */
    internal const val PUBLIC_BASE_URL_ENV_VARIABLE = "SEMPODS_PUBLIC_BASE_URL"

    /** Where the collections live. Same variable names and defaults the object-mapping layer read. */
    internal const val MONGODB_URL_ENV_VARIABLE = "MONGODB_URL"
    internal const val MONGODB_DB_NAME_ENV_VARIABLE = "MONGODB_DB_NAME"

    /**
     * The documentation address every OAuth error redirect points at — see
     * [SempodsConfig.oauthErrorDocBase].
     *
     * **No default on purpose.** Unset means no `error_uri` on the redirect at all, which is a
     * valid OAuth error and better than a link to a page nobody serves. A deployment that
     * publishes this repository's `docs/auth/oauth-errors.md` sets this to where it publishes it.
     *
     * Deliberately separate from [PUBLIC_BASE_URL_ENV_VARIABLE]: the documentation and the pod
     * need not live on the same host, and on sempods.org today they do not — the site is
     * chat-only and serves no documentation pages.
     */
    internal const val OAUTH_ERROR_DOC_BASE_ENV_VARIABLE = "SEMPODS_OAUTH_ERROR_DOC_BASE"

    /**
     * How long `oauth.serviceAuditLog` keeps a row — see [SempodsConfig.serviceAuditRetentionDays].
     *
     * Spelled with the `SEMPODS_` prefix every knob this server owns carries, rather than the
     * hosted MCP service's bare `AUDIT_RETENTION_DAYS`: the two trails are different collections
     * in different databases, and a host that exports one variable globally must not silently set
     * both. `SERVICE_` says *which* trail — this one records service-client requests, and is the
     * only audit collection the pod server has.
     */
    internal const val SERVICE_AUDIT_RETENTION_DAYS_ENV_VARIABLE = "SEMPODS_SERVICE_AUDIT_RETENTION_DAYS"

    /**
     * The per-client budget at the token endpoint — see [SempodsConfig.tokenRateLimitPerMinute].
     *
     * `SEMPODS_` prefixed for the same reason the one above is: a host running more than one of
     * these services must be able to give each its own budget, and a bare `TOKEN_RATE_LIMIT…`
     * exported globally would set all of them at once.
     */
    internal const val TOKEN_RATE_LIMIT_PER_MINUTE_ENV_VARIABLE = "SEMPODS_TOKEN_RATE_LIMIT_PER_MINUTE"

    /** The spike allowed beside that rate — see [SempodsConfig.tokenRateLimitBurst]. */
    internal const val TOKEN_RATE_LIMIT_BURST_ENV_VARIABLE = "SEMPODS_TOKEN_RATE_LIMIT_BURST"

    /** The aggregate per address — see [SempodsConfig.tokenRateLimitAddressPerMinute]. */
    internal const val TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE_ENV_VARIABLE =
      "SEMPODS_TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE"

    /** The spike allowed on that tier. */
    internal const val TOKEN_RATE_LIMIT_ADDRESS_BURST_ENV_VARIABLE = "SEMPODS_TOKEN_RATE_LIMIT_ADDRESS_BURST"

    /**
     * What a deployment that sets nothing gets.
     *
     * Named rather than written into [config] as literals so `SempodsConfigTest` can pin them.
     * That guard used to read [config] and assert the values it found, which held only while the
     * test JVM's environment was empty — it no longer is: the build hands every module's test JVM
     * its own database and its own port so the suites can run side by side (see the
     * `subprojects { tasks.test }` block in the root `build.gradle.kts`). The literals still live
     * in the test, these constants live here, and a silent change to either still fails.
     */
    internal const val DEFAULT_HTTP_PORT = 8090
    internal const val DEFAULT_MONGODB_URL = "mongodb://localhost:27018"
    internal const val DEFAULT_MONGODB_DB_NAME = "sempods-server"

    /**
     * 90 days, the retention the hosted MCP service's audit trail already runs on. Unlike the
     * three above this default is not "what has always been shipped" — the collection had no
     * retention at all — so it is a deliberate new value rather than a preserved one.
     */
    internal const val DEFAULT_SERVICE_AUDIT_RETENTION_DAYS = 90L

    /**
     * 20 requests a minute per `(address, client)` in production, and **off** in development.
     *
     * The on/off split follows the hosted MCP service's two budgets, and for the same reason: a
     * limit is a property of a deployment that faces the open internet, not of a machine running
     * the suite.
     *
     * **The rate is chosen against the traffic that is not wanted, and only that.** The client this
     * limit exists for sustained 78 requests a minute for twenty-one hours; anything at or above
     * that would never have emptied a bucket and never have refused it a single request. 20 a
     * minute holds it to roughly a quarter of what it managed, and every refusal carries
     * `Retry-After`. Nothing legitimate comes near: a browser refreshes once per pod connection per
     * hour, and a service client mints one token per pod per hour. What *does* arrive in a rush is
     * a provisioning sweep, and that is [DEFAULT_TOKEN_RATE_LIMIT_BURST]'s job rather than this
     * one's — the mistake this pair replaces was a single number trying to be both.
     */
    internal const val DEFAULT_TOKEN_RATE_LIMIT_PER_MINUTE = 20

    /**
     * 300 in one go, then the rate above.
     *
     * The number a legitimate spike is sized against: a service client provisioning many pods asks
     * for their tokens back to back, and being throttled there aborts the operation rather than
     * queuing it. Well past anything a browser does, and spent within seconds by a retry loop —
     * which then meets the rate.
     */
    internal const val DEFAULT_TOKEN_RATE_LIMIT_BURST = 300

    /**
     * 100 a minute per address after a burst of 1000 — five times the per-client tier, because one
     * address legitimately runs several clients and any one of them may be spending its own burst.
     *
     * What the number is really chosen against is the key space: to hold the shared bucket map full
     * a caller would have to touch every one of its entries inside the eviction window, which is
     * upwards of 4,000 requests a minute. At 100 it cannot come near, so the per-client tier below
     * cannot be reserved by anyone — and a caller varying its `client_id` gains nothing, since
     * every request it makes is counted here first.
     */
    internal const val DEFAULT_TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE = 100
    internal const val DEFAULT_TOKEN_RATE_LIMIT_ADDRESS_BURST = 1000

    /**
     * The pod server's configuration, read once from the environment.
     *
     * A companion `by lazy` rather than an injected object, and that is not a preference: the value
     * is read on class initialization, long before an injector exists — `SempodsBaseEndpoint` needs
     * the base URL to build a bearer challenge, and `SempodsMediaModule` needs the port to register
     * its routes. `Env` carries a standing note that a global lookup is a hidden dependency and that
     * configuration belongs in a typed object at the entry point; this *is* that typed object, and
     * everything downstream of the injector takes it by injection.
     */
    val config: SempodsConfig by lazy {
      val httpPort = Env.int(HTTP_PORT_ENV_VARIABLE, default = DEFAULT_HTTP_PORT)
      // `Env.baseUrl` normalises the trailing slash — pod IRIs are minted by appending to this
      // value and then persisted, so a missing slash would write `https://example.orgalice/…` into
      // every resource subject and named graph.
      val publicBaseUrl = Env.baseUrl(
        PUBLIC_BASE_URL_ENV_VARIABLE,
        default = if (Env.isDevelopment) "http://localhost:$httpPort/" else "https://sempods.org/",
      )

      // Hoisted for the same reason `httpPort` is: the address tier's rule below reads it, and a
      // constructor argument cannot refer to a sibling argument.
      val tokenRateLimitPerMinute = Env.int(
        TOKEN_RATE_LIMIT_PER_MINUTE_ENV_VARIABLE,
        default = if (Env.isDevelopment) 0 else DEFAULT_TOKEN_RATE_LIMIT_PER_MINUTE,
      )

      SempodsConfig(
        httpPort = httpPort,
        apiBaseUrl = publicBaseUrl,
        mongoUrl = Env.get(MONGODB_URL_ENV_VARIABLE, default = DEFAULT_MONGODB_URL),
        mongoDb = Env.get(MONGODB_DB_NAME_ENV_VARIABLE, default = DEFAULT_MONGODB_DB_NAME),
        // No default: unset means the redirects carry no `error_uri`. The normalisation rule
        // lives on `SempodsConfig` so it can be checked without an injector.
        oauthErrorDocBase = SempodsConfig.normalizeErrorDocBase(
          Env.get(OAUTH_ERROR_DOC_BASE_ENV_VARIABLE)
        ),
        // Unparseable or non-positive falls back to the default rather than failing the boot: the
        // value bounds a log, and a typo that stopped the server would cost more than the day
        // count it fixes. `0` is not "keep for ever" but "expire on write", which is the one
        // reading a typo must not be able to mean here.
        serviceAuditRetentionDays = Env.get(SERVICE_AUDIT_RETENTION_DAYS_ENV_VARIABLE)
          ?.toLongOrNull()
          ?.takeIf { it > 0 }
          ?: DEFAULT_SERVICE_AUDIT_RETENTION_DAYS,
        // Read above. `Env.int` rather than a lenient parse: a typo here would leave the endpoint
        // on a budget nobody chose, and unlike the retention above there is a value that
        // legitimately means "off" (`0`), so a fallback could not tell a mistake from a decision.
        // A *negative* value parses fine and is refused by `SempodsConfig` itself, because it
        // would otherwise read as "disabled" — silently, and in production.
        tokenRateLimitPerMinute = tokenRateLimitPerMinute,
        // Defaults to the rate outside a deployment, so a suite or a local run that sets only the
        // rate gets a bucket that behaves like the one number it stated.
        tokenRateLimitBurst = Env.int(
          TOKEN_RATE_LIMIT_BURST_ENV_VARIABLE,
          default = if (Env.isDevelopment) 0 else DEFAULT_TOKEN_RATE_LIMIT_BURST,
        ),
        tokenRateLimitAddressPerMinute = Env.int(
          TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE_ENV_VARIABLE,
          // The rule applies to the **default**, not to a value somebody typed. An operator who
          // sets only the documented off switch above gets the limit actually off, rather than a
          // tier they never chose still refusing. One who sets *both*, contradicting themselves, is
          // told by `SempodsConfig` — quietly picking one of the two would be the same trap this
          // rule exists to remove, just moved one variable along.
          default = SempodsConfig.resolveAddressRateLimit(
            perMinute = tokenRateLimitPerMinute,
            addressPerMinute = if (Env.isDevelopment) 0 else DEFAULT_TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE,
          ),
        ),
        tokenRateLimitAddressBurst = Env.int(
          TOKEN_RATE_LIMIT_ADDRESS_BURST_ENV_VARIABLE,
          default = if (Env.isDevelopment) 0 else DEFAULT_TOKEN_RATE_LIMIT_ADDRESS_BURST,
        ),
      )
    }
  }
}
