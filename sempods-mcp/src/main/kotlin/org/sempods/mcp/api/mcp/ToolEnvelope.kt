package org.sempods.mcp.api.mcp

/**
 * The per-pod failure entry — the one result shape that is genuinely this service's own.
 *
 * Everything else the two dispatchers emit is shared now: the tool-level error and the text result
 * are `toolError` / `toolText` in `:sempods-mcp-core`, and the `result` payload inside a successful
 * entry comes finished from `PodToolExecutor`. What stays here is fan-out vocabulary — a shape that
 * only exists because one call can address several pods and one of them can fail alone.
 */
object ToolEnvelope {

  /**
   * A single pod's failure inside the per-pod envelope. [kind] is a stable, machine-readable label
   * (`not_connected` | `no_token` | `pod_error`) so a caller can react per-pod (e.g. prompt a
   * reconnect on `no_token`) rather than parse the message string. [status] carries the pod's HTTP
   * status when the failure came from the pod (e.g. 412 precondition, 403 missing scope), so an
   * optimistic-concurrency caller can branch without regex-ing the message; it is absent when no
   * pod answered at all, such as a token that could not be acquired.
   */
  fun podError(pod: String, kind: String, message: String, status: Int? = null): Map<String, Any?> {
    val error = linkedMapOf<String, Any?>("kind" to kind, "message" to message)
    if (status != null) error["status"] = status
    return linkedMapOf("pod" to pod, "ok" to false, "error" to error)
  }
}
