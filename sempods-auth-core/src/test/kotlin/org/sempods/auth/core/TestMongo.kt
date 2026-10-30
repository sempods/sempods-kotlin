package org.sempods.auth.core

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The local MongoDB the store tests run against, because atomic consumption and TTL indexes are the
 * database's. Skipped rather than failed where it is absent, the repository convention.
 */
internal object TestMongo {

  private const val URL = "mongodb://localhost:27018"

  private val reachable by lazy {
    runCatching {
      val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(URL))
        .applyToClusterSettings { it.serverSelectionTimeout(1, TimeUnit.SECONDS) }
        .build()
      MongoClients.create(settings).use { it.getDatabase("admin").runCommand(Document("ping", 1)) }
      true
    }.getOrDefault(false)
  }

  private val client by lazy { MongoClients.create(URL) }

  /** A database of the caller's own, to drop when it is done; skips the caller where none runs. */
  fun database(): MongoDatabase {
    assumeTrue(reachable, "local MongoDB at $URL not reachable — skipping")
    return client.getDatabase("sempods-auth-core-test-" + UUID.randomUUID().toString().replace("-", "").take(10))
  }
}
