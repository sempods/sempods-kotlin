package org.sempods

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The media content parser, and above all that it does **not** accept an image *resource* URI.
 *
 * `ImageViewDefinition.name` is `media` and so is the System-layer route prefix, so
 * `{pod}/media/{id}` and `{pod}/_system/media/{id}/content` read alike while identifying different
 * things on different layers. There used to be a parser for each, and the near-collision is why they
 * were named apart; the image-resource one is gone because a picture is no longer named after its
 * bytes — `GCalMigrator.attachmentImageId` names it after the attachment, so the id in that position
 * is not a media id and a parser answering as if it were would have been worse than none.
 *
 * What survives is the one that reads an id out of a URL the pod itself minted, which is what a
 * caller holding a `schema:ImageObject` reaches through `schema:contentUrl`.
 */
class SempodsUriBuilderMediaTest {

  private val builder = SempodsUriBuilder("https://sempods.org/")
  private val pod = "alice"
  private val mediaId = "9F86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

  @Test
  fun `the parser inverts its own builder`() {
    assertEquals(
      mediaId,
      builder.parseMediaIdFromMediaContentUri(pod, builder.buildMediaContentUri(pod, mediaId)),
    )
  }

  /**
   * The near-collision the layers make possible: `{pod}/media/{id}` on the LOD layer reads exactly
   * like the media route's prefix and is not a content URL. Written out rather than built, because
   * the builder no longer mints that shape — an application that lays its resources out this way
   * does, and this parser has to keep refusing it.
   */
  @Test
  fun `a resource URI under a media-shaped path does not parse`() {
    assertNull(builder.parseMediaIdFromMediaContentUri(pod, builder.buildResourceUri(pod, "media/$mediaId")))
  }

  /**
   * Another pod's content URL is not this pod's media. Without the pod parameter it would parse, and
   * the caller would then unassign the id against the wrong pod.
   */
  @Test
  fun `a foreign pod's URI does not parse`() {
    assertNull(builder.parseMediaIdFromMediaContentUri(pod, builder.buildMediaContentUri("bob", mediaId)))
  }

  /** A different deployment's URI is not ours either, for the same reason [parsePodName] refuses one. */
  @Test
  fun `a foreign base does not parse`() {
    val foreign = SempodsUriBuilder("https://pods.example.org/")

    assertNull(builder.parseMediaIdFromMediaContentUri(pod, foreign.buildMediaContentUri(pod, mediaId)))
  }

  /**
   * The id is exactly one path segment. Anything that runs on past it — a deeper path, a query, a
   * fragment — is a URI this builder never minted, so it is not an id with something attached.
   */
  @Test
  fun `only a single segment is an id`() {
    assertNull(builder.parseMediaIdFromMediaContentUri(pod, URI("https://sempods.org/$pod/_system/media/$mediaId")))
    assertNull(builder.parseMediaIdFromMediaContentUri(pod, URI("https://sempods.org/$pod/_system/media//content")))
    assertNull(
      builder.parseMediaIdFromMediaContentUri(pod, URI("https://sempods.org/$pod/_system/media/$mediaId/content?v=2"))
    )
  }

  /**
   * **Structural, not existential.** A non-null answer says the URI is shaped like one this pod
   * mints, and the caller's next move is to ask the pod, which is the only thing that can tell
   * whether those bytes are there.
   */
  @Test
  fun `a legacy vendor id in the content position parses too`() {
    val legacy = "b1c2d3e4-0f56-4a7b-8c9d-0e1f2a3b4c5d"

    assertEquals(legacy, builder.parseMediaIdFromMediaContentUri(pod, builder.buildMediaContentUri(pod, legacy)))
  }

  /** Other resources of the same pod are not media. */
  @Test
  fun `a non-media resource of the same pod does not parse`() {
    assertNull(builder.parseMediaIdFromMediaContentUri(pod, builder.buildResourceUri(pod, "events/e1")))
    assertNull(builder.parseMediaIdFromMediaContentUri(pod, builder.buildContext(pod, "apps/notes/public")))
  }
}
