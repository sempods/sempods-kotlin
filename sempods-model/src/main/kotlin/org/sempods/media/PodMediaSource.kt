package org.sempods.media

/**
 * The wire form of "fetch these bytes yourself" — a media upload that carries an *instruction*
 * instead of a body.
 *
 * Defined here, in the media contract, because both sides need the same string: the pod's upload
 * route consumes the wildcard (any bytes at all are a valid media, a JSON document included), so
 * this type is the only thing separating "store this body" from "go and fetch that URL". Two copies
 * of it would be two chances to disagree about which one a request meant.
 *
 * The two sides are `SempodsClient.uploadMediaFromUrl` (which sends it) and the pod's
 * `PodMediaEndpoint` (which recognises it). The descriptor's field names are still string literals
 * on both — this object is where they would be centralised.
 *
 * TODO: centralise the descriptor's field names here and have both sides read them, so the wire
 *   format has one definition rather than two string literals that can drift.
 */
object PodMediaSource {

  const val MEDIA_TYPE: String = "application/vnd.sempods.media-source+json"
}
