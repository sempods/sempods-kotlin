package org.sempods.auth

import org.sempods.commons.config.Env
import org.sempods.auth.core.SempodsAuthCoreModule
import org.sempods.auth.persist.MongoSigningKeyStore
import org.sempods.auth.core.SigningKeys
import org.sempods.auth.core.SigningKeyStore
import com.google.inject.Provider
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import com.mongodb.client.MongoDatabase
import org.sempods.commons.guice.BaseModule
import org.sempods.commons.mongo.MongoModule
import org.sempods.auth.login.JwtIssuer
import org.sempods.auth.login.LoginService
import org.sempods.auth.login.StateStore
import org.sempods.auth.oidc.OidcProviderClient
import org.sempods.auth.oidc.OidcRegistry
import org.sempods.auth.oidc.OidcTokenExchange
import org.sempods.auth.oidc.apple.AppleOidcClient
import org.sempods.auth.oidc.google.GoogleOidcClient
import org.sempods.auth.persist.WebIdProfileDao
import org.sempods.commons.ktor.trace.TraceparentClientPlugin
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.github.oshai.kotlinlogging.KotlinLogging

class SempodsAuthModule(private val config: SempodsAuthConfig) : BaseModule() {

  override fun configure() {
    // `MongoClient` / `MongoDatabase` — the address comes from this service's own config, not
    // from a lookup inside the module (see `MongoModule`).
    install(MongoModule(connectionString = config.mongoUrl, databaseName = config.mongoDbName))
    // Loopback redirect addresses stay a development affordance: in production they would let
    // anything running on the user's machine intercept an authorization code.
    install(SempodsAuthCoreModule(allowLoopbackRedirects = Env.isDevelopment))

    bind<SempodsAuthConfig>().toInstance(config)
    bind<StateStore>().asSingleton()
    bind<OidcRegistry>().asSingleton()
    bindOidcProviders()

    // This service's composition root, so the setting goes here: `requireExplicitBindings` is
    // injector-global rather than module-local, and an implicit just-in-time binding is a
    // dependency nobody declared.
    binder().requireExplicitBindings()
  }

  /**
   * Registers a login provider per configured credential set — and none at all when nothing is
   * configured, which is a valid deployment: someone running their own identity service has no
   * reason to own a Google project or an Apple developer account.
   *
   * Bound here rather than through `@ProvidesIntoSet` because such a method always contributes;
   * the set has to stay empty when the credentials are absent. The empty set binder below is what
   * makes an injection of `Set<OidcProviderClient>` legal in that case.
   */
  private fun bindOidcProviders() {
    val providers = Multibinder.newSetBinder(binder(), OidcProviderClient::class.java)
    val tokenExchange = getProvider(OidcTokenExchange::class.java)

    config.google?.let { credentials ->
      providers.addBinding().toProvider(
        Provider {
          GoogleOidcClient(
            clientId = credentials.clientId,
            clientSecret = credentials.clientSecret,
            loginBaseUrl = config.idBaseUrl,
            tokenExchange = tokenExchange.get(),
          )
        },
      ).`in`(Singleton::class.java)
    }

    config.apple?.let { credentials ->
      providers.addBinding().toProvider(
        Provider {
          AppleOidcClient(
            teamId = credentials.teamId,
            serviceId = credentials.serviceId,
            keyId = credentials.keyId,
            applePrivateKeyPem = credentials.privateKeyPem,
            loginBaseUrl = config.idBaseUrl,
            tokenExchange = tokenExchange.get(),
          )
        },
      ).`in`(Singleton::class.java)
    }

    // Named at boot on purpose: a login path that quietly disappeared because a variable was
    // emptied is otherwise only noticed by the users who can no longer sign in.
    val active = listOfNotNull(
      config.google?.let { "google" },
      config.apple?.let { "apple" },
    )
    if (active.isEmpty()) {
      logger.warn { "No OIDC login providers configured — /authorize can authenticate nobody." }
    } else {
      logger.info { "OIDC login providers active: ${active.joinToString(", ")}" }
    }
  }

  @Provides @Singleton
  fun httpClient(): HttpClient = HttpClient(CIO) {
    // Carries the trace of the request that caused this call onto the OIDC provider.
    install(TraceparentClientPlugin)
  }

  @Provides @Singleton
  fun oidcTokenExchange(httpClient: HttpClient): OidcTokenExchange = OidcTokenExchange(httpClient)

  @Provides @Singleton
  fun webIdProfileDao(db: MongoDatabase): WebIdProfileDao = WebIdProfileDao(db)

  @Provides @Singleton
  fun signingKeyStore(db: MongoDatabase): SigningKeyStore = MongoSigningKeyStore(db)

  /**
   * Reads the key set at construction, which is what makes the boot log say which `kid` this
   * process will sign with — and makes a missing database a startup failure rather than a failure
   * on the first sign-in.
   */
  @Provides @Singleton
  fun signingKeys(store: SigningKeyStore): SigningKeys = SigningKeys(store)

  @Provides @Singleton
  fun jwtIssuer(signingKeys: SigningKeys): JwtIssuer =
    JwtIssuer(issuer = config.idBaseUrl, signingKeys = signingKeys)

  @Provides @Singleton
  fun loginService(dao: WebIdProfileDao): LoginService = LoginService(config, dao)

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
