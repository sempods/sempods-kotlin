package org.sempods.api.system.admin.pods

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
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
import org.sempods.pods.oauth.serviceclients.PodServiceClientFacade
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
 * Host-level pod lifecycle and app service-client provisioning over HTTP (control-plane admin
 * roadmap A1), authorized through the [AdminAuthorizer] seam — **not** pod-scoped: at `createPod`
 * the pod does not exist yet, so no `<context>#permission` scope could express the authority.
 *
 * Routes (all gated by [requireAdminOrThrow], see [AdminAuthorizedEndpoint]):
 *
 * ```
 * PUT    /_system/admin/pods/{pod}                             {ownerEmail}
 * DELETE /_system/admin/pods/{pod}
 * GET    /_system/admin/pods/{pod}
 * POST   /_system/admin/pods/{pod}/service-clients/{clientId}   {expectedRegistrationId?}
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
  private val podServiceClientFacade: PodServiceClientFacade,
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
   * Registers `clientId` as a 2-leg service client on `pod`, sandboxed to its own app root
   * (`<pod>/_system/contexts/apps/{clientId}#manage`) — the pod-side half of provisioning, which
   * a consumer used to perform in-process. See `docs/auth/service-clients.md`.
   *
   * Always performed, before anything else, because they are the sandbox invariant:
   * the app root context is created if missing, and a pre-existing **public** root is demoted to
   * private (a public root would expose every future descendant write to anonymous reads;
   * `createContext` is create-only and cannot fix it).
   *
   * **Idempotency contract.** The server does not and cannot know the caller's credential — only
   * the caller can decrypt and verify its own stored secret. So the caller asserts what it holds
   * via `expectedRegistrationId`:
   *
   * - It matches the current pod-side registration **and** the registered scope set is exactly the
   *   sandbox scope → nothing is written, 200 `{result:"alreadyProvisioned"}`, and **no secret is
   *   returned** (the existing one is still valid and the plaintext is unrecoverable here).
   * - Anything else — omitted, stale, or scope drift → the registration is replaced
   *   (unregister + register) and 200 `{result:"provisioned"}` carries the freshly minted secret.
   *   This is the correct answer for a caller with no credential at all (fresh pod) and for a
   *   half-provisioned state (registration exists, caller's credential row lost).
   *
   * The secret is returned **exactly once**, at the moment it is minted; the pod keeps only a
   * bcrypt hash. Re-minting invalidates the previous secret, but outstanding service tokens ride
   * out their ≤600 s TTL — revocation is registration-level, not token-level.
   *
   * **Concurrency.** The replace is conditional on the registration this request read: if another
   * provisioning call replaced it in between — or inserted first on a fresh pod — the answer is
   * `409`, not a `200` carrying a secret the competing registration already invalidated. The
   * server does not retry, because only the caller knows whether it now holds a usable
   * credential; it re-reads and decides, exactly as with `expectedRegistrationId`.
   *
   * What that does **not** promise: that a `200` stays valid forever. Two rotations without a
   * matching `expectedRegistrationId` both succeed and the later one wins — whether they run
   * back-to-back or overlap, because a rotation that observes the other's committed registration
   * is an ordinary rotation, not a lost update. Serialising per `(pod, clientId)` would not change
   * that; it would only make the order deterministic. Rejecting the second would mean declaring a
   * rotation invalid because another happened "recently", which is not a notion this contract has
   * — and it would break the self-healing path a stale `expectedRegistrationId` deliberately
   * takes. The caller detects the situation on its own side, where the knowledge is: the
   * credential no longer pairs with the pod-side registration id, so the next provisioning run
   * mints cleanly, which is what the caller's own health check is for.
   */
  @POST
  @Path("{pod}/service-clients/{clientId}")
  fun provisionServiceClient(
    @HeaderParam("Authorization") authorization: String?,
    @PathParam("pod") pod: String,
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

    if (!sempodsFacade.existsPod(pod)) {
      throw WebApplicationException(errorResponse(404, "unknown pod '$pod'"))
    }

    val rootContextUri = sempodsUriBuilder.buildContext(pod, "$APP_CONTEXT_ROOT_PREFIX$clientId")
    ensurePrivateAppRoot(pod = pod, clientId = clientId, rootContextUri = rootContextUri)

    val expectedScopes = setOf("$rootContextUri#manage")
    val existing = podServiceClientFacade.find(pod, clientId)
    // A row read back from Mongo always carries its `_id`; the type is nullable only because the
    // DBO doubles as the pre-insert shape. Assert it rather than let it flow on: below it is the
    // compare-and-swap key, and a `null` there would silently turn the conditional delete into an
    // unconditional one — re-opening exactly the race the condition exists for.
    val existingId = existing?.let { checkNotNull(it.id) { "registration without id: pod='$pod', clientId='$clientId'" } }

    if (existing != null &&
      request.expectedRegistrationId != null &&
      request.expectedRegistrationId == existingId.toString() &&
      existing.scopes == expectedScopes
    ) {
      return Response.ok(
        ProvisionServiceClientResponse(
          result = ALREADY_PROVISIONED,
          clientId = clientId,
          registrationId = existingId.toString(),
          // The stored set, not the expected one — on this path they are equal by definition
          // (scope drift is what sends the request down the re-mint branch instead).
          scopes = existing.scopes,
          contextRoot = rootContextUri.toString(),
        ),
      ).build()
    }

    if (existing != null) {
      logger.warn {
        "Pod '$pod': re-minting service client '$clientId' (expectedRegistrationId=" +
            "${LogSafeText.of(request.expectedRegistrationId.toString())}, current=$existingId, " +
            "scopes=${existing.scopes})"
      }
      // Conditional on the row we just read, not on `(pod, clientId)`: two concurrent replacements
      // would otherwise interleave as delete/insert/delete/insert, the second one removing the
      // first one's fresh registration — leaving that caller with a 200 whose secret no longer
      // authenticates. Removing nothing means someone else replaced it in between.
      if (!podServiceClientFacade.unregister(pod, clientId, expectedId = checkNotNull(existingId))) {
        throw conflict("service client '$clientId' on pod '$pod' was modified concurrently")
      }
    }

    val registered = try {
      podServiceClientFacade.register(
        podName = pod,
        clientId = clientId,
        scopes = expectedScopes,
        label = clientId,
      )
    } catch (e: MongoWriteException) {
      // Unique index on (podId, clientId) — a concurrent provisioning call inserted first. Same
      // answer as above: the caller has to re-read rather than receive a secret that a competing
      // registration already invalidated. Other write failures stay 500s; they are not conflicts
      // the caller can resolve by retrying.
      if (!e.isDuplicateKey()) throw e
      throw conflict("service client '$clientId' on pod '$pod' was provisioned concurrently")
    }

    logger.info {
      "Admin '$adminClientId' provisioned service client '$clientId' on pod '$pod' " +
          "(scope: $rootContextUri#manage)"
    }
    return Response.ok(
      ProvisionServiceClientResponse(
        result = PROVISIONED,
        clientId = clientId,
        registrationId = registered.dbo.id.toString(),
        scopes = registered.dbo.scopes,
        contextRoot = rootContextUri.toString(),
        secret = registered.plaintextSecret,
      ),
    ).build()
  }

  /**
   * Registers the app root context private, and demotes it if it already exists and is public.
   * `createContext` is create-only idempotent, so the demotion has to be explicit.
   */
  private fun ensurePrivateAppRoot(pod: String, clientId: String, rootContextUri: URI) {
    val created = podFacade.createContext(
      podName = pod,
      contextUri = rootContextUri,
      public = false,
      label = clientId,
      description = "Root of the $clientId-managed contexts (sandbox of the '$clientId' service client).",
    )
    if (!created && podFacade.getPublicContexts(pod).contains(rootContextUri)) {
      logger.warn { "Root context '$rootContextUri' on pod '$pod' was public — demoting to private." }
      podFacade.setContextPublic(podName = pod, contextUri = rootContextUri, public = false)
    }
  }

  /**
   * Parses an optional JSON body, rejecting unknown fields. The project-wide mapper ignores them,
   * which on an authorization-relevant body would let a typo'd `expectedRegistrationId` silently
   * become "no assertion" and re-mint a healthy client's secret — a fail-open. Same reasoning as
   * [org.sempods.api.pod.system.find.FindEndpoint]'s strict body mapper.
   */
  private fun <T> parseBody(body: String?, type: Class<T>): T? {
    val raw = body?.takeIf { it.isNotBlank() } ?: return null
    return try {
      strictBodyMapper.readValue(raw, type)
    } catch (e: Exception) {
      throw badRequest("invalid request body: ${e.message?.substringBefore('\n') ?: "could not parse"}")
    }
  }

  private fun badRequest(message: String): WebApplicationException =
    WebApplicationException(errorResponse(400, message))

  /**
   * 409 — the registration changed between reading it and replacing it. Deliberately not a retry
   * on the server: only the caller knows whether it now holds a usable credential, so it has to
   * re-read and decide (that is the same reasoning behind `expectedRegistrationId`).
   */
  private fun conflict(message: String): WebApplicationException =
    WebApplicationException(errorResponse(409, message))

  companion object {
    private val logger = KotlinLogging.logger {}

    private val strictBodyMapper = JsonMappers.newDefault()
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    /**
     * App-context convention (`docs/auth/service-clients.md`): an app's sandbox root is
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
   * The registration id the caller believes it holds a secret for. Absent means "I hold nothing" —
   * which always re-mints. See
   * [AdminPodsEndpoint.provisionServiceClient] for the full idempotency contract.
   */
  val expectedRegistrationId: String? = null,
)

/**
 * Response of `POST /_system/admin/pods/{pod}/service-clients/{clientId}`.
 *
 * [secret] is present **only** when [result] is `provisioned` — it is the one and only time the
 * plaintext exists outside the caller. On `alreadyProvisioned` nothing was written and no secret
 * can be produced.
 */
data class ProvisionServiceClientResponse(
  val result: String,
  val clientId: String,
  val registrationId: String,

  /**
   * The registration's scope set, verbatim — what the client may actually request at the token
   * endpoint. A statement about *state*, mirroring `PodServiceClientDbo.scopes`, so a caller can
   * verify its own health assumptions without re-deriving anything.
   */
  val scopes: Set<String>,

  /**
   * The sandbox root this call created or made sure of, and where the app hangs its own
   * sub-contexts. A statement about an *action*, which is why it is separate from [scopes]: it is
   * the one context host-level authority creates, because it cannot create contexts any other way
   * (`PodContextsEndpoint` authorizes on pod-owner principal or a covering `#manage` scope, and an
   * admin bearer is neither). Today the two are redundant — `scopes == {"$contextRoot#manage"}` —
   * but they answer different questions, and once a caller may pass its own scopes there is no
   * single "the root" to derive from them.
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
