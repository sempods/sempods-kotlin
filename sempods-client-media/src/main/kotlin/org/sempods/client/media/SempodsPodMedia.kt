package org.sempods.client.media

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import org.sempods.client.SempodsContent
import org.sempods.client.SempodsContentSource
import org.sempods.client.SempodsExchange
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsRepeatable
import org.sempods.client.SempodsResponse
import org.sempods.client.SempodsSession
import org.sempods.commons.net.SempodsPodRoutes
import org.sempods.media.PodMediaSource
import org.sempods.media.UploadedMedia
import java.io.IOException

/**
 * A pod's media: bytes in, and which contexts a stored media is reachable through.
 *
 * ```java
 * var media = new SempodsPodMedia(pod);
 * UploadedMedia stored = media.upload(tasks, "image/png", () -> Files.newInputStream(png), Files.size(png))
 *     .getBody();
 * media.assign(stored.getMediaId(), notes);
 * ```
 *
 * **Nothing here writes a triple.** Whoever wants a `schema:ImageObject` writes it themselves and
 * points its `schema:contentUrl` at [UploadedMedia.contentUrl], so the registry and the graph stay
 * unaware of each other and there is no synchronisation path to drift.
 *
 * **Reads are deliberately absent.** A media is fetched by dereferencing its content URL like any
 * other web resource — that is the point of the pod putting its own address there — so a `download`
 * here would be a second way to do what an `<img>` tag already does.
 *
 * **A pod that configures no media backend serves none of these routes** and answers `404` for all of
 * them. That is not softened into a no-op anywhere below: a pod serving no media at all is a
 * different situation from one with nothing to do, and only the first is a misconfiguration.
 *
 * **An upload answered `200` means the pod is not conformant, and it is still an answer.** Both
 * [upload] and [uploadFromUrl] take it.
 * [`SPS-MEDIA-011`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/media.md#SPS-MEDIA-011)
 * requires `201` whether or not the bytes were already stored, so a pod answering `200` has broken
 * it — and has stored the media all the same. Why such a status is listed rather than refused is
 * `docs/pod-client.md` §"Endpoint groups"; [SempodsResponse.status] is where a caller who wants to
 * notice looks.
 *
 * **A media id is one path segment**, so [assign] and [unassign] refuse an id
 * [SempodsSession.newRequest] cannot take as one — `..` among them — with an
 * [IllegalArgumentException], and nothing is sent.
 *
 * Every call runs on [pod]'s session, so it carries that credential and its recovery, the admission
 * budget, the outbound guard and the call's deadline, and `Call.cancel()` reaches it.
 */
class SempodsPodMedia(pod: SempodsPod) {

  private val session = pod.session

  private val exchange = SempodsExchange(pod.calls)

