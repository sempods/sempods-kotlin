package org.sempods.deployment

import org.sempods.commons.config.Env
import org.sempods.commons.guice.BaseModule
import org.sempods.SempodsModule
import org.sempods.pods.media.MediaSourceAddressGuard
import org.sempods.pods.media.PodMediaConfig
import org.sempods.pods.media.PodMediaModule
import org.sempods.pods.media.PodMediaStore
import org.sempods.pods.media.impls.fs.FilesystemPodMediaStore
import org.sempods.pods.media.impls.s3.S3MediaStoreConfig
import org.sempods.pods.media.impls.s3.S3PodMediaStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.nio.file.Path

/**
 * Which media store this deployment runs, if any.
 *
 * **The selection lives here rather than in `SempodsModule`, and that is forced by the module graph,
 * not a preference.** [S3PodMediaStore] ships as `:sempods-media-s3`, a *sibling* that depends on
 * `:sempods` — so `:sempods` can never name it to select it. Only the composition knows which store
 * modules are on its classpath, so only the composition can choose. `SempodsModule` therefore owns
 * [PodMediaStore] as a type and binds no implementation.
 *
 * This is also the one place that reads media configuration: `:sempods` gets a [PodMediaConfig] and
 * one store instance, and looks nothing up itself.
 *
 * ## One slot, three states
 *
 * `SEMPODS_MEDIA_BACKEND` is the whole switch: unset for no media at all, `filesystem`, or `s3`.
 * There is no second slot and no copy operation inside sempods — a deployment that wants its bytes
 * backed up or moved to another backend does that outside the server, against the layout its store
 * documents (`rclone`, `restic`, an object-store lifecycle rule). See `docs/media.md`
 * §"Deliberately outside".
 *
 * ## What "unset" means
 *
 * With no backend this module binds **nothing**. That is not a degraded mode — a pod server holds
 * RDF, and a deployment that stores no binaries is the ordinary case. What keeps it from breaking
 * the injector is the split between registry and store: `PodMediaDao` is bound unconditionally by
 * `SempodsModule`, so the lifecycle cascades keep working against an empty collection, and only
 * [PodMediaFacade] — which needs bytes — depends on a store being present.
 *
 * One transition is deliberately unsupported: **switching a deployment that already holds media to
 * no backend** leaves rows and objects with no route that can reach them. The way out of a backend
 * is to copy the bytes across first and then repoint the variables.
 *
 * See `docs/media.md`.
 */
object SempodsMediaModule : BaseModule() {

  /** The stores a deployment can select. */
  internal enum class Backend { NONE, FILESYSTEM, S3 }

  override fun configure() {
    val store = createStore()
    if (store == null) {
      logger.info { "Pod media: no backend configured ($BACKEND_ENV_VARIABLE) — this pod server stores no binaries" }
      return
    }

    // Copy-from-URL comes with the backend rather than on a switch of its own: what makes it safe is
    // the guard, not an operator remembering to leave it off. This is the only composition where
    // the strict policy is a production decision; the test compositions install the same module
    // with a guard of their own.
    install(
      PodMediaModule(
        store = store,
        config = PodMediaConfig(maxUploadBytes = resolveMaxUploadBytes()),
        addressGuard = MediaSourceAddressGuard.GLOBAL_UNICAST_ONLY,
        httpPort = SempodsModule.config.httpPort,
      ),
    )
  }

  /** Build the configured store, or `null` when no backend is selected. */
  private fun createStore(): PodMediaStore? =
    when (resolveBackend(Env.get(BACKEND_ENV_VARIABLE))) {
      Backend.NONE -> null

      Backend.FILESYSTEM -> FilesystemPodMediaStore(
        Path.of(required(PATH_ENV_VARIABLE, "filesystem")).toAbsolutePath().normalize(),
      )

      Backend.S3 -> S3PodMediaStore(
        S3MediaStoreConfig(
          endpoint = URI.create(required(S3_ENDPOINT_ENV_VARIABLE, "s3")),
          bucket = required(S3_BUCKET_ENV_VARIABLE, "s3"),
          accessKey = required(S3_ACCESS_KEY_ENV_VARIABLE, "s3"),
          secret = required(S3_SECRET_ENV_VARIABLE, "s3"),
          region = optional(S3_REGION_ENV_VARIABLE) ?: S3MediaStoreConfig.DEFAULT_REGION,
          pathStyle = resolvePathStyle(S3_PATH_STYLE_ENV_VARIABLE),
        ),
      )
    }

