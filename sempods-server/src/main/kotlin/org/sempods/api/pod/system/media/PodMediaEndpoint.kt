package org.sempods.api.pod.system.media

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.utils.HashUtil
import org.sempods.SempodsUriBuilder
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.api.pod.resources.PodContextWriteAuthorizer
import org.sempods.pods.PodFacade
import org.sempods.pods.media.MediaSourceException
import org.sempods.pods.media.MediaSourceFetcher
import org.sempods.pods.media.PodMediaConfig
import org.sempods.pods.media.PodMediaFacade
import org.sempods.pods.media.persist.MediaAssignment
import org.sempods.pods.media.persist.PodMedia
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.media.PodMediaSource
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HEAD
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.bson.types.ObjectId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path as FilePath

/**
 * The pod's media surface: bytes in, bytes out, and which contexts a media belongs to.
 *
 * ```
 * POST   {pod}/_system/media?context=<ctx>          upload; raw body + Content-Type,
 *                                                   or a source descriptor (see uploadFromSource)
 * GET    {pod}/_system/media/{id}                   metadata
 * GET    {pod}/_system/media/{id}/content           the bytes
 * PUT    {pod}/_system/media/{id}?context=<ctx>     assign to another context
 * DELETE {pod}/_system/media/{id}?context=<ctx>     drop one assignment
 * ```
 *
 * **Under `_system` because a media registry is control-plane state**, the same reason contexts,
 * grants and service-client registrations are — sempods-spec `spec/core/lod-crud.md` §5 states the rule.
 * Nothing here writes RDF: whoever wants a `schema:ImageObject` writes it themselves and points its
 * `schema:contentUrl` at the content route. Registry and graph stay unaware of each other, so
 * there is no synchronisation path to drift.
 *
 * **Authorization is the context model, not a new one.** Writes go through
 * [PodContextWriteAuthorizer] exactly as the LOD and slot layers do, manage-cascade included; reads
 * intersect the media's assignments with `SempodsCredentials.restrictedContexts`. Media therefore
 * inherits anonymous access to public contexts, `public-read`, and revocation without a line of its
 * own.
 *
 * **This class is bound by the deployment**, not by `SempodsModule`: with no media backend
 * configured there is no [PodMediaFacade] to inject, and these routes do not exist at all rather
 * than answering an error per call.
 *
 * Known limitation, stated rather than discovered: **no range requests**. `Accept-Ranges` is not
 * advertised and a `Range` header is ignored, so a client cannot resume a large download. It costs
 * nothing today because the objects are images; it is the first thing video would need.
 */
