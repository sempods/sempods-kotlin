package org.sempods.pods.media

import org.junit.jupiter.api.Test
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The deny table, as a table.
 *
 * No socket and no name resolution anywhere in here — every address is a literal, so
 * [InetAddress.getByName] parses rather than looks up. That is the point of the guard being a pure
 * function: the security-relevant decision is checkable without a network, which means it can be
 * checked exhaustively.
 *
 * The boundary cases carry as much weight as the obvious ones. `172.32.0.1` sitting just outside
 * `172.16.0.0/12` and `100.63.255.255` just outside carrier-grade NAT are what a hand-written mask
 * gets wrong, and a guard that rejects half the public internet would be found much later than one
 * that lets a private address through.
 */
class MediaSourceAddressGuardTest {

  private val guard = MediaSourceAddressGuard.GLOBAL_UNICAST_ONLY

  private fun assertRejected(literal: String) {
    val address = InetAddress.getByName(literal)
    assertNotNull(guard.rejectionReason(address), "$literal must be rejected")
  }

  private fun assertAllowed(literal: String) {
    val address = InetAddress.getByName(literal)
    assertNull(guard.rejectionReason(address), "$literal must be allowed")
  }

  @Test
  fun `loopback is rejected in both families`() {
    assertRejected("127.0.0.1")
    // The whole 127/8 block, not just the one address people write down.
    assertRejected("127.9.9.9")
    assertRejected("::1")
  }

  @Test
  fun `the unspecified address and the reserved zero block are rejected`() {
    assertRejected("0.0.0.0")
    assertRejected("::")
    // 0.0.0.0/8 — "this host on this network", which `isAnyLocalAddress` does not cover.
    assertRejected("0.1.2.3")
  }

  @Test
  fun `the cloud metadata address is rejected`() {
    // The single most valuable target of an SSRF: plain HTTP, no authentication, hands out
    // credentials for the machine it runs on.
    assertRejected("169.254.169.254")
    assertRejected("169.254.0.1")
  }

  @Test
  fun `private ranges are rejected and their neighbours are not`() {
    assertRejected("10.0.0.1")
    assertRejected("172.16.0.1")
    assertRejected("172.31.255.255")
    assertRejected("192.168.1.1")

    // Just outside 172.16.0.0/12 — public address space, and a mask written by hand is where this
    // goes wrong.
    assertAllowed("172.32.0.1")
    assertAllowed("172.15.255.255")
    assertAllowed("11.0.0.1")
  }

  @Test
  fun `carrier-grade NAT is rejected and its neighbours are not`() {
    // 100.64.0.0/10 is not "private" by `isSiteLocalAddress`, but it is not routable either — and
    // inside a datacentre it reaches other tenants.
    assertRejected("100.64.0.1")
    assertRejected("100.127.255.255")

    assertAllowed("100.63.255.255")
    assertAllowed("100.128.0.1")
  }

  @Test
  fun `multicast and the reserved top of the address space are rejected`() {
    assertRejected("224.0.0.1")
    assertRejected("240.0.0.1")
    assertRejected("255.255.255.255")
    assertRejected("ff02::1")
  }

  @Test
  fun `IPv6 link-local and unique-local are rejected`() {
    assertRejected("fe80::1")
    // fc00::/7 — what an IPv6 network actually uses internally. `isSiteLocalAddress` knows only the
    // deprecated fec0::/10, so this range is checked by hand and therefore worth its own case.
    assertRejected("fc00::1")
    assertRejected("fd12:3456:789a::1")
    assertRejected("fec0::1")
  }

  @Test
  fun `an IPv4 address wrapped in an IPv6 form is rejected even when the IPv4 address is public`() {
    // The JVM normalises the literal `::ffff:169.254.169.254` into an Inet4Address, so the IPv4
    // rules would catch that one anyway. This case cannot rely on that: 93.184.216.34 is public, so
    // the *only* thing that can reject it is the rule about the form itself — which is what keeps
    // the mapped form from being a way around an IPv4-shaped check.
    val mapped = Inet6Address.getByAddress(
      null,
      byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 93, 184.toByte(), 216.toByte(), 34),
      0,
    )