  /**
   * Stores what [source] yields and assigns it to [contextUri]: `POST {pod}/_system/media?context=…`,
   * answered `201` with the id and the URL the bytes are served from — and see this class for the
   * `200` a pod that broke the requirement sends instead.
   *
   * **[source] is opened once per attempt**, which is what lets an upload be resent after a connection
   * lost before any answer. Handed a stream that can be read only once, a second attempt would write
   * what is left of it, and the pod would store a truncated object and answer `201` for it
   * ([SempodsContent]).
   *
   * The request is marked [SempodsRepeatable], because a `POST` is not resent on its method alone and
   * this one may be: the id is the content hash, so the bytes arriving twice are one object either
   * way. [uploadFromUrl] carries no such mark — see its own note.
   *
   * [length] is the exact number of bytes, or `-1` when the caller does not know: it decides the
   * framing rather than what the pod accepts, which a pod enforces while reading. [filename] describes
   * *this* assignment and never decides how anything is served.
   *
   * The id is the content hash, so this is idempotent per pod: the same bytes uploaded twice are one
   * object with two context assignments, and the second upload answers with the first one's id.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun upload(
    contextUri: String,
    contentType: String,
    source: SempodsContentSource,
    length: Long = -1,
    filename: String? = null,
  ): SempodsResponse<UploadedMedia> =
    stored(
      SempodsRepeatable.mark(
        collection(contextUri, filename)
          .post(SempodsContent.of(source, length).requestBody(contentType.toMediaType())),
      ).build(),
    )

  /**
   * Has the pod fetch the bytes itself: the same route carrying a source descriptor instead of a body.
   *
   * **The descriptor goes in the body under its own media type**, and both halves matter. A source URL
   * is routinely a credential in itself — a signed Drive or S3 link — so a query parameter would land
   * in every access log on the way; and the upload route consumes the wildcard, so only a distinct
   * media type separates an instruction from a JSON document somebody meant to store.
   *
   * **Not marked [SempodsRepeatable]**, unlike [upload]. A connection lost before any answer leaves it
   * unknown whether the pod already fetched, and the fetch is the pod's to make, not this client's to
   * repeat: a source URL good for one use answers the second attempt with a failure for a media that
   * is by then already stored.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun uploadFromUrl(
    contextUri: String,
    sourceUrl: String,
    filename: String? = null,
  ): SempodsResponse<UploadedMedia> =
    stored(
      // No `?filename=`: the descriptor carries it, and the route reads it from there.
      collection(contextUri, filename = null)
        .post(
          SempodsContent.of(MediaJson.sourceDescriptor(sourceUrl, filename))
            .requestBody(PodMediaSource.MEDIA_TYPE.toMediaType()),
        )
        .build(),
    )

  /**
   * Makes the media [mediaId] reachable through [contextUri] as well: `PUT
   * {pod}/_system/media/{id}?context=…`, idempotent and `204` either way.
   *
   * A `404` is a refusal rather than nothing to do — the route answers it when the caller may not
   * *read* the media — so it is not listed here and arrives as a failure.
   */
  @Throws(IOException::class)
  fun assign(mediaId: String, contextUri: String): SempodsResponse<ByteArray> =
    exchange.bytes(one("PUT", mediaId, contextUri), 200, 204)

  /**
   * Ensures [contextUri] does not reach the media [mediaId]: `DELETE
   * {pod}/_system/media/{id}?context=…`.
   *
   * The route answers `204` even for a media the pod has never seen, so there is no 404-as-success
   * case here: a `404` from this URL means the pod serves no media surface at all, and reading that as
   * "removed" would hide a deployment holding assignments nothing can reach.
   */
  @Throws(IOException::class)
  fun unassign(mediaId: String, contextUri: String): SempodsResponse<ByteArray> =
    exchange.bytes(one("DELETE", mediaId, contextUri), 200, 204)

  /**
   * Both upload paths end here.
   *
   * `content_url` is read rather than rebuilt from the id: the pod knows the address it is published
   * at, this client knows only the one it dialled, and the value ends up in a persisted
   * `schema:contentUrl`. An app backend reaching a pod at an internal address would otherwise publish
   * a URL nobody outside can resolve.
   */
  private fun stored(request: Request): SempodsResponse<UploadedMedia> =
    exchange.text(request, 200, 201).map { MediaJson.uploaded(it) }

  private fun collection(contextUri: String, filename: String?): Request.Builder =
    withQuery(session.newRequest("POST", SempodsPodRoutes.MEDIA), "context" to contextUri, "filename" to filename)

  private fun one(method: String, mediaId: String, contextUri: String): Request =
    withQuery(session.newRequest(method, SempodsPodRoutes.MEDIA, mediaId), "context" to contextUri).build()

  /** [request] with the query a call carries; a `null` value leaves its parameter out. */
  private fun withQuery(request: Request.Builder, vararg query: Pair<String, String?>): Request.Builder {
    val built = request.build()
    val url = built.url.newBuilder().apply {
      query.forEach { (name, value) -> value?.let { addQueryParameter(name, it) } }
    }.build()
    return built.newBuilder().url(url)
  }
}
