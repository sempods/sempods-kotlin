package org.sempods.mcp.pods

import okhttp3.OkHttpClient
import org.sempods.client.SempodsOkHttp
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.commons.okhttp.TraceparentInterceptor

/**
 * The pod client the suites dial a simulated pod on — what `SempodsMcpModule.podCalls` builds,
 * without the per-pod budget.
 *
 * [allowLocal] is what separates the two uses: `true` lets loopback through, because that is where a
 * simulated pod runs; `false` is how a suite proves the SSRF guard fires on an address a pod
 * advertised.
 */
internal fun testPodCalls(allowLocal: Boolean = true): OkHttpClient =
  SempodsOkHttp.install(
    OkHttpClient.Builder().addInterceptor(TraceparentInterceptor),
    guard = SempodsOutboundGuard(policy = PodUrlPolicy(allowLocal = allowLocal).rules),
    admission = null,
  ).build()
