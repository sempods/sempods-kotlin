package org.sempods.mcp.crypto

/** A deterministic 32-byte-key cipher for tests that construct DAOs directly. */
fun testSecretCipher(): SecretCipher = SecretCipher(ByteArray(32) { (it + 1).toByte() })
