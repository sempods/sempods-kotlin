package org.sempods.retrieval.impls.virtualthreads

import org.sempods.retrieval.spi.FindExecutor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * [FindExecutor] on a virtual thread per task. It holds no idle threads, so one instance lives as
 * long as the server and needs no shutdown.
 */
class VirtualThreadFindExecutor : FindExecutor {

  private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

  override fun execute(task: Runnable) = executor.execute(task)
}
