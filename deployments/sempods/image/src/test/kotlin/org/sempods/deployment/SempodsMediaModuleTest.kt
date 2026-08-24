package org.sempods.deployment

import com.google.inject.Binding
import com.google.inject.CreationException
import com.google.inject.Guice
import com.google.inject.Key
import com.google.inject.spi.Elements
import com.google.inject.spi.InstanceBinding
import org.sempods.api.pod.system.media.PodMediaEndpoint
import org.sempods.api.system.admin.media.AdminMediaEndpoint
import org.sempods.deployment.SempodsMediaModule.Backend
import org.sempods.pods.media.MediaSourceAddressGuard
import org.sempods.pods.media.MediaSourceFetcher
import org.sempods.pods.media.PodMediaFacade
import org.sempods.pods.media.PodMediaStore
import org.sempods.pods.media.impls.s3.S3PodMediaStore
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Guards the one thing only this module can see: **which media store the production composition
 * binds**. `:sempods` owns the seam but can select nothing, because an S3-backed store ships as a
 * sibling module that depends on it — so this selection exists here and is testable nowhere else.
 *
 * Bindings are read statically ([Elements]), the way a composition test does in any other
 * image: nothing here starts a server or touches a database.
 */
class SempodsMediaModuleTest {

  private fun bindingsFor(key: Key<*>): List<Binding<*>> =
    Elements.getElements(SempodsMediaModule).filterIsInstance<Binding<*>>().filter { it.key == key }

  /**
   * The module reads configuration at `configure()` time, and [org.sempods.commons.config.Env] falls back
   * from the environment to system properties — so a test states the environment it means by
   * setting properties, and clears them afterwards. An exported variable would still win, which is
   * why the selection rule itself is tested as a pure function below rather than only through here.
   */
  private fun <T> withEnvironment(vararg settings: Pair<String, String?>, block: () -> T): T {
    val previous = settings.map { (name, _) -> name to System.getProperty(name) }
    settings.forEach { (name, value) ->
      if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }
    try {
      return block()
    } finally {
      previous.forEach { (name, value) ->
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
      }
    }
  }

  @Test
  fun `unset, blank or none selects no backend`() {
    assertEquals(Backend.NONE, SempodsMediaModule.resolveBackend(null))
    assertEquals(Backend.NONE, SempodsMediaModule.resolveBackend("   "))
    assertEquals(Backend.NONE, SempodsMediaModule.resolveBackend("none"))
  }

  @Test
  fun `the configured backend is matched regardless of case and padding`() {
    assertEquals(Backend.FILESYSTEM, SempodsMediaModule.resolveBackend("filesystem"))
    assertEquals(Backend.FILESYSTEM, SempodsMediaModule.resolveBackend("  FileSystem "))
    assertEquals(Backend.S3, SempodsMediaModule.resolveBackend("s3"))
  }

  @Test
  fun `an unknown backend fails at boot rather than falling back`() {
    // A fallback here would produce a server that behaves correctly in every visible respect while
    // quietly refusing to hold media — a symptom nobody sees until an upload fails.
    val e = assertFailsWith<IllegalStateException> { SempodsMediaModule.resolveBackend("filesytem") }

    assertEquals(
      "unsupported SEMPODS_MEDIA_BACKEND 'filesytem' (supported: none, filesystem, s3)",
      e.message,
    )
  }

  @Test
  fun `with no backend the composition binds no store, no facade and no routes`() {
    withEnvironment(SempodsMediaModule.BACKEND_ENV_VARIABLE to null) {
      assertEquals(0, bindingsFor(Key.get(PodMediaStore::class.java)).size)
      assertEquals(0, bindingsFor(Key.get(PodMediaFacade::class.java)).size)
      // The routes come with the store: `_system/media` must not exist at all rather than answer an
      // error per call on a pod server that holds no binaries.
      assertEquals(0, bindingsFor(Key.get(PodMediaEndpoint::class.java)).size)
      // Nor the maintenance route, and that is consistent rather than a gap: nothing was ever
      // written, so there is nothing to sweep and nothing to reconcile against.
      assertEquals(0, bindingsFor(Key.get(AdminMediaEndpoint::class.java)).size)
      // And with no routes there is nothing that could fetch a URL either.
      assertEquals(0, bindingsFor(Key.get(MediaSourceFetcher::class.java)).size)
      assertEquals(0, bindingsFor(Key.get(MediaSourceAddressGuard::class.java)).size)
    }
  }

  @Test
  fun `with the filesystem backend the composition binds the store, the facade and the routes`() {
    val root = Files.createTempDirectory("sempods-media-test")
    withEnvironment(
      SempodsMediaModule.BACKEND_ENV_VARIABLE to "filesystem",
      SempodsMediaModule.PATH_ENV_VARIABLE to root.toString(),
    ) {
      assertEquals(1, bindingsFor(Key.get(PodMediaStore::class.java)).size)
      assertEquals(1, bindingsFor(Key.get(PodMediaFacade::class.java)).size)
      assertEquals(1, bindingsFor(Key.get(PodMediaEndpoint::class.java)).size)
      assertEquals(1, bindingsFor(Key.get(AdminMediaEndpoint::class.java)).size)
      assertEquals(1, bindingsFor(Key.get(MediaSourceFetcher::class.java)).size)
    }
  }

