package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.SempodsModule
import org.sempods.auth.ConsentTransactionStore
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.podId
import org.sempods.pods.oauth.PodConsentDecisionStore
import java.net.URLEncoder
import kotlin.test.assertEquals

/**
 * Delegated access as a browser and an app run it: an app acting for a person through
 * Authorization Code + PKCE, from registration to the token endpoint.
 *
 * A singleton like `SempodsTestFactory`, and it holds no state. The caller passes the session
 * cookie, usually `signIn(pod.name, webId).cookie`: a test that signs a person out needs the
 * cookie from before the sign-out, and a session cache here would hand it a different one.
 *
 * Two ways to answer the consent screen:
 *
 * | Way | Use it for |
 * |---|---|
 * | [authorize], [ConsentPage.of], [submit] | what the rendered page offers and posts, PKCE included |
 * | [consent], [connect] | a post with no page rendered first, such as a sign-out after the session ended |
 *
 * [consent] posts the form an older node renders: an unbound token beside the request fields. The
 * server accepts that for the rest of 0.2.x (`ConsentTransactionStore`, rollout), and so does this.
 *
 * Every authorization carries [CODE_CHALLENGE], and every exchange [CODE_VERIFIER] unless the test
 * leaves it out. A `did:web:` client may leave PKCE out; the server ignores a verifier sent for a
 * code without a challenge.
 */
internal class DelegatedAccessFlow {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var consentTransactionStore: ConsentTransactionStore

  @Inject
  private lateinit var consentDecisionStore: PodConsentDecisionStore

  /** A client acting for a person: a `did:web:` identity, or a `dyn:` one from [register]. */
  class App(val clientId: String, val redirectUri: String)

  /** A token endpoint answer. [refreshToken] fails where there is none; read [json] for that case. */
  class Tokens(val json: Map<String, Any?>) {
    val accessToken: String get() = json["access_token"] as String
    val refreshToken: String get() = checkNotNull(json["refresh_token"] as String?) { "no refresh token: $json" }

    companion object {
      @Suppress("UNCHECKED_CAST")
      fun of(response: TestHttpResponse): Tokens =
        Tokens(JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>)
    }
  }

