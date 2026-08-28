package org.sempods

import org.sempods.commons.net.SempodsPodRoutes
import org.sempods.commons.utils.extract
import java.net.URI
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Mints and reads the URIs of a sempods deployment, given the address that deployment is known by.
 *
 * **The base URL is always stated, never guessed.** There used to be a second, injected
 * constructor that picked one out of the registered `Set<AppConfig>` with a `singleOrNull`
 * fallback: right for a process that *is* the pod server, silently wrong for every other — and
 * the URIs minted here are persisted (resource subjects, named graphs, media references), so
 * "silently wrong" meant wrong data rather than a failed request. It was also this class's only
 * tie to an application framework, and with it gone the whole module is framework-free. Each caller
 * now supplies its
 * own configured address: the server its `SEMPODS_PUBLIC_BASE_URL`, a client the address it
 * reaches the server at.
 */
class SempodsUriBuilder {

  private val baseUrl: String

  /**
   * Takes the trailing slash either way — callers configure the base as a URL
   * (`https://sempods.org/`) while every builder here appends `/`-prefixed segments.
   */
  constructor(baseUrl: String) {
    this.baseUrl = baseUrl.trimEnd('/')
    logger.info { "using following sempods base url: ${this.baseUrl}" }
  }

  constructor(baseUrl: URI) : this(baseUrl.toString())

  fun parsePodName(uri: URI): String? {
    return uri.toString().extract(this.baseUrl + "/", "/")
  }

  /**
   * The pod's own URI — its global identifier in a decentralized sempods world, and the prefix
   * every resource / context / view URI hangs off (e.g. `https://sempods.org/alice`). Canonical
   * form has **no trailing slash**, matching how all resource URIs are minted and the inverse of
   * [parsePodName]. Distinct from the OAuth issuer / RFC 9728 resource base used in the auth layer.
   */
  fun buildPodUri(podName: String): URI {
    return URI.create("$baseUrl/$podName")
  }

  fun buildResourceUri(podName: String, resourcePath: String): URI {
    val normalizedPath = resourcePath.trimStart('/')
    return URI.create("${buildPodUri(podName)}/$normalizedPath")
  }

  fun buildRelativeAppContext(appId: String, appContext: String): String {
    return "apps/$appId/$appContext"
  }

  fun buildAppContext(podName: String, appId: String, appContext: String): URI {
    return buildContext(podName, buildRelativeAppContext(appId, appContext))
  }

  /**
   * Canonical context IRI for a pod-relative context path (`apps/notes/public` →
   * `<pod>/_system/contexts/apps/notes/public`).
   *
   * Contexts live inside the reserved `_system` area because they are control-plane state, not
   * data: they are created by a control API, they carry permissions, and their IRI appears in
   * every scope string and in the named-graph position of every quad (`docs/vision.md` §5). The
   * prefix is set here and nowhere else — [CONTEXT_PATH_PREFIX] is the single definition, so the
   * management route and the client-side decomposition cannot drift from it.
   *
   * The relative part is unchanged by this: callers that hold `apps/notes/connectors/<id>` keep
   * working, which is why no consumer needs a data migration of its own.
   */
  fun buildContext(pod: String, relativeContext: String): URI {
    return URI.create("${buildPodUri(pod)}/$CONTEXT_PATH_PREFIX$relativeContext")
  }

  /**
   * Where a media object's **bytes** are served — the URL that belongs in a resource's
   * `schema:contentUrl`.
   *
   * Always the pod's own address, never the storage backend's. That is the load-bearing property of
   * the media design: the pod server decides at request time whether to stream the bytes or (later)
   * redirect to a CDN, and neither decision touches data that was already written. A delivery URL
   * in the graph would have pinned one answer for as long as the triple lives.
   *
   * `_system` because the media registry is control-plane state, the same reason contexts live
   * there — see [CONTEXT_PATH_PREFIX].
   */
  fun buildMediaContentUri(podName: String, mediaId: String): URI =
    URI.create("${buildPodUri(podName)}/$MEDIA_PATH_PREFIX$mediaId/content")

