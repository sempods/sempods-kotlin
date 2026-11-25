package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpRequest
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.mongo.persist.PodDbo
import java.net.URLEncoder
import kotlin.test.assertEquals

/**
 * A `did:web` service's calls, as [ServiceAccessFlow] is a `svc:` one's: registration with a
 * software statement, and a token with a client assertion. The keys are [DidWebService]'s.
 */
internal class DidWebAccessFlow {

  @Inject
  private lateinit var http: TestHttpClient

  /** `POST /register` for [statement], or with none where it is `null`, with [bearer] if given. */
  fun register(pod: PodDbo, statement: String?, name: String = "Owner Console", bearer: String? = null): TestHttpResponse {
    val body = linkedMapOf<String, Any>(
      "client_name" to name,
      "grant_types" to listOf("client_credentials"),
      "token_endpoint_auth_method" to "private_key_jwt",
    )
    statement?.let { body["software_statement"] = it }
    return http.preparePost(registerEndpoint(pod))
      .addHeader("Content-Type", "application/json")
      .apply { bearer?.let { addHeader("Authorization", "Bearer $it") } }
      .setBody(JsonMappers.default().writeValueAsString(body))
      .execute()
  }

  /** Registers [service] on [pod], which must succeed: provisional until the owner consents. */
  fun registered(pod: PodDbo, service: DidWebService): DidWebService {
    val response = register(pod, service.statement(pod))
    assertEquals(201, response.statusCode, response.responseBody)
    return service
  }

  fun token(pod: PodDbo, assertion: String): TestHttpResponse = tokenRequest(pod, assertion).execute()

  /** A token for [service], which must be issued. */
  fun accessToken(pod: PodDbo, service: DidWebService): String {
    val minted = token(pod, service.assertion(pod))
    assertEquals(200, minted.statusCode, minted.responseBody)
    return json(minted)["access_token"] as String
  }

  fun tokenRequest(pod: PodDbo, assertion: String, clientId: String? = null): TestHttpRequest =
    http.preparePost(DidWebService.tokenEndpoint(pod))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .setBody(
        "grant_type=client_credentials" +
          "&client_assertion_type=${enc("urn:ietf:params:oauth:client-assertion-type:jwt-bearer")}" +
          "&client_assertion=${enc(assertion)}" +
          (clientId?.let { "&client_id=${enc(it)}" } ?: ""),
      )

  fun registerEndpoint(pod: PodDbo) = "${DidWebService.issuer(pod)}/_system/auth/register"

  @Suppress("UNCHECKED_CAST")
  fun json(response: TestHttpResponse): Map<String, Any?> =
    JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>

  private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
}
