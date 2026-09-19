package org.sempods.controlplane

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import org.sempods.client.core.SempodsContent
import org.sempods.client.core.SempodsExchange
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsRequestAuth
import org.sempods.client.core.SempodsResponse
import org.sempods.client.core.SempodsSession
import java.io.IOException
import java.net.URI

/**
 * HTTP client for a sempods server's **host-level admin surface** — `{server}/_system/admin/pods/…`:
 * pod lifecycle and app provisioning.
 *
 * **This surface is not part of the pod specification and is not meant to become part of it.** A
 * pod's contract is what `:sempods-client-core` speaks: a graph, an addressing scheme and a
 * permission model under `{pod}/…`. Hosting *many* pods — creating them, deleting them, registering
 * apps on them — is a property of a deployment that hosts pods, and the reference implementation
 * ships one. That is why the two clients are two modules: a consumer implementing or consuming the
 * specification depends on `:sempods-client-core` alone, and nothing about this module can be
 * mistaken for the contract.
 *
 * ```java
 * OkHttpClient client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
 * var admin = new SempodsControlPlaneClient(
 *     new SempodsSession(SempodsPodBase.of("https://server.example"),
 *         SempodsRequestAuth.bearer(adminSecret)),
 *     client);
 *
 * if (admin.createPod("alice", "alice@example.com").getBody() == CreatePodResult.created) { … }
 * ```
 *
 * **The target is a host, and [SempodsPodBase] is the type that validates one.** Its clauses,
 * [SPS-CORE-019](https://github.com/sempods/sempods-spec/blob/main/spec/core/index.md#SPS-CORE-019)
 * and [SPS-CORE-020](https://github.com/sempods/sempods-spec/blob/main/spec/core/index.md#SPS-CORE-020),
 * ask for no pod segment — a pod may sit on the host root — so a server root such as
 * `https://server.example` is a base it accepts as it stands. What follows for confinement is that a
 * base with no path segment contains every URL on its scheme, host and port, which is exactly the
 * reach of host authority: it spans the host rather than a path under it. What follows for a
 * deployment is that `http` is usable only against a loopback address, as it is for a pod.
 *
 * **Bound to a server and a credential**, the way a pod client binds a pod and one.
 * A pod base URL varies per call in a backend serving many pods; a host admin credential does not —
 * it is one per deployment, read from configuration at startup, and threading it through every call
 * only creates opportunities to thread the wrong one.
 *
 * The credential is the host-level secret the server checks with its `AdminAuthorizer`
 * (`SEMPODS_ADMIN_CLIENTS` on the server side), carried as the session's [SempodsRequestAuth] — so a
 * deployment answering an API key, a bearer plus a header of its own, or a credential minted per
 * attempt supplies it through the interface a pod session uses, and this class needs no knowledge of
 * which. It grants **no** pod scopes and is not interchangeable with the pod-scoped service tokens
 * the data path uses: lifecycle authority cannot be expressed as a pod permission, because at
 * [createPod] the pod does not exist yet and there is nothing to scope against. See
 * `docs/concepts/modularity.md` §"The authority boundary".
 *
 * Every call runs on [session] and the client behind [calls], so it carries that credential and its
 * recovery, the confinement, the admission budget, the outbound guard and the call's deadline, and
 * `Call.cancel()` reaches it. A request built here therefore never reaches a pod under some other
 * session's credential, and no pod call carries this one.
 *
 * An application backend uses this authority to provision pods for its users. Proposed
 * owner/operator surfaces are tracked in https://github.com/sempods/sempods-kotlin/issues/139.
 */
