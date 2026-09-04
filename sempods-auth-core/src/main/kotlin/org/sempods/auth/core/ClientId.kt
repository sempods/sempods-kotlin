package org.sempods.auth.core

/**
 * What may appear as an OAuth `client_id`: RFC 6749 Appendix A.1's `*VSCHAR`, one printable ASCII
 * character (`%x20-7E`) at a time, and not empty.
 *
 * The rule is the RFC's and needs no restating. What is worth stating is why it is *checked*, since
 * a `client_id` is compared and stored rather than parsed, and nothing downstream had ever needed
 * it to be well-formed.
 *
 * **A client_id is the most-logged value in this tree.** It names the subject of every
 * authorization, token, consent and audit line — a bare count of the log statements interpolating
 * one runs to over fifty in the pod server alone. It is also self-service: `did:web:` identities
 * are presented, not issued, so whatever a stranger sends is what those lines carry. `*VSCHAR`
 * excludes every character `org.sempods.commons.logging.LogSafeText` escapes, so checking it once
 * here is what lets all of those lines interpolate the value plainly — see `docs/logging.md`
 * §"Three rules".
 *
 * That is a consequence of the check, not its justification: a `client_id` outside `*VSCHAR` is
 * malformed by the RFC that defines the field, and a `did:web:` one carrying a line break is not a
 * DID under any reading of the method. Refusing it is what the specification already asks for.
 */
object ClientId {

  fun isValid(clientId: String): Boolean =
    clientId.isNotEmpty() && clientId.all { it in VSCHAR }

  private val VSCHAR = ' '..'~'
}
