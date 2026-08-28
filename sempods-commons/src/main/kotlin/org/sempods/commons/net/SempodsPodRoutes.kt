package org.sempods.commons.net

import org.sempods.commons.utils.UriEncodingUtil
import java.net.URI

/**
 * The paths of a pod's HTTP surface, relative to the pod's base URL — one definition for every
 * client that dials one.
 *
 * **Why this is in `commons` and not with the pod contract.** Two layers of `:sempods-client` speak
 * these routes from different worlds — `PodWireClient` in JSON-LD with ETags, `SempodsClient` in
 * RDF4J `Model` over n-quads (see `docs/pod-client.md` §"Two representations, three
 * bindings") — and route knowledge is what they must not spell differently. It sits in `commons`
 * rather than beside either because `commons` is the module every consumer already has, and a
 * definition in `:sempods-model` would pull RDF4J toward services that hold no RDF. Same shape as
 * [org.sempods.commons.identity.WebIdUriDeriver]: a formula two modules must agree on, defined
 * once so they cannot drift apart quietly.
 *
 * **Paths only — no HTTP, no query parameters, no coroutines.** A caller appends its own
 * `?context=…` and picks its own transport; every value here is relative and carries no leading
 * slash, so it composes with both `URI.resolve` and plain concatenation onto a trimmed base.
 *
 * The routes themselves are specified in sempods-spec `spec/core/lod-crud.md`, `docs/media.md` and
 * `docs/auth/`; this object is where a client reads them, not where they are decided.
 */
object SempodsPodRoutes {

  /**
   * Where context IRIs live, relative to the pod root. Inside `_system` because contexts are
   * control-plane state rather than pod content, and identity and management route are the same
   * string — a context IRI *is* the URL that manages it.
   *
   * `org.sempods.SempodsUriBuilder.CONTEXT_PATH_PREFIX` re-exports this for the modules that mint
   * context IRIs; the string is defined here.
   */
  const val CONTEXT_PATH_PREFIX = "_system/contexts/"

  /** Where media routes live, relative to the pod root — the counterpart of [CONTEXT_PATH_PREFIX]. */
  const val MEDIA_PATH_PREFIX = "_system/media/"

  /** `GET {pod}/_system/contexts` — the contexts the bearer may see. */
  const val CONTEXTS = "_system/contexts"

  /** `POST {pod}/_system/sparql/query` — read-only SPARQL, scoped by the bearer. */
  const val SPARQL_QUERY = "_system/sparql/query"

  /** `POST {pod}/_system/find` — a context-sandboxed subgraph for a text query. */
  const val FIND = "_system/find"

  /** `POST {pod}/_system/media` — the media collection. */
  const val MEDIA = "_system/media"

  /** `GET {pod}/_system/meta/date-modified` — the pod's last write, served anonymously. */
  const val META_DATE_MODIFIED = "_system/meta/date-modified"

  /** `POST {pod}/_system/auth/token` — the pod's token endpoint. */
  const val AUTH_TOKEN = "_system/auth/token"

  /**
   * `GET {pod}/_system/auth/oidc/callback` — where the id-server sends the browser back.
   *
   * Named here because the login-pin cookie is scoped to exactly this path, and a cookie path that
   * drifts from the route it guards fails open: the browser simply stops sending it, and the
   * callback then refuses every login.
   */
  const val AUTH_OIDC_CALLBACK = "_system/auth/oidc/callback"

  private const val RESOURCES = "_system/resources"

  /** A context's management route, from its path below [CONTEXT_PATH_PREFIX]. */
  fun context(relativeContextPath: String): String = "$CONTEXT_PATH_PREFIX$relativeContextPath"

  /**
   * The System-layer route of one resource: `_system/resources/{b64url(iri)}`.
   *
   * Base64url (RFC 4648 §5, no padding) so the subject may be **any** IRI, including one outside
   * the pod — which is what separates the System layer from the LOD resource path.
   */
  fun resource(resourceIri: URI): String = "$RESOURCES/${segment(resourceIri)}"

  /** One slot — all values of `(subject, predicate)`: `_system/resources/{b64url}/{b64url}`. */
  fun slot(subjectIri: URI, predicateIri: URI): String =
    "$RESOURCES/${segment(subjectIri)}/${segment(predicateIri)}"

  /** One value inside a slot, addressed for idempotent removal. */
  fun slotValue(subjectIri: URI, predicateIri: URI, targetIri: URI): String =
    "${slot(subjectIri, predicateIri)}/${segment(targetIri)}"

  private fun segment(iri: URI): String = UriEncodingUtil.encodeUriToUrlSafeBase64(iri)
}
