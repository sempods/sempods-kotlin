package org.sempods.pods.contexts

import com.google.inject.Inject
import org.sempods.ExperimentalSempodsServerApi
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.pods.grants.EffectiveContextPermissions
import org.sempods.pods.grants.OFFLINE_ACCESS_SCOPE
import org.sempods.pods.grants.PodScopeValidator
import java.util.Optional

/**
 * Bound by the module that serves context creation and deletion, the spec's `context-management`
 * module (`:sempods-context-management`). Its presence is the whole signal: this server holds no
 * route for the lifecycle itself.
 */
@ExperimentalSempodsServerApi
interface ContextManagementInstalled

/**
 * Whether this deployment serves context creation and deletion, and what follows here when it does
 * not.
 *
 * Without the module, nothing may promise a lifecycle no route serves: [CONTEXTS_MANAGE_SCOPE] is
 * not requestable, not in `scopes_supported` and not part of a service's management authority, and
 * the registry reports no `manage` in any representation ([shown]; sempods-spec
 * `docs/proposals/context-contract.md` §"Caller access summary"). A `#manage` grant keeps covering writes and sub-contexts: that is the
 * grant model's, not the module's.
 *
 * The scope stays known to the validator either way, so a token carrying it is still read as a
 * privileged one and reaches no data.
 */
internal class ContextManagementSupport @Inject constructor(
  installed: Optional<ContextManagementInstalled>,
) {

  /** Whether the lifecycle routes are served. */
  val offered: Boolean = installed.isPresent

  /** The privileged scopes an authorization request may name here ([PodScopeValidator.requestablePrivilegedScopes]). */
  val requestablePrivilegedScopes: Set<String> = offeredOf(PodScopeValidator.requestablePrivilegedScopes)

  /** What a service's management authority carries here ([PodScopeValidator.serviceManagementScopes]). */
  val serviceManagementScopes: Set<String> = offeredOf(PodScopeValidator.serviceManagementScopes)

  /**
   * `scopes_supported` (RFC 9728 §2, RFC 8414 §2): everything a request may name. A privileged
   * scope no request may name is absent; it reaches a service only inside its management authority,
   * which `sempods_service_management_scopes` advertises.
   */
  val scopesSupported: List<String> = PodScopeValidator.featureScopes.filter {
    it !in PodScopeValidator.privilegedFeatureScopes || it in requestablePrivilegedScopes
  } + OFFLINE_ACCESS_SCOPE

  /**
   * What the registry tells a caller it may do, in every representation: [effective] as resolved,
   * without `manage` where no lifecycle is served. Read, write and the grant's source stay, so a
   * `#manage` grant still shows the contexts it writes.
   */
  fun shown(effective: EffectiveContextPermissions): EffectiveContextPermissions =
    if (offered) {
      effective
    } else {
      effective.copy(byContext = effective.byContext.mapValues { (_, entry) -> entry.copy(permissions = entry.permissions - "manage") })
    }

  /** [scopes] without the ones only the module gives a meaning. */
  private fun offeredOf(scopes: Set<String>): Set<String> = if (offered) scopes else scopes - CONTEXTS_MANAGE_SCOPE
}
