package org.sempods.testsupport

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** The server listens on loopback and nowhere else. Why that matters: `docs/testing.md` §"Stubbing an external API". */
class MockPodLoopbackTest : MockPodTest() {

  private fun connect(address: InetAddress) = Socket().use { it.connect(InetSocketAddress(address, server.port), 1_000) }

  @Test
  fun `the server answers on 127_0_0_1`() {
    connect(InetAddress.getByName("127.0.0.1"))
  }

  @Test
  fun `the server answers on no other address of this machine`() {
    val others = NetworkInterface.networkInterfaces().toList()
      .filter { it.isUp && !it.isLoopback }
      .flatMap { it.inetAddresses().toList() }
      .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    assumeTrue(others.isNotEmpty(), "this machine has no address beyond loopback to try")

    others.forEach { address ->
      assertFailsWith<IOException>("nothing answers on $address") { connect(address) }
    }
  }
}
