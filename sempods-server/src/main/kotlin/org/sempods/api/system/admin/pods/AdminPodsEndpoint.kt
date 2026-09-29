package org.sempods.api.system.admin.pods

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.inject.Inject
import com.mongodb.MongoWriteException
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.mongo.isDuplicateKey
import org.sempods.commons.logging.LogSafeText
import org.sempods.SempodsFacade
import org.sempods.SempodsUriBuilder
import org.sempods.admin.AdminAuthorizer
import org.sempods.api.system.admin.AdminAuthorizedEndpoint
import org.sempods.pods.PodFacade
import org.sempods.pods.PodRepositoryCache
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.oauth.flows.PodServiceClientProvisioning
import org.sempods.pods.oauth.flows.PodServiceClientRefusal
import org.sempods.pods.oauth.flows.PodServiceClientRequest
import org.sempods.pods.oauth.flows.PodServiceClientResult
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Host-level pod lifecycle and app service-client provisioning over HTTP, authorized through
 * [AdminAuthorizer]. Its KDoc explains why pod-scoped permissions cannot express this authority.
 *
 * Routes (all gated by [requireAdminOrThrow], see [AdminAuthorizedEndpoint]):
 *
 * ```
 * PUT    /_system/admin/pods/{pod}                             {ownerEmail}
 * DELETE /_system/admin/pods/{pod}
 * GET    /_system/admin/pods/{pod}
 * POST   /_system/admin/pods/{pod}/service-clients/{clientId}   {expectedRegistrationId?, expectedSecretId?}
 * ```
 *
 * The only root resource under `_system/admin` since the maintenance route was retired with the
 * legacy decommission. JAX-RS selects root resources by literal character
 * count, so `_system/admin/pods` (18) still wins over `PodResourceEndpoint`'s `{pod: [^/]+}` (0) —
 * a pod may not be named `_system`. `AdminPodsEndpointHttpTest` guards that.
 *
 * Takes the module facades directly rather than a service abstraction over them: this endpoint *is*
 * where pod lifecycle happens, so it must never route back out over a pod-scoped HTTP binding —
 * that would be a request calling itself. With the facades named here there is no binding left that
 * could point anywhere else.
 */
