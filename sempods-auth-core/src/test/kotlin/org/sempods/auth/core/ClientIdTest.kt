package org.sempods.auth.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientIdTest {

  @Test
  fun `the identities this project issues and accepts are all inside the rule`() {
    assertTrue(ClientId.isValid("did:web:apps.sempods.org:chat"))
    assertTrue(ClientId.isValid("did:web:localhost%3A5173"))
    assertTrue(ClientId.isValid("dyn:AbC-123_x"))
    assertTrue(ClientId.isValid("notes-app"))
    // `*VSCHAR` is %x20-7E, and %x20 is the space: RFC 6749 permits one, so this check does not
    // invent a reason to refuse it.
    assertTrue(ClientId.isValid("a client"))
  }

  @Test
  fun `a client_id cannot carry a line break, whichever kind`() {
    // The consequence this buys, and the reason the check is here rather than at the log lines:
    // a `client_id` names the subject of every authorize, token, consent and audit line, and
    // `did:web:` identities are presented rather than issued. See `docs/logging.md` §"Three rules".
    assertFalse(ClientId.isValid("did:web:app.test\nERROR forged"))
    assertFalse(ClientId.isValid("did:web:app.test\r\nERROR forged"))
    assertFalse(ClientId.isValid("dyn:abc\u0000"))
    assertFalse(ClientId.isValid("dyn:abc\u0085"))
    // Neither a control character nor whitespace to `Character`, and a line break to plenty of log
    // viewers — the case a URI check would have let through.
    assertFalse(ClientId.isValid("did:web:app.test\u2028ERROR forged"))
    assertFalse(ClientId.isValid("did:web:app.test\u2029ERROR forged"))
  }

  @Test
  fun `an empty client_id is not one`() {
    assertFalse(ClientId.isValid(""))
  }

  @Test
  fun `non-ASCII is outside the rule, which is what the DID method already says`() {
    // A `did:web:` names its origin percent-encoded, so a raw non-ASCII host is not a DID under
    // any reading of the method.
    assertFalse(ClientId.isValid("did:web:beispiel\u00e4.tld"))
    assertFalse(ClientId.isValid("did:web:app.test\u00a0x"))
  }
}
