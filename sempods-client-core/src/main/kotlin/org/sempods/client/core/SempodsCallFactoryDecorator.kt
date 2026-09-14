package org.sempods.client.core

import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * Turns the client a [SempodsTransport] has configured into the [Call.Factory] its sessions call.
 *
 * This is where an instrumentation plugs in that wraps a client rather than adding an interceptor to
 * it. OpenTelemetry's OkHttp library is one: it derives a client of its own from the one it is
 * given, adds its interceptors and hands back a factory.
 *
 * ```java
 * var transport = SempodsTransport.builder()
 *     .callFactory(client -> OkHttpTelemetry.create(openTelemetry).createCallFactory(client))
 *     .build();
 * ```
 *
 * **Derive from the client [decorate] receives.** It already carries the outbound guard, the
 * redirect and resend policy and the deadlines; a factory built from any other client runs without
 * them.
 */
fun interface SempodsCallFactoryDecorator {

  fun decorate(client: OkHttpClient): Call.Factory
}
