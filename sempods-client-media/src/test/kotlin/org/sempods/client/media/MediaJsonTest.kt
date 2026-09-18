package org.sempods.client.media

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import kotlin.test.assertEquals

/** The two documents the upload route exchanges, read and written without a server in the way. */
class MediaJsonTest {

  @Test
  fun `a descriptor names the source, and the filename only when there is one`() {
    assertEquals(
      """{"source_url":"https://drive.example/a"}""",
      MediaJson.sourceDescriptor("https://drive.example/a", filename = null),
    )
    assertEquals(
      """{"source_url":"https://drive.example/a","filename":"plan.png"}""",
      MediaJson.sourceDescriptor("https://drive.example/a", "plan.png"),
    )
  }

  @Test
  fun `an answer is the id and the URL the pod publishes the bytes at`() {
    val uploaded = MediaJson.uploaded("""{"id":"abc","content_url":"https://pods.example/a/content","extra":1}""")

    assertEquals("abc", uploaded.mediaId)
    assertEquals(URI("https://pods.example/a/content"), uploaded.contentUrl)
  }

  @Test
  fun `a member the route guarantees is missing, and that is a broken contract rather than an empty value`() {
    assertThrows<IllegalArgumentException> { MediaJson.uploaded("""{"id":"abc"}""") }
    assertThrows<IllegalArgumentException> { MediaJson.uploaded("""{"id":1,"content_url":"https://a"}""") }
    assertThrows<IllegalArgumentException> { MediaJson.uploaded("not json at all") }
    assertThrows<IllegalArgumentException> { MediaJson.uploaded("""["id"]""") }
  }
}
