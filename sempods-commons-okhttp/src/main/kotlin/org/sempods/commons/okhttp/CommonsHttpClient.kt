package org.sempods.commons.okhttp

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.json.JsonUtil
import java.nio.charset.StandardCharsets

/**
 * The project's wrapper around OkHttp: send a JSON document, get a body back or an exception.
 *
 * Deliberately **small**: it carries the two decisions its callers would otherwise each make (how
 * a payload becomes a request, and which status codes are an error) and nothing else.
 *
 * **Blocking**, because on Java 25 a blocking send on a virtual thread is what an async client used
 * to buy — the reasoning `docs/pod-client.md` records for `:sempods-client`.
 */
class CommonsHttpClient(
  private val client: OkHttpClient,
  objectMapper: ObjectMapper = JsonMappers.default(),
) {

  val jsonUtil = JsonUtil(objectMapper)

  /**
   * A POST carrying [payload] as JSON, ready for the caller to add its own headers.
   *
   * The body declares no content type and the header is set by hand, which is not the roundabout
   * way of doing it: OkHttp appends `; charset=utf-8` to a content type a body declares, and an
   * API that matches on the exact media type would see a header this project never sent before.
   */
  fun prepareJsonPost(url: String, payload: Any): Request.Builder =
    Request.Builder()
      .url(url)
      .header("Content-Type", "application/json")
      .post(jsonUtil.write(payload).toByteArray(StandardCharsets.UTF_8).toRequestBody(null))

  /**
   * Sends [request] and returns the body as text.
   *
   * [validResponseCodes] empty means every status is the caller's to interpret; naming any makes
   * everything else an [HttpResponseException]. **The server's body travels with that exception**
   * — an API that refuses a request explains why in it, and dropping it turns every diagnosis into
   * a log expedition.
   */
  fun execute(request: Request.Builder, vararg validResponseCodes: Int): String =
    client.newCall(request.build()).execute().use { response ->
      val body = response.body.string()
      if (validResponseCodes.isNotEmpty() && response.code !in validResponseCodes) {
        throw HttpResponseException(response.code, body)
      }
      body
    }
}
