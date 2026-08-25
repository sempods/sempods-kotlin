package org.sempods.api.system.admin.media

import com.fasterxml.jackson.databind.DeserializationFeature
import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.admin.AdminAuthorizer
import org.sempods.api.system.admin.AdminAuthorizedEndpoint
import org.sempods.pods.media.MediaReconcileReport
import org.sempods.pods.media.MediaSweepReport
import org.sempods.pods.media.PodMediaFacade
import org.sempods.pods.media.PodMediaRef
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

/**
 * Media maintenance over the host-level admin surface: reclaim what nothing references any more, and
 * report where the store and the registry have drifted apart.
 *
 * ```
 * POST /_system/admin/media/sweep       {gracePeriodSeconds?, dryRun?}
 * POST /_system/admin/media/reconcile
 * ```
 *
 * **Host-level rather than pod-scoped, and the grace period is what forces it.**
 * `SempodsFacade.deletePod` stamps a pod's media rows and then removes the pod, so by the time those
 * rows come due a `{pod}/…` route could not resolve one. That also matches where the admin surface
 * already lives — [org.sempods.api.system.admin.pods.AdminPodsEndpoint] is `_system/admin/pods`,
 * and [AdminAuthorizedEndpoint] states why host-level authority is a different authorization input
 * that grants no pod scopes.
 *
 * **No in-process scheduler.** `SempodsModule` runs with `tasks = false` and sempods has none, so
 * the honest answer is an operator cron against this route.
 *
 * **Bound by the deployment**, like [org.sempods.api.pod.system.media.PodMediaEndpoint]: with
 * `SEMPODS_MEDIA_BACKEND=none` these routes do not exist at all. That is consistent rather than a
 * gap — nothing was ever written, so there is nothing to sweep.
 *
 * JAX-RS selects root resources by literal character count, so `_system/admin/media` (19) wins over
 * `PodResourceEndpoint`'s `{pod: [^/]+}` (0); a pod may not be named `_system`.
 */
@Path("_system/admin/media")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class AdminMediaEndpoint @Inject constructor(
  private val mediaFacade: PodMediaFacade,
  adminAuthorizer: AdminAuthorizer,
) : AdminAuthorizedEndpoint(adminAuthorizer) {

  /**
   * Delete the bytes of every media unreferenced for longer than the grace period.
   *
   * An empty body means the deployment's configured grace period
   * ([org.sempods.pods.media.PodMediaConfig.sweepGracePeriod], 30 days by default), which is what a
   * cron sends. The overrides exist for the two occasions an operator deviates, and both are one-off:
   * reclaim now (`gracePeriodSeconds: 0`), or look before leaping (`dryRun: true`).
   *
   * **The body is parsed strictly** — an unknown field is a `400`, not a shrug. The project-wide
   * mapper ignores unknown fields, which here would turn a misspelt `dryRun` into "no assertion" and
   * delete bytes the operator meant to preview. Same reasoning as `AdminPodsEndpoint`'s
   * `expectedRegistrationId`, and the stakes are the same kind: silent, and irreversible.
   */
  @POST
  @Path("sweep")
  fun sweep(
    @HeaderParam("Authorization") authorization: String?,
    body: String?,
  ): Response {

    val adminClientId = requireAdminOrThrow(authorization)
    val request = parseBody(body, SweepRequest::class.java) ?: SweepRequest()

    val gracePeriod = request.gracePeriodSeconds?.let {
      // Zero is legal and means "everything unreferenced up to now"; negative is not, and would
      // otherwise reach into the future and take rows that are still inside their grace period.
      if (it < 0) throw badRequest("gracePeriodSeconds must not be negative")
      Duration.ofSeconds(it)
    }
    val dryRun = request.dryRun ?: false

    val report = if (gracePeriod == null) {
      mediaFacade.sweepUnreferenced(dryRun = dryRun)
    } else {
      mediaFacade.sweepUnreferenced(gracePeriod = gracePeriod, dryRun = dryRun)
    }

    logger.info {
      "[media/audit] outcome=sweep_run admin='$adminClientId' dry_run=${report.dryRun} cutoff=${report.cutoff} candidates=${report.candidates} deleted=${report.deleted} " +
          "revived=${report.revived} failures=${report.objectDeletionFailures} reclaimed_bytes=${report.reclaimedBytes}"
    }
    return Response.ok(report.toResponse()).build()
  }

  /**
   * Report where the store and the registry disagree, in both directions. **It corrects nothing,
   * and no route does** — correcting either direction means deleting bytes or writing them on the
   * strength of a query, and a repair nobody reviewed is the one failure mode this surface refuses.
   * The remedies are an operator's and live outside the server: remove a leaked object from the
   * store, restore a missing one from the backup or let the source re-upload it.
   *
   * The two lists are capped at [MAX_REPORTED_REFS] entries each with the full counts kept beside
   * them, because a registry gone properly wrong would otherwise put a million ids into one HTTP
   * response and become the next incident. `truncated` says when that happened rather than leaving
   * a short list to look like a clean bill of health.
   */
  @POST
  @Path("reconcile")
  fun reconcile(
    @HeaderParam("Authorization") authorization: String?,
  ): Response {

    val adminClientId = requireAdminOrThrow(authorization)
    val report = mediaFacade.reconcile()

    logger.info {
      "[media/audit] outcome=reconcile_run admin='$adminClientId' objects=${report.checkedObjects} rows=${report.checkedRows} objects_without_row=${report.objectsWithoutRow.size} " +
          "rows_without_object=${report.rowsWithoutObject.size}"
    }
    return Response.ok(report.toResponse()).build()
  }

  private fun MediaSweepReport.toResponse() = SweepResponse(
    dryRun = dryRun,
    gracePeriodSeconds = gracePeriod.seconds,
    cutoff = cutoff.toString(),
    candidates = candidates,
    deleted = deleted,
    revived = revived,
    objectDeletionFailures = objectDeletionFailures,
    brokenMedia = brokenMedia,
    strandedPodsMarked = strandedPodsMarked,
    reclaimedBytes = reclaimedBytes,
  )

  private fun MediaReconcileReport.toResponse() = ReconcileResponse(
    checkedObjects = checkedObjects,
    checkedRows = checkedRows,
    objectsWithoutRowCount = objectsWithoutRow.size,
    rowsWithoutObjectCount = rowsWithoutObject.size,
    objectsWithoutRow = objectsWithoutRow.take(MAX_REPORTED_REFS).map { it.toResponse() },
    rowsWithoutObject = rowsWithoutObject.take(MAX_REPORTED_REFS).map { it.toResponse() },
    truncated = objectsWithoutRow.size > MAX_REPORTED_REFS || rowsWithoutObject.size > MAX_REPORTED_REFS,
  )

  private fun PodMediaRef.toResponse() = MediaRefResponse(podId = podId.value, mediaId = mediaId)

  /** Parses an optional JSON body, rejecting unknown fields — see [sweep] for why strictly. */
  private fun <T> parseBody(body: String?, type: Class<T>): T? {
    val raw = body?.takeIf { it.isNotBlank() } ?: return null
    return try {
      strictBodyMapper.readValue(raw, type)
    } catch (e: Exception) {
      throw badRequest("invalid request body: ${e.message?.substringBefore('\n') ?: "could not parse"}")
    }
  }

  private fun badRequest(message: String): WebApplicationException =
    WebApplicationException(errorResponse(400, message))

  companion object {

    private val logger = KotlinLogging.logger {}

    private val strictBodyMapper = JsonMappers.newDefault()
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    /**
     * How many refs per direction the reconcile response carries. A hundred is enough to see what
     * kind of drift this is; the counts beside them say how much of it there is, and the log line
     * plus a second, pod-scoped look are where an operator goes from there.
     */
    private const val MAX_REPORTED_REFS = 100
  }
}

