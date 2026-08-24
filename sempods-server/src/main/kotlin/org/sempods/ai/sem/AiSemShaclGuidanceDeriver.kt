package org.sempods.ai.sem

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.StringReader

class AiSemShaclGuidanceDeriver @Inject constructor() {

  fun derive(shaclSyntax: String, shaclData: String): AiSemShaclDerivedGuidance? {
    val model = parseShaclModel(shaclSyntax, shaclData) ?: return null
    val targetClasses = deriveTargetClasses(model)
    val propertyHints = derivePropertyHints(model)

    if (targetClasses.isEmpty() && propertyHints.isEmpty()) {
      return null
    }

    val instructions = buildInstructions(targetClasses, propertyHints)
    val terms = buildTermsJson(propertyHints)

    return AiSemShaclDerivedGuidance(
      instructions = instructions,
      terms = terms,
    )
  }

  private fun parseShaclModel(shaclSyntax: String, shaclData: String): Model? {
    val format = when (shaclSyntax) {
      "text/turtle" -> RDFFormat.TURTLE
      "application/ld+json" -> RDFFormat.JSONLD
      else -> return null
    }

    return runCatching {
      Rio.parse(StringReader(shaclData), "urn:sempods:shacl", format)
    }.onFailure { error ->
      logger.warn { "failed to parse SHACL for auto guidance derivation: ${error.message}" }
    }.getOrNull()
  }

  private fun deriveTargetClasses(model: Model): List<String> {
    return model.getStatements(null, shTargetClass, null)
      .asSequence()
      .mapNotNull { stmt ->
        (stmt.`object` as? IRI)?.stringValue() ?: stmt.`object`?.stringValue()
      }
      .distinct()
      .toList()
  }

  private fun derivePropertyHints(model: Model): List<AiSemPropertyHint> {
    val propertyShapes = model.getStatements(null, shProperty, null)
      .asSequence()
      .mapNotNull { stmt -> stmt.`object` as? Resource }
      .toList()

    return propertyShapes
      .mapNotNull { shape -> toPropertyHint(model, shape) }
      .distinctBy { it.path }
  }

  private fun toPropertyHint(model: Model, propertyShape: Resource): AiSemPropertyHint? {
    val path = model.getStatements(propertyShape, shPath, null)
      .firstOrNull()
      ?.`object`
      ?.stringValue()
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?: return null

    val description = firstStringValue(model, propertyShape, shDescription)
      ?: firstStringValue(model, propertyShape, rdfsComment)
    val datatype = model.getStatements(propertyShape, shDatatype, null)
      .firstOrNull()
      ?.`object`
      ?.stringValue()
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
    val minCount = model.getStatements(propertyShape, shMinCount, null)
      .firstOrNull()
      ?.`object`
      ?.stringValue()
      ?.toIntOrNull()
    val maxCount = model.getStatements(propertyShape, shMaxCount, null)
      .firstOrNull()
      ?.`object`
      ?.stringValue()
      ?.toIntOrNull()

    return AiSemPropertyHint(
      path = path,
      description = description,
      datatype = datatype,
      minCount = minCount,
      maxCount = maxCount,
    )
  }

  private fun buildInstructions(
    targetClasses: List<String>,
    propertyHints: List<AiSemPropertyHint>,
  ): String? {
    if (targetClasses.isEmpty() && propertyHints.isEmpty()) {
      return null
    }

    val propertyPaths = propertyHints
      .map { it.path }
      .distinct()

    return buildString {
      appendLine("Use SHACL-derived constraints as primary extraction scope.")
      if (targetClasses.isNotEmpty()) {
        appendLine("Target classes: ${targetClasses.joinToString(", ")}")
      }
      if (propertyPaths.isNotEmpty()) {
        appendLine("Allowed property paths: ${propertyPaths.joinToString(", ")}")
      }
      appendLine("Do not add unknown properties outside the SHACL paths unless required for JSON-LD structure.")
    }.trim()
  }

  private fun buildTermsJson(propertyHints: List<AiSemPropertyHint>): ObjectNode? {
    if (propertyHints.isEmpty()) {
      return null
    }

    val objectMapper = JsonMappers.default()
    val terms = objectMapper.createObjectNode()
    propertyHints.forEach { hint ->
      val term = objectMapper.createObjectNode()
      hint.description?.let { term.put("description", it) }
      hint.datatype?.let { term.put("datatype", it) }
      hint.minCount?.let { term.put("minCount", it) }
      hint.maxCount?.let { term.put("maxCount", it) }
      terms.set<ObjectNode>(hint.path, term)
    }
    return terms
  }

  private fun firstStringValue(model: Model, subject: Resource, predicate: IRI): String? {
    return model.getStatements(subject, predicate, null)
      .firstOrNull()
      ?.`object`
      ?.stringValue()
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
    private val valueFactory = SimpleValueFactory.getInstance()

    private val shTargetClass: IRI = valueFactory.createIRI("http://www.w3.org/ns/shacl#targetClass")
    private val shProperty: IRI = valueFactory.createIRI("http://www.w3.org/ns/shacl#property")
    private val shPath: IRI = valueFactory.createIRI("http://www.w3.org/ns/shacl#path")
    private val shDescription: IRI = valueFactory.createIRI("http://www.w3.org/ns/shacl#description")
    private val shDatatype: IRI = valueFactory.createIRI("http://www.w3.org/ns/shacl#datatype")
    private val shMinCount: IRI = valueFactory.createIRI("http://www.w3.org/ns/shacl#minCount")
    private val shMaxCount: IRI = valueFactory.createIRI("http://www.w3.org/ns/shacl#maxCount")
    private val rdfsComment: IRI = valueFactory.createIRI("http://www.w3.org/2000/01/rdf-schema#comment")
  }
}

data class AiSemShaclDerivedGuidance(
  val instructions: String?,
  val terms: ObjectNode?,
)

private data class AiSemPropertyHint(
  val path: String,
  val description: String?,
  val datatype: String?,
  val minCount: Int?,
  val maxCount: Int?,
)
