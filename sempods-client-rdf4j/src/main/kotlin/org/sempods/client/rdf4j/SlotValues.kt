package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.base.CoreDatatype
import tools.jackson.databind.json.JsonMapper

/** [value] as the one JSON-LD value object a slot's `POST` sends (SPS-CRUD-049). */
@JvmSynthetic
internal fun slotValueObject(value: Value): ByteArray = SlotJson.write(valueObject(value))

/** [values] as the JSON-LD array a slot's `PUT` sends; an empty one clears the slot. */
@JvmSynthetic
internal fun slotValueArray(values: Collection<Value>): ByteArray = SlotJson.write(values.map(::valueObject))

/**
 * An IRI as `{"@id"}`; a literal as `{"@value"}` with its lexical form, and `@language` or, unless it is
 * an `xsd:string`, `@type`. `@value` is always a string: a JSON number would be read as `xsd:integer` or
 * `xsd:decimal` and lose its lexical form.
 */
private fun valueObject(value: Value): Map<String, String> = when (value) {
  is IRI -> mapOf("@id" to value.stringValue())
  is Literal -> {
    require(value.baseDirection == Literal.BaseDirection.NONE) {
      "A slot value has no base direction: a value object carries a language or a datatype (SPS-CRUD-023)."
    }
    val language = value.language.orElse(null)
    when {
      language != null -> mapOf("@value" to value.label, "@language" to language)
      value.coreDatatype == CoreDatatype.XSD.STRING -> mapOf("@value" to value.label)
      else -> mapOf("@value" to value.label, "@type" to value.datatype.stringValue())
    }
  }
  else -> throw IllegalArgumentException(
    "A ${value.javaClass.simpleName} has no JSON-LD value object: a slot value is an IRI or a literal (SPS-CRUD-023).",
  )
}

/** The one place this module names Jackson, in no declaration public in bytecode. */
private object SlotJson {

  private val mapper = JsonMapper.builder().build()

  fun write(value: Any): ByteArray = mapper.writeValueAsBytes(value)
}