  @Test
  fun `the s3 backend binds the store that ships as a sibling module`() {
    // The whole point of the split: `:sempods` owns the seam and cannot name `S3PodMediaStore`,
    // because that class lives in a module which depends on `:sempods`. Only this composition holds
    // both, so only this composition can select — and only this test can see that it did.
    withEnvironment(
      SempodsMediaModule.BACKEND_ENV_VARIABLE to "s3",
      SempodsMediaModule.S3_ENDPOINT_ENV_VARIABLE to "http://garage:3900",
      SempodsMediaModule.S3_BUCKET_ENV_VARIABLE to "sempods-media",
      SempodsMediaModule.S3_ACCESS_KEY_ENV_VARIABLE to "GK0123456789abcdef01234567",
      SempodsMediaModule.S3_SECRET_ENV_VARIABLE to "secret",
      SempodsMediaModule.S3_REGION_ENV_VARIABLE to "garage",
    ) {
      val binding = bindingsFor(Key.get(PodMediaStore::class.java)).single()

      assertIs<S3PodMediaStore>((binding as InstanceBinding<*>).instance)
      assertEquals(1, bindingsFor(Key.get(PodMediaEndpoint::class.java)).size)
    }
  }

  @Test
  fun `the s3 backend without an endpoint fails at boot`() {
    // No default, for the same reason SEMPODS_MEDIA_PATH has none — except that here the silent
    // fallback would be worse than a lost volume: an unset endpoint means the real AWS.
    val message = assertBootFails(
      SempodsMediaModule.BACKEND_ENV_VARIABLE to "s3",
      SempodsMediaModule.S3_ENDPOINT_ENV_VARIABLE to null,
      SempodsMediaModule.S3_BUCKET_ENV_VARIABLE to "sempods-media",
    )

    assertTrue(
      message.contains("SEMPODS_MEDIA_S3_ENDPOINT must be set"),
      "the failure must name the missing variable, got: $message",
    )
  }

  /**
   * The assertion this file exists for, in its sharpest form: **production fetches only public
   * addresses**.
   *
   * A guard that accepts a private address exists exactly once in the project, in `:sempods`' test
   * source set, so that no configuration and no typo can reach it from here. This checks the other
   * half — that the binding the deployment does make is the strict one — because "the permissive
   * policy is not on the classpath" and "the strict policy is bound" are two different claims and
   * only one of them is enforced by the module graph.
   */
  @Test
  fun `the deployment binds the strict address policy`() {
    val root = Files.createTempDirectory("sempods-media-test")
    withEnvironment(
      SempodsMediaModule.BACKEND_ENV_VARIABLE to "filesystem",
      SempodsMediaModule.PATH_ENV_VARIABLE to root.toString(),
    ) {
      val binding = bindingsFor(Key.get(MediaSourceAddressGuard::class.java)).single()

      assertSame(MediaSourceAddressGuard.GLOBAL_UNICAST_ONLY, (binding as InstanceBinding<*>).instance)
    }
  }

  /**
   * The misconfiguration cases go through [Guice.createInjector] rather than [Elements],
   * because **`Elements.getElements` catches whatever `configure()` throws** and records it as a
   * `Message` element instead of propagating it. Asserting `assertFailsWith` around it therefore
   * passes for nothing at all, which is a worse outcome than no test. `createInjector` is what the
   * starter calls, it collects those messages and throws — so this is both the honest mechanism and
   * the one that matches production.
   */
  private fun assertBootFails(vararg settings: Pair<String, String?>): String {
    val e = withEnvironment(*settings) {
      assertFailsWith<CreationException> { Guice.createInjector(SempodsMediaModule) }
    }
    return checkNotNull(e.message)
  }

  @Test
  fun `the filesystem backend without a path fails at boot`() {
    // No default on purpose: a silent fallback to a temporary directory would start cleanly and
    // lose every byte on the next container restart.
    val message = assertBootFails(
      SempodsMediaModule.BACKEND_ENV_VARIABLE to "filesystem",
      SempodsMediaModule.PATH_ENV_VARIABLE to null,
    )

    assertTrue(
      message.contains("SEMPODS_MEDIA_PATH must be set"),
      "the failure must name the missing variable, got: $message",
    )
  }

  @Test
  fun `an unparseable upload limit fails at boot`() {
    val root = Files.createTempDirectory("sempods-media-test")

    val message = assertBootFails(
      SempodsMediaModule.BACKEND_ENV_VARIABLE to "filesystem",
      SempodsMediaModule.PATH_ENV_VARIABLE to root.toString(),
      SempodsMediaModule.MAX_UPLOAD_BYTES_ENV_VARIABLE to "25MB",
    )

    assertTrue(
      message.contains("SEMPODS_MEDIA_MAX_UPLOAD_BYTES must be a whole number"),
      "the failure must name the unparseable value, got: $message",
    )
  }
}
