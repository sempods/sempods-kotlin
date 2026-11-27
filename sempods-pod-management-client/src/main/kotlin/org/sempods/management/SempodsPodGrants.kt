package org.sempods.management

import okhttp3.Call
import okhttp3.Request
import org.sempods.client.SempodsExchange
import org.sempods.client.SempodsResponse
import org.sempods.client.SempodsSession
import java.io.IOException
import java.util.Base64

/**
 * The grants held on each context of a pod, and the withdrawal of one direct grant — what an owner
 * console shows its owner. Only explicit grants are listed: a public context is readable by anyone,
 * and withdrawing grants leaves that as it is. `sempods-server/docs/auth/service-clients.md`
 * §"Managing grants" has the pod's side.
 *
 * **These routes are the sempods reference implementation's own, and stay so:** the specification
 * leaves an owner's grant management to implementations (sempods-spec#137). That is why this is a
 * module of its own rather than a group of `:sempods-client`, which speaks what any pod offers.
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

  private val exchange = SempodsExchange(calls)

  /**
   * How many people, owner's apps and services hold grants reaching each registered context, in one
   * request. A service holding the management authority is not counted: its tokens reach no data.
   */
  @Throws(IOException::class)
  fun counts(): SempodsResponse<List<SempodsContextHolderCounts>> = countsJson().map { GrantsJson.counts(it) }

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun countsJson(): SempodsResponse<String> = exchange.text(countsRequest(), *ANSWERS)

  /** Who holds grants reaching [contextIri], grouped by person, the owner's apps and services. */
  @Throws(IOException::class)
  fun holders(contextIri: String): SempodsResponse<SempodsContextHolders> = holdersJson(contextIri).map { GrantsJson.holders(it) }

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun holdersJson(contextIri: String): SempodsResponse<String> = exchange.text(holdersRequest(contextIri), *ANSWERS)

  /**
   * Withdraws [webId]'s direct grants on [contextIri], and with them the apps acting for that person
   * there. `true` for `204`, `false` for `404`: they hold nothing there to withdraw.
   */
  @Throws(IOException::class)
  fun withdrawPerson(contextIri: String, webId: String): Boolean =
    exchange.status(withdrawal(contextIri, PEOPLE, webId).build(), 204, 404) == 204

  /** Withdraws what the owner delegated to [clientId] on [contextIri]. `true` for `204`, `false` for `404`. */
  @Throws(IOException::class)
  fun withdrawApp(contextIri: String, clientId: String): Boolean =
    exchange.status(withdrawal(contextIri, APPS, clientId).build(), 204, 404) == 204

  /**
   * Withdraws [clientId]'s direct grants on [contextIri], keeping its others — only if its grants are
   * still at [grantsVersion], the [SempodsGrantService.grantsVersion] of a read: otherwise `412`.
   * `true` for `204`, `false` for `404`.
   */
  @Throws(IOException::class)
  fun withdrawService(contextIri: String, clientId: String, grantsVersion: Long): Boolean {
    require(grantsVersion >= 0) { "A grants version is not negative." }
    val request = withdrawal(contextIri, SERVICES, clientId).header("If-Match", "\"$grantsVersion\"").build()
    return exchange.status(request, 204, 404) == 204
  }

  private fun countsRequest(): Request = session.newRequest("GET", GRANTS).header("Accept", APPLICATION_JSON).build()

  private fun holdersRequest(contextIri: String): Request =
    session.newRequest("GET", GRANTS, segment(contextIri, "context")).header("Accept", APPLICATION_JSON).build()

  private fun withdrawal(contextIri: String, group: String, holder: String): Request.Builder =
    session.newRequest("DELETE", GRANTS, segment(contextIri, "context"), group, segment(holder, "holder"))
      .header("Accept", APPLICATION_JSON)

  private companion object {

    const val GRANTS = "_system/auth/grants"

    const val PEOPLE = "people"

    const val APPS = "apps"

    const val SERVICES = "services"

    const val APPLICATION_JSON = "application/json"

    val ANSWERS = (200..299).toList().toIntArray()

    /** base64url without padding over the UTF-8 bytes (RFC 4648 §5), as `_system/resources` addresses an IRI. */
    fun segment(value: String, role: String): String {
      require(value.isNotBlank()) { "A $role must not be blank." }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    }
  }
}
