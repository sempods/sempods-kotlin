package org.sempods.pods.oauth.serviceclients

import com.google.inject.Inject
import org.sempods.SempodsUriBuilder
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDbo
import org.bson.types.ObjectId

/**
 * Name-keyed surface over the pod service-client registry
 * ([PodServiceClientStore] / [PodServiceClientDao]).
 *
 * Pod rows are keyed by `podId` internally, and `podId` and the pod base URL are sempods-internal
 * concepts, so a caller that knows a pod by name only reaches the registry through here: this
 * facade resolves the pod and delegates. Scope strings are passed through verbatim and validated by
 * [PodServiceClientStore.register] against the pod base.
 *
 * **Module-internal, and its parameters are not the reason.** This used to say "public surface" for
 * "consumers in other modules", and the half of that which was true is the *name* keying — it is
 * genuinely the translation a caller outside cannot do. What made the claim untrue was the other
 * end: every method here answers with [PodServiceClientDbo], the stored row, which nothing outside
 * this module may read (`docs/architecture/module-layering.md` §"Module Boundaries"). A facade
 * earns a caller by taking values that caller can obtain *and* answering in values it may hold;
 * this one has always only done the first. Giving it a row-free answer is what would open it again,
 * and that is a design question rather than a modifier — #42, which #35 will reach first.
 */
class PodServiceClientFacade @Inject constructor(
  private val podDao: PodDao,
  private val podServiceClientDao: PodServiceClientDao,
  private val podServiceClientStore: PodServiceClientStore,
  private val sempodsUriBuilder: SempodsUriBuilder,
) {

  /**
   * Registers [clientId] on pod [podName] and returns the row plus the freshly minted
   * plaintext secret — the only moment the plaintext is available; the caller must
   * persist it (encrypted) on its side. Throws [IllegalArgumentException] for an unknown
   * pod, an invalid scope, or an already-registered `(pod, clientId)` pair (unique index).
   */
  internal fun register(
    podName: String,
    clientId: String,
    scopes: Set<String>,
    label: String? = null,
  ): PodServiceClientStore.Registered {
    val pod = requireNotNull(podDao.fetchByName(podName)) { "unknown pod '$podName'" }
    return podServiceClientStore.register(
      podId = checkNotNull(pod.id),
      podBaseUrl = sempodsUriBuilder.buildResourceUri(podName, "").toString(),
      clientId = clientId,
      scopes = scopes,
      label = label,
    )
  }

  /** The registration row for `(podName, clientId)`, or `null` (unknown pod included). */
  internal fun find(podName: String, clientId: String): PodServiceClientDbo? {
    val podId = podDao.fetchByName(podName)?.id ?: return null
    return podServiceClientDao.findByClientId(podId, clientId)
  }

  /**
   * Removes the registration for `(podName, clientId)`. Returns `true` if a row was
   * removed. Existing tokens stay valid until they expire (TTL 600 s) — revocation is
   * registration-level, not token-level.
   *
   * Pass [expectedId] when replacing a registration you just read: the delete then only hits
   * that exact row, so two concurrent replacements cannot delete each other's freshly inserted
   * one. A `false` return means the row changed underneath — re-read rather than continuing.
   *
   * TODO: deprovisioning removes only the registration — the app's context subtree
   *  (`_system/contexts/apps/<clientId>/...`) and its data stay. Undecided whether that should
   *  cascade or stay as orphaned data the pod owner can still reach. Leaning towards keeping it:
   *  the data is the owner's, and the contexts are now addressable through the management route,
   *  so they can be inspected and removed deliberately rather than by side effect.
   */
  internal fun unregister(podName: String, clientId: String, expectedId: ObjectId? = null): Boolean {
    val podId = podDao.fetchByName(podName)?.id ?: return false
    return podServiceClientDao.delete(podId, clientId, expectedId)
  }

  /**
   * Validates `(clientId, secret)` against the persisted hash — same semantics as
   * [PodServiceClientStore.authenticate], keyed by pod name.
   */
  internal fun authenticate(podName: String, clientId: String, secret: String): PodServiceClientDbo? {
    val podId = podDao.fetchByName(podName)?.id ?: return null
    return podServiceClientStore.authenticate(podId, clientId, secret)
  }
}
