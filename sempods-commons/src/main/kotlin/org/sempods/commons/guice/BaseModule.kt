package org.sempods.commons.guice

import com.google.inject.AbstractModule
import com.google.inject.Singleton
import com.google.inject.binder.AnnotatedBindingBuilder
import com.google.inject.binder.ScopedBindingBuilder

/**
 * Kotlin conveniences over [AbstractModule] — `bind<T>()` instead of `bind(T::class.java)`, and a
 * singleton scope that reads as one word.
 *
 * Guice is a `compileOnly` dependency of `commons` on purpose: this class is the only thing in the
 * module that needs it, and a consumer that does not use Guice — `sempods-client` runs from a plain
 * `main` — must not inherit a DI container to get a URL helper. Anyone extending this class already
 * has Guice on its own classpath.
 */
abstract class BaseModule : AbstractModule() {

  protected inline fun <reified T> bind(): AnnotatedBindingBuilder<T> = bind(T::class.java)

  protected inline fun <reified T : Annotation> ScopedBindingBuilder.to(): Unit = `in`(T::class.java)

  protected fun ScopedBindingBuilder.asSingleton(eager: Boolean = false) {
    when (eager) {
      true -> asEagerSingleton()
      false -> to<Singleton>()
    }
  }
}
