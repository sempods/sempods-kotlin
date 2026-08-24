package org.sempods.rdf

import org.sempods.commons.datetime.DateTimeUtil
import org.eclipse.rdf4j.model.*
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import java.net.URI
import java.time.Instant
import java.time.temporal.Temporal

object Rdf4JUtil {

  fun literal(instant: Instant?): Literal? {
    instant ?: return null
    // There are bugs for instant: https://github.com/eclipse-rdf4j/rdf4j/issues/4104
    return Values.getValueFactory().createLiteral(
      DateTimeUtil.toUtcLocalDateTime(instant).atZone(DateTimeUtil.utcZone)
    )
  }

  fun literal(temporal: Temporal?): Literal? {
    temporal ?: return null
    if (temporal is Instant) {
      return literal(temporal)
    }
    return Values.getValueFactory().createLiteral(temporal)
  }

  fun literal(iri: IRI): Literal {
    return Values.getValueFactory().createLiteral(iri.stringValue())
  }

}

fun Model.filter(resource: IRI, predicates: Set<IRI>, context: IRI): Model {
  val toRemove = this.filter { stmt ->
    stmt.subject.toString() == resource.toString()
        && stmt.context.toString() == context.toString()
        && predicates.contains(stmt.predicate)
  }
  this.removeAll(toRemove.toSet())
  return this
}

fun Model.iterate(
  subjects: Set<Resource>? = null,
  predicates: Set<IRI>? = null,
  objects: Set<Value>? = null,
  contexts: Set<IRI>? = null,
): Sequence<Statement> {
  var seq = this.getStatements(null, null, null)
    .asSequence()
  if (subjects != null) {
    seq = seq.filter { stmt -> subjects.contains(stmt.subject) }
  }
  if (predicates != null) {
    seq = seq.filter { stmt -> predicates.contains(stmt.predicate) }
  }
  if (objects != null) {
    seq = seq.filter { stmt -> objects.contains(stmt.`object`) }
  }
  if (contexts != null) {
    seq = seq.filter { stmt -> contexts.contains(stmt.context) }
  }
  return seq
}

/**
 * @return True, if the resource has any values, except for rdf:type.
 */
fun Model.hasValues(resource: IRI): Boolean {
  return getStatements(resource, null, null)
    .any { it.predicate != RDF.TYPE }
}

fun Model.values(resource: IRI, predicate: IRI): Sequence<Value> {
  return getStatements(resource, predicate, null)
    .asSequence()
    .mapNotNull { it.`object` }
}

fun Model.values(resource: Resource, predicates: Set<IRI>, vararg contexts: IRI): Sequence<Value> {
  return getStatements(resource, null, null, *contexts)
    .asSequence()
    .filter { stmt -> predicates.contains(stmt.predicate) }
    .mapNotNull { it.`object` }
}

fun Model.values(resource: Resource, predicate: IRI, vararg contexts: IRI): Sequence<Value> {
  return getStatements(resource, predicate, null, *contexts)
    .asSequence()
    .mapNotNull { it.`object` }
}

fun Model.firstValueOrNull(resource: Resource, predicate: IRI, vararg contexts: IRI): Value? {
  return values(resource, predicate, *contexts).firstOrNull()
}

fun Model.firstStringValueOrNull(resource: Resource, predicate: IRI): String? {
  return values(resource, predicate).firstOrNull()?.stringValue()
}

fun Model.firstStringValueOrNull(resource: Resource, predicate: IRI, vararg contexts: IRI): String? {
  return firstValueOrNull(resource, predicate, *contexts)?.stringValue()
}

fun Model.minInstantValueOrNull(resource: IRI, predicate: IRI): Instant? {
  return values(resource, predicate)
    .mapNotNull { value ->
      val temporal = DateTimeUtil.parseTemporal(value.stringValue())
      return@mapNotNull DateTimeUtil.toInstant(temporal)
//      try {
//        return@mapNotNull Instant.parse(value.stringValue())
//      } catch (_: Exception) { }
//      return@mapNotNull null
    }
    .minOrNull()
}

fun Model.maxInstantValueOrNull(resource: Resource, predicate: IRI): Instant? {
  return values(resource, predicate)
    .mapNotNull { value ->
      val temporal = DateTimeUtil.parseTemporal(value.stringValue())
      return@mapNotNull DateTimeUtil.toInstant(temporal)
//      try {
//        return@mapNotNull Instant.parse(value.stringValue())
//      } catch (_: Exception) { }
//      return@mapNotNull null
    }
    .maxOrNull()
}

fun Model.maxInstantValueOrNull(resource: Resource, predicate: IRI, context: IRI): Instant? {
  return maxInstantValueOrNull(resource = resource, predicate = predicate, contexts = arrayOf(context))
}

fun Model.maxInstantValueOrNull(resource: Resource, predicate: IRI, vararg contexts: IRI): Instant? {
  return values(resource, predicate, *contexts)
    .mapNotNull { value ->
      val temporal = DateTimeUtil.parseTemporal(value.stringValue())
      return@mapNotNull DateTimeUtil.toInstant(temporal)
    }
    .maxOrNull()
}

//fun Model.firstInstantValueOrNull(resource: IRI, predicate: IRI): Instant? {
//  val isoDateStr = firstStringValueOrNull(resource, predicate)
//  return if (isoDateStr == null) {
//    null
//  } else {
//    Instant.parse(isoDateStr)
//  }
//}
//
//fun Model.firstInstantValueOrNull(resource: Resource, predicate: IRI, vararg contexts: IRI): Instant? {
//  val isoDateStr = firstStringValueOrNull(resource, predicate, *contexts)
//  return if (isoDateStr == null) {
//    null
//  } else {
//    Instant.parse(isoDateStr)
//  }
//}

fun Model.firstTemporalValueOrNull(resource: Resource, predicate: IRI, vararg contexts: IRI): Temporal? {
  return DateTimeUtil.parseTemporal(firstStringValueOrNull(resource, predicate, *contexts))
}

fun URI.toIri(): IRI = Values.iri(toString())
