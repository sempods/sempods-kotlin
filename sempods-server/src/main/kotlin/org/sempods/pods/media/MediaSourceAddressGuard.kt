package org.sempods.pods.media

// Inet4Address is referenced from the KDoc below, where the mapped-address normalisation matters.
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Which addresses the pod server may connect to when a caller hands it a URL to copy from.
 *
 * **This is the security boundary of `MediaSourceFetcher`.** Copy-from-URL turns "hold write on any
 * one context" into "make the pod server issue an HTTP request to an address of my choosing", on a
 * machine that also runs a database, an object store and a model server. The guard is what keeps
 * that request pointed at the public internet.
 *
 * A pure function over a single [InetAddress] on purpose: the whole deny table is then testable
 * without a socket, and the *policy* question ("may this address be reached") stays separate from
 * the *protocol* question ("did we actually connect to the address we checked"), which is
 * [MediaSourceFetcher]'s job.
 *
 * The caller applies this to **every** record a hostname resolves to and refuses the host if any one
 * fails — see [verifyAllOrThrow]. A host answering with one public and one private address is not
 * partially usable: which record a connection ends up on is not the caller's to decide.
 *
 * Implementations are expected to be stateless and thread-safe.
 */
interface MediaSourceAddressGuard {

  /**
   * Why [address] may not be connected to, or `null` when it may.
   *
   * A string rather than a boolean so a rejection can say which rule caught it — the message reaches
   * the log, not the caller, who gets an undifferentiated `400`.
   */
  fun rejectionReason(address: InetAddress): String?

  /**
   * Runs [rejectionReason] over every record and throws on the first rejection.
   *
   * **All of them, not the first one.** A hostname that resolves to a public address *and* to
   * `169.254.169.254` would otherwise pass whenever the public record happened to sort first, and
   * the connection could still land on the other one.
   */
  fun verifyAllOrThrow(host: String, addresses: List<InetAddress>) {
    require(addresses.isNotEmpty()) { "no addresses to verify for '$host'" }
    addresses.forEach { address ->
      rejectionReason(address)?.let { reason ->
        throw MediaSourceException("'$host' resolves to ${address.hostAddress}, which is $reason")
      }
    }
  }

