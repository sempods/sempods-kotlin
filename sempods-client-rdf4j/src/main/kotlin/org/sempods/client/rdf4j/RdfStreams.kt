package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.RDFHandler
import org.eclipse.rdf4j.rio.RDFParseException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * What a streamed read ended with: the number of statements [readStatements] handed on, or a body that
 * does not parse, kept for [org.sempods.client.core.SempodsResponse.map] to report with the answer's
 * status and headers.
 */
internal class StreamOutcome private constructor(
  private val count: Long,
  private val unreadable: RDFParseException?,
) {

  /** The count; a body that did not parse is thrown here. */
  fun countOrThrow(): Long {
    unreadable?.let { throw it }
    return count
  }

  companion object {

    fun read(count: Long) = StreamOutcome(count, null)

    fun unreadable(failure: Exception) =
      StreamOutcome(0, failure as? RDFParseException ?: RDFParseException(failure))
  }
}

/**
 * [body] parsed as [format] into [handler] while it arrives, with [context] set on every statement when
 * one is given.
 *
 * **A failure is sorted by where it came from.** What [handler] throws, and what [body] throws — a lost
 * connection, a cancelled call — is thrown as it is, found by identity also where the parser wrapped it.
 * Anything else is the body's fault and comes back as a [StreamOutcome]. The statements handed on before
 * a failure stay handed on.
 */
@JvmSynthetic
internal fun readStatements(
  body: InputStream,
  format: RDFFormat,
  baseUri: String?,
  handler: RDFHandler,
  context: Resource?,
): StreamOutcome {
  val stream = RecordingInputStream(body)
  val counting = CountingHandler(handler, context)
  val parser = Rdf4jCodec.parser(format)
  parser.setRDFHandler(counting)
  try {
    Rdf4jCodec.parse(parser, format, stream, baseUri)
  } catch (failure: Exception) {
    counting.failure?.takeIf { failure.isOrWasCausedBy(it) }?.let { throw it }
    stream.failure?.takeIf { failure.isOrWasCausedBy(it) }?.let { throw it }
    return StreamOutcome.unreadable(failure)
  }
  return StreamOutcome.read(counting.count)
}

private fun Throwable.isOrWasCausedBy(target: Throwable): Boolean =
  generateSequence(this) { it.cause }.take(MAX_CAUSES).any { it === target }

private const val MAX_CAUSES = 32

private val values = SimpleValueFactory.getInstance()

/** [delegate], counting the statements it takes and keeping what it threw. */
private class CountingHandler(
  private val delegate: RDFHandler,
  private val context: Resource?,
) : RDFHandler {

  var count = 0L
    private set

  var failure: RuntimeException? = null
    private set

  override fun startRDF() = recording { delegate.startRDF() }

  override fun endRDF() = recording { delegate.endRDF() }

  override fun handleNamespace(prefix: String, uri: String) = recording { delegate.handleNamespace(prefix, uri) }

  override fun handleStatement(statement: Statement) = recording {
    delegate.handleStatement(
      if (context == null) statement else values.createStatement(statement.subject, statement.predicate, statement.`object`, context),
    )
    count++
  }

  override fun handleComment(comment: String) = recording { delegate.handleComment(comment) }

  private inline fun recording(block: () -> Unit) {
    try {
      block()
    } catch (thrown: RuntimeException) {
      failure = thrown
      throw thrown
    }
  }
}

/** [input], keeping the `IOException` it threw. */
private class RecordingInputStream(input: InputStream) : FilterInputStream(input) {

  var failure: IOException? = null
    private set

  override fun read(): Int = recording { super.read() }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int = recording { super.read(buffer, offset, length) }

  override fun skip(n: Long): Long = recording { super.skip(n) }

  override fun available(): Int = recording { super.available() }

  private inline fun <T> recording(block: () -> T): T {
    try {
      return block()
    } catch (thrown: IOException) {
      failure = thrown
      throw thrown
    }
  }
}
