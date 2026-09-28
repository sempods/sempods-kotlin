package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.SempodsModule
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.mongo.persist.PodDbo
import java.net.URLEncoder
import java.util.Base64
import kotlin.test.assertEquals

/**
 * Service access as a service and the owner's browser run it: a service registers itself, sends the
 * owner to its consent, and learns what it may do from the token endpoint and `GET /contexts`.
 *
 * Stateless, like [DelegatedAccessFlow], whose [DelegatedAccessFlow.ConsentPage] and
 * [DelegatedAccessFlow.submit] answer the dialog here too: read the page with [page], post it with
 * `submit`.
 */
internal class ServiceAccessFlow {

  @Inject
  private lateinit var http: TestHttpClient

  /** A registered service and the secret it was given once. */
  class Service(val clientId: String, val secret: String, val issuedAt: Long)

  /** Registers a service over RFC 7591, with no bearer: provisional until the owner confirms it. */
  fun register(pod: PodDbo, name: String = "Notes Sync", redirectUris: List<String> = emptyList()): Service {
    val redirects = if (redirectUris.isEmpty()) "" else
      ""","redirect_uris":[${redirectUris.joinToString(",") { "\"$it\"" }}]"""
    val response = http.preparePost("${podBase(pod)}/_system/auth/register")
      .addHeader("Content-Type", "application/json")
      .setBody(
        """{"client_name":"$name","grant_types":["client_credentials"],""" +
          """"token_endpoint_auth_method":"client_secret_basic"$redirects}""",
      )
      .execute()
    assertEquals(201, response.statusCode, response.responseBody)
    val body = json(response)
    return Service(
      clientId = body["client_id"] as String,
      secret = body["client_secret"] as String,
      issuedAt = (body["client_id_issued_at"] as Number).toLong(),
    )
  }

  /** `GET /service-consent` in the browser holding [cookie], or none. Redirects are not followed. */
  fun open(
    pod: PodDbo,
    clientId: String,
    cookie: String?,
    redirectUri: String? = null,
    state: String? = "consent-1",
  ): TestHttpResponse {
    val request = http.prepareGet(consentUrl(pod)).addQueryParam("client_id", clientId)
    redirectUri?.let { request.addQueryParam("redirect_uri", it) }
    state?.let { request.addQueryParam("state", it) }
    cookie?.let { request.addHeader("Cookie", it) }
    return request.setFollowRedirect(false).execute()
  }

  /** The service consent form in [response]. */
  fun page(response: TestHttpResponse): DelegatedAccessFlow.ConsentPage =
    DelegatedAccessFlow.ConsentPage.of(response, formId = "serviceConsentForm")

  /** The Client Credentials request the service sends. */
  fun token(pod: PodDbo, service: Service, secret: String = service.secret): TestHttpResponse =
    http.preparePost("${podBase(pod)}/_system/auth/token")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basic(service.clientId, secret))
      .setBody("grant_type=client_credentials")
      .execute()

  /** A token from [token], which must succeed. */
  fun accessToken(pod: PodDbo, service: Service): String {
    val minted = token(pod, service)
    assertEquals(200, minted.statusCode, minted.responseBody)
    return json(minted)["access_token"] as String
  }

  /** The `error` of a token endpoint answer, or `null` on a token. */
  fun tokenError(response: TestHttpResponse): String? =
    if (response.statusCode == 200) null else json(response)["error"] as String?

  /** The context IRIs [accessToken] reaches, as `GET /contexts` lists them. */
  @Suppress("UNCHECKED_CAST")
  fun contexts(pod: PodDbo, accessToken: String): List<String> {
    val response = http.prepareGet("${podBase(pod)}/_system/contexts")
      .addHeader("Accept", "application/json")
      .addHeader("Authorization", "Bearer $accessToken")
      .execute()
    assertEquals(200, response.statusCode, response.responseBody)
    return (json(response)["contexts"] as List<Map<String, Any?>>).map { it["context_iri"] as String }
  }

  fun consentUrl(pod: PodDbo) = "${podBase(pod)}/_system/auth/service-consent"

  @Suppress("UNCHECKED_CAST")
  private fun json(response: TestHttpResponse): Map<String, Any?> =
    JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>

  private fun podBase(pod: PodDbo) = "${SempodsModule.config.apiBaseUrl}${pod.name}"

  /** RFC 6749 §2.3.1: each half form-encoded first, which a `svc:` identifier needs. */
  private fun basic(clientId: String, secret: String): String =
    "Basic " + Base64.getEncoder().encodeToString("${enc(clientId)}:${enc(secret)}".toByteArray(Charsets.UTF_8))

  private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
