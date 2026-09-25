package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.OFFLINE_ACCESS_SCOPE
import org.sempods.pods.grants.PodScopeValidator
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
 * The pod base `P`, e.g. `https://host/alice`, is both the protected resource and the issuer
 * (SPS-AUTH-028, SPS-AUTH-065, SPS-AUTH-066). The authorization, token, registration and JWKS
 * endpoints live below it, under `P/_system/auth`.
 *
 * Routes we serve (and why):
 *
 *  1. `P/.well-known/oauth-protected-resource` (SPS-AUTH-045) — the target of the
 *     `WWW-Authenticate: …, resource_metadata=…` hint on a 401. Claude Code follows this.
 *  2. `P/.well-known/oauth-authorization-server` (SPS-AUTH-066) — the issuer's metadata, at the
 *     address SPS-AUTH-067 builds from `P`.
 *  3. `/.well-known/oauth-protected-resource/{pod}` and `/.well-known/oauth-authorization-server/{pod}`
 *     on [RootOAuthMetadataEndpoint] — the host-rooted RFC 9728 §3.1 and RFC 8414 §3.1 addresses for
 *     `P`, which SPS-AUTH-067 allows beside the two above. Browser SPAs on `oauth4webapi` probe the
 *     first before they see a 401; claude.ai Web reads `authorization_servers[0]` and probes the
 *     second.
 *  4. `/.well-known/oauth-protected-resource/{pod}/_system/mcp` — MCP 2025-11-25 clients
 *     (claude.ai Web) treat the MCP URL itself as the protected-resource identifier and
 *     proactively probe the RFC-9728-strict path with that identifier. Body is identical to
 *     the pod-level variants — the pod remains the unit of access control. There is no
 *     `oauth-authorization-server` counterpart: the MCP URL is not an issuer identifier, and
 *     RFC 8414 §3.3 requires the served `issuer` to match the URL it was fetched from.
 *  5. `{pod}/_system/mcp/.well-known/oauth-protected-resource` — the MCP-URL append-style
 *     counterpart (on [org.sempods.api.pod.system.mcp.McpEndpoint]), kept alongside the strict
 *     form because the exact claude.ai probe set is not fully documented.
 *
 * `scopes_supported` names everything a client may put in `scope`: the feature scopes, and
 * `offline_access` for a client that wants a long connection. The list is short and complete because
 * the per-context permissions a caller ends up with are grants — agreed in the consent dialog and
 * resolved per request, never asked for through `scope`. Advertising it is what lets a client that
 * has read no sempods documentation discover the extension at all; `openid` is deliberately absent,
 * because this pod issues no `id_token`.
 *
 * `service-clients:install` is on the list under the same rule, and what it buys a caller is bounded by
 * `docs/auth/oauth.md` §"Installing a service client": the authorization registers one service and
 * reaches nothing else.
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

  @GET
  @Path(".well-known/oauth-authorization-server")
  @Produces(MediaType.APPLICATION_JSON)
  fun authorizationServerMetadata(@PathParam("pod") pod: String): Response =
    buildAuthorizationServerMetadata(fetchPodOrThrow(pod), config.apiBaseUrl)
}

/**
 * Host-rooted OAuth discovery: RFC 9728 §3.1 and RFC 8414 §3.1 insert the well-known segment between
 * the host and the identifier's path. SPS-AUTH-067 allows the two addresses for `P` beside the
 * pod-local ones on [PodOAuthMetadataEndpoint], each answering the same pod's metadata.
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
  @Path("oauth-authorization-server/{pod}")
  @Produces(MediaType.APPLICATION_JSON)
  fun authorizationServerMetadataForPod(@PathParam("pod") pod: String): Response =
    buildAuthorizationServerMetadata(fetchPodOrThrow(pod), config.apiBaseUrl)

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

/** RFC 9728 §2 and RFC 8414 §2 both call the field `scopes_supported`, and both mean this list. */
private val SCOPES_SUPPORTED = PodScopeValidator.featureScopes.toList() + OFFLINE_ACCESS_SCOPE

internal fun buildProtectedResourceMetadata(
  pod: PodDbo,
  apiBaseUrl: String,
  publicContextsCount: Int,
): Response {
  val podBaseUrl = "$apiBaseUrl${pod.name}"
  // sempods extensions on top of RFC 9728 §3: optional `name` and always-present
  // `public_contexts` count. Consumers that don't know these fields ignore them.
  // `public_contexts` is a count, not the URI list, so a pod does not have to leak
  // its public-context topology to advertise that it has any.
  val body = linkedMapOf<String, Any>(
    "resource" to podBaseUrl,
    "authorization_servers" to listOf(podBaseUrl),
    "bearer_methods_supported" to listOf("header"),
    "scopes_supported" to SCOPES_SUPPORTED,
    "public_contexts" to publicContextsCount,
  )
  pod.displayName?.takeIf { it.isNotBlank() }?.let { body["name"] = it }
  return Response.ok(body).build()
}

internal fun buildAuthorizationServerMetadata(
  pod: PodDbo,
  apiBaseUrl: String,
): Response {
  val issuer = "$apiBaseUrl${pod.name}"
  val endpoints = "$issuer/_system/auth"
  val body = linkedMapOf(
    "issuer" to issuer,
    "authorization_endpoint" to "$endpoints/authorize",
    "token_endpoint" to "$endpoints/token",
    "registration_endpoint" to "$endpoints/register",
    "jwks_uri" to "$endpoints/jwks.json",
    "response_types_supported" to listOf("code"),
    // `client_credentials` is the 2-leg flow consumed by statically-registered
    // pod service clients (see [PodServiceClientStore]). DCR-based clients
    // (MCP, app integrations) cannot use it — but RFC 8414 §2 wants this list
    // to reflect what the token endpoint actually accepts, so we advertise it
    // honestly. `client_secret_basic` is its required authentication method.
    "grant_types_supported" to listOf("authorization_code", "refresh_token", "client_credentials"),
    "scopes_supported" to SCOPES_SUPPORTED,
    "code_challenge_methods_supported" to listOf("S256"),
    "token_endpoint_auth_methods_supported" to listOf("none", "client_secret_basic"),
  )
  return Response.ok(body).build()
}
