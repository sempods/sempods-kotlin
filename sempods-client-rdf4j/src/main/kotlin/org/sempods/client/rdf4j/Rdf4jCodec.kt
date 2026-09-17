package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.rio.ParserConfig
import org.eclipse.rdf4j.rio.RDFParseException
import org.eclipse.rdf4j.rio.RDFParser
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.rio.WriterConfig
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings
import org.eclipse.rdf4j.rio.helpers.LargeLiteralHandling
import org.eclipse.rdf4j.rio.helpers.StatementCollector
import org.eclipse.rdf4j.rio.jsonld.JSONLDMode
import org.eclipse.rdf4j.rio.jsonld.JSONLDParser
import org.eclipse.rdf4j.rio.jsonld.JSONLDSettings
import org.eclipse.rdf4j.rio.jsonld.JSONLDWriter
import org.eclipse.rdf4j.rio.nquads.NQuadsParser
import org.eclipse.rdf4j.rio.ntriples.NTriplesParserSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction

/**
 * Reads the pod's RDF into a [Model] and writes a [Model] as a request body, with every value as it was
 * sent.
 *
 * **Every setting that changes a value is set here.** RDF4J's defaults rewrite some — a language tag to
 * its canonical case, an `urn:rdf4j:tripleTerm:` IRI to a triple term — and a setting left unset is read
 * from a JVM system property, so a consumer's JVM flags would otherwise decide what a read returns.
 *
 * **A body that does not parse is an [RDFParseException]**, an `IOException` of the parser's own
 * included, so that [org.sempods.client.core.SempodsResponse.map] reports it as a decoding failure.
 * Parsers are not thread-safe; each read makes its own.
 */
internal object Rdf4jCodec {

  /** [nQuads], UTF-8 and strictly so, as a model keeping every statement's context. */
  fun readNQuads(nQuads: ByteArray, baseUri: String): Model = read(NQuadsParser(), parserConfig(), nQuads, baseUri)

  /**
   * [jsonLd], UTF-8 and strictly so, as a model keeping every named graph as its statements' context.
   *
   * **Nothing the document names is fetched.** A remote `@context` is a parse failure: loading it would
   * be a request outside the session, its guard and its admission.
   *
   * **A language tag comes back in lower case**, and no setting keeps its case: JSON-LD 1.1 lets a
   * processor lower-case a tag, and RDF4J's does. RDF compares tags without regard to case, and so does
   * RDF4J's `Literal.equals`.
   */
  fun readJsonLd(jsonLd: ByteArray, baseUri: String): Model {
    val config = parserConfig().apply {
      set(JSONLDSettings.SECURE_MODE, true)
      set(JSONLDSettings.WHITELIST, emptySet())
      set(JSONLDSettings.EXCEPTION_ON_WARNING, true)
    }
    return read(JSONLDParser(), config, jsonLd, baseUri)
  }

  private fun read(parser: RDFParser, config: ParserConfig, bytes: ByteArray, baseUri: String): Model {
    val model = LinkedHashModel()
    parser.setParserConfig(config)
    parser.setRDFHandler(StatementCollector(model))
    try {
      parser.parse(strictUtf8(bytes), baseUri)
    } catch (unreadable: IOException) {
      throw RDFParseException(unreadable)
    }
    return model
  }

  /** A parser's own `InputStream` reader replaces malformed UTF-8 with U+FFFD. */
  private fun strictUtf8(bytes: ByteArray): Reader {
    val decoder = Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    return InputStreamReader(ByteArrayInputStream(bytes), decoder)
  }

  /** [model] as expanded JSON-LD, each statement in the named graph of its context. */
  fun writeJsonLd(model: Model): ByteArray {
    val out = ByteArrayOutputStream()
    val writer = JSONLDWriter(out)
    writer.setWriterConfig(writerConfig())
    Rio.write(model, writer)
    return out.toByteArray()
  }

  private fun parserConfig(): ParserConfig = ParserConfig().apply {
    set(BasicParserSettings.NORMALIZE_LANGUAGE_TAGS, false)
    set(BasicParserSettings.NORMALIZE_DATATYPE_VALUES, false)
    set(BasicParserSettings.PROCESS_ENCODED_TRIPLE_TERMS, false)
    set(BasicParserSettings.PRESERVE_BNODE_IDS, false)
    set(BasicParserSettings.SKOLEMIZE_ORIGIN, "")
    set(BasicParserSettings.LARGE_LITERALS_HANDLING, LargeLiteralHandling.PRESERVE)
    set(BasicParserSettings.VERIFY_URI_SYNTAX, true)
    set(BasicParserSettings.VERIFY_RELATIVE_URIS, true)
    set(BasicParserSettings.VERIFY_LANGUAGE_TAGS, true)
    set(BasicParserSettings.FAIL_ON_UNKNOWN_LANGUAGES, false)
    set(BasicParserSettings.VERIFY_DATATYPE_VALUES, false)
    set(BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES, false)
    set(NTriplesParserSettings.FAIL_ON_INVALID_LINES, true)
  }

  private fun writerConfig(): WriterConfig = WriterConfig().apply {
    set(JSONLDSettings.JSONLD_MODE, JSONLDMode.EXPAND)
    set(JSONLDSettings.USE_NATIVE_TYPES, false)
    set(JSONLDSettings.USE_RDF_TYPE, false)
    set(JSONLDSettings.PRODUCE_GENERALIZED_RDF, false)
    set(JSONLDSettings.HIERARCHICAL_VIEW, false)
    set(JSONLDSettings.EXCEPTION_ON_WARNING, true)
    set(BasicWriterSettings.PRETTY_PRINT, false)
  }
}
