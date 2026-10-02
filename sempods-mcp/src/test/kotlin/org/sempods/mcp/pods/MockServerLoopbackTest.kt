package org.sempods.mcp.pods

import org.mockserver.integration.ClientAndServer
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The simulated pods in this suite are MockServers, and a MockServer on the wildcard address can be
 * shadowed: another process that holds, or later takes, the same port on `127.0.0.1` receives
 * every request the test sends. Why, and where the address is set: `docs/testing.md` §"Stubbing an
 * external API".
 *
 * Linux refuses the second bind either way, so this only tells the two cases apart on macOS.
 */
class MockServerLoopbackTest {

  @Test
  fun `nothing else can listen on a simulated pod's port on loopback`() {
    val pod = ClientAndServer.startClientAndServer(0)
    try {
      assertFailsWith<BindException>("the port is the pod's alone") {
        // SO_REUSEADDR, as Go and the JDK set it on a listener: the option that lets the shadowing
        // bind through.
        ServerSocket().use {
          it.reuseAddress = true
          it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), pod.port))
        }
      }
    } finally {
      pod.stop()
    }
  }
}