  private fun optional(variable: String): String? = Env.get(variable)?.trim()?.takeIf { it.isNotEmpty() }

  /** A value with no safe default: a boot failure naming the variable is the cheapest diagnosis. */
  private fun required(variable: String, backend: String): String =
    optional(variable) ?: throw IllegalStateException(
      "$variable must be set when $BACKEND_ENV_VARIABLE=$backend",
    )

  /**
   * Whether the S3 client addresses `{endpoint}/{bucket}` rather than `{bucket}.{endpoint}`.
   *
   * Defaults to path style, which is the opposite of the SDK's default and right for a self-hosted
   * store: virtual-host addressing needs wildcard DNS and a matching certificate, and a store
   * reached by container name has neither. AWS and R2 want `false`.
   */
  private fun resolvePathStyle(variable: String): Boolean {
    val configured = optional(variable) ?: return true
    return when (configured.lowercase()) {
      "true" -> true
      "false" -> false
      else -> throw IllegalStateException("$variable must be true or false, got '$configured'")
    }
  }

  private fun resolveMaxUploadBytes(): Long {
    val configured = Env.get(MAX_UPLOAD_BYTES_ENV_VARIABLE) ?: return PodMediaConfig.DEFAULT_MAX_UPLOAD_BYTES
    return configured.trim().toLongOrNull()
      ?: throw IllegalStateException("$MAX_UPLOAD_BYTES_ENV_VARIABLE must be a whole number of bytes, got '$configured'")
  }

  /** Everything this module reads is `SEMPODS_MEDIA_` plus one of the names below. */
  private const val PREFIX = "SEMPODS_MEDIA_"

  /** Which store fronts this deployment's binaries. */
  internal const val BACKEND_ENV_VARIABLE = PREFIX + "BACKEND"

  /**
   * Where [Backend.FILESYSTEM] keeps them. **Required when that backend is selected, with no default
   * on purpose.** A silent fallback to a temporary directory would start cleanly and lose every byte
   * on the next container restart; a boot failure is the cheaper diagnosis.
   */
  internal const val PATH_ENV_VARIABLE = PREFIX + "PATH"

  /** What [Backend.S3] needs. The endpoint has no default either: an unset one would mean real AWS. */
  internal const val S3_ENDPOINT_ENV_VARIABLE = PREFIX + "S3_ENDPOINT"
  internal const val S3_BUCKET_ENV_VARIABLE = PREFIX + "S3_BUCKET"
  internal const val S3_ACCESS_KEY_ENV_VARIABLE = PREFIX + "S3_ACCESS_KEY"
  internal const val S3_SECRET_ENV_VARIABLE = PREFIX + "S3_SECRET"
  internal const val S3_REGION_ENV_VARIABLE = PREFIX + "S3_REGION"
  internal const val S3_PATH_STYLE_ENV_VARIABLE = PREFIX + "S3_PATH_STYLE"

  internal const val MAX_UPLOAD_BYTES_ENV_VARIABLE = PREFIX + "MAX_UPLOAD_BYTES"

  /**
   * What [BACKEND_ENV_VARIABLE] selects: [Backend.NONE] when unset or blank, and an exception for
   * anything unrecognised.
   *
   * A pure function so the rule can be tested without an injector — the shape `resolveAdminClients`
   * established. Failing at boot is the point: a typo that fell back to the default would produce a
   * server that behaves correctly in every visible respect while quietly refusing to hold media, and
   * the first symptom would be an upload failing months later.
   */
  internal fun resolveBackend(configured: String?): Backend {
    val value = configured?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Backend.NONE
    return when (value) {
      "none" -> Backend.NONE
      "filesystem" -> Backend.FILESYSTEM
      "s3" -> Backend.S3
      else -> throw IllegalStateException(
        "unsupported $BACKEND_ENV_VARIABLE '$value' (supported: none, filesystem, s3)",
      )
    }
  }

  private val logger = KotlinLogging.logger {}
}