class SempodsControlPlaneClient(
  private val session: SempodsSession,
  calls: Call.Factory,
) {

  private val exchange = SempodsExchange(calls)

  /**
   * `PUT {server}/_system/admin/pods/{pod}` — creates [podName] owned by [ownerEmail].
   *
   * Idempotent by contract: an existing pod answers [CreatePodResult.alreadyExists] and keeps its
   * stored owner. The distinction is returned rather than thrown because the caller has to act on
   * it — a caller that treats "already exists" as success would go on to compensate away somebody
   * else's pod.
   */
  @Throws(IOException::class)
  fun createPod(podName: String, ownerEmail: String): SempodsResponse<CreatePodResult> {
    val request = pods("PUT", podName)
      .header("Accept", APPLICATION_JSON)
      .put(json(ControlPlaneJson.podOwner(ownerEmail)))
      .build()

    // The outcome is the status rather than the body, and a decoder is handed only the body — so the
    // answer is read here and its status closed over, which is what keeps the two in one object.
    val answer = exchange.bytes(request, 201, 200)
    return answer.map {
      if (answer.status == 201) CreatePodResult.created else CreatePodResult.alreadyExists
    }
  }

  /**
   * `DELETE {server}/_system/admin/pods/{pod}` — idempotent, 204 for a pod that was never there.
   *
   * **204 is the only answer listed**, because it is the only one the route has: deleting an unknown
   * pod is a no-op that answers 204 like any other removal. A 200 therefore means something else
   * answered — a proxy, or a server whose contract has drifted — and reading it as a removal that
   * happened is how a caller comes to believe a pod is gone that is still there.
   *
   * Deletes pod-side state only. Callers holding their own per-pod rows (a stored credential, say)
   * clean those up themselves.
   */
  @Throws(IOException::class)
  fun deletePod(podName: String): SempodsResponse<ByteArray> =
    exchange.bytes(pods("DELETE", podName).build(), 204)

  /**
   * `GET {server}/_system/admin/pods/{pod}` — the authorized existence check, `200` for a pod the
   * server knows and `404` for one it does not.
   *
   * **Both are answers**, so the question is `getStatus() != 404`: the route answers a document
   * rather than `exists: false`, and a `404` therefore arrives with a null body like any listed
   * status outside 2xx. A refusal — an unconfigured admin authority, a bad credential — is neither,
   * and stays an exception.
   *
   * A caller on the data path asks `SempodsPodMetadata.exists` instead, which needs no
   * credential. Both questions exist on purpose: the answer here carries host authority, and code
   * that only needs to know whether a pod is there must not acquire that authority to find out.
   */
  @Throws(IOException::class)
  fun podExists(podName: String): SempodsResponse<ByteArray> =
    exchange.bytes(pods("GET", podName).header("Accept", APPLICATION_JSON).build(), 200, 404)

  /**
   * `POST {server}/_system/admin/pods/{pod}/service-clients/{clientId}` — registers [clientId] as a
   * service client on [podName] and returns the sandbox root it was scoped to.
   *
   * [expectedRegistrationId] is the caller's assertion about what it already holds: matching it
   * yields `alreadyProvisioned` **without** a secret, anything else re-mints and returns one
   * exactly once. Passing `null` means "I hold nothing", which always re-mints.
   *
   * **Deliberately not repeatable.** A `POST` is resent after a lost connection only on
   * `SempodsRepeatable`, and this one carries no such mark: an attempt that reached the server before
   * the connection went already minted a secret, and a second attempt would mint another — leaving
   * the caller holding the one credential the server no longer accepts.
   *
   * A `409` means a concurrent caller won the race. The server deliberately does not retry — only
   * the caller knows whether it now holds a usable credential — so it is not listed as an answer and
   * arrives as a `SempodsStatusException` with `status == 409` for the caller's own retry loop.
   */
  @Throws(IOException::class)
  fun provisionServiceClient(
    podName: String,
    clientId: String,
    expectedRegistrationId: String?,
  ): SempodsResponse<ProvisionServiceClientResult> {
    val request = pods("POST", podName, SERVICE_CLIENTS, clientId)
      .header("Accept", APPLICATION_JSON)
      .post(json(ControlPlaneJson.provisionRequest(expectedRegistrationId)))
      .build()

    return exchange.text(request, 200).map { ControlPlaneJson.provisioned(it, clientId) }
  }

  /**
   * The admin pods route under this server, with the segments a call addresses.
   *
   * The request is built through the session, so it carries it — the URL is then extended rather
   * than composed, which leaves the encoding to OkHttp and the server's address to the session. A
   * segment arrives from a caller, and one holding a separator is refused by the confinement rather
   * than quietly addressing something else.
   */
  private fun pods(method: String, vararg segments: String): Request.Builder {
    val built = session.newRequest(method, ADMIN_PODS).build()
    val url = built.url.newBuilder().apply { segments.forEach(::addPathSegment) }.build()
    return built.newBuilder().url(url)
  }

  private fun json(body: String) = SempodsContent.of(body).requestBody(APPLICATION_JSON.toMediaType())

  private companion object {

    /**
     * This surface belongs to the reference implementation, so its path is spelled here.
     * `SempodsPodRoutes` holds the paths relative to a *pod*, which the specification owns.
     */
    const val ADMIN_PODS = "_system/admin/pods"

    const val SERVICE_CLIENTS = "service-clients"

    const val APPLICATION_JSON = "application/json"
  }
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
