package org.sempods.commons.okhttp

import com.google.inject.Provides
import com.google.inject.Singleton
import okhttp3.OkHttpClient
import org.sempods.commons.guice.BaseModule
import java.time.Duration

/**
 * The one HTTP client a composition shares, and the trace binding on it.
 *
 * **An `object` without constructor parameters, deliberately.** Guice deduplicates installed modules
 * by equality, and two module instances that are not equal but both bind `OkHttpClient` are a
 * duplicate-binding error rather than a merge — which is exactly what happens where two
 * compositions meet in one injector. An `object` deduplicates by identity and cannot get this
 * wrong. A composition that needs different budgets derives them with `newBuilder()`, which keeps
 * this client's connection pool and dispatcher.
 *
 * **The timeouts are set here rather than inherited**, because OkHttp's defaults are not this
 * project's budget: 10 s read, and no whole-call bound at all. The four values below state that
 * budget in one place, so no call site has to decide it and none can drift from the others.
 *
 * There is no cookie jar to disable — OkHttp's default is already `CookieJar.NO_COOKIES`, which is
 * what a shared client wants: one caller's session must not travel on another caller's request.
 */
object OkHttpClientModule : BaseModule() {

  /** The TCP + TLS handshake alone. */
  private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

  /** The gap between two bytes in either direction — not the length of the whole exchange. */
  private val SOCKET_TIMEOUT: Duration = Duration.ofSeconds(60)

  /**
   * The whole call including the body, which is the only budget a peer cannot outlast by answering
   * slowly: [SOCKET_TIMEOUT] measures the gap between two bytes, so a drip just inside it runs
   * forever.
   */
  private val CALL_TIMEOUT: Duration = Duration.ofSeconds(60)

  @Provides
  @Singleton
  fun okHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT)
      .readTimeout(SOCKET_TIMEOUT)
      .writeTimeout(SOCKET_TIMEOUT)
      .callTimeout(CALL_TIMEOUT)
      .addInterceptor(TraceparentInterceptor)
      .build()
}