  companion object {

    /**
     * The production policy: **globally reachable unicast, and nothing else.**
     *
     * Stated as an exclusion rather than an allowlist because that is what the registries give:
     * IANA's IPv4 and IPv6 Special-Purpose Address Registries mark each block "globally reachable"
     * yes or no, and [DENIED_BLOCKS] is the *no* column. Everything left over is an address a
     * request can honestly be sent to.
     *
     * Denying only the *private* ranges is the tempting half-measure and is not enough. Two examples
     * of what it misses:
     *
     * - **`198.18.0.0/15`**, reserved for network-device benchmarking, is not private and is
     *   routinely routed inside corporate and lab networks — an address the naive policy would treat
     *   as ordinary public space.
     * - **`2002::/16`**, deprecated 6to4, embeds an IPv4 address in its second through fifth bytes.
     *   `2002:a9fe:a9fe::` *is* `169.254.169.254` to any host with a 6to4 route, which makes it the
     *   IPv6 twin of the mapped-address trick below.
     *
     * **IPv4-mapped and IPv4-compatible forms are rejected outright**, before any range test. A JVM
     * normalises the literal `[::ffff:169.254.169.254]` into an [Inet4Address], so the IPv4 rules
     * would catch it anyway — but that normalisation is an implementation detail of the resolver,
     * and this is the smuggling path the roadmap names. No legitimate public host needs to be
     * addressed in either form, so refusing them costs nothing and does not depend on the JVM's
     * behaviour staying what it is.
     */
    val GLOBAL_UNICAST_ONLY: MediaSourceAddressGuard = object : MediaSourceAddressGuard {

      override fun rejectionReason(address: InetAddress): String? = when {
        address.isAnyLocalAddress -> "the unspecified address"
        address.isLoopbackAddress -> "a loopback address"
        // Before the range tests: an address arriving in this shape is either a JVM that did not
        // normalise it or a caller who wrote it out deliberately.
        isIPv4Embedded(address) -> "an IPv4 address written in an IPv6 form"
        address.isLinkLocalAddress -> "a link-local address"
        address.isMulticastAddress -> "a multicast address"
        address.isSiteLocalAddress -> "a private address"
        else -> DENIED_BLOCKS.firstOrNull { it.contains(address) }?.reason
      }
    }

    /**
     * Whether [address] is written in one of the two IPv6 forms that wrap an IPv4 address.
     *
     * Checked on the raw bytes rather than through [Inet6Address.isIPv4CompatibleAddress] alone,
     * because the mapped form (`::ffff:a.b.c.d`) is not "compatible" in that method's sense.
     */
    internal fun isIPv4Embedded(address: InetAddress): Boolean {
      if (address !is Inet6Address) return false
      val bytes = address.address
      // Both forms are ten zero bytes followed by either 0x0000 (compatible) or 0xFFFF (mapped).
      if (bytes.take(10).any { it.toInt() != 0 }) return false
      val marker = ((bytes[10].toInt() and 0xFF) shl 8) or (bytes[11].toInt() and 0xFF)
      return marker == 0x0000 || marker == 0xFFFF
    }

    /**
     * The blocks IANA marks as not globally reachable, minus the ones [InetAddress] already answers
     * for (loopback, link-local, site-local, multicast, unspecified).
     *
     * A table rather than a chain of byte comparisons because that is what it is: adding a block is
     * one line naming the RFC, and each entry can be read against the registry it came from.
     */
    private val DENIED_BLOCKS: List<AddressBlock> = listOf(
      // ── IPv4 ──
      // "This host on this network" (RFC 1122). `isAnyLocalAddress` covers only 0.0.0.0 itself.
      AddressBlock("0.0.0.0/8", "in the reserved 0.0.0.0/8 block"),
      // Carrier-grade NAT (RFC 6598) — not private by `isSiteLocalAddress`, not routable either,
      // and inside a datacentre it reaches other tenants.
      AddressBlock("100.64.0.0/10", "a carrier-grade NAT address"),
      AddressBlock("192.0.0.0/24", "in the IETF protocol assignments block"),
      AddressBlock("192.0.2.0/24", "a documentation address (TEST-NET-1)"),
      AddressBlock("192.88.99.0/24", "a deprecated 6to4 relay anycast address"),
      // RFC 2544. The one on this list most likely to be genuinely routed in a corporate network,
      // which is exactly why a private-ranges-only policy is not enough.
      AddressBlock("198.18.0.0/15", "in the network benchmarking block"),
      AddressBlock("198.51.100.0/24", "a documentation address (TEST-NET-2)"),
      AddressBlock("203.0.113.0/24", "a documentation address (TEST-NET-3)"),
      // 240.0.0.0/4, which also swallows the 255.255.255.255 broadcast address.
      AddressBlock("240.0.0.0/4", "in the reserved 240.0.0.0/4 block"),

      // ── IPv6 ──
      // NAT64 (RFC 6052/8215): the low 32 bits are an IPv4 address a translator will forward to.
      AddressBlock("64:ff9b::/96", "a NAT64 address, which carries an IPv4 target"),
      AddressBlock("64:ff9b:1::/48", "a local-use NAT64 address"),
      AddressBlock("100::/64", "a discard-only address"),
      // The whole IETF protocol-assignments parent, not its children one by one — the IPv4 side
      // already takes this shape with 192.0.0.0/24 above, and for the same reason.
      //
      // IANA marks 2001::/23 not globally reachable and then carves globally reachable
      // more-specifics out of it (the PCP / TURN / DNS-SD anycasts at 2001:1::1-3, AMT at
      // 2001:3::/32, AS112 at 2001:4:112::/48, ORCHIDv2 at 2001:20::/28, Drone Remote ID at
      // 2001:30::/28). Naming only the *denied* children — Teredo, benchmarking, ORCHID — is what
      // this list used to do, and it left every unassigned gap in the parent open: 2001:5::1 was
      // treated as ordinary public space, and would be reachable on a host that routes protocol
      // space internally. Denying the parent inverts that: a block IANA assigns inside it in future
      // is refused until someone decides otherwise, rather than allowed by omission.
      //
      // The globally reachable carve-outs are denied along with it, deliberately. They are protocol
      // machinery — anycast rendezvous addresses, multicast relays, DNS sinks, cryptographic
      // identifiers — and none of them serves a file a pod would copy. Losing them costs nothing;
      // the gaps cost the guarantee this guard exists for.
      AddressBlock("2001::/23", "in the IETF protocol assignments block"),
      // Outside 2001::/23 (0x0db8 is far past 0x01ff), so it needs its own line.
      AddressBlock("2001:db8::/32", "a documentation address"),
      // 6to4 (RFC 3056, deprecated by RFC 7526): bytes 2–5 are an IPv4 address.
      AddressBlock("2002::/16", "a 6to4 address, which carries an IPv4 target"),
      AddressBlock("3fff::/20", "a documentation address"),
      AddressBlock("5f00::/16", "a segment-routing address"),
      // `isSiteLocalAddress` knows only the deprecated fec0::/10, so the range an actual IPv6
      // network uses internally today has to be named here.
      AddressBlock("fc00::/7", "a unique-local address"),
    )

    /** One CIDR block and why an address inside it may not be reached. */
    private class AddressBlock(cidr: String, val reason: String) {

      private val network: ByteArray = InetAddress.getByName(cidr.substringBefore('/')).address
      private val prefixBits: Int = cidr.substringAfter('/').toInt()

      init {
        require(prefixBits in 0..(network.size * 8)) { "prefix out of range for $cidr" }
      }

      /** Whether [address] falls inside this block — `false` for anything of the other family. */
      fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        val wholeBytes = prefixBits / 8
        for (i in 0 until wholeBytes) {
          if (bytes[i] != network[i]) return false
        }
        val remainingBits = prefixBits % 8
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (bytes[wholeBytes].toInt() and mask) == (network[wholeBytes].toInt() and mask)
      }
    }
  }
}
