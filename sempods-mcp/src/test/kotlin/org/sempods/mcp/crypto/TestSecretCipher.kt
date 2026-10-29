package org.sempods.mcp.crypto

import org.sempods.auth.core.SecretCipher

/** A deterministic 32-byte-key cipher for tests that construct DAOs directly. */
fun testSecretCipher(): SecretCipher = SecretCipher(ByteArray(32) { (it + 1).toByte() })
