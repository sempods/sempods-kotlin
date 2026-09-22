package org.sempods.pods.oauth.flows

/**
 * The client's `state`, as this pod hands it back: byte for byte, whitespace included. `state` is
 * opaque (RFC 6749 §4.1.1), and a client that signs it and checks the signature on the callback is
 * broken by a single stripped space.
 *
 * An empty parameter counts as one that was never sent (RFC 6749 §3.1), so `?state=` answers with
 * no `state` at all.
 *
 * The spelling on the wire may still differ: the query is `application/x-www-form-urlencoded`, so
 * a space travels as `+`. What a client decodes is what it sent.
 */
internal fun suppliedState(raw: String?): String? = raw?.takeIf { it.isNotEmpty() }
