package org.sempods.testsupport

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * The server stops and the client shuts down once the class is done. Only a class that has finished
 * can show that, so this one launches [OneStubbedPod] on the JUnit Platform and looks afterwards.
 */
class MockPodReleaseTest {

  @Test
  fun `the server and the client are released after the class`() {
    OneStubbedPod.used = null
    val summary = SummaryGeneratingListener()
    LauncherFactory.create().execute(
      LauncherDiscoveryRequestBuilder.request()
        .selectors(selectClass(OneStubbedPod::class.java))
        .configurationParameter(LAUNCHED, "true")
        .build(),
      summary,
    )
    assertEquals(1, summary.summary.testsSucceededCount, "the launched class ran: ${summary.summary.failures.map { it.exception }}")

    val (server, client) = assertNotNull(OneStubbedPod.used)
    assertTrue(server.hasStopped(), "the server stopped")
    assertFailsWith<ConnectException>("its port is closed") {
      Socket().use { it.connect(InetSocketAddress("127.0.0.1", server.port), 1_000) }
    }
    assertTrue(client.dispatcher.executorService.isShutdown, "the client's dispatcher shut down")
    assertEquals(0, client.connectionPool.connectionCount(), "the client holds no connection")
  }
}

private const val LAUNCHED = "org.sempods.testsupport.launchedByReleaseTest"

/** Runs only when [MockPodReleaseTest] launches it; the build's own run reports it skipped. */
class LaunchedByReleaseTest : ExecutionCondition {
  override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
    if (context.getConfigurationParameter(LAUNCHED).isPresent) {
      ConditionEvaluationResult.enabled("launched by MockPodReleaseTest")
    } else {
      ConditionEvaluationResult.disabled("a fixture of MockPodReleaseTest, which launches it")
    }
}

/** A class that uses both the server and the client, and hands them out to be looked at afterwards. */
@ExtendWith(LaunchedByReleaseTest::class)
class OneStubbedPod : MockPodTest() {

  @Test
  fun `uses the server and the client`() {
    server.`when`(request()).respond(response().withStatusCode(204))
    client.newCall(Request.Builder().url(origin).build()).execute().use { assertEquals(204, it.code) }
    used = server to client
  }

  companion object {
    @Volatile
    var used: Pair<ClientAndServer, OkHttpClient>? = null
  }
}
