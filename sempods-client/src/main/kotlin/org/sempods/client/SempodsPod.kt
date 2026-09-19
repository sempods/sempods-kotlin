package org.sempods.client

import okhttp3.Call

/**
 * One pod and what runs its requests — the handle the endpoint groups hang from.
 *
 * ```java
 * OkHttpClient client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
 * SempodsPod pod = new SempodsPod(
 *     new SempodsSession(SempodsPodBase.of("https://pods.example/alice")), client);
 *
 * SempodsResponse<SempodsPodDateModified> answer = pod.metadata().dateModified();
 * ```
 *
 * **Everything a call carries comes from [session] and [calls]**: authentication and confinement
 * from the session; resend, admission and the deadline from the client [SempodsOkHttp.install]
 * configured. No group adds a credential, an executor or a retry of its own. A handle is as cheap as
 * its session, and any number of them share one client.
 *
 * [calls] is a `Call.Factory`, so a factory wrapping an installed client — OpenTelemetry's
 * `createCallFactory` — serves as well as the client itself. Over a plain client a session's request
 * fails to resolve ([SempodsSession]).
 */
class SempodsPod(
  val session: SempodsSession,
  val calls: Call.Factory,
) {

  private val exchange = Exchange(calls)

  private val metadataGroup = SempodsPodMetadata.of(session, exchange)

  private val sparqlGroup = SempodsPodSparql.of(session, exchange)

  private val resourcesGroup = SempodsPodResources.of(ResourceOperations(session, exchange, ResourceAddress.LodPath(session.podBase)))

  private val systemOperations = ResourceOperations(session, exchange, ResourceAddress.SystemRoute)

  private val subjectsGroup = SempodsPodSubjects.of(systemOperations)

  private val slotsGroup = SempodsPodSlots.of(systemOperations)

  private val contextsGroup =
    SempodsPodContexts.of(ResourceOperations(session, exchange, ResourceAddress.RegistryPath(session.podBase)), sparqlGroup)

  /** Whether the pod exists, and when it was last written to. */
  fun metadata(): SempodsPodMetadata = metadataGroup

  /** SPARQL queries against the pod: typed SELECT and ASK results, and every result as the pod sent it. */
  fun sparql(): SempodsPodSparql = sparqlGroup

  /** Resources the pod hosts, at their own addresses. */
  fun resources(): SempodsPodResources = resourcesGroup

  /** Any subject by its IRI, through the System route. */
  fun subjects(): SempodsPodSubjects = subjectsGroup

  /** The values of one predicate on a subject, and single IRI values through their edges. */
  fun slots(): SempodsPodSlots = slotsGroup

  /** The contexts a session sees, what the registry holds for one, creating one, and exporting what is in one. */
  fun contexts(): SempodsPodContexts = contextsGroup
}
