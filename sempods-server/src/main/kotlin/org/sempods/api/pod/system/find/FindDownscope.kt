package org.sempods.api.pod.system.find

import org.sempods.pods.IriSyntax
import org.sempods.pods.grants.SempodsCredentials
import java.net.URI

/**
 * The optional `context` downscope of a find, worked out from the credentials alone
 * (`SPS-CRUD-014`, `SPS-CRUD-015`).
 *
 * A requested context is taken relative to the pod's URI unless it is absolute, and dropped
 * silently when [IriSyntax] refuses it, it lies outside the pod or it carries a fragment. What is
 * left is intersected with [SempodsCredentials.restrictedContexts].
 *
 * No context registry is asked whether a context exists. The answer cannot widen what a caller
 * reads: the intersection is a subset of the readable contexts a find without `context` already
 * searches, and a context nobody registered holds no statements to return.
 */
internal object FindDownscope {

  /**
   * `null` when [rawContexts] is absent, which means pod-wide within the readable contexts. A
   * present list is fail-closed: blank entries are dropped, and a list with nothing else left
   * resolves to an empty scope rather than to pod-wide.
   */
  fun resolve(credentials: SempodsCredentials, rawContexts: List<String>?): Set<URI>? {
    val raw = rawContexts ?: return null
    val podBase = "${credentials.pod.uri}/"
    val requested = raw.mapNotNullTo(mutableSetOf()) { contextOrNull(podBase, it) }
    val readable = credentials.restrictedContexts ?: return requested
    return requested.intersect(readable)
  }

  private fun contextOrNull(podBase: String, value: String): URI? {
    val raw = value.trim().takeIf(String::isNotEmpty) ?: return null
    val context = try {
      URI(if (raw.contains("://")) raw else podBase + raw.trimStart('/'))
    } catch (_: Exception) {
      return null
    }
    return context.takeIf {
      IriSyntax.rejectionReason(it.toString()) == null &&
        it.toString().startsWith(podBase) &&
        it.fragment == null
    }
  }
}
