package org.sempods.pods.grants

import org.sempods.pods.oauth.PodAccessToken
import org.sempods.spec.PodRef

/**
 * The **authorization seam** (`docs/modularity.md`): decides which contexts a caller may
 * reach on a pod, and returns that as the [SempodsCredentials] every endpoint below `{pod}/…`
 * carries.
 *
 * Sits behind the verification, not beside it: `PodTokenAuthenticator` establishes *who is calling*
 * — signature, expiry, the issuer being this pod — and is concrete, because every deployment of
 * this server verifies the same self-issued JWT. What a verified caller may then see is the part a
 * deployment can genuinely differ on: the grant model this implementation runs, a fixed read-only
 * view over virtual contexts, or an external policy engine.
 *
 * One implementation exists:
 *
 * - [GrantStorePodAuthorizer] — the reference model: durable per-context grants resolved per
 *   request from the grant store, the slash-delimited `<root>#manage` cascade, and `public-read`
 *   as an additive feature scope. [org.sempods.SempodsModule] binds it unconditionally; there is no
 *   authorization switch to get wrong.
 *
 * **What an implementation may decide, and what it may not.** It decides **which** contexts a
 * caller sees — it never decides **whether** the sandbox applies. An implementation that returned
 * "all contexts, unfiltered" for an untrusted caller would not be a configuration choice but a
 * conformance break (`docs/modularity.md` §"What is not selectable"). The invariants in
 * `sempods/AGENTS.md` hold for every binding of this interface, which is what keeps a conformance
 * suite meaningful: it runs against the invariants, not against a particular set of bindings.
 *
 * Implementations must not throw for a caller they cannot authorize — an unknown client or a
 * revoked grant is an empty context set, not an error. Whether an empty set is refused, and with
 * which status, is the endpoint's decision.
 */
interface PodAuthorizer {

  /**
   * Resolves what [token] may reach on [pod]. [token] is already verified; an implementation must
   * not re-decide whether the caller is authentic.
   */
  fun authorize(pod: PodRef, token: PodAccessToken): SempodsCredentials

  /**
   * Resolves what a caller with no bearer at all may reach on [pod]. Not a failure path: a pod is
   * Linked Open Data, so every endpoint accepts anonymous reads on whatever the implementation
   * considers public.
   */
  fun anonymous(pod: PodRef): SempodsCredentials
}
