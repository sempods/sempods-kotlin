package org.sempods.commons.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForwardedForTest {

  @Test fun `a single entry is the client`() {
    assertEquals("203.0.113.7", ForwardedFor.clientIp("203.0.113.7"))
  }

  @Test fun `the rightmost entry wins, because the proxy appended it`() {
    assertEquals("203.0.113.7", ForwardedFor.clientIp("198.51.100.4, 203.0.113.7"))
  }

  @Test fun `a forged leftmost entry does not move the answer`() {
    // What a client writes for itself lands on the left; the proxy's own view is appended after.
    assertEquals("203.0.113.7", ForwardedFor.clientIp("1.2.3.4, 203.0.113.7"))
    assertEquals("203.0.113.7", ForwardedFor.clientIp("1.2.3.4, 5.6.7.8, 203.0.113.7"))
  }

  @Test fun `whitespace around the separators is not part of the address`() {
    assertEquals("203.0.113.7", ForwardedFor.clientIp("  198.51.100.4 ,   203.0.113.7  "))
  }

  @Test fun `a trailing separator does not become the answer`() {
    assertEquals("203.0.113.7", ForwardedFor.clientIp("203.0.113.7, "))
  }

  @Test fun `an absent or empty header means no proxy spoke`() {
    assertNull(ForwardedFor.clientIp(null))
    assertNull(ForwardedFor.clientIp(""))
    assertNull(ForwardedFor.clientIp("   "))
    assertNull(ForwardedFor.clientIp(", ,"))
  }

  @Test fun `the value is returned as written`() {
    // An IPv6 address, and a proxy that appends a port: both are identities to compare, not
    // addresses to dial, so nothing is parsed away.
    assertEquals("[2001:db8::1]", ForwardedFor.clientIp("[2001:db8::1]"))
    assertEquals("203.0.113.7:41234", ForwardedFor.clientIp("198.51.100.4, 203.0.113.7:41234"))
  }
}
