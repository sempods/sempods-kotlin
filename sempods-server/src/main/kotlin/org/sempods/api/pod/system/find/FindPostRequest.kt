package org.sempods.api.pod.system.find

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * JSON body of `POST /{pod}/_system/find` — the body-based twin of the GET query parameters, for
 * requests whose `contexts` list (or, later, a `filter`) no longer fits a URL. Field names mirror
 * the shape reserved in `docs/ai-retrieval.md`. All fields optional / nullable; the same
 * [FindRequestParser] validation applies (absent/blank `text` → 400).
 *
 * The general predicate `filter` is intentionally not yet a field (still deferred). The endpoint
 * parses this body with a **strict** mapper (unknown fields rejected), so a client that sends
 * `filter` — or a typo'd field — gets a 400 rather than a silently broadened, half-honored result.
 */
data class FindPostRequest(
  @JsonProperty("text") val text: String? = null,
  @JsonProperty("type") val type: List<String>? = null,
  @JsonProperty("contexts") val contexts: List<String>? = null,
  @JsonProperty("include_contexts") val includeContexts: Boolean? = null,
  @JsonProperty("limit") val limit: Int? = null,
)
