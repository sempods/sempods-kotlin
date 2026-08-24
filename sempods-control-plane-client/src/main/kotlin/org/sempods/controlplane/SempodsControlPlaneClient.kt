package org.sempods.controlplane

import org.sempods.client.SempodsBody
import org.sempods.client.SempodsClientException
import org.sempods.client.SempodsHttpTransport
import java.net.URI

/**
 * HTTP client for a sempods server's **host-level admin surface** — `{server}/_system/admin/pods/…`:
 * pod lifecycle and app provisioning.
 *
 * **This surface is not part of the pod specification and is not meant to become part of it.** A
 * pod's contract is what `:sempods-client` speaks: a graph, an addressing scheme and a permission
 * model under `{pod}/…`. Hosting *many* pods — creating them, deleting them, registering apps on
 * them — is a property of a deployment that hosts pods, and the reference implementation ships one.
 * That is why the two clients are two modules: a consumer implementing or consuming the
 * specification depends on `:sempods-client` alone, and nothing about this module can be mistaken
 * for the contract.
 *
 * **Bound to a server and a credential, deliberately unlike [org.sempods.client.SempodsClient].**
 * A pod base URL varies per call in a backend serving many pods; a host admin credential does not —
 * it is one per deployment, read from configuration at startup, and threading it through every call
 * only creates opportunities to thread the wrong one.
 *
 * The credential is the host-level secret the server checks with its `AdminAuthorizer`
 * (`SEMPODS_ADMIN_CLIENTS` on the server side). It grants **no** pod scopes and is not
 * interchangeable with the pod-scoped service tokens the data path uses: lifecycle authority cannot
 * be expressed as a pod permission, because at [createPod] the pod does not exist yet and there is
 * nothing to scope against. See `docs/modularity.md` §"The authority boundary outlived the
 * types" and the maintainer's internal roadmap.
 *
 * Known consumers: an application backend that provisions pods for its users, and — planned — the
 * operator UI `admin.sempods.org` (roadmap A3) and the pod-owner layer `my.sempods.org` for its
 * "create a pod here" affordance.
 */
