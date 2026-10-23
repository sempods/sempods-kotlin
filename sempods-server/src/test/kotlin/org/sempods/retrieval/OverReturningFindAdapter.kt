package org.sempods.retrieval

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import java.util.concurrent.ConcurrentHashMap

/**
 * A [FindAdapter] that is not self-scoped and returns whatever a test armed it with for a pod,
 * ignoring the request, the context scope and the `type` constraint — an engine that over-returns.
 *
 * Bound for the whole suite, so it is keyed by pod name: a pod nobody armed it for gets nothing,
 * and every other `find` test runs as if it were not there.
 */
class OverReturningFindAdapter : FindAdapter {

  private val armed = ConcurrentHashMap<String, Model>()

  /** Return [model] from every `find` against [podName] until [disarm] is called. */
  fun arm(podName: String, model: Model) {
    armed[podName] = model
  }

  fun disarm(podName: String) {
    armed.remove(podName)
  }

  override fun find(request: FindRequest, sandbox: FindSandbox): Model =
    armed[sandbox.credentials.pod.name]?.let(::LinkedHashModel) ?: LinkedHashModel()
}