@Path("_system/admin/pods")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class AdminPodsEndpoint @Inject constructor(
  private val sempodsFacade: SempodsFacade,
  private val podDao: PodDao,
  private val podFacade: PodFacade,
  private val podRepositoryCache: PodRepositoryCache,
  private val webIdUriDeriver: WebIdUriDeriver,
  private val podServiceClientProvisioning: PodServiceClientProvisioning,
  private val sempodsUriBuilder: SempodsUriBuilder,
  adminAuthorizer: AdminAuthorizer,
) : AdminAuthorizedEndpoint(adminAuthorizer) {

  /**
   * Creates the pod for `ownerEmail`. The owner is stored as the WebID derived from the email —
   * sempods knows persons only as WebID URIs, and the caller's own user ids must not be sent here.
   *
   * Idempotent, like every `PUT` in this codebase (cf.
   * [org.sempods.api.pod.system.contexts.PodContextsEndpoint.put]): an existing pod yields
   * 200 `alreadyExists` and its stored owner is **not** overwritten — this route creates pods, it
   * does not transfer ownership. A fresh pod yields 201 `created`.
   */
  @PUT
  @Path("{pod}")
  fun createPod(
    @HeaderParam("Authorization") authorization: String?,
    @PathParam("pod") pod: String,
    body: String?,
  ): Response {

    val adminClientId = requireAdminOrThrow(authorization)
    val request = parseBody(body, CreatePodRequest::class.java)
      ?: throw badRequest("missing JSON request body")
    val ownerEmail = request.ownerEmail?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw badRequest("ownerEmail must not be blank")

    if (sempodsFacade.existsPod(pod)) {
      return Response.ok(PodLifecycleResponse(pod = pod, result = ALREADY_EXISTS)).build()
    }

    val ownerWebId = try {
      // sempods knows persons only as WebID URIs: the email is derived and then dropped, never
      // stored. Derivation sits inside the `try` because an address no WebID can be derived from
      // fails the same way a rejected pod name does — see the second catch.
      val webId = webIdUriDeriver.deriveFromEmail(ownerEmail)
      podDao.insert(name = pod, owner = webId)
      webId
    } catch (e: MongoWriteException) {
      // Unique index on the pod name — a concurrent create won the race. The post-condition the
      // caller asked for holds either way, so this stays idempotent rather than surfacing a 500.
      // Only for that case: another write failure reported as `alreadyExists` would tell the
      // caller a pod is there when none is.
      if (!e.isDuplicateKey()) throw e
      return Response.ok(PodLifecycleResponse(pod = pod, result = ALREADY_EXISTS)).build()
    } catch (e: IllegalArgumentException) {
      // Rejected pod name (`SempodsUriBuilder.checkPodName`) or an email no WebID can be derived from — both
      // are bad input from the caller, not a server fault.
      throw badRequest(e.message ?: "invalid pod name or ownerEmail")
    }

    logger.info { "Admin '$adminClientId' created pod '$pod' for owner '$ownerWebId'" }
    return Response.status(201).entity(PodLifecycleResponse(pod = pod, result = CREATED)).build()
  }

  /**
   * Deletes the pod and everything cascading from it (RDF resources, contexts, grants, tokens,
   * service-client registrations, audit log — see [org.sempods.SempodsFacade.deletePod]).
   *
   * Idempotent: deleting an unknown pod is a no-op and still answers 204. Callers keeping their own
   * per-pod state (a stored credential row, say) clean that up on their side — the server does not
   * know about it.
   */
  @DELETE
  @Path("{pod}")
  fun deletePod(
    @HeaderParam("Authorization") authorization: String?,
    @PathParam("pod") pod: String,
  ): Response {

    val adminClientId = requireAdminOrThrow(authorization)

    // Drop the in-memory RDF repository first so that an in-flight SPARQL query cannot observe
    // stale state while the DB rows below disappear. Ordered here rather than inside
    // [SempodsFacade.deletePod] on purpose: PodRepositoryCache already injects SempodsFacade, so
    // a reverse injection would introduce a Guice cycle.
    podRepositoryCache.invalidate(pod)
    sempodsFacade.deletePod(pod)

    // Unlike the create above, this route never resolves the name: deleting an unknown pod is a
    // no-op that still answers 204, so nothing has vouched for `pod` by the time it is logged.
    logger.info { "Admin '$adminClientId' deleted pod '${LogSafeText.of(pod)}'" }
    return Response.noContent().build()
  }

  /** 200 with `{pod, exists:true}` if the pod exists, 404 otherwise. */
  @GET
  @Path("{pod}")
  fun existsPod(
    @HeaderParam("Authorization") authorization: String?,
    @PathParam("pod") pod: String,
  ): Response {

    requireAdminOrThrow(authorization)

    if (!sempodsFacade.existsPod(pod)) {
      throw WebApplicationException(errorResponse(404, "unknown pod '$pod'"))
    }
    return Response.ok(PodExistsResponse(pod = pod, exists = true)).build()
  }

  /**
   * Registers `clientId` as a service client on `pod`, sandboxed to its own app root
   * (`<pod>/_system/contexts/apps/{clientId}#manage`). What each call writes and answers, and the
   * idempotency through `expectedRegistrationId` and `expectedSecretId`, is
   * `sempods-server/docs/host-provisioning.md` §"Provisioning over the admin surface".
   *
   * **Concurrency.** A `409` is not retried here, because only the caller knows whether it now
   * holds a usable credential; it re-reads and decides, as with the two identifiers.
   *
   * What that does **not** promise: that a `200` stays valid forever. Two calls without matching
   * identifiers that do not overlap both succeed and the later secret wins. Rejecting the second
   * would mean declaring a rotation invalid because another happened "recently", which is not a
   * notion this contract has. The caller detects it on its next run: the `secretId` it stored is no
   * longer the current one, so it is answered a new secret rather than `alreadyProvisioned`.
   */
  @POST
  @Path("{pod}/service-clients/{clientId}")
  fun provisionServiceClient(
    @HeaderParam("Authorization") authorization: String?,
    @PathParam("pod") podName: String,
    @PathParam("clientId") clientId: String,
    body: String?,
  ): Response {

    val adminClientId = requireAdminOrThrow(authorization)
    // The clientId becomes a context path segment below, so this is the path-traversal guard —
    // not merely input hygiene.
    if (!CLIENT_ID_PATTERN.matches(clientId)) {
      throw badRequest("clientId must match ${CLIENT_ID_PATTERN.pattern}")
    }
    val request = parseBody(body, ProvisionServiceClientRequest::class.java)
      ?: ProvisionServiceClientRequest()

    // Read once; every store call below takes the pod.
    val pod = podDao.fetchByName(podName)?.toHostedPod(sempodsUriBuilder)
      ?: throw WebApplicationException(errorResponse(404, "unknown pod '$podName'"))

    // This route's own policy, and deliberately not the provisioning contract's: an owner-facing
    // registration starts with no grants and gets them from the owner, where operator provisioning
    // had nobody to ask and derives a sandbox instead.
    val rootContextUri = sempodsUriBuilder.buildContext(podName, "$APP_CONTEXT_ROOT_PREFIX$clientId")

    val result = podServiceClientProvisioning.provision(
      pod = pod,
      request = PodServiceClientRequest(
        clientId = clientId,
        scopes = setOf("$rootContextUri#manage"),
        label = clientId,
        expectedRegistrationId = request.expectedRegistrationId,
        expectedSecretId = request.expectedSecretId,
      ),
      beforeCreating = { ensurePrivateAppRoot(podName = podName, clientId = clientId, rootContextUri = rootContextUri) },
    )

    // One answer, three ways of arriving at it — a field added to the response must not be
    // addable to one branch only.
    val (outcome, registration, secret) = when (result) {
      is PodServiceClientResult.AlreadyProvisioned ->
        Triple(ALREADY_PROVISIONED, result.registration, null)

      is PodServiceClientResult.Provisioned -> {
        logger.info {
          "Admin '$adminClientId' provisioned service client '$clientId' on pod '$podName' " +
              "(registration: ${result.registration.id}, scopes: ${result.registration.scopes})"
        }
        Triple(PROVISIONED, result.registration, result.secret)
      }

      is PodServiceClientResult.Refused -> throw conflict(
        when (result.reason) {
          PodServiceClientRefusal.MODIFIED_CONCURRENTLY ->
            "service client '$clientId' on pod '$podName' was modified concurrently"
          PodServiceClientRefusal.PROVISIONED_CONCURRENTLY ->
            "service client '$clientId' on pod '$podName' was provisioned concurrently"
        },
      )
    }
    return Response.ok(
      ProvisionServiceClientResponse(
        result = outcome,
        clientId = clientId,
        registrationId = registration.id.value,
        secretId = registration.secretId,
        // The stored set: an existing registration's grants are the owner's.
        scopes = registration.scopes,
        contextRoot = rootContextUri.toString(),
        secret = secret,
      ),
    ).build()
  }

  /**
   * Registers the app root context private, and demotes it if it already exists and is public.
   * `createContext` is create-only idempotent, so the demotion has to be explicit. Runs only before
   * a registration is created: afterwards the root is the owner's.
   */
  private fun ensurePrivateAppRoot(podName: String, clientId: String, rootContextUri: URI) {
    val created = podFacade.createContext(
      podName = podName,
      contextUri = rootContextUri,
      public = false,
      label = clientId,
      description = "Root of the $clientId-managed contexts (sandbox of the '$clientId' service client).",
    )
    if (!created && podFacade.getPublicContexts(podName).contains(rootContextUri)) {
      logger.warn { "Root context '$rootContextUri' on pod '$podName' was public — demoting to private." }
      podFacade.setContextPublic(podName = podName, contextUri = rootContextUri, public = false)
    }
  }

  /**
   * Parses an optional JSON body with [JsonMappers.strict]: a typo'd `expectedRegistrationId` must
   * not silently become "no assertion" and replace a healthy client's secret.
   */
  private fun <T> parseBody(body: String?, type: Class<T>): T? {
    val raw = body?.takeIf { it.isNotBlank() } ?: return null
    return try {
      JsonMappers.strict().readValue(raw, type)
    } catch (e: Exception) {
      throw badRequest("invalid request body: ${e.message?.substringBefore('\n') ?: "could not parse"}")
    }
  }

  private fun badRequest(message: String): WebApplicationException =
    WebApplicationException(errorResponse(400, message))

  /**
   * 409 — the registration changed between reading it and writing it. Deliberately not a retry
   * on the server: only the caller knows whether it now holds a usable credential, so it has to
   * re-read and decide (that is the same reasoning behind `expectedRegistrationId`).
   */
  private fun conflict(message: String): WebApplicationException =
    WebApplicationException(errorResponse(409, message))

  companion object {
    private val logger = KotlinLogging.logger {}

    /**
     * App-context convention (`sempods-server/docs/host-provisioning.md`): an app's sandbox root is
     * the `apps` type under the reserved context namespace, i.e.
     * `<pod>/_system/contexts/apps/<clientId>` once `SempodsUriBuilder.buildContext` prepends the
     * prefix. This is the only place a type root is created — the management route refuses to,
     * because host-level authority has no other way to create a context.
     */
    private const val APP_CONTEXT_ROOT_PREFIX = "apps/"

    /** Keeps the clientId a single, traversal-safe path segment. */
    private val CLIENT_ID_PATTERN = Regex("[A-Za-z0-9._-]+")

    private const val CREATED = "created"
    private const val ALREADY_EXISTS = "alreadyExists"
    private const val ALREADY_PROVISIONED = "alreadyProvisioned"
    private const val PROVISIONED = "provisioned"
  }
}

