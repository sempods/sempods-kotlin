package org.sempods.pods.mongo.persist

import org.bson.types.ObjectId
import org.sempods.pods.PodId

/** This implementation's key as a [PodId]: the token is the hex. */
internal fun ObjectId.toPodId(): PodId = PodId(toHexString())

/**
 * [PodId] as this implementation's key, or `null` when the token is not shaped like one it mints.
 *
 * A shape check and not an ownership one — a token another deployment minted the same way passes
 * it. `internal`, because a DAO speaks its driver's type and a seam speaks [PodId]; nothing leaving
 * this module names both.
 */
internal fun PodId.toObjectIdOrNull(): ObjectId? =
  if (ObjectId.isValid(value)) ObjectId(value) else null
