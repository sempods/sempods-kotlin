package org.sempods.client.net

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * The union of what two guards used to check separately — `sempods-mcp`'s pod-base admission and a
 * consumer-side guard for dereferencing foreign Linked-Data URIs. Both sets of expectations are
 * asserted here, so neither profile loses a range by having its own table deleted.
 */
class SempodsUrlPolicyTest {

  private val strict = SempodsUrlPolicy(allowPrivateAddresses = false)
  private val local = SempodsUrlPolicy(allowPrivateAddresses = true)

  private fun addr(host: String) = InetAddress.getByName(host)

  // --- pod base admission (was PodUrlPolicy.reject) ------------------------

  @Test fun `https public pod is accepted`() {
    assertNull(strict.rejectPodBase("https://sempods.org/alice"))
  }

  @Test fun `http is rejected in strict mode`() {
    assertNotNull(strict.rejectPodBase("http://sempods.org/alice"))
  }

  @Test fun `loopback http is accepted only in local mode`() {
    assertNotNull(strict.rejectPodBase("http://127.0.0.1:8090/pod"))
    assertNull(local.rejectPodBase("http://127.0.0.1:8090/pod"))
    assertNull(local.rejectPodBase("http://localhost:8090/pod"))
  }

  @Test fun `fragments, userinfo, and bad schemes are rejected on a pod base`() {
    assertNotNull(strict.rejectPodBase("https://sempods.org/pod#frag"))
    assertNotNull(strict.rejectPodBase("https://user@sempods.org/pod"))
    assertNotNull(strict.rejectPodBase("ftp://sempods.org/pod"))
    assertNotNull(strict.rejectPodBase("not a url"))
  }

  @Test fun `loopback names are rejected in strict mode regardless of scheme`() {
    assertNotNull(strict.rejectPodBase("https://localhost:8443/pod"))
    assertNotNull(strict.rejectPodBase("https://api.localhost/pod"))
    assertNotNull(strict.rejectPodBase("https://ip6-localhost/pod"))
    assertNull(local.rejectPodBase("https://localhost:8443/pod"))
  }

  @Test fun `non-dotted IPv4 loopback encodings are rejected in strict mode`() {
    assertNotNull(strict.rejectPodBase("https://2130706433/pod")) // decimal dword
    assertNotNull(strict.rejectPodBase("https://127.1/pod"))      // short form
    assertNotNull(strict.rejectPodBase("https://0x7f000001/pod")) // hex
    assertNotNull(strict.rejectPodBase("https://[::1]/pod"))
    assertNull(strict.rejectPodBase("https://93.184.216.34/pod"))
  }

  // --- per-request target guard -------------------------------------------

  @Test fun `a target may be http and may carry a fragment`() {
    // Dereferencing foreign Linked-Data URIs makes `http` and `#fragment` the normal case, and
    // rejecting them would take away the capability rather than protect it.
    assertNull(strict.rejectTarget(java.net.URI("http://example.org/data#me")))
    assertNull(strict.rejectTarget(java.net.URI("https://example.org/data#me")))
  }

  @Test fun `a target must still be HTTP(S) with a host`() {
    assertNotNull(strict.rejectTarget(java.net.URI("ftp://example.org/x")))
    assertNotNull(strict.rejectTarget(java.net.URI("file:///etc/passwd")))
    assertNotNull(strict.rejectTarget(java.net.URI("mailto:someone@example.org")))
  }

  @Test fun `an IP-literal target in a non-global range is refused before any connection`() {
    // The case the DNS hook cannot see: an engine given a literal host has nothing to resolve.
    listOf(
      "http://169.254.169.254/latest/meta-data",
      "http://127.0.0.1/x",
      "http://[::1]/x",
      "http://[fd00::1]/x",
      "http://2130706433/x",
      "http://0x7f000001/x",
    ).forEach { raw ->
      assertNotNull(strict.rejectTarget(java.net.URI(raw)), "must refuse $raw")
      assertNull(local.rejectTarget(java.net.URI(raw)), "relaxed mode reaches private pods on purpose")
    }
  }

  // --- the address table (was SempodsHttpEventLookup.isPubliclyRoutable) ---

  @Test fun `public addresses pass the rule`() {
    listOf("93.184.216.34", "8.8.8.8", "2606:2800:220:1::1", "2001:4860:4860::8888").forEach {
      assertTrue(SempodsUrlPolicy.isPubliclyRoutable(addr(it)), "$it is public and must pass")
      assertNull(strict.rejectAddress(addr(it)))
    }
  }

  @Test fun `every non-global range is rejected, including the ones the JDK predicates miss`() {
    listOf(
      "fd00::1", "fc00::1",                       // ULA — isSiteLocalAddress says false for these
      "::1", "fe80::1", "::", "fec0::1",
      "ff02::1",
      "127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.1", "169.254.169.254",
      "100.64.0.1",                               // carrier-grade NAT
      "0.0.0.1",                                  // "this network"
      "192.0.0.1", "198.18.0.1",
      "192.0.2.1", "198.51.100.1", "203.0.113.1", // documentation
      "192.88.99.1",                              // 6to4 relay anycast — only the dereference guard had this
      "224.0.0.1", "240.0.0.1", "255.255.255.255",
    ).forEach {
      assertTrue(!SempodsUrlPolicy.isPubliclyRoutable(addr(it)), "$it must be rejected")
      assertNotNull(strict.rejectAddress(addr(it)), "$it must be rejected")
    }
  }

  @Test fun `IPv6 forms that embed or reserve a non-global target are rejected`() {
    listOf(
      "2002:0a00:0001::1",   // 6to4 reaching 10.0.0.1
      "64:ff9b::a00:1",      // NAT64 reaching 10.0.0.1
      "2001:0:0a00:0001::1", // Teredo
      "2001:db8::1",         // documentation
      "100::1",              // discard-only — only the dereference guard had this
      "::a00:1",             // v4-compatible reaching 10.0.0.1 — only PodUrlPolicy had this
      "::ffff:10.0.0.1",     // v4-mapped (the JDK hands this back as an Inet4Address)
    ).forEach {
      assertNotNull(strict.rejectAddress(addr(it)), "$it must be rejected")
    }
  }

  @Test fun `the whole NAT64 prefix is refused, payload or not`() {
    // A deliberate tightening where the two sources disagreed: `sempods-mcp` extracted the embedded
    // IPv4 and let a public payload through, the dereference guard refused the prefix outright. The
    // strict reading wins, because taking the lenient one would have removed a range that guard
    // blocks today — and a pod reachable only through NAT64 is not a deployment either profile has.
    assertNotNull(strict.rejectAddress(addr("64:ff9b::5db8:d822"))) // embeds public 93.184.216.34
    assertNotNull(strict.rejectAddress(addr("64:ff9b:1::5db8:d822")))
  }

  @Test fun `relaxed mode lets everything through`() {
    listOf("10.0.0.1", "127.0.0.1", "::1", "169.254.169.254").forEach {
      assertNull(local.rejectAddress(addr(it)))
    }
  }
}
