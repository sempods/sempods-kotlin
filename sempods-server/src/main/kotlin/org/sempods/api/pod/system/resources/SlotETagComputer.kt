package org.sempods.api.pod.system.resources

import org.sempods.commons.utils.HashUtil
import java.net.URI

/**
 * Strong ETag value for a slot, deterministic in
 * `(resource-validator, subject, predicate, context)`. Shared between [PodSystemResourcesEndpoint]
 * (HTTP `GET`/conditional writes) and [org.sempods.api.pod.system.mcp.McpEndpoint]
 * (MCP `if_match` argument check) so both surfaces honor the exact same tag.
 *
 * Resource-Snapshot semantics: the [resourceValidator] is a content hash over the whole subject
 * resource, so any change to the subject invalidates this tag — even for slots
 * of the same subject in a different predicate. That is conservative but correct (the subject's
 * snapshot is the invalidation unit). For a not-yet-existing subject (no validator), the `"0"`
 * fallback keeps the tag deterministic and stable across an `If-None-Match: *` create flow.
 */
object SlotETagComputer {

  fun compute(
    resourceValidator: String?,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
  ): String {
    val baseValue = "${resourceValidator ?: "0"}:$subjectUri:$predicateUri:$contextUri"
    return HashUtil.sha256Hex(baseValue).take(16)
  }
}