@Path("{pod}/_system/media")
class PodMediaEndpoint @Inject constructor(
  private val mediaFacade: PodMediaFacade,
  private val mediaConfig: PodMediaConfig,
  private val mediaSourceFetcher: MediaSourceFetcher,
  private val contextWriteAuthorizer: PodContextWriteAuthorizer,
  private val sempodsUriBuilder: SempodsUriBuilder,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  /**
   * Take a media into the pod and assign it to `?context=` — either the bytes in the request body,
   * or, on [PodMediaSource.MEDIA_TYPE], the URL to fetch them from.
   *
   * `POST` rather than `PUT` on a known URL because the caller cannot know the id: it is the
   * content hash, which only the server has computed once the bytes are in.
   *
   * **Always `201`, even when the pod already held these bytes**, and the response says only where
   * the object is. Distinguishing "created" from "assigned an existing object" would tell an
   * uploader that this pod holds a given file — in contexts they may not read — for the price of
   * hashing it themselves, and that is the leak the `404` on the read side exists to prevent.
   *
   * The status alone is not enough for that, which is why the body is [PodMediaUploadResponse] and
   * not the full metadata: on a dedup hit the row belongs to the *first* uploader, so returning its
   * `filename`, `created_at`, `content_type` and context set would have said "this was already
   * here" in four other ways. Whoever wants metadata asks for it, and that read goes through the
   * visibility filter like any other.
   *
   * **One method with a branch, not two methods with different `@Consumes` — and that is forced by
   * JAX-RS, not chosen.** A second method consuming only the descriptor type looks tidier and is
   * wrong here: a request arriving *without* a `Content-Type` is compatible with every `@Consumes`,
   * so both methods are candidates and the more specific one wins. Every body posted without a type
   * would then be read as a source descriptor, and the "Content-Type is required" rule below — which
   * exists because the registry records that type and the content route serves it — could never fire.
   * The wildcard has to be the only door, so the choice is made behind it.
   */
  @POST
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.APPLICATION_JSON)
  fun upload(
    @PathParam("pod") pod: String,
    @QueryParam("context") contextParams: List<String>?,
    @QueryParam("filename") filename: String?,
    @HeaderParam(HttpHeaders.CONTENT_TYPE) contentTypeHeader: String?,
    body: InputStream?,
  ): Response {
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val contextUri = contextWriteAuthorizer.resolveSingleWriteContextOrThrow(pod, contextParams)
    contextWriteAuthorizer.authorizeWriteOrThrow(credentials, contextUri)

    val contentType = contentTypeHeader?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw badRequest("Content-Type is required — the registry records it and the content route serves it")
    val stream = body ?: throw badRequest("missing request body")

    if (baseTypeOf(contentType) == PodMediaSource.MEDIA_TYPE) {
      return uploadFromSource(pod, credentials, contextUri, stream)
    }

    return storeAndRespond(
      pod = pod,
      credentials = credentials,
      contextUri = contextUri,
      buffered = bufferOrThrow(stream),
      contentType = contentType,
      filename = filename,
    )
  }

  /**
   * Take a media the caller *names* rather than sends: the pod fetches [MediaSourceRequest.sourceUrl]
   * itself and stores what comes back.
   *
   * **Its own media type rather than `application/json`**, because the route accepts any bytes at
   * all: a JSON document is a perfectly ordinary media, and on `application/json` the two would be
   * indistinguishable — one caller's upload would become another's instruction.
   *
   * **Not a query parameter**, which the roadmap settles for a reason worth repeating: source URLs
   * are routinely credentials in themselves — a signed Drive or S3 link — and a query parameter
   * lands in every access log along the way.
   *
   * Everything after the fetch is the raw path exactly, `201` and dedup included. What differs is
   * who reads the bytes, and that is the whole security weight of this milestone — see
   * [MediaSourceFetcher].
   *
   * **The failure is deliberately undifferentiated**: `400` for every refusal except the size limit,
   * with the reason in the log and not in the response. A caller who could tell "does not resolve"
   * from "resolves to a private address" from "answered 500" would have a probe of the network the
   * pod server sits in, which is the map the guard exists to withhold.
   */
  private fun uploadFromSource(
    pod: String,
    credentials: SempodsCredentials,
    contextUri: URI,
    body: InputStream,
  ): Response {
    val descriptor = readDescriptorOrThrow(body)
    val sourceUrl = descriptor.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw badRequest("source_url is required")

    val fetched = try {
      mediaSourceFetcher.fetch(sourceUrl)
    } catch (e: MediaSourceException) {
      logger.warn {
        "[media/audit] outcome=source_rejected pod='$pod' context='$contextUri' " +
            "client_id='${credentials.oauthClientId ?: "(anon)"}' reason='${e.message}'"
      }
      throw if (e.tooLarge) {
        WebApplicationException(
          Response.status(413)
            .entity("media exceeds the maximum of ${mediaConfig.maxUploadBytes} bytes")
            .type(MediaType.TEXT_PLAIN)
            .build(),
        )
      } else {
        badRequest("the source could not be fetched")
      }
    }

    return storeAndRespond(
      pod = pod,
      credentials = credentials,
      contextUri = contextUri,
      buffered = fetched.body,
      contentType = fetched.contentType,
      filename = descriptor.filename,
    )
  }

  /**
   * Reads the descriptor, refusing a body too large to be one.
   *
   * The cap is not about memory — 8 KiB either way is nothing — but about not letting a media-sized
   * body through a door that promised to carry a URL. Without it the descriptor type would be a way
   * to hand the pod an arbitrary amount of data that never reaches the size check on the media path.
   */
  private fun readDescriptorOrThrow(body: InputStream): MediaSourceRequest {
    val raw = body.readNBytes(MAX_DESCRIPTOR_BYTES + 1)
    if (raw.size > MAX_DESCRIPTOR_BYTES) throw badRequest("the source descriptor is larger than $MAX_DESCRIPTOR_BYTES bytes")
    return try {
      JsonMappers.default().readValue(raw, MediaSourceRequest::class.java)
    } catch (e: Exception) {
      // The exception class and nothing else: Jackson quotes the offending source in its message,
      // and the offending source here is a body that may hold a signed URL.
      logger.warn { "[media/audit] outcome=source_descriptor_unreadable error='${e.javaClass.simpleName}'" }
      throw badRequest("the source descriptor is not readable as ${PodMediaSource.MEDIA_TYPE}")
    }
  }

  /** The media type without its parameters, lowercased — `image/png; charset=x` is `image/png`. */
  private fun baseTypeOf(contentType: String): String = contentType.substringBefore(';').trim().lowercase()

  /**
   * What both ingestion paths do once the bytes are on disk and the write is authorized.
   *
   * Shared rather than duplicated because the `201`-always rule is a confidentiality decision, not a
   * formality: a route that answered `200` on a dedup hit would tell an uploader that this pod
   * already holds those bytes, possibly in a context they cannot read. Two copies of that rule is
   * one copy too many.
   *
   * Takes ownership of [buffered] and deletes it.
   */
  private fun storeAndRespond(
    pod: String,
    credentials: SempodsCredentials,
    contextUri: URI,
    buffered: FilePath,
    contentType: String,
    filename: String?,
  ): Response {
    val stored = try {
      // The context was registered when this request was parsed. Getting the bytes here took time —
      // a buffered body, or on the copy-from-URL path a remote fetch under a timeout measured in
      // tens of seconds — and `PodFacade.removeContext` may have run in between. Writing the
      // assignment now would re-create the one that cascade had just pulled, leaving a media
      // attached to a context that does not exist: never unreferenced, so never collectable, and
      // readable to whoever still holds an unexpired token naming it.
      contextWriteAuthorizer.requireContextStillRegisteredOrThrow(credentials.pod.name, contextUri)

      mediaFacade.store(
        podId = podId(credentials),
        context = contextUri,
        source = buffered,
        contentType = contentType,
        filename = filename?.trim()?.takeIf { it.isNotEmpty() },
        createdBy = credentials.tokenSub ?: credentials.oauthClientId,
      )
    } finally {
      Files.deleteIfExists(buffered)
    }

    logMediaAudit("stored", pod, stored.mediaId, contextUri, credentials)
    return Response.status(201)
      .header(HttpHeaders.LOCATION, contentUri(pod, stored.mediaId).toString())
      .entity(PodMediaUploadResponse(id = stored.mediaId, contentUrl = contentUri(pod, stored.mediaId).toString()))
      .build()
  }

  /**
   * What this caller may know about the media.
   *
   * **`no-store`, not merely `private`.** The body is filtered to the caller's readable contexts, so
   * the same URL answers differently per caller *and* differently over time for the same one — a
   * context turning private, or a grant being revoked, changes it with no change to the URL. A
   * response carrying no explicit freshness may still be kept under a cache's *heuristic* lifetime
   * (RFC 9111 §4.2.2), and `private` only restricts **who** may store it, not for how long. So an
   * intermediary or a browser could keep answering with a context name, filename and content type
   * the caller is no longer allowed to see, which would quietly undo the read sandbox.
   *
   * `no-store` rather than `no-cache` here because there is nothing to gain: this body is small and
   * per-caller, so revalidating it costs about as much as re-rendering it. The content route makes
   * the opposite trade — see there.
   *
   * `Vary: Authorization` says out loud what the filtering already implies.
   */
  @GET
  @Path("{mediaId}")
  @Produces(MediaType.APPLICATION_JSON)
  fun metadata(
    @PathParam("pod") pod: String,
    @PathParam("mediaId") mediaId: String,
  ): Response {
    val credentials = authenticate(pod)
    val podMedia = readableOrThrow(credentials, mediaId)
    return Response.ok(podMedia.toResponse(pod, credentials.restrictedContexts))
      .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
      .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
      .build()
  }

  @HEAD
  @Path("{mediaId}")
  @Produces(MediaType.APPLICATION_JSON)
  fun headMetadata(
    @PathParam("pod") pod: String,
    @PathParam("mediaId") mediaId: String,
  ): Response = metadata(pod, mediaId)

  /**
   * The bytes.
   *
   * **The ETag is the content hash and the selected type**, which makes it a strong validator by
   * construction rather than one derived from a timestamp — the bytes under a given id are
   * immutable, so it can never go stale for them.
   *
   * The type has to be in there because it is *not* fixed per URL: the content type is claimed per
   * assignment, so losing access to one assignment can change which claim this URL answers with. On
   * a media-id-only validator, a client revalidating after that change would get `304` and keep the
   * old `Content-Type` and `Content-Disposition` — a representation the server no longer serves. An
   * entity tag identifies a *representation*, and the media type is part of one.
   *
   * **`Cache-Control: private, no-cache`, and both halves earn their place.** `private` keeps a
   * shared cache from handing one caller's answer to another — the same URL is a `404` for someone
   * without a readable assignment. `no-cache` then forces revalidation before every reuse, because
   * `private` alone leaves the response eligible for a cache's *heuristic* freshness lifetime
   * (RFC 9111 §4.2.2): a browser could go on serving bytes after the grant that allowed them was
   * revoked. sempods-spec `spec/core/grants.md` promises revocation takes effect immediately, and a cache that may
   * answer for an unbounded window would make that promise false.
   *
   * `no-cache` rather than `no-store` because the entity tag exists precisely to make revalidation
   * cheap: the client keeps the bytes, asks, and gets a `304` — or a `404` once it may no longer
   * read them. Not storing would throw the payload away on every view for no gain in safety.
   */
  @GET
  @Path("{mediaId}/content")
  // The stored type is whatever was uploaded, so this route produces anything. Without the
  // declaration it inherits `application/json` from `commons-jaxrs`' BaseEndpoint and Jersey
  // answers 406 to `Accept: image/png` — before the method runs, so no amount of correct
  // Content-Type on the way out would help. That is what an `<img>` tag sends.
  @Produces(MediaType.WILDCARD)
  fun content(
    @PathParam("pod") pod: String,
    @PathParam("mediaId") mediaId: String,
  ): Response {
    val credentials = authenticate(pod)

    // Visibility first, preconditions after — the same ordering rule the LOD layer states: a
    // conditional request from a caller who may not see the object must not short-circuit to 304
    // and confirm both its existence and its content hash.
    val podMedia = readableOrThrow(credentials, mediaId)

    // The type is a *claim*, made per assignment, so the answer is the claim made in a context this
    // caller can read — not the first uploader's, which they may not be allowed to know about.
    // Resolved before the validator, because it is part of what the validator identifies.
    val contentType = describedBy(podMedia, credentials).contentType

    val entityTag = representationTag(podMedia.mediaId, contentType)
    evaluatePreconditions(entityTag)?.let {
      // The `304` repeats the policy rather than relying on the stored one: a cache updates its
      // held headers from this response, and one that arrived without the directive would be free
      // to fall back on heuristic freshness for the next round.
      return Response.fromResponse(it)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
        .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
        .build()
    }

    val streamingOutput = StreamingOutput { out ->
      // A row whose object is missing is a real state: a crash between the two writes, or the
      // narrow sweep window `PodMediaFacade.sweepUnreferenced` documents. Left to the generic
      // mapper it becomes a `500` whose body is the exception message — and for the filesystem
      // store that message **is the absolute path of the media root**, handed to anyone who may
      // read this media. The status is right and stays; only the disclosure goes.
      //
      // `Exception`, not `IOException`: what a store throws for "not there" is its own business
      // (the filesystem one throws `NoSuchFileException`, an object store will throw something of
      // its own), and this is strictly better than the alternative in every case — the same `500`
      // either way, with the detail in the log instead of in the response.
      val input = try {
        mediaFacade.open(podMedia)
      } catch (e: Exception) {
        logger.error(e) { "Media ${podMedia.mediaId} of pod $pod has a registry row but no object" }
        throw WebApplicationException(
          Response.status(500).entity("media content is unavailable").type(MediaType.TEXT_PLAIN).build(),
        )
      }
      // Outside the catch on purpose: a failure from here on is the copy, not the lookup — a client
      // that hung up mid-download is not an error worth a stack trace, and the response is
      // committed by then anyway.
      input.use { it.copyTo(out) }
    }
    return Response.ok(streamingOutput, contentType)
      .tag(entityTag)
      .header(HttpHeaders.CONTENT_LENGTH, podMedia.size)
      .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
      .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
      .header(CONTENT_TYPE_OPTIONS, "nosniff")
      .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(contentType))
      // Second layer under `nosniff`: even if a browser were talked into rendering one of these,
      // a sandboxed document has no access to the pod's origin.
      .header(CONTENT_SECURITY_POLICY, "sandbox")
      .build()
  }

  @HEAD
  @Path("{mediaId}/content")
  @Produces(MediaType.WILDCARD)
  fun headContent(
    @PathParam("pod") pod: String,
    @PathParam("mediaId") mediaId: String,
  ): Response = content(pod, mediaId)

  /**
   * Assign an existing media to another context — one upload, visible in a second place, one copy
   * of the bytes.
   *
   * **Two permissions, and the second one is the point:** write on the target context *and* read on
   * a context the media already carries. Without the read check, anyone holding write anywhere
   * could attach any media id to their own context and read it back from there.
   *
   * Idempotent: assigning a context the media already has answers `204` and changes nothing.
   */
  @PUT
  @Path("{mediaId}")
  fun assign(
    @PathParam("pod") pod: String,
    @PathParam("mediaId") mediaId: String,
    @QueryParam("context") contextParams: List<String>?,
  ): Response {
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val contextUri = contextWriteAuthorizer.resolveSingleWriteContextOrThrow(pod, contextParams)
    contextWriteAuthorizer.authorizeWriteOrThrow(credentials, contextUri)

    // `404` rather than `403` when the caller may not read it, deliberately and consistently with
    // the read routes: the id is a content hash, so telling the two apart answers "does this pod
    // hold exactly these bytes" for anyone who has them.
    val podMedia = readableOrThrow(credentials, mediaId)

    // Same re-check as the upload path, for the same reason and with a much smaller window: nothing
    // here is slow, but the read above is still a round trip, and the rule belongs on every path
    // that creates an assignment rather than only on the one where it is easy to hit.
    contextWriteAuthorizer.requireContextStillRegisteredOrThrow(credentials.pod.name, contextUri)

    mediaFacade.assign(
      podId = podId(credentials),
      mediaId = mediaId,
      context = contextUri,
      // Copy the description this caller can actually see. They hold read on one assignment by the
      // rule above, and that is the only one they may propagate.
      describedBy = describedBy(podMedia, credentials),
      createdBy = credentials.tokenSub ?: credentials.oauthClientId,
    )
    logMediaAudit("assigned", pod, mediaId, contextUri, credentials)
    return Response.status(204).build()
  }

  /**
   * Drop one context assignment. When it was the last, the row is stamped unreferenced and the
   * bytes become a sweep candidate — they are **not** deleted here, which is the grace period the
   * whole design rests on.
   *
   * Write on the named context is the whole check: you are removing the media from *your* context,
   * and needing to read it elsewhere first would make cleaning up after a revoked grant impossible.
   *
   * **Always `204` — never `404`, not even for a media id this pod has never seen.** The obvious
   * shape, reporting `404` when nothing was removed, turns this route into an existence oracle:
   * hold write on any one context, send the hash of a file you have, and `204` versus `404` answers
   * whether the pod holds it — including when it lives only in contexts you cannot read. That is
   * exactly the question the read routes' uniform `404` refuses, and it would be a strange place to
   * give the answer away.
   *
   * So the operation is *ensure this context does not reference this media*, which is satisfied by
   * doing nothing when there was nothing. The price is that a typo in the id succeeds silently; for
   * an idempotent ensure-absent that is the ordinary trade, and the alternative is the oracle.
   */
  @DELETE
  @Path("{mediaId}")
  fun unassign(
    @PathParam("pod") pod: String,
    @PathParam("mediaId") mediaId: String,
    @QueryParam("context") contextParams: List<String>?,
  ): Response {
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val contextUri = contextWriteAuthorizer.resolveSingleWriteContextOrThrow(pod, contextParams)
    contextWriteAuthorizer.authorizeWriteOrThrow(credentials, contextUri)

    // The boolean says whether a row matched; it is deliberately not turned into a status — see the
    // comment above. It still distinguishes the two cases in the audit line, where the reader is an
    // operator rather than the caller.
    val matched = mediaFacade.unassign(podId(credentials), mediaId, contextUri)
    logMediaAudit(if (matched) "unassigned" else "unassigned_noop", pod, mediaId, contextUri, credentials)
    return Response.status(204).build()
  }

  /**
   * Copies the body into a temporary file, refusing at [PodMediaConfig.maxUploadBytes] **while
   * reading** rather than trusting `Content-Length`, which is optional and forgeable.
   *
   * A file rather than memory because the hash has to be computed before the id — and therefore the
   * storage location — is known, so the bytes are read twice either way.
   */
  private fun bufferOrThrow(body: InputStream): FilePath {
    val buffered = Files.createTempFile("sempods-media-", ".upload")
    try {
      val copied = Files.newOutputStream(buffered).use { out ->
        body.copyToLimited(out, mediaConfig.maxUploadBytes)
      }
      if (copied == null) {
        throw WebApplicationException(
          Response.status(413)
            .entity("media exceeds the maximum of ${mediaConfig.maxUploadBytes} bytes")
            .type(MediaType.TEXT_PLAIN)
            .build(),
        )
      }
      if (copied == 0L) throw badRequest("refusing to store an empty media object")
      return buffered
    } catch (e: Throwable) {
      Files.deleteIfExists(buffered)
      throw e
    }
  }

  /**
   * Copies at most [limit] bytes and returns how many; `null` as soon as the source has more, so
   * the caller can refuse without having read the rest of an arbitrarily large body.
   */
  private fun InputStream.copyToLimited(out: java.io.OutputStream, limit: Long): Long? {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
      val read = read(buffer)
      if (read == -1) return total
      total += read
      if (total > limit) return null
      out.write(buffer, 0, read)
    }
  }

  private fun readableOrThrow(credentials: SempodsCredentials, mediaId: String): PodMedia =
    mediaFacade.findReadable(podId(credentials), mediaId, credentials.restrictedContexts) ?: throw notFound()

  private fun podId(credentials: SempodsCredentials): ObjectId =
    checkNotNull(podFacade.getPodId(credentials.pod.name))

  private fun contentUri(pod: String, mediaId: String): URI =
    sempodsUriBuilder.buildMediaContentUri(pod, mediaId)

  /**
   * `inline` only for image types a browser cannot be talked into executing, `attachment` for
   * everything else.
   *
   * Unconditional `attachment` would be simpler and would break every consumer that shows a
   * picture: an image URL points at the content route and the browser has to *render* it.
   * Unconditional
   * `inline` would be worse — an uploaded SVG carries script and would run in the pod's own origin,
   * with same-origin access to whatever else the pod serves there. So the allowlist is explicit,
   * and anything not on it, including an unknown type, downloads.
   */
  private fun contentDisposition(contentType: String): String =
    if (baseTypeOf(contentType) in INLINE_CONTENT_TYPES) "inline" else "attachment"

  /**
   * The assignment this caller sees the media through — its description, in their terms.
   *
   * A caller may hold several; the one with the lowest context URI is picked so the answer is
   * stable across requests rather than however the set happened to iterate. That it is *a* readable
   * one is what matters: [readableOrThrow] has already established there is at least one, so the
   * `error` below is a broken invariant, not a request outcome.
   */
  /**
   * The strong validator for what this URL currently answers with: the content hash, plus a digest
   * of the selected media type.
   *
   * Not `BaseEndpoint.createContentTypeAwareEntityTag`, deliberately. That one maps the three RDF
   * types it was written for onto short names and otherwise strips non-alphanumerics and
   * **truncates to ten characters** — fine for a fixed, known set, and a collision for the
   * arbitrary types uploaded here (`application/x-foo` and `application/x-bar` both become
   * `applicatio`). Two types sharing a validator is exactly the staleness this method exists to
   * prevent, so the type is hashed in full instead.
   */
  private fun representationTag(mediaId: String, contentType: String): EntityTag =
    EntityTag("$mediaId.${HashUtil.sha256Hex(contentType).take(TYPE_DIGEST_LENGTH)}")

  private fun describedBy(podMedia: PodMedia, credentials: SempodsCredentials): MediaAssignment =
    podMedia.readableBy(credentials.restrictedContexts).minByOrNull { it.context }
      ?: error("readable media ${podMedia.mediaId} has no readable assignment")

  /**
   * **`contexts` is filtered to what [readableContexts] covers**, and that is not cosmetic: a media
   * assigned to a private context *and* a public one is readable by anyone, and reporting the whole
   * assignment set would hand every anonymous caller the name of the private context — which is a
   * path inside the pod's `_system/contexts` tree and says what it is for. Readability is decided
   * by intersection, so the answer has to be reported by intersection too.
   *
   * `null` means unrestricted, matching `SempodsCredentials.restrictedContexts`.
   */
  private fun PodMedia.toResponse(pod: String, readableContexts: Set<URI>?) = PodMediaResponse(
    id = mediaId,
    contentUrl = contentUri(pod, mediaId).toString(),
    size = size,
    assignments = readableBy(readableContexts).sortedBy { it.context }.map {
      MediaAssignmentResponse(
        context = it.context,
        contentType = it.contentType,
        filename = it.filename,
        createdAt = it.createdAt.toString(),
      )
    },
  )

  private fun badRequest(message: String) = WebApplicationException(
    Response.status(400).entity(message).type(MediaType.TEXT_PLAIN).build(),
  )

  private fun notFound() = WebApplicationException(Response.status(404).build())

  /**
   * Audit line for media writes, alongside `[lod/audit]` and `[slot/audit]` so ops can split them
   * out of one log stream with a grep. Reads are not audited — they are the common case and the
   * pod's other read paths are not either.
   */
  private fun logMediaAudit(
    outcome: String,
    pod: String,
    mediaId: String,
    contextUri: URI,
    credentials: SempodsCredentials,
  ) {
    logger.info {
      "[media/audit] outcome=$outcome pod='$pod' media='$mediaId' context='$contextUri' " +
          "client_id='${credentials.oauthClientId ?: "(anon)"}'"
    }
  }

  companion object {

    private val logger = KotlinLogging.logger {}

    private const val CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
    private const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"

    /**
     * The most a source descriptor may weigh. A URL and a filename; 8 KiB is room for a signed link
     * several times over and still nowhere near a media.
     */
    private const val MAX_DESCRIPTOR_BYTES = 8 * 1024

    /**
     * How much of the content type's digest goes into the entity tag. Twelve hex characters is 48
     * bits — the tag only has to separate the handful of types one media is claimed under, not
     * resist an attacker, and a validator is not a secret.
     */
    private const val TYPE_DIGEST_LENGTH = 12

    /**
     * Raster image types a browser renders without a scripting context. Deliberately a list of
     * exact types rather than a prefix test on `image/`: `image/svg+xml` passes such a test and is
     * a document that can carry script.
     */
    private val INLINE_CONTENT_TYPES = setOf(
      "image/png",
      "image/jpeg",
      "image/gif",
      "image/webp",
      "image/avif",
    )
  }
}