    assertNotNull(guard.rejectionReason(mapped))
  }

  @Test
  fun `special-use blocks that are not private are rejected too`() {
    // The half-measure this guards against is "deny the private ranges and call it done". None of
    // these is private, all of them are marked not-globally-reachable by IANA, and the first is
    // routinely routed inside corporate and lab networks — so a policy that let it through would
    // reach real hosts on the far side.
    assertRejected("198.18.0.1")
    assertRejected("198.19.255.255")
    assertRejected("192.0.2.1")
    assertRejected("198.51.100.1")
    assertRejected("203.0.113.1")
    assertRejected("192.0.0.1")
    assertRejected("192.88.99.1")

    assertRejected("2001:db8::1")
    assertRejected("100::1")
    assertRejected("3fff::1")
    assertRejected("5f00::1")

    // And their neighbours are ordinary public space.
    assertAllowed("198.17.255.255")
    assertAllowed("198.20.0.1")
    assertAllowed("192.0.3.1")
    assertAllowed("203.0.114.1")
    assertAllowed("2001:db9::1")
  }

  @Test
  fun `the whole IETF protocol assignments block is denied, not its known children`() {
    // Teredo, IPv6 benchmarking, deprecated ORCHID, ORCHIDv2 — the blocks that used to be listed
    // one by one.
    assertRejected("2001::1")
    assertRejected("2001:2::1")
    assertRejected("2001:10::1")
    assertRejected("2001:20::1")

    // The gaps between them, which is the point. IANA marks the 2001::/23 parent not globally
    // reachable, so an address in space it has not assigned is not "ordinary public" — on a host
    // that routes protocol space internally it is reachable, and naming only the known-bad children
    // left every one of these open.
    assertRejected("2001:4::1")
    assertRejected("2001:5::1")
    assertRejected("2001:ff::1")
    assertRejected("2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff")

    // The globally reachable carve-outs inside the parent go with it, deliberately: 2001:1::1 is
    // the Port Control Protocol anycast address, 2001:3::/32 is AMT, 2001:4:112::/48 is AS112.
    // IANA marks all three reachable, and none of them serves a file worth copying — the guard
    // over-blocks by a rounding error and gains a boundary with no holes in it.
    assertRejected("2001:1::1")
    assertRejected("2001:3::1")
    assertRejected("2001:4:112::1")

    // Immediately past the parent (0x0200) is ordinary space again.
    assertAllowed("2001:200::1")
  }

  @Test
  fun `IPv6 forms that carry an IPv4 target are rejected`() {
    // 6to4 puts an IPv4 address in bytes 2 through 5, so `2002:a9fe:a9fe::` *is* 169.254.169.254 to
    // any host with a 6to4 route — the IPv6 twin of the mapped-address trick, and invisible to a
    // check that only looks at IPv4.
    assertRejected("2002:a9fe:a9fe::")
    assertRejected("2002:c0a8:0101::")
    // NAT64 carries the IPv4 target in its low 32 bits and a translator forwards it.
    assertRejected("64:ff9b::a9fe:a9fe")
    assertRejected("64:ff9b:1::1")
  }

  @Test
  fun `the mapped metadata literal is rejected however the JVM parses it`() {
    // Written the way an attacker would send it. Which rule catches it is an implementation detail;
    // that it is caught is not.
    assertRejected("::ffff:169.254.169.254")
  }

  @Test
  fun `public addresses are allowed`() {
    assertAllowed("93.184.216.34")
    assertAllowed("8.8.8.8")
    assertAllowed("2606:2800:220:1:248:1893:25c8:1946")
  }

  @Test
  fun `one private record among public ones rejects the whole host`() {
    // The case the roadmap names: a hostname answering with a public and a private address. Taking
    // the public one and proceeding would be wrong, because which record a connection lands on is
    // not the pod server's to decide — and an attacker controls the record set.
    val addresses = listOf(
      InetAddress.getByName("93.184.216.34"),
      InetAddress.getByName("169.254.169.254"),
    )

    val e = assertFailsWith<MediaSourceException> { guard.verifyAllOrThrow("mixed.test", addresses) }

    assertEquals(
      "'mixed.test' resolves to 169.254.169.254, which is a link-local address",
      e.message,
    )
  }

  @Test
  fun `every denied block names a reason, and the reason is the one that applies`() {
    // The message is what an operator diagnoses a refusal from, so a block whose reason came out of
    // the wrong row would be worse than no message at all.
    assertEquals(
      "'bench.test' resolves to 198.18.0.1, which is in the network benchmarking block",
      assertFailsWith<MediaSourceException> {
        guard.verifyAllOrThrow("bench.test", listOf(InetAddress.getByName("198.18.0.1")))
      }.message,
    )
  }

  @Test
  fun `a host resolving only to public addresses passes`() {
    guard.verifyAllOrThrow(
      "public.test",
      listOf(
        InetAddress.getByName("93.184.216.34"),
        InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946"),
      ),
    )
  }

  @Test
  fun `an empty resolution is a programming error, not a pass`() {
    // Silently allowing "no addresses" would make a resolver that returns nothing look like a
    // verified host.
    assertFailsWith<IllegalArgumentException> { guard.verifyAllOrThrow("empty.test", emptyList()) }
  }
}
