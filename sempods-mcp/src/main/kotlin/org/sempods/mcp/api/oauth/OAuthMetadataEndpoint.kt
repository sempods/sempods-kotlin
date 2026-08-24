package org.sempods.mcp.api.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.api.resolveProfileOr404
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.ProfilePath
import org.sempods.auth.core.DidWeb
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * OAuth discovery documents for the service (RFC 9728 protected-resource metadata + RFC 8414
 * authorization-server metadata). The **service itself** is the protected resource and its own
 * authorization server — the AI-client → service OAuth layer.
 *
 * Profile-aware (M5): the default profile is the service root (`$base`); a named profile is the
 * resource `$base/<profile>` with its own `authorize` / `token` / `register` endpoints under that
 * segment. The signing keys are service-wide, so `jwks_uri` always points at the root `/jwks.json`.
 *
 * Both the host-rooted RFC-9728 form (the `/.well-known/...` prefix with the resource path
 * appended after it) and the path-appended form (`.well-known` appended after the resource path)
 * are served because different AI clients probe different shapes. All variants for one resource
 * return the same body — the service (per profile) is the unit of access control.
 */
fun Application.oauthMetadataEndpoint(config: SempodsMcpConfig, objectMapper: ObjectMapper) {
  val base = config.mcpBaseUrl

  fun protectedResourceMetadata(profile: String): String {
    val resource = ProfilePath.baseUrlFor(base, profile)
    return objectMapper.writeValueAsString(
      linkedMapOf<String, Any>(
        "resource" to resource,
        "authorization_servers" to listOf(resource),
        "bearer_methods_supported" to listOf("header"),
      ),
    )
  }

  fun authorizationServerMetadata(profile: String): String {
    val resource = ProfilePath.baseUrlFor(base, profile)
    return objectMapper.writeValueAsString(
      linkedMapOf<String, Any>(
        "issuer" to resource,
        "authorization_endpoint" to "$resource/authorize",
        "token_endpoint" to "$resource/token",
        "registration_endpoint" to "$resource/register",
        // Signing keys are service-wide → the JWKS is always at the root, regardless of profile.
        "jwks_uri" to "$base/jwks.json",
        "response_types_supported" to listOf("code"),
        "grant_types_supported" to listOf("authorization_code", "refresh_token"),
        "code_challenge_methods_supported" to listOf("S256"),
        "token_endpoint_auth_methods_supported" to listOf("none"),
      ),
    )
  }

  // Resolve the `{profile}` path parameter, answering 404 for a reserved/malformed segment so a
  // bogus profile cannot masquerade as discoverable.
  suspend fun ApplicationCall.respondForProfile(body: (String) -> String) {
    val profile = resolveProfileOr404() ?: return
    respondText(body(profile), ContentType.Application.Json)
  }

  // The service's own did:web document — a pod using the did:web static-client model resolves the
  // service's `client_id` (did:web:<host>) to this and checks `id` matches before accepting the
  // connect. Service-wide (host identity), not per-profile.
  val didDocument = objectMapper.writeValueAsString(DidWeb.document(DidWeb.clientId(base)))

  routing {
    // --- did:web client document (service identity toward pods that skip DCR) ---
    get("/.well-known/did.json") {
      call.respondText(didDocument, ContentType.Application.Json)
    }

    // --- Default profile (service root) ---
    get("/.well-known/oauth-protected-resource") {
      call.respondText(protectedResourceMetadata(PodKey.DEFAULT_PROFILE), ContentType.Application.Json)
    }
    get("/.well-known/oauth-authorization-server") {
      call.respondText(authorizationServerMetadata(PodKey.DEFAULT_PROFILE), ContentType.Application.Json)
    }

    // --- Named profile, host-rooted RFC-9728 form (resource path appended after the well-known) ---
    get("/.well-known/oauth-protected-resource/{profile}") {
      call.respondForProfile { protectedResourceMetadata(it) }
    }
    get("/.well-known/oauth-authorization-server/{profile}") {
      call.respondForProfile { authorizationServerMetadata(it) }
    }

    // --- Named profile, path-appended form (well-known appended after the resource path) ---
    get("/{profile}/.well-known/oauth-protected-resource") {
      call.respondForProfile { protectedResourceMetadata(it) }
    }
    get("/{profile}/.well-known/oauth-authorization-server") {
      call.respondForProfile { authorizationServerMetadata(it) }
    }
  }
}
