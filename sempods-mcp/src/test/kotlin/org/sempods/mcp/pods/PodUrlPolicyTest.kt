package org.sempods.mcp.pods

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PodUrlPolicyTest {

  private val strict = PodUrlPolicy(allowLocal = false)
  private val local = PodUrlPolicy(allowLocal = true)

  @Test fun `https public pod is accepted`() {
    assertNull(strict.reject("https://sempods.org/alice"))
  }

  @Test fun `http is rejected in strict mode`() {
    assertNotNull(strict.reject("http://sempods.org/alice"))
  }

  @Test fun `loopback http is accepted only in local mode`() {
    assertNotNull(strict.reject("http://127.0.0.1:8090/pod"))
    assertNull(local.reject("http://127.0.0.1:8090/pod"))
    assertNull(local.reject("http://localhost:8090/pod"))
  }

  @Test fun `private and reserved IP literals are rejected in strict mode`() {
    assertNotNull(strict.reject("https://10.0.0.5/pod"))
    assertNotNull(strict.reject("https://192.168.1.10/pod"))
    assertNotNull(strict.reject("https://127.0.0.1/pod"))
    assertNotNull(strict.reject("https://169.254.169.254/latest/meta-data"))
  }

  @Test fun `non-dotted IPv4 loopback encodings are rejected in strict mode`() {
    assertNotNull(strict.reject("https://2130706433/pod"))  // 127.0.0.1 as a decimal dword
    assertNotNull(strict.reject("https://127.1/pod"))        // short form
    assertNotNull(strict.reject("https://0x7f000001/pod"))   // hex form
    assertNotNull(strict.reject("https://[::1]/pod"))        // IPv6 loopback literal
    // a genuinely public dotted IPv4 still passes
    assertNull(strict.reject("https://93.184.216.34/pod"))
  }

  @Test fun `https loopback names are rejected in strict mode regardless of scheme`() {
    assertNotNull(strict.reject("https://localhost:8443/pod"))
    assertNotNull(strict.reject("https://localhost/pod"))
    assertNotNull(strict.reject("https://api.localhost/pod"))
    assertNotNull(strict.reject("https://ip6-localhost/pod"))
    // …but allowed in local mode.
    assertNull(local.reject("https://localhost:8443/pod"))
  }

  @Test fun `fragments, userinfo, and bad schemes are rejected`() {
    assertNotNull(strict.reject("https://sempods.org/pod#frag"))
    assertNotNull(strict.reject("https://user@sempods.org/pod"))
    assertNotNull(strict.reject("ftp://sempods.org/pod"))
    assertNotNull(strict.reject("not a url"))
  }

  @Test fun `reserved IPv4 range literals are rejected in strict mode`() {
    assertNotNull(strict.reject("https://100.64.0.1/pod"))          // CGNAT
    assertNotNull(strict.reject("https://198.18.0.1/pod"))          // benchmarking
    assertNotNull(strict.reject("https://0.177.1.2/pod"))           // 0.0.0.0/8
    assertNotNull(strict.reject("https://192.0.0.170/pod"))         // protocol assignments
    assertNotNull(strict.reject("https://192.0.2.1/pod"))           // TEST-NET-1
    assertNotNull(strict.reject("https://198.51.100.1/pod"))        // TEST-NET-2
    assertNotNull(strict.reject("https://203.0.113.1/pod"))         // TEST-NET-3
    assertNotNull(strict.reject("https://224.0.0.1/pod"))           // multicast
    assertNotNull(strict.reject("https://255.255.255.255/pod"))     // broadcast (240/4)
  }

  @Test fun `special IPv6 literals are rejected in strict mode`() {
    assertNotNull(strict.reject("https://[fc00::1]/pod"))            // ULA
    assertNotNull(strict.reject("https://[fe80::1]/pod"))            // link-local
    assertNotNull(strict.reject("https://[2001:db8::1]/pod"))        // documentation
    assertNotNull(strict.reject("https://[64:ff9b::a00:1]/pod"))     // NAT64 embedding 10.0.0.1
    assertNotNull(strict.reject("https://[::ffff:10.0.0.1]/pod"))    // v4-mapped
    assertNotNull(strict.reject("https://[ff02::1]/pod"))            // multicast
    assertNotNull(strict.reject("https://[2002:a00:1::]/pod"))       // 6to4 (embeds 10.0.0.1)
    assertNotNull(strict.reject("https://[2001:0:53aa:64c::1]/pod")) // Teredo
  }

  @Test fun `rejectAddress vets resolved addresses in strict mode only`() {
    fun addr(host: String) = InetAddress.getByName(host)
    assertNotNull(strict.rejectAddress(addr("10.0.0.1")))
    assertNotNull(strict.rejectAddress(addr("169.254.169.254")))
    assertNotNull(strict.rejectAddress(addr("100.64.0.1")))
    assertNotNull(strict.rejectAddress(addr("::1")))
    assertNotNull(strict.rejectAddress(addr("fc00::1")))
    assertNotNull(strict.rejectAddress(addr("64:ff9b::a00:1")))      // NAT64 embedding 10.0.0.1
    assertNotNull(strict.rejectAddress(addr("::a00:1")))             // v4-compatible embedding 10.0.0.1
    assertNull(strict.rejectAddress(addr("93.184.216.34")))
    // NAT64 is now refused by prefix rather than by payload: the dereference guard blocked the
    // whole range and this side extracted the embedded IPv4, so the shared table took the stricter
    // reading. A pod reachable only through NAT64 is not a deployment either profile has.
    assertNotNull(strict.rejectAddress(addr("64:ff9b::5db8:d822")))  // NAT64 embedding public 93.184.216.34
    // relaxed mode: everything passes
    assertNull(local.rejectAddress(addr("10.0.0.1")))
    assertNull(local.rejectAddress(addr("127.0.0.1")))
    assertNull(local.rejectAddress(addr("::1")))
  }
}
