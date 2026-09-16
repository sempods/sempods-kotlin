package org.sempods.client.core

import java.io.IOException
import java.io.InputStream

/**
 * Reads an answer's body from the connection, while it arrives.
 *
 * ```java
 * SempodsResponse<Long> written = pod.contexts().export(tasks, body -> body.transferTo(out));
 * ```
 *
 * **The stream lives as long as the call.** It is valid inside [read] and closed as soon as it
 * returns, so what a reader needs beyond it, it copies. Returning early ends the transfer, and so
 * does throwing: the connection is closed rather than returned to the pool, and the exception
 * reaches the caller as it is.
 *
 * **Nothing is buffered on the way**, so an answer of any size passes, and the call holds its
 * admission slot until the reader is done ([SempodsAdmission]). The buffered operations beside these
 * read at most 16 MiB.
 *
 * **Stopping is the reader's**, on the thread the call runs on. A caller that has to end a transfer
 * from another thread builds its request through [SempodsSession.newRequest] and cancels the OkHttp
 * `Call` it ran it with; the endpoint groups keep no call to cancel.
 */
fun interface SempodsBodyReader<T : Any> {

  /** [body] as this operation's result. */
  @Throws(IOException::class)
  fun read(body: InputStream): T
}
