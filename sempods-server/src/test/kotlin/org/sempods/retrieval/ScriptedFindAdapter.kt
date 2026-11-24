package org.sempods.retrieval

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import java.util.concurrent.ConcurrentHashMap

/**
 * A [FindAdapter] that answers a scripted model for one search text, and nothing for any other: an
 * engine that annotates its hits, which the built-in SPARQL adapter never does.
 *
 * It self-scopes as an index would: a statement in a context outside [FindSandbox.effectiveScope]
 * is left out, and one without a context passes. A test scripts a text no stored literal holds, so
 * the SPARQL adapter beside it finds nothing for that text, and no other test reaches the script.
 */
class ScriptedFindAdapter : FindAdapter {

  private val answers = ConcurrentHashMap<String, Model>()

  /** Answer [model] to a search for [text], a single token. */
  fun script(text: String, model: Model) {
    answers[text] = model
  }

  override fun find(request: FindRequest, sandbox: FindSandbox): Model {
    val model = answers[request.tokens.joinToString(" ")] ?: return LinkedHashModel()
    val scope = sandbox.effectiveScope()?.mapTo(HashSet()) { it.toString() }
    return model.filterTo(LinkedHashModel()) { st -> st.context == null || scope == null || st.context.stringValue() in scope }
  }
}
