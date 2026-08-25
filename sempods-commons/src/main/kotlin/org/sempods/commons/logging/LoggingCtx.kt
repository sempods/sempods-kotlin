package org.sempods.commons.logging

import org.slf4j.MDC

object LoggingCtx {

  fun <T> withLabels(
    vararg pairs: Pair<String, String>,
    block: () -> T,
  ): T {
    val keys = pairs.map { it.first }.toTypedArray()
    val replacements = putLabels(*pairs)
    return try {
      block()
    } finally {
      removeLabels(*keys, replacements = replacements)
    }
  }

  fun putLabels(vararg pairs: Pair<String, String>): Map<String, String>? {
    val replacements = mutableMapOf<String, String>()
    pairs.forEach { (key, value) ->
      val previous = MDC.get(key)
      if (previous != null) {
        replacements[key] = previous
      }
      MDC.put(key, value)
    }
    return replacements.ifEmpty { null }
  }

  fun removeLabels(vararg keys: String, replacements: Map<String, String>? = null) {
    keys.forEach { key ->
      val replacement = replacements?.get(key)
      if (replacement == null) {
        MDC.remove(key)
      } else {
        MDC.put(key, replacement)
      }
    }
  }
}
