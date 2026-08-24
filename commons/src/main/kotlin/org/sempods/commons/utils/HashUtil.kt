package org.sempods.commons.utils

import java.security.MessageDigest

object HashUtil {

  /**
   * SHA-256 hex digest of [input], over its **UTF-8** bytes.
   *
   * The charset is part of the contract, not an implementation detail: it decides the digest, and
   * a stored digest outlives the code that produced it. UTF-8 is the one choice that maps every
   * string to distinct bytes — `US_ASCII` replaces anything outside 7-bit with `?`, so `"stéte"`
   * and `"st?te"` would hash alike.
   *
   * That mattered when `sempods-mcp`'s own `sha256Hex` (US_ASCII) folded in here: everything it
   * hashes is a Base64-URL secret from `SecureRandom` or a UUID, i.e. ASCII, where the two
   * charsets agree byte for byte — so every hash already stored still matches. The change only
   * separates values the service never issues, and could therefore never look up.
   */
  fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }
}