/** Body of `PUT /_system/admin/pods/{pod}`. */
data class CreatePodRequest(
  /**
   * Email of the pod owner. The server derives the owner WebID from it
   * ([org.sempods.commons.identity.WebIdUriDeriver.deriveFromEmail]); it never stores the email itself.
   *
   * Only checked for being non-blank: derivation is a lowercase-and-hash, so any string yields a
   * well-formed WebID and the server has no way to tell a typo from a real address. Verifying that
   * the address belongs to the person is the caller's job.
   */
  val ownerEmail: String? = null,
)

/** Response of `PUT /_system/admin/pods/{pod}`: `result` is `created` or `alreadyExists`. */
data class PodLifecycleResponse(
  val pod: String,
  val result: String,
)

/** Response of `GET /_system/admin/pods/{pod}` (404 when the pod is unknown). */
data class PodExistsResponse(
  val pod: String,
  val exists: Boolean,
)

/** Body of `POST /_system/admin/pods/{pod}/service-clients/{clientId}`. */
data class ProvisionServiceClientRequest(
  /**
   * The registration id the caller believes it holds a secret for. Absent means "I hold nothing",
   * which answers a new secret whether or not a registration exists. See
   * [AdminPodsEndpoint.provisionServiceClient] for the full idempotency contract.
   */
  val expectedRegistrationId: String? = null,

  /**
   * The `secretId` of the secret the caller holds. Absent means "I do not know", which answers a new
   * secret wherever a registration exists.
   */
  val expectedSecretId: String? = null,
)

