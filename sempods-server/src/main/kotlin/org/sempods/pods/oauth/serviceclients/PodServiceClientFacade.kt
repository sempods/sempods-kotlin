package org.sempods.pods.oauth.serviceclients

import com.google.inject.Inject
import org.sempods.SempodsUriBuilder
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDbo
import org.bson.types.ObjectId

/**
 * Name-keyed surface over the pod service-client registry
 * ([PodServiceClientStore] / [PodServiceClientDao]): resolves a pod name to its `podId` and
 * delegates. Scope strings are passed through verbatim and validated by
 * [PodServiceClientStore.register] against the pod base.
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
