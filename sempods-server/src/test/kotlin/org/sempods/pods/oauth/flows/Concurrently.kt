package org.sempods.pods.oauth.flows

import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs [action] [times] times at once and answers the results in call order.
 *
 * Every call waits at one barrier first, so they reach the store together rather than one after
 * another — which is what an atomic operation has to survive, and what a sequential replay test
 * cannot show.
 */
internal fun <T> concurrently(times: Int = 8, action: (Int) -> T): List<T> {
  val barrier = CyclicBarrier(times)
  val pool = Executors.newFixedThreadPool(times)
  try {
    return (0 until times)
      .map { i -> pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); action(i) }) }
      .map { it.get(60, TimeUnit.SECONDS) }
  } finally {
    pool.shutdownNow()
  }
}