  /**
   * The media id inside one of [podName]'s **media content** URIs — the inverse of
   * [buildMediaContentUri], i.e. `{pod}/_system/media/{id}/content`. `null` for anything else.
   *
   * **The one place a media id can be read out of data**, and the reason there is only one: an
   * image *resource* URI used to carry it too, back when a picture was named after its bytes.
   * Naming a picture after its source instead (`GCalMigrator.attachmentImageId`) left that parser
   * answering with something that was not a media id, so it was removed rather than left to be
   * called. A caller holding a `schema:ImageObject` reads `schema:contentUrl` and asks here.
   *
   * **Structural only.** A non-null answer says the URI is shaped like one of this pod's media
   * content URIs, not that the pod holds those bytes — the pod answers that, and it is the only
   * thing that can.
   *
   * Takes [podName] because [buildMediaContentUri] does. Without it another pod's URI would yield
   * an id that a caller would then use against *this* pod.
   */
  fun parseMediaIdFromMediaContentUri(podName: String, uri: URI): String? =
    segmentBetween(uri, prefix = "${buildPodUri(podName)}/$MEDIA_PATH_PREFIX", suffix = "/content")

  /**
   * The single path segment between [prefix] and [suffix], or `null` when [uri] does not have that
   * shape.
   *
   * "Single segment" is the whole check that keeps these parsers from over-accepting: a `/` in the
   * result would mean the URI went on past the id, and a `?` or `#` would mean the id ran into a
   * query or fragment. None of those are URIs this builder mints, so each is a `null` rather than an
   * id with something stuck to it.
   */
  private fun segmentBetween(uri: URI, prefix: String, suffix: String): String? {
    val value = uri.toString()
    if (value.length <= prefix.length + suffix.length) return null
    if (!value.startsWith(prefix) || !value.endsWith(suffix)) return null
    val segment = value.substring(prefix.length, value.length - suffix.length)
    return segment.takeIf { candidate -> candidate.none { it == '/' || it == '?' || it == '#' } }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    /**
     * Where media routes live, relative to the pod root — the counterpart of [CONTEXT_PATH_PREFIX]
     * for `PodMediaEndpoint`.
     *
     * Not to be confused with `ImageViewDefinition.name`, which is also `media` and yields
     * `{pod}/media/{id}` on the *LOD* layer. The two do not collide — different layers, different
     * prefixes — but they read alike, so anything parsing one must not accept the other.
     *
     * Re-exported from [SempodsPodRoutes], where the pod's routes are defined once for every
     * client that dials one — a pod-owned URI and the route that serves it are the same string,
     * and two definitions of it would be two chances to disagree.
     */
    const val MEDIA_PATH_PREFIX = SempodsPodRoutes.MEDIA_PATH_PREFIX

    /**
     * Where context IRIs live, relative to the pod root. Inside `_system` so contexts inherit the
     * control-plane protection rather than sitting in the freely writable resource namespace —
     * sempods-spec `spec/core/contexts.md` §2 has the reasoning.
     *
     * Single definition on purpose: `PodContextsEndpoint` (management route),
     * `PodAuthEndpoint` (consent dialog) and `SempodsClient.contextManagementUrl` all derive from
     * it, so the route that addresses a context and the IRI that identifies it cannot drift apart.
     *
     * Re-exported from [SempodsPodRoutes] for the same reason as [MEDIA_PATH_PREFIX]; this is the
     * name the modules that mint context IRIs use, that is where the string lives.
     */
    const val CONTEXT_PATH_PREFIX = SempodsPodRoutes.CONTEXT_PATH_PREFIX

    private val POD_NAME_REGEX = "^[a-z0-9]+(?:-[a-z0-9]+)*$".toRegex()

    /**
     * Rejects [podName] unless it can be the first path segment of a pod URI: lowercase
     * alphanumerics in hyphen-separated groups, 4 to 24 characters.
     *
     * Lives next to [CONTEXT_PATH_PREFIX] and for the same reason — a pod name is a URI component,
     * so the rule that constrains it belongs where the URIs are minted rather than in whichever
     * store happens to persist one. Both the server (before inserting a pod) and app backends
     * (before asking for one) check it, and they have to agree.
     */
    fun checkPodName(podName: String) {
      check(podName.matches(POD_NAME_REGEX) && podName.length in 4..24) {
        throw IllegalArgumentException("Invalid pod name '$podName'")
      }
    }
  }
}