/**
 * Response of `POST /_system/admin/pods/{pod}/service-clients/{clientId}`.
 *
 * [secret] is present **only** when [result] is `provisioned` — it is the one and only time the
 * plaintext exists outside the caller. On `alreadyProvisioned` nothing was written and no secret
 * can be produced. [registrationId] stays the same across calls until the registration is removed.
 */
data class ProvisionServiceClientResponse(
  val result: String,
  val clientId: String,
  val registrationId: String,

  /**
   * Names the secret that authenticates now: the one in [secret], or on `alreadyProvisioned` the one
   * the caller holds. It changes with every secret issued, and says nothing about the secret itself.
   */
  val secretId: String,

  /**
   * The registration's scope set, verbatim — what the client may actually request at the token
   * endpoint. A statement about *state*, mirroring `ServiceClientRegistration.scopes`. The sandbox
   * scope on the creating call; afterwards whatever the owner left, possibly nothing.
   */
  val scopes: Set<String>,

  /**
   * The sandbox root, where the app hangs its own sub-contexts. The creating call created it; a later
   * one names it without looking, so it may since have been deleted or made public by the owner.
   * Separate from [scopes], which states what the client holds now. It is the one context
   * host-level authority creates, because it cannot create contexts any other way
   * (`PodContextsEndpoint` authorizes on pod-owner principal or a covering `#manage` scope, and an
   * admin bearer is neither).
   *
   * Returned on **both** results, because the caller needs it either way. Sent rather than left to
   * be derived so the naming convention lives in one place: a caller that rebuilds this string is
   * silently wrong the day the convention moves — it would keep writing under the old root while
   * its scope points at the new one, surfacing as runtime 403s instead of a compile error. The
   * convention has moved once (contexts into `_system/contexts/`), which is what made the point
   * concrete rather than hypothetical.
   */
  val contextRoot: String,

  // Omitted entirely (not serialized as null) so `alreadyProvisioned` responses carry no hint of
  // a secret at all.
  @field:[JsonProperty JsonInclude(JsonInclude.Include.NON_NULL)]
  val secret: String? = null,
)
