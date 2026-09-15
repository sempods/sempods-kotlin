package org.sempods.client.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.EnumSet

/**
 * A pod's contexts: the listing at `{pod}/_system/contexts`, and creating a context at its own IRI.
 *
 * **What is listed is the session's to decide.** A pod lists the contexts the request's credential can
 * see (SPS-CTX-021), so a session without a pod credential lists the public contexts, and a
 * deployment's own authentication stays on that request as on any other.
 *
 * The typed methods read the specification's `ContextList` and `Context` schemas and nothing else. The
 * `…Json` and `…Bytes` methods return the body as the pod sent it, from the same call.
 */
class SempodsPodContexts internal constructor(
  private val session: SempodsSession,
  private val exchange: Exchange,
) {

  /**
   * The contexts this session can see. `GET`, `Accept: application/json`, no query and no body; the
   * route answers `200` and nothing else.
   *
   * An entry without a `contextUri` string, or a member holding another type than its schema's, is a
   * [SempodsDecodingException] at that member's pointer.
   */
  @Throws(IOException::class)
  fun list(): SempodsResponse<SempodsContextList> = exchange.run(listRequest(), LISTED, CONTEXT_LIST)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun listJson(): SempodsResponse<String> = exchange.run(listRequest(), LISTED, BodyReading.TEXT)

  /** The same answer with the body as the bytes the server sent. */
  @Throws(IOException::class)
  fun listBytes(): SempodsResponse<ByteArray> = exchange.run(listRequest(), LISTED, BodyReading.BYTES)

  /**
   * Creates the context [contextUri], or finds it: `201` for a context this call created, `200` for one
   * that was there already and is left unchanged (SPS-CTX-016). Both carry the context.
   *
   * [contextUri] is the IRI exactly as the pod gave it, and it is also the route (SPS-CTX-005), so this
   * client composes no IRI (SPS-CTX-023). One that does not lie under this pod's `_system/contexts/`, or
   * that carries a query or a fragment, is an [IllegalArgumentException], and nothing is sent.
   *
   * `PUT` with [body] as `application/json`, and `Accept: application/json`. Like any `PUT`, it is sent
   * once more after a connection lost before an answer, so a creation whose answer was lost that way
   * reports `200`.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun create(
    contextUri: String,
    body: SempodsContextCreate = SempodsContextCreate.fields(),
  ): SempodsResponse<SempodsContext> = exchange.run(createRequest(contextUri, body), CREATED, CONTEXT)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @JvmOverloads
  @Throws(IOException::class)
  fun createJson(
    contextUri: String,
    body: SempodsContextCreate = SempodsContextCreate.fields(),
  ): SempodsResponse<String> = exchange.run(createRequest(contextUri, body), CREATED, BodyReading.TEXT)

  /** The same answer with the body as the bytes the server sent. */
  @JvmOverloads
  @Throws(IOException::class)
  fun createBytes(
    contextUri: String,
    body: SempodsContextCreate = SempodsContextCreate.fields(),
  ): SempodsResponse<ByteArray> = exchange.run(createRequest(contextUri, body), CREATED, BodyReading.BYTES)

  private fun listRequest(): Request = session.newRequest("GET", ROUTE).header("Accept", JSON).build()

  private fun createRequest(contextUri: String, body: SempodsContextCreate): Request {
    val namespace = "${session.podBase.url.toString().removeSuffix("/")}/$ROUTE/"
    require(contextUri.startsWith(namespace) && contextUri.length > namespace.length) {
      "'$contextUri' is not a context of the pod '${session.podBase}': a context's IRI lies under '$namespace'."
    }
    require('?' !in contextUri && '#' !in contextUri) {
      "'$contextUri' carries a query or a fragment, which a context IRI cannot."
    }
    return session.newRequest("PUT", "$ROUTE/${contextUri.removePrefix(namespace)}")
      .header("Accept", JSON)
      .put(body.encoded().toRequestBody(JSON_TYPE))
      .build()
  }

  private companion object {

    const val ROUTE = "_system/contexts"

    const val JSON = "application/json"

    val JSON_TYPE = JSON.toMediaType()

    val LISTED = setOf(200)

    val CREATED = setOf(200, 201)

    val CONTEXT_LIST = BodyReading<SempodsContextList> { bytes, _ ->
      val listing = decodeObject(bytes)
      SempodsContextList(
        podBaseUrl = listing.stringOrNull("podBaseUrl"),
        authenticated = listing.booleanOrNull("authenticated"),
        contexts = listing.objectsOrNull("contexts").orEmpty().map { context(it) },
        writableContexts = listing.stringsOrNull("writableContexts").orEmpty(),
      )
    }

    val CONTEXT = BodyReading<SempodsContext> { bytes, _ -> context(decodeObject(bytes)) }

    fun context(entry: ProtocolObject) = SempodsContext(
      contextUri = entry.string("contextUri"),
      label = entry.stringOrNull("label"),
      description = entry.stringOrNull("description"),
      public = entry.booleanOrNull("public"),
      permissions = entry.stringsOrNull("permissions").orEmpty()
        .mapNotNullTo(EnumSet.noneOf(SempodsContextPermission::class.java)) { permission(it) },
    )

    fun permission(name: String): SempodsContextPermission? =
      when (name) {
        "read" -> SempodsContextPermission.READ
        "write" -> SempodsContextPermission.WRITE
        "manage" -> SempodsContextPermission.MANAGE
        else -> null
      }
  }
}
