package org.sempods.mcp.api.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.sempods.mcp.core.BearerChallenge
import org.sempods.mcp.core.Capabilities
import org.sempods.mcp.core.ContentItem
import org.sempods.mcp.core.Tool
import org.sempods.mcp.core.ToolCallResult
import org.sempods.mcp.core.ToolsListResult
import org.sempods.mcp.core.Implementation
import org.sempods.mcp.core.InitializeResult
import org.sempods.mcp.core.JsonRpcError
import org.sempods.mcp.core.JsonRpcErrorCodes
import org.sempods.mcp.core.JsonRpcErrorResponse
import org.sempods.mcp.core.JsonRpcResponse
import org.sempods.mcp.core.McpProtocol
import org.sempods.mcp.core.PromptsListResult
import org.sempods.mcp.core.ReauthorizeChallengeStore
import org.sempods.mcp.core.ResourcesListResult
import org.sempods.mcp.core.ToolsCapability
import org.sempods.mcp.core.isNotification
import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.api.resolveProfileOr404
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.auth.ServiceBearerVerifier
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.ProfilePath
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The MCP JSON-RPC 2.0 surface. M1 shipped the front-door skeleton; the read tools (M3) and write
 * tools (M4) that front pods are wired in via [readToolDispatch] / [writeToolDispatch].
 *
 * Unlike the per-pod MCP (which serves public contexts to anonymous callers), the hosted service
 * has **no public/anonymous mode**: there is no service-level data to serve without a connected
 * pod, so every id-bearing request — `initialize`, `tools/list`, `tools/call`, `resources/list`,
 * `prompts/list`, `ping` — requires a valid service session. A missing bearer and a present-but-
 * invalid bearer both get 401 + WWW-Authenticate (realm / error / resource / resource_metadata),
 * so a client cannot complete an anonymous handshake and then appear "connected" while being
 * unable to do anything. Notifications (no id) are the sole exception: acked with 202, they
 * disclose nothing. The `.well-known/oauth-protected-resource` metadata the challenge points at
 * stays public so discovery still works.
 *
 * Hand-rolled JSON-RPC (no MCP SDK dependency).
 */
private val logger = KotlinLogging.logger("org.sempods.mcp.api.mcp")


