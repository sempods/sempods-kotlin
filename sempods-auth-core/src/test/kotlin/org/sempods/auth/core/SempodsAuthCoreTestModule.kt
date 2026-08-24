package org.sempods.auth.core

import com.mongodb.client.MongoDatabase
import org.sempods.commons.guice.BaseModule
import java.lang.reflect.Proxy

/**
 * What a consuming service would install beside [SempodsAuthCoreModule]: a database.
 *
 * The `MongoDatabase` is a JDK proxy that exists and does nothing. Enough to satisfy the
 * dependency graph, which is all this needs — the stores are lazy providers, so nothing here ever
 * calls a method on it.
 */
object SempodsAuthCoreTestModule : BaseModule() {

  override fun configure() {
    bind<MongoDatabase>().toInstance(stubDatabase)
  }

  private val stubDatabase: MongoDatabase = Proxy.newProxyInstance(
    MongoDatabase::class.java.classLoader,
    arrayOf(MongoDatabase::class.java),
  ) { _, _, _ -> null } as MongoDatabase
}