class SempodsControlPlaneClient(
  private val serverBaseUrl: URI,
  private val adminSecret: String,
  private val transport: SempodsHttpTransport = SempodsHttpTransport(),
) {

  private val objectMapper = transport.objectMapper

  /**
   * `PUT {server}/_system/admin/pods/{pod}` — creates [podName] owned by [ownerEmail].
   *
   * Idempotent by contract: an existing pod answers [CreatePodResult.alreadyExists] and keeps its
   * stored owner. The distinction is returned rather than thrown because the caller has to act on
   * it — a caller that treats "already exists" as success would go on to compensate away somebody
   * else's pod.
   */
  fun createPod(podName: String, ownerEmail: String): CreatePodResult {
    val targetUrl = adminPodUrl(podName)
    val body = objectMapper.writeValueAsBytes(mapOf("ownerEmail" to ownerEmail))

    val request = transport.newRequest(targetUrl, adminSecret)
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .PUT(SempodsBody.bytes(body))
      .build()

    val response = transport.send(request)
    return when (response.statusCode) {
      201 -> CreatePodResult.created
      200 -> CreatePodResult.alreadyExists
      else -> throw transport.failure("PUT", targetUrl, response.statusCode, response.body)
    }
  }

  /**
   * `DELETE {server}/_system/admin/pods/{pod}` — idempotent, 204 for a pod that was never there.
   *
   * Deletes pod-side state only. Callers holding their own per-pod rows (a stored credential, say)
   * clean those up themselves.
   */
  fun deletePod(podName: String) {
    val targetUrl = adminPodUrl(podName)

    val request = transport.newRequest(targetUrl, adminSecret)
      .DELETE()
      .build()

    val response = transport.send(request)
    if (response.statusCode / 100 != 2) {
      throw transport.failure("DELETE", targetUrl, response.statusCode, response.body)
    }
  }

  /**
   * `GET {server}/_system/admin/pods/{pod}` — the authorized existence check. The route answers 404
   * for an unknown pod rather than `exists: false`, so the boolean comes from the status.
   *
   * A caller on the data path asks `SempodsClient.podExists(podBaseUrl)` instead, which needs no
   * credential. Both questions exist on purpose: the answer here carries host authority, and code
   * that only needs to know whether a pod is there must not acquire that authority to find out.
   */
  fun podExists(podName: String): Boolean {
    val targetUrl = adminPodUrl(podName)

    val request = transport.newRequest(targetUrl, adminSecret)
      .header("Accept", "application/json")
      .GET()
      .build()

    val response = transport.send(request)
    if (response.statusCode == 404) {
      return false
    }
    if (response.statusCode / 100 != 2) {
      throw transport.failure("GET", targetUrl, response.statusCode, response.body)
    }
    return true
  }

  /**
   * `POST {server}/_system/admin/pods/{pod}/service-clients/{clientId}` — registers [clientId] as a
   * service client on [podName] and returns the sandbox root it was scoped to.
   *
   * [expectedRegistrationId] is the caller's assertion about what it already holds: matching it
   * yields `alreadyProvisioned` **without** a secret, anything else re-mints and returns one
   * exactly once. Passing `null` means "I hold nothing", which always re-mints.
   *
   * A `409` means a concurrent caller won the race. The server deliberately does not retry — only
   * the caller knows whether it now holds a usable credential — so this surfaces as
   * [SempodsClientException] with `statusCode == 409` for the caller's own retry loop.
   */
  fun provisionServiceClient(
    podName: String,
    clientId: String,
    expectedRegistrationId: String?,
  ): ProvisionServiceClientResult {
    val targetUrl = adminBase()
      .resolve(adminPodPath(podName) + "/service-clients/" + transport.urlPathSegment(clientId))
    val body = objectMapper.writeValueAsBytes(mapOf("expectedRegistrationId" to expectedRegistrationId))

    val request = transport.newRequest(targetUrl, adminSecret)
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(SempodsBody.bytes(body))
      .build()

    val response = transport.send(request)
    if (response.statusCode / 100 != 2) {
      throw transport.failure("POST", targetUrl, response.statusCode, response.body)
    }
    val root = objectMapper.readTree(response.body)
    val scopes = root.path("scopes").takeIf { it.isArray }
      ?.mapNotNull { node -> node.takeIf { it.isTextual }?.asText() }
      ?.toSet()
      ?: throw SempodsClientException(
        "Response from $targetUrl missing 'scopes': ${response.body.take(200)}"
      )

    return ProvisionServiceClientResult(
      alreadyProvisioned = transport.requiredText(root, "result", targetUrl, response.body) == "alreadyProvisioned",
      clientId = root.path("clientId").takeIf { it.isTextual }?.asText() ?: clientId,
      registrationId = transport.requiredText(root, "registrationId", targetUrl, response.body),
      scopes = scopes,
      contextRoot = URI(transport.requiredText(root, "contextRoot", targetUrl, response.body)),
      // absent on `alreadyProvisioned` (@JsonInclude NON_NULL) — the caller keeps what it holds
      secret = root.path("secret").takeIf { it.isTextual }?.asText(),
    )
  }

  private fun adminBase(): URI = transport.baseWithTrailingSlash(serverBaseUrl)

  private fun adminPodUrl(podName: String): URI = adminBase().resolve(adminPodPath(podName))

  private fun adminPodPath(podName: String): String =
    "_system/admin/pods/" + transport.urlPathSegment(podName)
}

/** Outcome of [SempodsControlPlaneClient.createPod] — `PUT` answers 201 for a fresh pod and 200 for one that was already there. */
enum class CreatePodResult { created, alreadyExists }

/**
 * Outcome of [SempodsControlPlaneClient.provisionServiceClient].
 *
 * [contextRoot] and [scopes] come back on **both** results, so no caller has to rebuild the
 * `apps/<clientId>` convention. [secret] is present only when the registration was (re-)minted;
 * on [alreadyProvisioned] the caller keeps the credential it already holds, because the server
 * hands a secret out exactly once.
 */
data class ProvisionServiceClientResult(
  val alreadyProvisioned: Boolean,
  val clientId: String,
  val registrationId: String,
  val scopes: Set<String>,
  val contextRoot: URI,
  val secret: String?,
)
