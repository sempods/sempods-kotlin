package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.PodFacade
import org.sempods.pods.contexts.ContextPathRules
import org.sempods.pods.contexts.ContextUriResolution
import org.sempods.pods.grants.GrantRecipient
import org.sempods.pods.grants.GrantReplacement
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.ScopePermission
import java.net.URI

/**
 * What a consent dialog's grant selection is worth, for any recipient: which rows were ticked, which
 * contexts to create, and the replace that makes the selection the recipient's grants.
 *
 * **A submission the dialog could not have produced is refused whole, before anything is written.**
 * Dropping the odd row instead would not be harmless: the replace is complete, so a row that
 * silently fell out takes a grant with it that the person never saw go.
 *
 * **Contexts are created before the grants are replaced**, and stay when the replace is refused
 * afterwards: private, owner-only, with no grant. [Applied.created] names them, so the answer can say
 * which exist now and that no access changed. There is no transaction and no automatic deletion.
 */
internal class ConsentSelection @Inject constructor(
  private val podFacade: PodFacade,
  private val podGrantsFacade: PodGrantsFacade,
) {

  /**
   * What the dialog put to the person, from its transaction.
   *
   * @param scopes every value it rendered as a `scope` checkbox.
   * @param contextCreation whether it let the person create contexts.
   */
  data class Offer(val scopes: Set<String>, val contextCreation: Boolean)

  /** The selection fields as posted — untrimmed, any of them absent. */
  data class Submission(
    val scopes: List<String>?,
    val newContexts: List<String>?,
    val newContextScopes: List<String>?,
  )

  /** A context to create, with the permissions ticked on it. */
  data class PendingContext(val relativePath: String, val uri: URI, val permissions: Set<ScopePermission>)

  /** What [parse] made of a submission. */
  sealed interface Parsed {

    /** Nothing ticked anywhere: the empty confirmation. Pending contexts without a box ticked are not built. */
    data object Empty : Parsed

    /** The dialog could not have produced this. Nothing was written. */
    data class Refused(val error: OAuthErrorCode, val description: String) : Parsed

    /**
     * @param scopes the ticked existing rows, `public-read` among them where ticked.
     * @param pending the contexts to create, keyed by relative path.
     */
    data class Selection(val scopes: Set<String>, val pending: Map<String, PendingContext>) : Parsed {
      val publicRead: Boolean get() = PUBLIC_READ_SCOPE in scopes
    }
  }

  /**
   * @param replacement what [PodGrantsFacade.replaceGrants] answered; `null` where no ticked row
   *   survived the resolution, and nothing was replaced. What an empty selection means is the
   *   recipient's to say.
   * @param created the contexts this call created, in the order they were posted.
   */
  data class Applied<R : GrantReplacement>(val replacement: R?, val created: List<URI>)

  /**
   * Checks the submission against [offer] and the context rules, and writes nothing.
   *
   * @param offer `null` for a transaction an older node wrote, which bound no rows
   *   (`ConsentTransactionStore`, rollout): rows are then taken as posted and only filtered in
   *   [apply], as before, and only an owner may create contexts. The context rules hold either way.
   */
  fun parse(pod: HostedPod, submission: Submission, offer: Offer?, isOwner: Boolean): Parsed {
    val scopes = submission.scopes.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.toSet()
    val newContexts = submission.newContexts.orEmpty()
      .map { ContextPathRules.normalize(it) }
      .filter { it.isNotBlank() }
      .distinct()
    // `<relative-path>#<permission>`: a context that does not exist yet has no IRI to name, so the
    // form posts its path and this resolves it against the IRI built below.
    val newContextScopes = submission.newContextScopes.orEmpty().map { it.trim() }.filter { it.isNotBlank() }

    if (offer != null && !offer.scopes.containsAll(scopes)) {
      logger.warn {
        "[oauth/consent] rejected: rows this dialog did not offer (pod='${pod.name}', " +
            "rows=${LogSafeText.of((scopes - offer.scopes).sorted().joinToString(" "))})"
      }
      return Parsed.Refused(OAuthErrorCode.INVALID_SCOPE, "a context this dialog did not offer")
    }

    // Nothing ticked is the empty confirmation, and it is answerable before any context is checked:
    // a pending context with no permission ticked would be built for an authorization that is not
    // happening.
    if (scopes.isEmpty() && newContextScopes.isEmpty()) return Parsed.Empty

    val creationOffered = offer?.contextCreation ?: isOwner
    if (newContexts.isNotEmpty() && !creationOffered) {
      return refusedContext(pod, newContexts.first(), "this dialog does not create contexts")
    }

    val existing = if (newContexts.isEmpty()) emptySet() else podFacade.getContexts(pod.name)
    val resolved = mutableMapOf<String, URI>()
    for (relativePath in newContexts) {
      ContextPathRules.rejectionReason(relativePath)?.let { return refusedContext(pod, relativePath, it) }
      // The builder the management route uses, so the two cannot disagree about what a path maps
      // to. Concatenating instead once persisted `…/_system/contexts/foo#bar`: unaddressable, and
      // ambiguous against the `<iri>#<permission>` grammar.
      val uri = when (val resolution = ContextPathRules.resolve(pod.baseUrl, relativePath)) {
        is ContextUriResolution.Rejected -> return refusedContext(pod, relativePath, resolution.reason)
        is ContextUriResolution.Resolved -> resolution.uri
      }
      // The dialog offers an existing context as a row, never as one to create. One that exists
      // now was created by somebody else while the page was open.
      if (uri in existing) return refusedContext(pod, relativePath, "the context exists already")
      resolved[relativePath] = uri
    }

    val permissions = mutableMapOf<String, MutableSet<ScopePermission>>()
    for (raw in newContextScopes) {
      val relativePath = ContextPathRules.normalize(raw.substringBeforeLast('#', missingDelimiterValue = ""))
      val permission = ScopePermission.of(raw.substringAfterLast('#', missingDelimiterValue = ""))
      if (permission == null || relativePath !in resolved) {
        return refusedContext(pod, relativePath, "not a permission on a context this submission creates")
      }
      permissions.getOrPut(relativePath) { mutableSetOf() } += permission
    }

    // A pending context nobody ticked a box on is still created: the person added it to the list.
    val pending = resolved.mapValues { (path, uri) -> PendingContext(path, uri, permissions[path].orEmpty()) }
    return Parsed.Selection(scopes, pending)
  }

  /**
   * Creates the pending contexts, then replaces [recipient]'s grants with the selection.
   *
   * The selection is resolved against what [approverUris] may delegate once the contexts exist. A
   * row the person lost while the page was open drops out here. That is a race, and the rest of
   * the submission stands.
   *
   * @param approverUris every URI that names the person deciding, and [approver] the one they are
   *   signed in as: the contexts' creator and the grants' `grantedBy`.
   */
  fun <R : GrantReplacement> apply(
    pod: HostedPod,
    selection: Parsed.Selection,
    recipient: GrantRecipient<R>,
    approverUris: Collection<String>,
    approver: String,
  ): Applied<R> {
    val created = selection.pending.values.mapNotNull { context ->
      // Private and owner-only: the owner is the creator, and nothing else is granted here but the
      // ticked rows below.
      val isNew = podFacade.createContext(pod = pod, contextUri = context.uri, createdBy = approver)
      logger.info { "[oauth/consent] Context created: pod='${pod.name}', context='${context.uri}', new=$isNew" }
      context.uri.takeIf { isNew }
    }
    val pendingScopes = selection.pending.values.flatMap { context ->
      context.permissions.map { "${context.uri}#${it.value}" }
    }
    val delegatable = podGrantsFacade.resolveUserGrants(pod, approverUris)
    val contextScopes = (selection.scopes - PUBLIC_READ_SCOPE + pendingScopes).filterTo(mutableSetOf()) { it in delegatable }
    val chosen = if (selection.publicRead) contextScopes + PUBLIC_READ_SCOPE else contextScopes
    if (chosen.isEmpty()) return Applied(null, created)
    val replacement = podGrantsFacade.replaceGrants(pod, recipient, chosen, grantedBy = approver)
    return Applied(replacement, created)
  }

  private fun refusedContext(pod: HostedPod, relativePath: String, reason: String): Parsed.Refused {
    logger.warn {
      "[oauth/consent] rejected: a context to create (pod='${pod.name}', " +
          "path='${LogSafeText.of(relativePath)}') — ${LogSafeText.of(reason)}"
    }
    // The reason stays in the log: it can quote the path, and the path is the person's own input.
    return Parsed.Refused(OAuthErrorCode.INVALID_REQUEST, "a context to create was refused")
  }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
