package org.sempods.mcp.core

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * What a caller says when a pod refused a tool call — the words a model reads.
 *
 * Here rather than in a surface because both surfaces get the same answers from the same routes,
 * and a refusal one of them phrases well is one the other should not have to rediscover. What stays
 * per surface is the *shape* the words go into, which is why there are two entry points: the
 * pod-immanent MCP has one text field and needs the status inside it ([describe]), while the hosted
 * service carries the status structurally beside the message and would only repeat itself
 * ([detail]).
 *
 * Nothing here classifies beyond what the pod already said. [PodToolExecutor] deliberately lets
 * failures propagate, so a caller arrives with the pod's status and body in hand; a taxonomy on top
 * would be guessing at HTTP the pod has been explicit about.
 */
object PodToolFailure {

  /**
   * The whole sentence, status included — for a surface whose only channel is the text.
   *
   * [podMessage] is the pod's response body, not an exception message: a message carries the URL
   * that was dialled, and this text goes to a language model, which has no use for one and some
   * tendency to repeat it back to the user as an address to visit.
   */
  fun describe(toolName: String, statusCode: Int?, podMessage: String): String {
    val status = statusCode?.let { " ($it)" } ?: ""
    return "Error$status: ${detail(toolName, statusCode, podMessage)}"
  }

  /**
   * The reason alone — for a surface that reports the status as its own field.
   *
   * Passes the pod's body through unchanged except where the pod's answer is correct HTTP and poor
   * advice; see [sparqlModeHint].
   */
  fun detail(toolName: String, statusCode: Int?, detail: String): String =
    sparqlModeHint(toolName, statusCode) ?: unwrapErrorEnvelope(detail).ifBlank { "the pod refused the call" }

  /**
   * The message out of a sempods `{"errors":[{"code","message"}]}` body, or the body unchanged.
   *
   * A pod refuses through one error envelope, which is right for a programmatic caller and wrong to
   * hand a model: `{"errors":[{"code":"common.not-found","message":"resource <…> does not exist in
   * context <…> — use create_resource to create it"}]}` buries the one sentence that says what to do
   * next. Unwrapped here rather than in the client, because the shape belongs to the sempods HTTP
   * API and only a caller that renders prose needs it flattened.
   *
   * Anything that is not that envelope — plain text, a truncated body, another service's JSON —
   * passes through untouched. `code` is dropped deliberately: it is a routing key for software, and
   * the message it accompanies already says the same thing in words.
   */
  private fun unwrapErrorEnvelope(body: String): String {
    if (!body.trimStart().startsWith("{")) return body
    val errors = runCatching { mapper.readTree(body) }.getOrNull()?.path("errors") ?: return body
    if (!errors.isArray || errors.isEmpty) return body
    val messages = errors.mapNotNull { it.path("message").takeIf { m -> m.isTextual }?.asText()?.takeIf(String::isNotBlank) }
    return messages.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: body
  }

  /** Read-only, so a plain mapper with no configuration is the whole of what is needed. */
  private val mapper = ObjectMapper()

  /**
   * The one refusal worth rephrasing: a query sent to the wrong SPARQL tool.
   *
   * The pod enforces the split by content negotiation — `sparql_select` asks for
   * `application/sparql-results+json`, which a CONSTRUCT cannot produce, and `sparql_graph` asks for
   * `application/ld+json`, which a SELECT cannot — so the mistake arrives as a **406** whose body
   * talks about media types. Correct to tell an HTTP client, useless to tell a model: it never chose
   * a media type and cannot act on one. It chose a tool, so the answer names tools.
   *
   * Worth the special case because it is a standing failure mode: a model fluent in SPARQL reaches
   * for `sparql_select` for everything, CONSTRUCT and DESCRIBE included.
   */
  private fun sparqlModeHint(toolName: String, statusCode: Int?): String? {
    if (statusCode != 406) return null
    return when (toolName) {
      "sparql_select" ->
        "sparql_select expects a SELECT or ASK query, got GRAPH. Use sparql_graph for CONSTRUCT and DESCRIBE."

      "sparql_graph" ->
        "sparql_graph expects a CONSTRUCT or DESCRIBE query. Use sparql_select for SELECT and ASK."

      else -> null
    }
  }
}
