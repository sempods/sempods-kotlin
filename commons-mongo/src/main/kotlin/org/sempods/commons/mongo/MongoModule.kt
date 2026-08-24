package org.sempods.commons.mongo

import com.google.inject.Provides
import com.google.inject.Singleton
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.sempods.commons.guice.BaseModule

/**
 * `MongoClient` and `MongoDatabase` for a service that talks to the driver directly.
 *
 * This replaces the word-for-word identical providers `SempodsAuthModule` and `SempodsMcpModule`
 * each carried. Both spelled out the same four lines of `MongoClientSettings` builder, which is
 * four lines of driver ceremony that says nothing about either service.
 *
 * **The address is a constructor parameter, not an environment lookup.** The connection string and
 * database name arrive from the service's own typed configuration — `SempodsAuthConfig`,
 * `SempodsMcpConfig` — which is where they are already read, with a per-service default
 * (`sempods-auth`, `sempods-mcp`) that a shared `MONGODB_DB_NAME` lookup here could not express.
 * It also keeps the local mains working: they construct their config by hand and never touch the
 * environment at all. `org.sempods.commons.config.Env` carries a standing note that a global lookup is a
 * hidden dependency and that configuration belongs in a typed object at the entry point; a module
 * that reads the environment itself would walk back exactly that.
 *
 * A `data class` rather than a plain one: Guice deduplicates modules by `equals`, and this one
 * carries `@Provides` methods — two instances it cannot compare would bind `MongoClient` twice and
 * refuse to build the injector.
 *
 * No codec registry is configured. The POJO codec belongs to whoever maps documents — an
 * object-mapping layer builds its own, and a driver-level DAO maps by hand (see the helpers in
 * [org.sempods.commons.mongo]). A registry installed here would be a policy nobody asked for.
 */
data class MongoModule(
  private val connectionString: String,
  private val databaseName: String,
) : BaseModule() {

  @Provides
  @Singleton
  fun mongoClient(): MongoClient = MongoClients.create(
    MongoClientSettings.builder()
      .applyConnectionString(ConnectionString(connectionString))
      .build(),
  )

  @Provides
  @Singleton
  fun mongoDatabase(client: MongoClient): MongoDatabase = client.getDatabase(databaseName)
}
