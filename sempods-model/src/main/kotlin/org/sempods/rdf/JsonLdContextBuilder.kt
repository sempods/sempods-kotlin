package org.sempods.rdf

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Namespace

class JsonLdContextBuilder(
  private val defaultNs: Namespace? = null,
  namespaces: Collection<Namespace>? = null,
) {

  private val ns = mutableSetOf<Namespace>()

  private val usedNs = mutableSetOf<Namespace>()

  private val prefixes = mutableMapOf<String, Namespace>()

  private val mappings = mutableMapOf<IRI, JsonLdContextEntry>()

  init {
    defaultNs?.let { putNs(it) }
    namespaces?.forEach { putNs(it) }
  }

  fun build(): Map<String, Any> {
    val context = mutableMapOf<String, Any>()

    if (defaultNs != null && usedNs.contains(defaultNs)) {
      context["@vocab"] = defaultNs.name
    }
    usedNs.forEach { ns ->
      if (ns != defaultNs) {
        context[ns.prefix] = ns.name
      }
    }

    mappings.values.forEach {
      it.contextEntry()?.let { (name, v) -> context[name] = v }
    }

    return context
  }

  fun putNs(ns: Namespace) {
    if (this.ns.add(ns)) {
      prefixes[ns.prefix] = ns
    }
  }

  private fun getNs(iri: IRI): Namespace? {
    val iriStr = iri.stringValue()
    return ns.firstOrNull { iriStr.startsWith(it.name) }
  }

//  fun prefixed(iri: IRI): String {
//    val ns = getNs(iri)
//    return if (ns == null) {
//      iri.stringValue()
//    } else {
//      if (ns == defaultNs) {
//        iri.toString().substringAfter(ns.name)
//      } else {
//        "${ns.prefix}:${iri.toString().substringAfter(ns.name)}"
//      }
//    }
//  }

  fun shorten(iri: IRI): String {
    return determineEntry(iri).name()
  }

  private fun determineEntry(iri: IRI): JsonLdContextEntry {
    return mappings.computeIfAbsent(iri) {
      JsonLdContextEntry(iri = iri).also { entry ->
        entry.ns()?.let(usedNs::add)
      }
    }
  }

  private inner class JsonLdContextEntry(
    iri: IRI,
  ) {

    private val ns: Namespace? = getNs(iri)

    private val name: String

    private val pred: String

    init {
      if (ns == null) {
        name = iri.toString()
        pred = iri.toString()
      } else {
        if (ns == defaultNs) {
          name = iri.toString().substringAfter(ns.name)
          pred = iri.toString().substringAfter(ns.name)
        } else {
          name = iri.toString().substringAfter(ns.name)
          pred = "${ns.prefix}:${iri.toString().substringAfter(ns.name)}"
        }
      }
    }

    fun ns(): Namespace? {
      return ns
    }

    fun name(): String {
      return name
    }

    fun value(): String {
      return if (ns != null && ns == defaultNs) {
        "${ns.prefix}:$pred"
      } else {
        pred
      }
    }

    fun contextEntry(): Pair<String, Any>? {
      return if (name == pred) {
        null
      } else {
        name to pred
      }
    }
  }
}
