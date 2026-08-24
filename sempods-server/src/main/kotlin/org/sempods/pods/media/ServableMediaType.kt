package org.sempods.pods.media

import jakarta.ws.rs.core.MediaType

/**
 * Whether [value] is a media type the content route can actually answer with.
 *
 * **Deliberately [MediaType.valueOf] and not a pattern of our own.** The check has to accept exactly
 * what the later serve accepts, and the later serve is `Response.ok(entity, contentType)` — which
 * runs this very parser and throws `IllegalArgumentException` on anything it dislikes. A hand-rolled
 * approximation would have a different acceptance set, so a value could pass the check and still
 * fail the serve, which is the failure this exists to prevent.
 *
 * That failure is worth naming, because it is nastier than it looks: the type is recorded at
 * *upload* time and used at *read* time, so an unparseable one is accepted with a `201` and then
 * turns every later read of that assignment into a `500`. Nothing connects the two events for
 * whoever has to diagnose it. Rejecting on the way in keeps the report at the moment and place the
 * bad value arrived.
 *
 * Why a JAX-RS type appears in `pods.media` at all: the registry's contract for `contentType` is
 * "something the content route can serve", so the HTTP layer's notion of a media type *is* the
 * domain constraint here rather than a leak of it.
 */
fun isServableMediaType(value: String): Boolean = try {
  MediaType.valueOf(value)
  true
} catch (e: IllegalArgumentException) {
  false
}