/**
 * What a copy-from-URL `POST` sends: where to get the bytes, and what to call them.
 *
 * No `content_type` field, deliberately. The source's own `Content-Type` is the claim that gets
 * recorded, and letting the descriptor override it would let a caller have the pod serve an SVG as
 * `image/png` — past the `Content-Disposition` allowlist and into the pod's own origin.
 *
 * `filename` is the caller's, though: it describes *their* assignment and never decides how anything
 * is served.
 */
data class MediaSourceRequest(
  @JsonProperty("source_url") val sourceUrl: String? = null,
  @JsonProperty("filename") val filename: String? = null,
)

/**
 * What `POST` answers: where the object is, and nothing about it.
 *
 * Deliberately not [PodMediaResponse]. On a dedup hit the stored row is the first uploader's, so
 * every descriptive field would report *their* value and thereby announce that the bytes were
 * already in the pod — which is what the uniform `201` exists to avoid saying.
 */
data class PodMediaUploadResponse(
  val id: String,

  @field:JsonProperty("content_url")
  val contentUrl: String,
)

/**
 * What `GET {id}` answers.
 *
 * Only `size` sits at the top: it is settled by the bytes, so it cannot differ between one reader
 * and another. Everything descriptive is **per assignment**, and only the assignments this caller
 * may read are listed — see [org.sempods.pods.media.persist.MediaAssignment] for why the
 * description belongs there rather than to the object.
 */
data class PodMediaResponse(
  val id: String,

  @field:JsonProperty("content_url")
  val contentUrl: String,

  val size: Long,

  val assignments: List<MediaAssignmentResponse>,
)

data class MediaAssignmentResponse(
  val context: String,

  @field:JsonProperty("content_type")
  val contentType: String,

  @field:[JsonProperty JsonInclude(JsonInclude.Include.NON_EMPTY)]
  val filename: String?,

  @field:JsonProperty("created_at")
  val createdAt: String,
)
