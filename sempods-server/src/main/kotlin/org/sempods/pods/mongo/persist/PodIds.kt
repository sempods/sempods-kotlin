package org.sempods.pods.mongo.persist

import org.bson.types.ObjectId
import org.sempods.pods.PodId

/**
 * The other edge of the one [PodDbo.toRef] opened: this implementation's key type on one side, the
 * seams' [PodId] on the other, and the translation in exactly this file.
 *
 * `internal`, because the whole point is that no signature leaving this module names both. A DAO
 * keeps speaking its driver's type — that is what a DAO is for — and a seam keeps speaking
 * [PodId]; whoever crosses between them converts here.
 *
 * **The hex string is the token.** `ObjectId.toHexString` is what both media stores already wrote
 * into a directory name and an object key before this type existed, so a [PodId] minted here is
 * character-for-character the segment already on disk and already in the bucket. Introducing the
 * type moved no bytes.
 */
internal fun ObjectId.toPodId(): PodId = PodId(toHexString())

/**
 * [PodId] as this implementation's key, or `null` when the token is not one of ours.
 *
 * Nullable rather than throwing because of where it is called from: a store walking its own backend
 * hands back every pod it finds bytes for, and a media root or a bucket may hold a directory this
 * server never wrote. Deciding *which* tokens are pod ids is the deployment's business — it is the
 * side that mints them — which is why the check lives here and not in a store.
 */
internal fun PodId.toObjectIdOrNull(): ObjectId? =
  if (ObjectId.isValid(value)) ObjectId(value) else null
