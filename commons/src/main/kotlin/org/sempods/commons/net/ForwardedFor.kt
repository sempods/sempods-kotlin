package org.sempods.commons.net

/**
 * Reads the client address out of an `X-Forwarded-For` header.
 *
 * **The rightmost entry, not the leftmost.** A reverse proxy *appends* the address it saw to
 * whatever the request already carried, so everything left of the last entry is text the caller
 * wrote. Taking the first entry means taking a value any client can choose: as a rate-limit key it
 * would be evaded by one header, and as an address recorded on an audit row it would be a forgery
 * with a plausible shape. The last entry is the one the proxy in front of this server put there.
 *
 * **That reading assumes exactly one trusted proxy**, which is what the reference deployment has:
 * the pod server is reachable only through the edge proxy, never directly. Behind two chained
 * proxies the last entry names the inner one, and every caller would collapse into a single
 * bucket — a deployment that adds a hop has to say so here rather than discover it.
 *
 * Returns `null` when the header is absent or holds nothing but separators — meaning *no proxy
 * spoke*, which a caller has to decide about rather than paper over. The value is returned as
 * written: a port or brackets an unusual proxy adds stay part of it, because this is an identity to
 * compare and record, never an address to dial.
 */
object ForwardedFor {

  fun clientIp(header: String?): String? =
    header
      ?.split(',')
      ?.map(String::trim)
      ?.lastOrNull(String::isNotEmpty)
}
