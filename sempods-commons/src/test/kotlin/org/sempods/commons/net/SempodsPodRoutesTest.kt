package org.sempods.commons.net

import org.junit.jupiter.api.Test
import org.sempods.commons.utils.UriEncodingUtil
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SempodsPodRoutesTest {

  private val pod = URI("https://pods.example.com/alice/")

  @Test
  fun `every route is relative, so a pod base resolves onto it`() {
    // A leading slash would make `URI.resolve` replace the pod's path instead of extending it —
    // `https://pods.example.com/alice/` + `/_system/contexts` is a different pod's route.
    val routes = listOf(
      SempodsPodRoutes.CONTEXTS,
      SempodsPodRoutes.SPARQL_QUERY,
      SempodsPodRoutes.FIND,
      SempodsPodRoutes.MEDIA,
      SempodsPodRoutes.META_DATE_MODIFIED,
      SempodsPodRoutes.AUTH_TOKEN,
      SempodsPodRoutes.CONTEXT_PATH_PREFIX,
      SempodsPodRoutes.MEDIA_PATH_PREFIX,
    )
    routes.forEach { assertFalse(it.startsWith("/"), "route must be relative: $it") }
    assertEquals(
      URI("https://pods.example.com/alice/_system/sparql/query"),
      pod.resolve(SempodsPodRoutes.SPARQL_QUERY),
    )
  }

  @Test
  fun `a context path extends the context prefix`() {
    assertEquals(
      URI("https://pods.example.com/alice/_system/contexts/apps/notes/public"),
      pod.resolve(SempodsPodRoutes.context("apps/notes/public")),
    )
  }

  @Test
  fun `a resource is addressed by the base64url of its whole IRI`() {
    // Base64url, not a path — which is what lets the System layer address a subject that lives
    // outside this pod (an offer keyed by its ticket-shop URL).
    val external = URI("https://shop.example.org/offers/42?x=1#y")
    val encoded = UriEncodingUtil.encodeUriToUrlSafeBase64(external)
    assertEquals("_system/resources/$encoded", SempodsPodRoutes.resource(external))
    assertEquals(external, UriEncodingUtil.decodeUrlSafeBase64ToUriStrict(encoded))
  }

  @Test
  fun `a slot is subject then predicate, a slot value appends the target`() {
    val subject = URI("https://pods.example.com/alice/events/1")
    val predicate = URI("https://schema.org/location")
    val target = URI("https://pods.example.com/alice/places/2")

    assertEquals("${SempodsPodRoutes.resource(subject)}/${seg(predicate)}", SempodsPodRoutes.slot(subject, predicate))
    assertEquals("${SempodsPodRoutes.slot(subject, predicate)}/${seg(target)}", SempodsPodRoutes.slotValue(subject, predicate, target))
  }

  @Test
  fun `the prefixes carry their trailing slash and the collection routes do not`() {
    // `SempodsUriBuilder` concatenates onto the prefixes to mint IRIs, while the collection
    // constants are dialled as they are. Swapping the two spellings silently produces a 404.
    assertEquals("_system/contexts/", SempodsPodRoutes.CONTEXT_PATH_PREFIX)
    assertEquals("_system/media/", SempodsPodRoutes.MEDIA_PATH_PREFIX)
    assertEquals("_system/contexts", SempodsPodRoutes.CONTEXTS)
    assertEquals("_system/media", SempodsPodRoutes.MEDIA)
  }

  private fun seg(uri: URI) = UriEncodingUtil.encodeUriToUrlSafeBase64(uri)
}
