package org.sempods.mcp.api.mcp

import org.sempods.mcp.core.ToolCatalog
import org.sempods.mcp.core.ToolInputSchema
import org.sempods.mcp.core.ToolVariant
import org.sempods.mcp.core.PropertySchema

/**
 * This service's tool surface: the shared catalog in its multi-pod variant.
 *
 * The specs themselves live in `:sempods-mcp-core` because the pod-immanent MCP in `sempods-server`
 * advertises the same fourteen tools, with the same argument names and the same `required` lists.
 * What this service adds is the fan-out vocabulary — `targets` on the reads, a required `target` on
 * the writes, and `list_pods` — which is what [ToolVariant.MULTI_POD] means.
 *
 * The human-readable contract is `sempods-mcp/docs/tool-contract.md`.
 */
internal val hostedToolCatalog: ToolCatalog = ToolCatalog.of(ToolVariant.MULTI_POD)

/**
 * `authorize` is deliberately not in the shared catalog.
 *
 * It is the one tool whose meaning differs per surface — on the pod it upgrades the grant for that
 * pod, here it re-opens consent to connect further pods — and the one whose description depends on
 * the caller's auth state on the other side. Sharing the type while writing the text locally is the
 * honest split; sharing the entry would need an exception in every catalog-wide invariant.
 */
internal val AUTHORIZE_INPUT_SCHEMA = ToolInputSchema(
  type = "object",
  properties = mapOf(
    "reauthorize" to PropertySchema(
      type = "boolean",
      description = "If true, force the OAuth flow to start again even if already authorized. Default: false.",
    ),
  ),
  required = emptyList(),
  additionalProperties = false,
)