  /**
   * The consent form as the server rendered it, read the way a browser reads it.
   *
   * The inputs of the page's `<template>` are left out: the script clones them for a new context,
   * and until then they are not part of the form.
   *
   * @property action where the form posts.
   * @property hidden every hidden field, by name.
   * @property offered the value of every `scope` checkbox.
   * @property ticked those of [offered] rendered ticked.
   * @property durableTicked whether the lifetime control is rendered ticked; `false` where there is none.
   */
  class ConsentPage private constructor(
    val action: String,
    val hidden: Map<String, String>,
    val offered: Set<String>,
    val ticked: Set<String>,
    val durableTicked: Boolean,
    private val form: String,
  ) {

    /** Whether the form holds an element with this `id`, such as `disconnectBtn` or `durableToggle`. */
    fun has(id: String): Boolean = Regex("""\sid="${Regex.escape(id)}"""").containsMatchIn(form)

    companion object {
      fun of(response: TestHttpResponse): ConsentPage {
        assertEquals(200, response.statusCode, "no consent page: ${response.getHeader("Location")} ${response.responseBody}")
        val form = checkNotNull(Regex("""<form id="consentForm".*?</form>""", RegexOption.DOT_MATCHES_ALL).find(response.responseBody)) {
          "no consent form: ${response.responseBody}"
        }.value.replace(Regex("<template.*?</template>", RegexOption.DOT_MATCHES_ALL), "")
        val action = unescape(checkNotNull(Regex("""<form[^>]*\saction="([^"]*)"""").find(form)).groupValues[1])
        val inputs = Regex("<input\\b[^>]*>").findAll(form).map { it.value }.toList()
        val hidden = inputs.filter { attribute(it, "type") == "hidden" }
          .associate { checkNotNull(attribute(it, "name")) to attribute(it, "value").orEmpty() }
        val scopes = inputs.filter { attribute(it, "type") == "checkbox" && attribute(it, "name") == "scope" }
        val durable = inputs.singleOrNull { attribute(it, "name") == "durable" }
        return ConsentPage(
          action = action,
          hidden = hidden,
          offered = scopes.map { checkNotNull(attribute(it, "value")) }.toSet(),
          ticked = scopes.filter { isChecked(it) }.map { checkNotNull(attribute(it, "value")) }.toSet(),
          durableTicked = durable != null && isChecked(durable),
          form = form,
        )
      }

      private fun attribute(tag: String, name: String): String? =
        Regex("""\s$name="([^"]*)"""").find(tag)?.groupValues?.get(1)?.let(::unescape)

      private fun isChecked(tag: String): Boolean = Regex("""\schecked\b""").containsMatchIn(tag)

      /** `escapeHtml`'s five entities undone, as a browser does before it submits a value. */
      private fun unescape(value: String): String =
        value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    }
  }

  /** Registers a public client over RFC 7591 and answers its `dyn:` identity. */
  fun register(pod: PodDbo, redirectUri: String = "http://localhost:5173/callback"): App {
    val response = http.preparePost("${podBase(pod)}/_system/auth/register")
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["$redirectUri"],"client_name":"Delegated App"}""")
      .execute()
    assertEquals(201, response.statusCode, response.responseBody)
    val clientId = JsonMappers.default().readValue(response.responseBody, Map::class.java)["client_id"] as String
    return App(clientId, redirectUri)
  }

  /** `GET /authorize` for [app] in the browser holding [cookie], with the S256 challenge. Redirects are not followed. */
  fun authorize(
    pod: PodDbo,
    app: App,
    cookie: String,
    scope: String? = null,
    prompt: String? = null,
    state: String = "delegated",
  ): TestHttpResponse {
    val request = http.prepareGet("${podBase(pod)}/_system/auth/authorize")
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", app.clientId)
      .addQueryParam("redirect_uri", app.redirectUri)
      .addQueryParam("state", state)
      .addQueryParam("code_challenge", CODE_CHALLENGE)
      .addQueryParam("code_challenge_method", "S256")
    scope?.let { request.addQueryParam("scope", it) }
    prompt?.let { request.addQueryParam("prompt", it) }
    return request.addHeader("Cookie", cookie).setFollowRedirect(false).execute()
  }

  /**
   * Submits [page] the way a browser does: to its action, with every hidden field it rendered.
   *
   * @param scopes the ticked `scope` boxes. Each must be one the page offers.
   * @param newContexts the contexts added with the page's "Create Context" control, by relative path,
   *   each with the permissions left ticked. The page must offer the control.
   * @param action the button pressed: `null` for Authorize, or `cancel`, `disconnect` or `signout`.
   *   The page must render that button.
   * @param extra fields posted beside the rest and checked against nothing: what a browser would not
   *   send, for a test of what the server refuses.
   */
  fun submit(
    page: ConsentPage,
    cookie: String,
    scopes: Set<String> = page.ticked,
    newContexts: Map<String, Set<String>> = emptyMap(),
    durable: Boolean = page.durableTicked,
    action: String? = null,
    extra: List<Pair<String, String>> = emptyList(),
  ): TestHttpResponse {
    require(page.offered.containsAll(scopes)) { "the page does not offer ${scopes - page.offered}" }
    require(newContexts.isEmpty() || page.has("newContextInput")) { "the page offers no context creation" }
    require(!durable || page.has("durableToggle")) { "the page offers no lifetime control" }
    action?.let { require(page.has(BUTTONS.getValue(it))) { "the page renders no $it button" } }
    val fields = page.hidden.toList() +
      scopes.map { "scope" to it } +
      newContexts.flatMap { (path, permissions) ->
        listOf("new_context" to path) + permissions.map { "new_context_scope" to "$path#$it" }
      } +
      listOfNotNull(if (durable) "durable" to "1" else null, action?.let { "action" to it }) +
      extra
    return http.preparePost(page.action)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", cookie)
      .setBody(fields.joinToString("&") { (name, value) -> "$name=${enc(value)}" })
      .setFollowRedirect(false).execute()
  }

  /**
   * The form token a page an older node rendered for [app] right now would carry: bound to the
   * consent standing now and to the count of endings it stands under, and to no request or rows.
   */
  @Suppress("DEPRECATION")
  fun formToken(pod: PodDbo, webId: String, app: App): String {
    val standing = consentDecisionStore.find(pod.podId(), app.clientId, listOf(webId))
    return consentTransactionStore.issue(pod.name, webId, standing?.generation, emptySet(), standing?.disconnects ?: 0L)
  }

  /**
   * Posts the consent form without rendering it first. Without [action] it ticks `public-read`
   * alone, which every pod from `SempodsTestFactory.newPod` offers.
   */
  fun consent(
    pod: PodDbo,
    webId: String,
    app: App,
    cookie: String,
    state: String,
    durable: Boolean = false,
    action: String? = null,
    csrf: String? = formToken(pod, webId, app),
  ): TestHttpResponse =
    http.preparePost("${podBase(pod)}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", cookie)
      .setBody(
        "client_id=${enc(app.clientId)}&redirect_uri=${enc(app.redirectUri)}&state=$state" +
          "&code_challenge=$CODE_CHALLENGE&code_challenge_method=S256" +
          (csrf?.let { "&csrf=${enc(it)}" } ?: "") +
          (action?.let { "&action=$it" } ?: "&scope=public-read") +
          (if (durable) "&durable=1" else ""),
      )
      .setFollowRedirect(false).execute()

  /** [consent], then the exchange: an app connected the way a browser connects it. */
  fun connect(pod: PodDbo, webId: String, app: App, cookie: String, durable: Boolean = false): Tokens {
    val exchanged = exchangeCode(pod, app, codeFrom(consent(pod, webId, app, cookie, state = "connect", durable = durable)))
    assertEquals(200, exchanged.statusCode, exchanged.responseBody)
    return Tokens.of(exchanged)
  }

  /** The authorization code in a redirect's `Location`. */
  fun codeFrom(response: TestHttpResponse): String {
    val location = checkNotNull(response.getHeader("Location")) { "no redirect: ${response.statusCode} ${response.responseBody}" }
    return Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1) ?: error("no code in $location")
  }

  /** The `authorization_code` exchange. A `null` [verifier] leaves `code_verifier` out. */
  fun exchangeCode(pod: PodDbo, app: App, code: String, verifier: String? = CODE_VERIFIER): TestHttpResponse = postForm(
    "${podBase(pod)}/_system/auth/token",
    "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(app.redirectUri)}" +
      "&client_id=${enc(app.clientId)}" + (verifier?.let { "&code_verifier=${enc(it)}" } ?: ""),
  )

  /** The `refresh_token` grant. */
  fun refresh(pod: PodDbo, app: App, refreshToken: String): TestHttpResponse = postForm(
    "${podBase(pod)}/_system/auth/token",
    "grant_type=refresh_token&refresh_token=${enc(refreshToken)}&client_id=${enc(app.clientId)}",
  )

  private fun postForm(url: String, body: String): TestHttpResponse =
    http.preparePost(url)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .setBody(body)
      .execute()

  private fun podBase(pod: PodDbo) = "${SempodsModule.config.apiBaseUrl}${pod.name}"

  private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

  companion object {
    /** The PKCE pair from RFC 7636 appendix B. */
    const val CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    const val CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    private val BUTTONS = mapOf("cancel" to "cancelBtn", "disconnect" to "disconnectBtn", "signout" to "signOutBtn")
  }
}
