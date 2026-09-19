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
      SempodsPodRoutes.FIND,
      SempodsPodRoutes.MEDIA,
      SempodsPodRoutes.AUTH_OIDC_CALLBACK,
      SempodsPodRoutes.CONTEXT_PATH_PREFIX,
      SempodsPodRoutes.MEDIA_PATH_PREFIX,
    )
    routes.forEach { assertFalse(it.startsWith("/"), "route must be relative: $it") }
    assertEquals(
      URI("https://pods.example.com/alice/_system/contexts/apps/notes/public"),
      pod.resolve(SempodsPodRoutes.CONTEXT_PATH_PREFIX + "apps/notes/public"),
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
  fun `the prefixes carry their trailing slash and the collection routes do not`() {
    // `SempodsUriBuilder` concatenates onto the prefixes to mint IRIs, while the collection
    // constants are dialled as they are. Swapping the two spellings silently produces a 404.
    assertEquals("_system/contexts/", SempodsPodRoutes.CONTEXT_PATH_PREFIX)
    assertEquals("_system/media/", SempodsPodRoutes.MEDIA_PATH_PREFIX)
    assertEquals("_system/contexts", SempodsPodRoutes.CONTEXTS)
    assertEquals("_system/media", SempodsPodRoutes.MEDIA)
  }
}
