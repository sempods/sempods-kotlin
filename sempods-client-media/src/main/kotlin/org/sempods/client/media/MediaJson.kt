package org.sempods.client.media

import org.sempods.media.UploadedMedia
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI

/**
 * The upload route's two JSON documents: the answer both upload paths get, and the descriptor one of
 * them sends.
 *
 * A body that is not the document the route promises fails here, and the failure carries no part of
 * it: [org.sempods.client.SempodsResponse.map] turns what this throws into a decoding failure
 * with the answer's status and headers.
 */
internal object MediaJson {

  private val mapper = JsonMapper.builder().build()

  fun uploaded(json: String): UploadedMedia {
    val root = try {
      mapper.readTree(json)
    } catch (malformed: JacksonException) {
      throw IllegalArgumentException("the answer is not one JSON object")
    }
    if (root !is ObjectNode) throw IllegalArgumentException("the answer is not one JSON object")
    return UploadedMedia(mediaId = text(root, "id"), contentUrl = URI(text(root, "content_url")))
  }

  fun sourceDescriptor(sourceUrl: String, filename: String?): String {
    val descriptor = mapper.createObjectNode().put("source_url", sourceUrl)
    filename?.let { descriptor.put("filename", it) }
    return mapper.writeValueAsString(descriptor)
  }

  /** A member the route guarantees. A missing one is a broken contract rather than an empty value. */
  private fun text(root: ObjectNode, member: String): String {
    val value = root[member]
    require(value != null && value.isString) { "the answer has no string member '$member'" }
    return value.stringValue()
  }
}