fun Application.mcpEndpoint(
  config: SempodsMcpConfig,
  bearerVerifier: ServiceBearerVerifier,
  reauthorizeChallengeStore: ReauthorizeChallengeStore,
  /** Executes a read tool (M3) for an authenticated session; production wires [ReadTools.dispatch]. */
  readToolDispatch: suspend (toolName: String, arguments: JsonNode?, session: ServiceBearerVerifier.Session) -> ToolCallResult,
  /** Executes a write/property tool (M4) for an authenticated session; production wires [WriteTools.dispatch]. */
  writeToolDispatch: suspend (toolName: String, arguments: JsonNode?, session: ServiceBearerVerifier.Session) -> ToolCallResult,
  objectMapper: ObjectMapper,
  userRateLimiter: UserRateLimiter,
  auditLog: AuditLog,
) {
  val base = config.mcpBaseUrl

  // Per-profile RFC 6750 challenge. The MCP endpoint URL is the resource itself (suffix-free,
  // M5): the default profile is the service root `$base`, a named profile is `$base/<profile>`.
  // A client that follows only the WWW-Authenticate header (instead of probing the well-known
  // routes) lands on the discovery for exactly this resource. The pod-immanent MCP has one
  // surface per pod and needs no equivalent: its challenge always names the pod.
  fun challengeFor(profile: String): String {
    val resource = ProfilePath.baseUrlFor(base, profile)
    val resourceMetadataUrl =
      if (profile == PodKey.DEFAULT_PROFILE) "$base/.well-known/oauth-protected-resource"
      else "$base/.well-known/oauth-protected-resource/$profile"
    return BearerChallenge.forResource(
      realm = "sempods-mcp",
      resource = resource,
      resourceMetadataUrl = resourceMetadataUrl,
    )
  }

  suspend fun handleRpc(call: ApplicationCall, pathProfile: String) {
      val challenge = challengeFor(pathProfile)
      val rpc = runCatching { objectMapper.readTree(call.receiveText()) }.getOrNull()
      if (rpc == null || rpc["jsonrpc"]?.asText() != "2.0" || rpc["method"] == null) {
        return call.respondText(
          objectMapper.writeValueAsString(jsonRpcError(
            // JSON-RPC 2.0 §5: an id that could not be read is reported as null, not omitted.
            NullNode.getInstance(), JsonRpcErrorCodes.INVALID_REQUEST, "Invalid Request",
          )),
          ContentType.Application.Json, HttpStatusCode.BadRequest,
        )
      }
      val rawId: JsonNode? = rpc["id"]
      val method = rpc["method"].asText()

      // Notifications (no id) are acknowledged with 202 and no body.
      if (isNotification(rawId)) {
        return call.respondText("", status = HttpStatusCode.Accepted)
      }
      // Non-null past the notification guard — `isNotification` covers both an absent `id` and an
      // explicit JSON `null`, which is what the shared rule spells out for both surfaces.
      val id: JsonNode = rawId!!

      // No public/anonymous mode on the hosted service: every id-bearing request needs a valid
      // service session. A missing bearer and a present-but-invalid bearer both land here and get
      // the same 401 + WWW-Authenticate, so a client can never complete the anonymous handshake
      // (initialize / tools/list) and appear "connected" while being unable to do anything — the
      // challenge drives it straight into OAuth instead.
      val bearer = call.request.headers["Authorization"]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()
      val session = bearer?.let { bearerVerifier.verify(it) }
      if (session == null) {
        return call.respondChallenge(objectMapper, id, challenge)
      }

      // Hard profile isolation (M5): a token minted for one profile must not act on another
      // profile's path. The token carries its profile as a claim; if it does not match the path
      // this request came in on, treat the bearer as invalid *for this resource* and answer the
      // 401 challenge that points at this path's metadata, so the client re-auths against it.
      if (session.profile.ifBlank { PodKey.DEFAULT_PROFILE } != pathProfile) {
        return call.respondChallenge(objectMapper, id, challenge)
      }

      when (method) {
        "initialize" -> {
          val requested = rpc["params"]?.get("protocolVersion")?.asText()
          val negotiated = McpProtocol.negotiate(requested)
          call.respondRpc(objectMapper, id, InitializeResult(
            protocolVersion = negotiated,
            capabilities = Capabilities(ToolsCapability()),
            serverInfo = Implementation(name = "sempods-mcp", version = "0.2.0-M2"),
            instructions = "Hosted MCP service fronting your sempods pods. You are signed in (this endpoint has no anonymous mode). Call `list_pods` / `list_contexts` to see your connected pods; if none are connected yet, connect one at $base/_system/ui. Read tools fan out across pods (optional `targets`); write tools target exactly one `target` pod + one `context_iri`.",
          ))
        }

        "tools/list" -> {
          // Reached only by an authenticated session (anonymous callers were 401'd above), so the
          // full tool set is always advertised: the `authorize`/reauthorize helper plus the pod
          // read (M3) and write (M4) tools.
          val tools = buildList {
            add(authorizeToolSpec())
            addAll(hostedToolCatalog.readTools())
            addAll(hostedToolCatalog.writeTools())
          }
          call.respondRpc(objectMapper, id, ToolsListResult(tools))
        }

        "tools/call" -> {
          // A valid session is guaranteed here — anonymous callers never reach dispatch.
          val params = rpc["params"]
          val toolName = params?.get("name")?.asText()
          // Per-user quota (M6.4), keyed on the VERIFIED (user, profile) — deliberately after the
          // bearer + profile-isolation gates, so an unauthenticated spray cannot drain a victim's
          // budget. Scoped to the vault-accessing pod tools (read/write) plus the unknown-tool
          // rejection (so a misbehaving client cannot spam it for free); `authorize` is EXEMPT —
          // it is the OAuth re-consent / pod-connect escape hatch, so like the handshake methods
          // (initialize / tools/list / ping) it must stay reachable even when the budget is spent,
          // or a user who exhausted it could never re-consent to recover. Over-quota is a
          // protocol-level JSON-RPC error (HTTP 200), not a tool result: a server condition, same
          // shape as the unknown-tool error below. -32000 = implementation-defined server error
          // (-32001 is already this endpoint's auth challenge).
          if (toolName != "authorize" && !userRateLimiter.tryAcquire(session.user, pathProfile)) {
            auditLog.rateLimited(session.user, pathProfile)
            return call.respondRpc(objectMapper, id, error = JsonRpcError(
              JsonRpcErrorCodes.SERVER_ERROR, "Rate limit exceeded for this user/profile — retry shortly",
            ))
          }
          when {
            toolName == "authorize" -> {
              val reauthorize = params.get("arguments")?.get("reauthorize")?.asBoolean(false) ?: false
              // Idempotent unless reauthorize forces a fresh flow.
              if (reauthorize) {
                // Keyed by the path profile as the realm: a session is bound to one profile
                // (checked above), so a challenge raised in one must not be answered by a call in
                // another.
                val isReplay = reauthorizeChallengeStore.consumeIfReplay(
                  pathProfile, session.clientId, session.user, session.tokenJti, session.issuedAt,
                )
                if (!isReplay) {
                  reauthorizeChallengeStore.record(pathProfile, session.clientId, session.user, session.tokenJti)
                  return call.respondChallenge(objectMapper, id, challenge)
                }
              }
              call.respondRpc(objectMapper, id, ToolCallResult(
                content = listOf(ContentItem(type = "text", text = "Signed in as ${session.user}.")),
              ))
            }

            toolName in hostedToolCatalog.readToolNames -> {
              val result = readToolDispatch(toolName!!, params?.get("arguments"), session)
              call.respondRpc(objectMapper, id, result)
            }

            toolName in hostedToolCatalog.writeToolNames -> {
              val result = writeToolDispatch(toolName!!, params?.get("arguments"), session)
              call.respondRpc(objectMapper, id, result)
            }

            else -> call.respondRpc(objectMapper, id, error = JsonRpcError(JsonRpcErrorCodes.METHOD_NOT_FOUND, "Unknown tool: $toolName"))
          }
        }

        "resources/list" -> call.respondRpc(objectMapper, id, ResourcesListResult())
        "prompts/list" -> call.respondRpc(objectMapper, id, PromptsListResult())
        "ping" -> call.respondRpc(objectMapper, id, emptyMap<String, Any>())

        else -> call.respondRpc(objectMapper, id, error = JsonRpcError(JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method"))
      }
  }

  routing {
    // M5: the MCP endpoint IS the resource URL (suffix-free). The default profile is the service
    // root; a named profile is `/{profile}`. The pre-M5 `/mcp` path is gone (404). A reserved or
    // malformed profile segment is rejected with 404 rather than treated as the default.
    post("/") { handleRpc(call, PodKey.DEFAULT_PROFILE) }
    post("/{profile}") {
      call.resolveProfileOr404()?.let { handleRpc(call, it) }
    }
  }
}


private suspend fun ApplicationCall.respondChallenge(objectMapper: ObjectMapper, id: JsonNode, challenge: String) {
  response.header("WWW-Authenticate", challenge)
  respondText(
    objectMapper.writeValueAsString(jsonRpcError(id, JsonRpcErrorCodes.UNAUTHORIZED, "Authentication required")),
    ContentType.Application.Json, HttpStatusCode.Unauthorized,
  )
}

private fun authorizeToolSpec(): Tool {
  // Only ever advertised to an already-authenticated session (the hosted service has no anonymous
  // mode — an unauthenticated request is met with 401 + WWW-Authenticate, which the MCP client
  // follows to start OAuth). So this tool is the "who am I / re-consent" helper, not a bootstrap.
  return Tool(
    name = "authorize",
    description = "Return the active sempods MCP session, or force a fresh OAuth flow. Called without " +
      "arguments it returns the active session info with no side effects. Pass " +
      "`reauthorize: true` to re-run the OAuth flow — e.g. to re-open the consent screen and " +
      "connect additional pods. (An unauthenticated caller never sees this tool: the server " +
      "answers every request with HTTP 401 + WWW-Authenticate, which the client follows to " +
      "begin OAuth.)",
    inputSchema = AUTHORIZE_INPUT_SCHEMA,
  )
}

private suspend fun ApplicationCall.respondRpc(
  objectMapper: ObjectMapper,
  id: JsonNode,
  result: Any? = null,
  error: JsonRpcError? = null,
) {
  val body: Any = if (error != null) {
    JsonRpcErrorResponse("2.0", id, error)
  } else {
    JsonRpcResponse("2.0", id, result ?: emptyMap<String, Any>())
  }
  respondText(objectMapper.writeValueAsString(body), ContentType.Application.Json)
}

private fun jsonRpcError(id: JsonNode?, code: Int, message: String) =
  JsonRpcErrorResponse("2.0", id, JsonRpcError(code, message))
