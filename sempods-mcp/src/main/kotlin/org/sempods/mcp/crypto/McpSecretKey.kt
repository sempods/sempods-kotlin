package org.sempods.mcp.crypto

import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.SecretCipher
import org.sempods.commons.config.Env
import java.security.MessageDigest
import java.util.Base64

/**
 * Where this service's [SecretCipher] key comes from. It encrypts the pod OAuth tokens in the
 * vault, the private OAuth signing-key JWK and the PKCE verifiers of sign-ins in flight.
 */
object McpSecretKey {

  private val logger = KotlinLogging.logger {}

  const val ENV_VARIABLE = "MCP_SECRET_KEY"

  /**
   * Resolve the key from [ENV_VARIABLE] (base64, 32 bytes). On a **real** deployment the key is
   * mandatory — a missing key fails startup loudly. "Real" is `isSecure` (an https
   * [org.sempods.mcp.SempodsMcpConfig.mcpBaseUrl]) **or** `PRODUCTION=true`, so an instance behind
   * a TLS-terminating proxy (where `mcpBaseUrl` might read http) does not silently fall back to the
   * dev key. On a genuine local / self-host-dev deployment a deterministic dev key is derived so
   * local setups need no configuration; that key must never protect a public instance.
   */
  fun cipherFromEnv(isSecure: Boolean): SecretCipher = SecretCipher(resolveKey(isSecure))

  // TODO: the same defect the pod server's admin fallback had — an environment inferred rather
  //  than a choice made. `!isSecure && !isProduction` still takes the development path for a
  //  deployment served over plain HTTP with `PRODUCTION` unset, which is a first self-host or
  //  anything behind a TLS-terminating proxy, and this key encrypts stored refresh tokens.
  //  `SempodsModule` now takes an explicit `SEMPODS_DEV_ADMIN_FALLBACK`; this wants the same
  //  shape. Not done with it because the admin surface was the published-credential half and
  //  this is not: the key here is derived, not a constant anyone can read off the source.
  private fun resolveKey(isSecure: Boolean): ByteArray {
    val encoded = Env.get(ENV_VARIABLE)?.takeIf(String::isNotBlank)
    if (encoded != null) {
      val decoded = runCatching { Base64.getDecoder().decode(encoded) }
        .getOrElse { Base64.getUrlDecoder().decode(encoded) }
      require(decoded.size == SecretCipher.KEY_BYTES) {
        "$ENV_VARIABLE must decode to ${SecretCipher.KEY_BYTES} bytes, got ${decoded.size}"
      }
      return decoded
    }
    val isProduction = Env.get("PRODUCTION")?.toBooleanStrictOrNull() == true
    check(!isSecure && !isProduction) {
      "$ENV_VARIABLE must be set on a public/production deployment (base64, ${SecretCipher.KEY_BYTES} bytes)"
    }
    logger.warn {
      "$ENV_VARIABLE not set — using the deterministic development key. " +
        "Never run a public instance with this key."
    }
    // The seed is frozen (`docs/naming.md` §3): a local deployment's rows were written under it.
    return MessageDigest.getInstance("SHA-256")
      .digest("sempods-mcp-dev-secret-key".toByteArray(Charsets.UTF_8))
  }
}
