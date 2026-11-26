package org.sempods.client

import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * The grants held on each context of a pod, and the withdrawal of one direct grant — what an owner
 * console shows its owner. Only explicit grants are listed: a public context is readable by anyone,
 * and withdrawing grants leaves that as it is. Experimental, and specific to the sempods reference implementation: the
 * specification leaves an owner's grant management to implementations. `sempods-server/docs/auth/service-clients.md`
 * §"Managing grants" has the pod's side.
 *
 * Every call needs a bearer carrying `grants:manage`, which only a service client holding the pod's
 * management authority receives. A pod offering it lists the scope in
 * `sempods_service_management_scopes` in its authorization-server metadata; on a pod that does not,
 * every call here is refused.
 *
 * ```java
 * var grants = new SempodsPodGrants(new SempodsSession(pod, SempodsRequestAuth.bearer(token)), client);
 * SempodsContextHolders holders = grants.holders(contextIri).getBody();
 * for (SempodsGrantPerson person : holders.getPeople()) {
 *   if (person.getGrants().stream().anyMatch(SempodsReachingGrant::isDirect)) {
 *     grants.withdrawPerson(contextIri, person.getWebId());
 *   }
 * }
 * ```
 *
 * **Only a direct grant is withdrawn on a context.** One whose [SempodsReachingGrant.inheritedFrom]
 * names an ancestor is withdrawn there, and the pod answers `409` here. A withdrawal holds on the
 * holder's next request.
 *
 * **What is safe to send again:** every call. A withdrawal whose answer was lost answers, on the
 * resend, `false` where nothing is left, `409` where an inherited grant still reaches the context,
 * and [withdrawService] `412`, because the version moved. Read [holders] again before deciding.
 *
 * A refusal is a [SempodsStatusException] with the status, the headers and the pod's error document:
 *
 * | The pod answers | Means |
 * |---|---|
 * | `401` with `WWW-Authenticate: Bearer error="invalid_token"` | the token is unknown, expired or withdrawn |
 * | `403` with `error="insufficient_scope"` | the token lacks `grants:manage`, or the service's authority ended with a change of owner |
 * | `404` | [holders]: no such context. A withdrawal answers `false` instead |
 * | `409` | a withdrawal: the holder reaches the context only through an ancestor; the error document's `inherited_from` names it |
 * | `412` | [withdrawService]: the grants changed since [SempodsGrantService.grantsVersion]; read them again |
 * | `428` | [withdrawService] sent without a version; this client always sends one |
 */
