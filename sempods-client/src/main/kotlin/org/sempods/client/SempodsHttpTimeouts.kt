package org.sempods.client

import java.time.Duration

/**
 * The deadlines [SempodsHttpTransport] passes to the engine.
 *
 * [connect], [read] and [write] bound a single step — the handshake, and the gap between two bytes
 * in either direction. [call] bounds the **whole** call including the body, and is the only one a
 * peer cannot outlast by answering slowly: [read] measures the gap between two bytes, so a drip
 * just inside it runs forever.
 *
 * [Duration.ZERO] on [call] means no whole-call deadline — a caller's choice for a genuinely
 * unbounded read, not a default.
 */
data class SempodsHttpTimeouts @JvmOverloads constructor(
  val connect: Duration = Duration.ofSeconds(10),
  val read: Duration = Duration.ofSeconds(30),
  val write: Duration = Duration.ofSeconds(30),
  val call: Duration = Duration.ofMinutes(2),
)
