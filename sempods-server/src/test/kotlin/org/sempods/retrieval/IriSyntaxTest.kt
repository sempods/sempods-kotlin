package org.sempods.retrieval

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The IRIs are built with the lax `SimpleValueFactory.createIRI`, as `FindRequestParser` builds
 * them: a stricter constructor would reject most of these strings before the guard sees them.
 */
class IriSyntaxTest {

  private val vf = SimpleValueFactory.getInstance()

  private fun iri(raw: String): IRI = vf.createIRI(raw)

  private fun withCodePoint(codePoint: Int): IRI =
    iri("https://example.org/a${String(Character.toChars(codePoint))}b")

  private fun assertRejected(vararg codePoints: Int) = codePoints.forEach {
    assertFalse(IriSyntax.isWellFormed(withCodePoint(it)), "must reject U+%04X".format(it))
  }

  private fun assertAccepted(vararg codePoints: Int) = codePoints.forEach {
    assertTrue(IriSyntax.isWellFormed(withCodePoint(it)), "must accept U+%04X".format(it))
  }

  @Test
  fun `accepts ordinary IRIs`() {
    assertTrue(IriSyntax.isWellFormed(iri("https://schema.org/Event")))
    assertTrue(IriSyntax.isWellFormed(iri("https://example.org/posts/123?page=2#top")))
    assertTrue(IriSyntax.isWellFormed(iri("urn:isbn:0451450523")))
  }

  @Test
  fun `accepts an umlaut`() {
    assertTrue(IriSyntax.isWellFormed(iri("https://example.org/M\u00FCller")))
  }

  @Test
  fun `accepts a supplementary character encoded as a surrogate pair`() {
    // U+20000; each half alone is a lone surrogate, the pair is one valid ucschar.
    assertTrue(IriSyntax.isWellFormed(iri("https://example.org/a\uD840\uDC00b")))
    assertTrue(IriSyntax.isWellFormed(iri("https://example.org/a\uD800\uDC00b")), "U+10000")
  }

  @Test
  fun `rejects the SPARQL IRIREF break-out characters`() {
    assertFalse(IriSyntax.isWellFormed(iri("https://evil/a> } UNION { ?s ?p ?o")))
    "<>\"{}|^`\\".forEach { ch ->
      assertFalse(IriSyntax.isWellFormed(iri("https://evil/a${ch}b")), "must reject '$ch'")
    }
  }

  @Test
  fun `rejects space and control characters`() {
    assertRejected(0x00, 0x09, 0x1F, 0x20, 0x7F, 0x80, 0x85, 0x9F)
    assertAccepted(0xA0)
  }

  @Test
  fun `rejects lone surrogates`() {
    assertRejected(0xD800, 0xDBFF, 0xDC00, 0xDFFF)
    assertAccepted(0xD7FF)
  }

  @Test
  fun `rejects private-use code points`() {
    assertRejected(0xE000, 0xF8FF, 0xF0000, 0xFFFFD, 0x100000, 0x10FFFD)
    assertAccepted(0xF900, 0xEFFFD)
  }

  @Test
  fun `rejects non-characters`() {
    assertRejected(0xFDD0, 0xFDEF, 0xFFFE, 0xFFFF, 0x1FFFE, 0x1FFFF, 0xEFFFE, 0x10FFFE, 0x10FFFF)
    assertAccepted(0xFDCF, 0xFDF0, 0x1FFFD)
  }
}
