package org.sempods.media

import java.net.URI

/**
 * Where a stored media landed: its id, and the URL its bytes are served from.
 *
 * **[contentUrl] comes from the pod rather than being rebuilt by the caller**, and that matters
 * wherever the two disagree — an app backend reaching a pod at an internal address would otherwise
 * persist that address into `schema:contentUrl` and publish a URL nobody outside can resolve. The
 * pod knows the address it is known by; the caller only knows the one it dialled.
 *
 * @property mediaId the base64url SHA-256 of the bytes, and therefore stable: the same content
 *   always yields the same id in the same pod.
 */
data class UploadedMedia(
  val mediaId: String,
  val contentUrl: URI,
)
