package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * OAuth discovery documents for the pod.
 *
 * Identifier layout:
 *
 *  - `resource` (RFC 9728) = `{pod}` — the pod is the unit of access control.
 *  - `issuer`   (RFC 8414) = `{pod}/_system/auth` — keeps discovery and the AS endpoints in one
 *    subtree. The append-style `oauth-authorization-server` well-known path therefore lives on
 *    [PodAuthEndpoint] (which owns `{pod}/_system/auth`); JAX-RS routes sub-paths of a class to
 *    that class only, so it has to sit there.
 *
 * Routes we serve (and why):
 *
 *  1. `{pod}/.well-known/oauth-protected-resource` — append-style PRM. Canonical target of the
 *     `WWW-Authenticate: …, resource_metadata=…` hint returned on 401. Claude Code follows this.
 *  2. `{pod}/_system/auth/.well-known/oauth-authorization-server` — append-style AS-metadata
 *     (on [PodAuthEndpoint]). Canonical target of `authorization_servers[0]` in the PRM body.
 *  3. `/.well-known/oauth-authorization-server/{pod}/_system/auth` — RFC-8414-strict AS-metadata
 *     for the pod's issuer. claude.ai Web reads `authorization_servers[0]` from the PRM and then
 *     probes this host-rooted path; the append-style variant at (2) is not enough for it.
 *  4. `/.well-known/oauth-protected-resource/{pod}` — RFC-9728-strict PRM for the pod resource.
 *     Browser SPAs that drive RFC-9728 discovery through `oauth4webapi` (and any client that
 *     follows the §3.1 host-rooted form) probe this first. Body is identical to (1).
 *  5. `/.well-known/oauth-protected-resource/{pod}/_system/mcp` — MCP 2025-11-25 clients
 *     (claude.ai Web) treat the MCP URL itself as the protected-resource identifier and
 *     proactively probe the RFC-9728-strict path with that identifier. Body is identical to
 *     the pod-level variants — the pod remains the unit of access control. There is no
 *     `oauth-authorization-server` counterpart: the MCP URL is not an issuer identifier, and
 *     RFC 8414 §3.3 requires the served `issuer` to match the URL it was fetched from.
 *  6. `{pod}/_system/mcp/.well-known/oauth-protected-resource` — the MCP-URL append-style
 *     counterpart (on [org.sempods.api.pod.system.mcp.McpEndpoint]), kept alongside the strict
 *     form because the exact claude.ai probe set is not fully documented.
 *
 * `scopes_supported` is intentionally omitted — the scope space is per-context and partially
 * synthesised by apps, so the consent dialog is the single source of truth for grantable scopes.
 */
@Path("{pod}")
class PodOAuthMetadataEndpoint @Inject constructor(
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  @GET
  @Path(".well-known/oauth-protected-resource")
  @Produces(MediaType.APPLICATION_JSON)
  fun protectedResourceMetadata(@PathParam("pod") pod: String): Response {
    val podDbo = fetchPodOrThrow(pod)
    return buildProtectedResourceMetadata(
      podDbo, config.apiBaseUrl,
      publicContextsCount = podFacade.getPublicContexts(podName = podDbo.name).size,
    )
  }
}

/**
 * Host-rooted (RFC-strict) OAuth discovery for MCP 2025-11-25 clients, which use the MCP URL
 * as the protected-resource identifier and insert the well-known suffix between host and the
 * identifier's path.
 */
@Path(".well-known")
class RootOAuthMetadataEndpoint @Inject constructor(
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  @GET
  @Path("oauth-authorization-server/{pod}/_system/auth")
  @Produces(MediaType.APPLICATION_JSON)
  fun authorizationServerMetadataForPodIssuer(@PathParam("pod") pod: String): Response =
    buildAuthorizationServerMetadata(fetchPodOrThrow(pod), config.apiBaseUrl)

  // RFC-9728-strict host-rooted PRM for the pod resource itself (no MCP suffix). Body is the
  // same as the append-style PRM at `{pod}/.well-known/oauth-protected-resource`; this just
  // satisfies the §3.1 "insert /.well-known/<…> between authority and path" probe form that
  // browser SPAs and oauth4webapi-based clients use.
  @GET
  @Path("oauth-protected-resource/{pod}")
  @Produces(MediaType.APPLICATION_JSON)
  fun protectedResourceMetadataForPodResource(@PathParam("pod") pod: String): Response {
    val podDbo = fetchPodOrThrow(pod)
    return buildProtectedResourceMetadata(
      podDbo, config.apiBaseUrl,
      publicContextsCount = podFacade.getPublicContexts(podName = podDbo.name).size,
    )
  }

  // RFC-strict host-rooted PRM for the MCP URL. Clients that treat the MCP URL as the
  // protected-resource identifier probe `/.well-known/<…>/<mcp-url-path>`. The body is the
  // pod-level one: the MCP URL is an alternative spelling of the same resource, not a
  // resource of its own.

  @GET
  @Path("oauth-protected-resource/{pod}/_system/mcp")
  @Produces(MediaType.APPLICATION_JSON)
  fun protectedResourceMetadataForMcp(@PathParam("pod") pod: String): Response {
    val podDbo = fetchPodOrThrow(pod)
    return buildProtectedResourceMetadata(
      podDbo, config.apiBaseUrl,
      publicContextsCount = podFacade.getPublicContexts(podName = podDbo.name).size,
    )
  }
}

internal fun buildProtectedResourceMetadata(
  pod: PodDbo,
  apiBaseUrl: String,
  publicContextsCount: Int,
): Response {
  val podBaseUrl = "$apiBaseUrl${pod.name}"
  val authIssuer = "$podBaseUrl/_system/auth"
  // sempods extensions on top of RFC 9728 §3: optional `name` and always-present
  // `public_contexts` count. Consumers that don't know these fields ignore them.
  // `public_contexts` is a count, not the URI list, so a pod does not have to leak
  // its public-context topology to advertise that it has any.
  val body = linkedMapOf<String, Any>(
    "resource" to podBaseUrl,
    "authorization_servers" to listOf(authIssuer),
    "bearer_methods_supported" to listOf("header"),
    "public_contexts" to publicContextsCount,
  )
  pod.displayName?.takeIf { it.isNotBlank() }?.let { body["name"] = it }
  return Response.ok(body).build()
}

internal fun buildAuthorizationServerMetadata(
  pod: PodDbo,
  apiBaseUrl: String,
): Response {
  val authIssuer = "$apiBaseUrl${pod.name}/_system/auth"
  val body = linkedMapOf(
    "issuer" to authIssuer,
    "authorization_endpoint" to "$authIssuer/authorize",
    "token_endpoint" to "$authIssuer/token",
    "registration_endpoint" to "$authIssuer/register",
    "jwks_uri" to "$authIssuer/jwks.json",
    "response_types_supported" to listOf("code"),
    // `client_credentials` is the 2-leg flow consumed by statically-registered
    // pod service clients (see [PodServiceClientStore]). DCR-based clients
    // (MCP, app integrations) cannot use it — but RFC 8414 §2 wants this list
    // to reflect what the token endpoint actually accepts, so we advertise it
    // honestly. `client_secret_basic` is its required authentication method.
    "grant_types_supported" to listOf("authorization_code", "refresh_token", "client_credentials"),
    "code_challenge_methods_supported" to listOf("S256"),
    "token_endpoint_auth_methods_supported" to listOf("none", "client_secret_basic"),
  )
  return Response.ok(body).build()
}
