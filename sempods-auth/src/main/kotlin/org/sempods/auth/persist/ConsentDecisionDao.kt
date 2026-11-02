package org.sempods.auth.persist

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import org.sempods.auth.SempodsAuthCollections
import org.sempods.commons.mongo.getStringSet
import java.time.Instant
import java.util.Date

/**
 * What a person agreed to release to a client: the scopes beyond `openid`, one row per WebID and
 * `client_id`.
 *
 * Only an agreement is stored. A refusal is not remembered, so the next request for the same claims
 * asks again.
 */
class ConsentDecisionDao(db: MongoDatabase, collectionName: String = SempodsAuthCollections.OAUTH_CONSENT_DECISIONS) {

  private val decisions = db.getCollection(collectionName)

  init {
    decisions.createIndex(
      Indexes.ascending(WEB_ID, CLIENT_ID),
      IndexOptions().unique(true),
    )
  }

  /** The scopes [webId] agreed to release to [clientId]; empty when there is no agreement. */
  fun agreedScopes(webId: String, clientId: String): Set<String> =
    decisions.find(filter(webId, clientId)).firstOrNull()?.getStringSet(SCOPES).orEmpty()

  /** Adds [scopes] to what [webId] agreed to release to [clientId]. */
  fun agree(webId: String, clientId: String, scopes: Set<String>, at: Instant = Instant.now()) {
    if (scopes.isEmpty()) return
    decisions.updateOne(
      filter(webId, clientId),
      Updates.combine(
        Updates.addEachToSet(SCOPES, scopes.toList()),
        Updates.set(AGREED_AT, Date.from(at)),
      ),
      UpdateOptions().upsert(true),
    )
  }

  private fun filter(webId: String, clientId: String) =
    Filters.and(Filters.eq(WEB_ID, webId), Filters.eq(CLIENT_ID, clientId))

  private companion object {
    const val WEB_ID = "webId"
    const val CLIENT_ID = "clientId"
    const val SCOPES = "scopes"
    const val AGREED_AT = "agreedAt"
  }
}
