package org.sempods.commons.guice

import com.google.inject.Inject
import com.google.inject.Injector
import com.google.inject.TypeLiteral
import org.sempods.commons.trace.TraceContextHolder
import io.mockk.mockkClass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import kotlin.reflect.KClass
import io.github.oshai.kotlinlogging.KotlinLogging

private data class DelegateInstructions(
  val traceId: String,
  val useStrictResult: Boolean = false,
)

open class GuiceAppTestProxy<T : Any>(
  type: KClass<T>? = null,
  typeLiteral: TypeLiteral<T>? = null,
  protected var globalDefaultBehavior: T,
) {

  private val delegates = CopyOnWriteArrayList<Pair<T, DelegateInstructions>>()

  private val rawType: Class<out Any> = typeLiteral?.rawType
    ?: type?.java
    ?: throw IllegalStateException()

  /**
   * The proxy which should be bound via Guice. It invokes always the given default implementation,
   * as well as all registered delegates.
   */
  val injectableProxy = Proxy.newProxyInstance(rawType.classLoader, arrayOf(rawType)) { _, method, args ->

    var strictDelegateResult: Supplier<Any?>? = null
    delegates.forEach { (delegate, instructions) ->

      if (instructions.traceId != TraceContextHolder.getTraceId()) {
        // Not within the same trace, this delegate can be ignored for invocation
        return@forEach
      }

      // We are in "strict mode"
      if (instructions.useStrictResult) {

        // We use "supplier" to invoke this delegate later (after all delegates + default behavior)

        if (strictDelegateResult != null) {
          // We have already a strict result, this is a conflict
          throw IllegalStateException(
            "Conflicting strict observe logics for traceId '${instructions.traceId}'!",
          )
        }

        strictDelegateResult = Supplier {
          try {
            if (args == null) {
              method.invoke(delegate)
            } else {
              method.invoke(delegate, *args)
            }
          } catch (e: InvocationTargetException) {
            (e.cause as? RuntimeException)?.let { throw it }
            throw e
          }
        }
      } else {
        // strict yes, but result can be ignored
        try {
          if (args == null) {
            method.invoke(delegate)
          } else {
            method.invoke(delegate, *args)
          }
        } catch (e: Exception) {
          logger.warn(e) { "error on invoking test proxy" }
        }
      }
    }

    // Check if we have a strict result to return.
    strictDelegateResult?.let {
      // Return this result and do not call the default implementation.
      return@newProxyInstance it.get()
    }

    return@newProxyInstance if (args == null) {
      method.invoke(globalDefaultBehavior)
    } else {
      method.invoke(globalDefaultBehavior, *args)
    }
  } as T

  protected lateinit var injector: Injector

  @Inject
  internal fun initialize(injector: Injector) {
    this.injector = injector
    this.injector.injectMembers(globalDefaultBehavior)
  }

  fun <R> observe(useDelegateResult: Boolean = false, block: (delegate: T) -> R): R = observe(
    delegate = mockkClass(type = globalDefaultBehavior::class, relaxed = true),
    useDelegateResult = useDelegateResult,
    block = block,
  )

  fun <D : T, R> observe(delegate: D, useDelegateResult: Boolean = false, block: (delegate: D) -> R): R {
    return TraceContextHolder.withInherited { bound ->
      val pair = Pair(
        delegate,
        DelegateInstructions(traceId = bound.traceId, useStrictResult = useDelegateResult),
      )
      delegates.add(pair)
      try {
        return@withInherited block(delegate)
      } finally {
        delegates.remove(pair)
      }
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