class SempodsPodGrants(
  val session: SempodsSession,
  val calls: Call.Factory,
) {

  private val exchange = Exchange(calls)

  /**
   * How many people, owner's apps and services hold grants reaching each registered context, in one
   * request. A service holding the management authority is not counted: its tokens reach no data.
   */
  @Throws(IOException::class)
  fun counts(): SempodsResponse<List<SempodsContextHolderCounts>> = exchange.run(countsRequest(), ANSWERS, COUNTS)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun countsJson(): SempodsResponse<String> = exchange.run(countsRequest(), ANSWERS, BodyReading.TEXT)

  /** Who holds grants reaching [contextIri], grouped by person, the owner's apps and services. */
  @Throws(IOException::class)
  fun holders(contextIri: String): SempodsResponse<SempodsContextHolders> = exchange.run(holdersRequest(contextIri), ANSWERS, HOLDERS)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun holdersJson(contextIri: String): SempodsResponse<String> = exchange.run(holdersRequest(contextIri), ANSWERS, BodyReading.TEXT)

  /**
   * Withdraws [webId]'s direct grants on [contextIri], and with them the apps acting for that person
   * there. `true` for `204`, `false` for `404`: they hold nothing there to withdraw.
   */
  @Throws(IOException::class)
  fun withdrawPerson(contextIri: String, webId: String): Boolean =
    exchange.status(withdrawal(contextIri, PEOPLE, webId).build(), WITHDRAW_ANSWERS) == 204

  /** Withdraws what the owner delegated to [clientId] on [contextIri]. `true` for `204`, `false` for `404`. */
  @Throws(IOException::class)
  fun withdrawApp(contextIri: String, clientId: String): Boolean =
    exchange.status(withdrawal(contextIri, APPS, clientId).build(), WITHDRAW_ANSWERS) == 204

  /**
   * Withdraws [clientId]'s direct grants on [contextIri], keeping its others — only if its grants are
   * still at [grantsVersion], the [SempodsGrantService.grantsVersion] of a read: otherwise `412`.
   * `true` for `204`, `false` for `404`.
   */
  @Throws(IOException::class)
  fun withdrawService(contextIri: String, clientId: String, grantsVersion: Long): Boolean {
    require(grantsVersion >= 0) { "A grants version is not negative." }
    val request = withdrawal(contextIri, SERVICES, clientId).header("If-Match", "\"$grantsVersion\"").build()
    return exchange.status(request, WITHDRAW_ANSWERS) == 204
  }

  private fun countsRequest(): Request = session.newRequest("GET", GRANTS).header("Accept", "application/json").build()

  private fun holdersRequest(contextIri: String): Request =
    session.newRequest("GET", GRANTS, segment(contextIri, "context")).header("Accept", "application/json").build()

  private fun withdrawal(contextIri: String, group: String, holder: String): Request.Builder =
    session.newRequest("DELETE", GRANTS, segment(contextIri, "context"), group, segment(holder, "holder"))
      .header("Accept", "application/json")

  private companion object {

    const val GRANTS = "_system/auth/grants"

    const val PEOPLE = "people"

    const val APPS = "apps"

    const val SERVICES = "services"

    val ANSWERS = (200..299).toSet()

    val WITHDRAW_ANSWERS = setOf(204, 404)

    /** base64url without padding over the UTF-8 bytes (RFC 4648 §5), as `_system/resources` addresses an IRI. */
    fun segment(value: String, role: String): String {
      require(value.isNotBlank()) { "A $role must not be blank." }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    val COUNTS = BodyReading<List<SempodsContextHolderCounts>> { bytes, _ ->
      decodeObject(bytes).objects("contexts").map { entry ->
        SempodsContextHolderCounts.of(
          contextIri = entry.string("context_iri"),
          people = count(entry, "people"),
          apps = count(entry, "apps"),
          services = count(entry, "services"),
        )
      }
    }

    val HOLDERS = BodyReading<SempodsContextHolders> { bytes, _ ->
      val document = decodeObject(bytes)
      SempodsContextHolders.of(
        contextIri = document.string("context_iri"),
        people = document.objects("people").map { person ->
          SempodsGrantPerson.of(
            webId = person.string("web_id"),
            permissions = person.strings("permissions"),
            grants = grants(person),
            delegations = person.objects("delegations").map(::app),
          )
        },
        apps = document.objects("apps").map(::app),
        services = document.objects("services").map { service ->
          SempodsGrantService.of(
            clientId = service.string("client_id"),
            clientName = service.stringOrNull("client_name"),
            permissions = service.strings("permissions"),
            grants = grants(service),
            lastUsedAt = instant(service, "last_used_at"),
            grantsVersion = service.longOrNull("grants_version") ?: throw service.violation("grants_version: expected an integer"),
          )
        },
      )
    }

    fun app(document: ProtocolObject): SempodsGrantApp = SempodsGrantApp.of(
      clientId = document.string("client_id"),
      clientName = document.stringOrNull("client_name"),
      permissions = document.strings("permissions"),
      grants = grants(document),
      lastUsedAt = instant(document, "last_used_at"),
    )

    fun grants(document: ProtocolObject): List<SempodsReachingGrant> = document.objects("grants").map {
      SempodsReachingGrant.of(permission = it.string("permission"), inheritedFrom = it.stringOrNull("inherited_from"))
    }

    fun count(document: ProtocolObject, name: String): Int =
      document.longOrNull(name)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        ?: throw document.violation("$name: expected a count")

    /** An RFC 3339 time, as this route writes one; absent or null where the pod records none. */
    fun instant(document: ProtocolObject, name: String): Instant? = document.stringOrNull(name)?.let {
      try {
        Instant.parse(it)
      } catch (_: DateTimeParseException) {
        throw document.violation("$name: expected an RFC 3339 time")
      }
    }
  }
}
