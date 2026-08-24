package org.sempods

import com.google.inject.Provides
import com.google.inject.name.Named
import com.google.inject.Singleton
import org.sempods.commons.guice.BaseModule
import org.sempods.commons.tests.TestUtil
import org.sempods.admin.AdminAuthorizer
import org.sempods.admin.AdminAuthorizerTestDouble
import org.sempods.ai.AiServiceTestObserver
import org.sempods.auth.core.HttpTransport
import org.sempods.pods.media.LoopbackOnlyAddressGuard
import org.sempods.pods.media.PodMediaConfig
import org.sempods.pods.media.PodMediaModule
import org.sempods.pods.media.impls.fs.FilesystemPodMediaStore
import org.sempods.commons.okhttp.TestHttpClient
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.time.Duration

data class SempodsTestModule(
  private val config: SempodsConfig = SempodsModule.config,
): BaseModule() {

  override fun configure() {
    // Awaitility's defaults. `pollInSameThread` is the load-bearing one — see
    // `TestUtil.initializeAwaitilityDefaults`.
    TestUtil.initializeAwaitilityDefaults()

    AiServiceTestObserver.bindTestProxy(binder())
    bind<SempodsTestFactory>().asSingleton()

    // How the suite seeds pod state: an HTTP client against this JVM's own server, see
    // `docs/testing.md` §"Seeding a pod". Deliberately without an in-process alternative to
    // select — both halves of a test, the seeding and the assertion, have to cross the surface a
    // client crosses, or a call that only works in-process passes here and fails at deploy time.
    // An application's own suite seeds the same way, through the client it uses in production.
    bind<SempodsTestPodAccess>().asSingleton()

    // Overrides the env-selected admin authority (SempodsModule.bindAdminAuthorizer) with a known
    // test credential, and lets a test swap in a different authorizer — see AdminAuthorizerTestDouble.
    val adminAuthorizer = AdminAuthorizerTestDouble()
    bind<AdminAuthorizer>().toInstance(adminAuthorizer)
    bind<AdminAuthorizerTestDouble>().toInstance(adminAuthorizer)

    bindMediaForTests()
  }

  /**
   * Stands in for `SempodsMediaModule`, which lives in `:deployments:sempods:image` and therefore
   * cannot be imported here — that module depends on `:sempods`, not the other way round.
   *
   * So this makes the choice that module makes — which store, which limits, which address guard —
   * and installs the same [PodMediaModule] with it. What it deliberately does **not** cover is
   * whether the *deployment* selects them from the environment, which is a different assertion and
   * belongs to `SempodsMediaModuleTest` over there.
   *
   * The store root is a temp directory per JVM rather than per test: the injector is a lazily
   * created singleton shared by every integration test, so there is nowhere to hang a per-test
   * lifecycle. Media are content-addressed and scoped by pod id, so tests cannot collide anyway.
   */
  private fun bindMediaForTests() {
    val root = Files.createTempDirectory("sempods-media-test")
    root.toFile().deleteOnExit()

    // The same module the deployment installs, with test arguments — the store, the limits and the
    // guard are exactly what it takes. The one place in the project that passes a guard accepting a
    // non-public address, and it is in a test source set: see LoopbackOnlyAddressGuard for why that
    // matters. It lets the copy-from-URL route be exercised end to end against a server the test
    // itself started; SempodsMediaModule passes MediaSourceAddressGuard.GLOBAL_UNICAST_ONLY and
    // offers no alternative.
    install(
      PodMediaModule(
        store = FilesystemPodMediaStore(root),
        config = PodMediaConfig(
          maxUploadBytes = TEST_MAX_UPLOAD_BYTES,
          // Short enough that a stalling source fails inside a test's patience rather than the
          // production minute.
          sourceReadTimeout = Duration.ofSeconds(2),
          sourceRequestTimeout = Duration.ofSeconds(5),
        ),
        addressGuard = LoopbackOnlyAddressGuard,
        httpPort = config.httpPort,
      ),
    )
  }

  /**
   * Pin the identity issuer so the fake below and the WebID namespace agree on it. Without this
   * it comes from the environment, and the relying party refuses a provider whose advertised
   * issuer differs from the one it was configured with — correctly, and unhelpfully in a test.
   */
  @Provides
  @Singleton
  @Named("idBaseUrl")
  fun provideIdBaseUrl(): String = FakeIdServerTransport.ISSUER

  /**
   * Sign-ins complete against an in-process id-server: it signs real tokens with a real key and
   * serves a real JWKS, all over the transport port, so the OIDC round trip is genuinely
   * exercised and no test opens a socket to do it.
   */
  @Provides
  @Singleton
  fun provideHttpTransport(fake: FakeIdServerTransport): HttpTransport = fake

  @Provides
  @Singleton
  fun provideFakeIdServer(): FakeIdServerTransport = FakeIdServerTransport()

  companion object {

    /**
     * Small on purpose — 4 KiB — so the size-limit test can exceed it with a string literal instead
     * of allocating the production default's 25 MiB just to be refused.
     */
    internal const val TEST_MAX_UPLOAD_BYTES: Long = 4096
  }

  /**
   * The client the suite drives the running server with.
   *
   * Bound here rather than constructed per test class so every test shares the one connection pool
   * — and so the engine a test uses is the engine `SempodsModule` composed, not a second one that
   * could drift from it.
   */
  @Provides
  @Singleton
  fun testHttpClient(okHttpClient: OkHttpClient): TestHttpClient = TestHttpClient(okHttpClient)
}
