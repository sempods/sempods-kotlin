package org.sempods.client.net

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VettingDnsTest {

  private val strict = SempodsUrlPolicy(allowPrivateAddresses = false)
  private val relaxed = SempodsUrlPolicy(allowPrivateAddresses = true)

  private fun dns(policy: SempodsUrlPolicy, vararg addresses: String) =
    VettingDns(policy, resolve = { addresses.map { InetAddress.getByName(it) } })

  @Test fun `blocked ranges are rejected at resolve time under a strict policy`() {
    val blocked = listOf(
      "10.0.0.1",           // RFC 1918
      "169.254.169.254",    // link-local / metadata
      "100.64.0.1",         // CGNAT
      "198.18.0.1",         // benchmarking
      "0.0.0.5",            // 0.0.0.0/8
      "::1",                // IPv6 loopback
      "fc00::1",            // ULA
      "fe80::1",            // IPv6 link-local
      "64:ff9b::a00:1",     // NAT64 embedding 10.0.0.1
      "::a00:1",            // v4-compatible embedding 10.0.0.1
    )
    for (address in blocked) {
      val ex = assertFailsWith<SsrfBlockedException>("expected $address to be blocked") {
        dns(strict, address).lookup("pod.example")
      }
      assertTrue("pod.example" in ex.message!!, "message should name the host: ${ex.message}")
    }
  }

  @Test fun `a mixed lookup is rejected as a whole, not filtered`() {
    assertFailsWith<SsrfBlockedException> {
      dns(strict, "93.184.216.34", "10.0.0.1").lookup("pod.example")
    }
  }

  @Test fun `an all-public lookup is returned intact`() {
    val addresses = dns(strict, "93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946").lookup("pod.example")
    assertEquals(2, addresses.size)
    assertEquals("93.184.216.34", addresses[0].hostAddress)
  }

  @Test fun `a relaxed policy passes private addresses through`() {
    val addresses = dns(relaxed, "10.0.0.1", "127.0.0.1").lookup("pod.local")
    assertEquals(2, addresses.size)
  }

  @Test fun `an empty resolution is an UnknownHostException`() {
    assertFailsWith<UnknownHostException> { dns(strict).lookup("pod.example") }
  }

  @Test fun `a trusted host skips the range check even under a strict policy`() {
    val trusted = VettingDns(
      strict,
      trustedHosts = setOf("id.internal"),
      resolve = { listOf(InetAddress.getByName("10.0.0.1")) },
    )
    assertEquals(1, trusted.lookup("id.internal").size)
    // ...but only for the exact trusted hostname — everything else stays vetted.
    assertFailsWith<SsrfBlockedException> { trusted.lookup("pod.example") }
  }
}