/**
 * Body of `POST /_system/admin/media/sweep`. Both fields absent — an empty body — is the cron's
 * call: the deployment's grace period, and delete for real.
 */
data class SweepRequest(
  /**
   * How long a media stays reclaimable after its last assignment went, overriding the deployment's
   * configured value for this run. `0` means "everything unreferenced up to now".
   */
  val gracePeriodSeconds: Long? = null,

  /** Count and log what would go, delete nothing. */
  val dryRun: Boolean? = null,
)

/**
 * Response of `POST /_system/admin/media/sweep`.
 *
 * [deleted], [revived] and [objectDeletionFailures] add up to [candidates] on a real run — the
 * property that makes this readable, since it says outright that nothing was quietly skipped. On a
 * dry run everything but [candidates] and [reclaimedBytes] is zero.
 */
data class SweepResponse(
  val dryRun: Boolean,
  val gracePeriodSeconds: Long,

  /** `now - gracePeriod`: what this run actually considered, which the caller cannot derive. */
  val cutoff: String,

  val candidates: Int,
  val deleted: Int,

  /** Rows whose assignment came back mid-run. Ordinary — the sweep raced live traffic and yielded. */
  val revived: Int,

  /** Rows whose bytes could not be deleted. The row is kept, so the next run retries the same one. */
  val objectDeletionFailures: Int,

  /**
   * Rows assigned while their object was being deleted — something references bytes that are gone.
   * The only outcome here that hurts a user; the reconcile lists them under `rowsWithoutObject`.
   */
  val brokenMedia: Int,

  /**
   * Pods that no longer exist but still held assigned media, marked so the grace period reaches
   * them. Anything but zero means an upload committed a row while its pod was being deleted.
   */
  val strandedPodsMarked: Int,

  /** What the deleted objects weighed — or, on a dry run, what a real run would have reclaimed. */
  val reclaimedBytes: Long,
)

/**
 * Response of `POST /_system/admin/media/reconcile`.
 *
 * The counts are complete; the two lists are capped. [truncated] says when they were, so a short
 * list is never mistaken for a clean bill of health.
 */
data class ReconcileResponse(
  val checkedObjects: Int,
  val checkedRows: Int,

  val objectsWithoutRowCount: Int,
  val rowsWithoutObjectCount: Int,

  /** Bytes nothing in the registry claims — leaked, inert, and paid for. */
  val objectsWithoutRow: List<MediaRefResponse>,

  /** Rows whose bytes are gone — a broken media, and a `404` for whoever still holds the URL. */
  val rowsWithoutObject: List<MediaRefResponse>,

  val truncated: Boolean,
)

/**
 * One media, as this surface names it: the pod id rather than the pod name, because a swept row's
 * pod may no longer exist and would have no name left to report.
 */
data class MediaRefResponse(
  val podId: String,
  val mediaId: String,
)
