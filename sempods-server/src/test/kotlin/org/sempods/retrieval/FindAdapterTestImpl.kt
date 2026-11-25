package org.sempods.retrieval

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel

/**
 * A [FindAdapter] standing in for an engine that annotates its hits, which the built-in SPARQL
 * adapter never does. It answers nothing until a test calls [answerWith].
 *
 * After [answerWith] it self-scopes as an index would: a statement in a context outside
 * [FindSandbox.effectiveScope] is left out, and one without a context passes. After [overReturnWith]
 * it is an index that cannot: it answers the whole model and says it is not [selfScoped]. The SPARQL
 * adapter still runs beside it, so a test that wants only the scripted answer searches for a text no
 * stored literal holds.
 *
 * Only a [stateful] instance takes an answer. The suite-wide default is not one, so no test can
 * leave an answer behind for every other test to receive, or keep its model alive for the run.
 */
class FindAdapterTestImpl(private val stateful: Boolean = false) : FindAdapter {

  @Volatile
  private var answer: Model = LinkedHashModel()

  @Volatile
  private var scoped: Boolean = true

  /** Answer [model] to every search, within the caller's selection. Only inside a `withSetup` block. */
  fun answerWith(model: Model) {
    check(stateful) { "the suite-wide find adapter takes no answer; script the setup's findAdapter in a withSetup block" }
    answer = LinkedHashModel(model)
    scoped = true
  }

  /** Answer all of [model] to every search, whatever the caller may read. Only inside a `withSetup` block. */
  fun overReturnWith(model: Model) {
    answerWith(model)
    scoped = false
  }

  override val selfScoped: Boolean get() = scoped

  override fun find(request: FindRequest, sandbox: FindSandbox): Model {
    if (!scoped) return LinkedHashModel(answer)
    val scope = sandbox.effectiveScope()?.mapTo(HashSet()) { it.toString() }
    return answer.filterTo(LinkedHashModel()) { st -> st.context == null || scope == null || st.context.stringValue() in scope }
  }
}
