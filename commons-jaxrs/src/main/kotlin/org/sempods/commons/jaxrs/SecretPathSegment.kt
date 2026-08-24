package org.sempods.commons.jaxrs

/**
 * Declares that the request-path segment **following** [afterSegment] is a credential, and must
 * never reach a log line.
 *
 * `ApiExceptionMapper` names the method and path of every failure it logs, and for a failure
 * decided *during* matching that is all it has: Jersey supplies no matched template for a 405 or a
 * 406, so there is nothing route-shaped to log instead of the concrete path. A route that puts a
 * usable secret in its URL therefore has to say so, and this is how — one entry per such route,
 * contributed to the set binder by the composition that owns it:
 *
 * ```kotlin
 * JaxRsApplicationModule.declareSecretPathSegment(binder(), "join")
 * ```
 *
 * The marker is the literal segment in front of the secret, because that is the part a
 * pre-matching filter can recognise without a template. `ideas/42/join/<token>` declares `join`.
 *
 * A declaration here narrows one sink. It does not make the URL safe: a path travels through
 * browser history, `Referer` headers and every proxy on the way, none of which this repository
 * controls. The durable fix for such a route is to move the credential out of the URL.
 */
data class SecretPathSegment(val afterSegment: String)
