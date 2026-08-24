package org.sempods.mcp.persist.oauth

import org.sempods.auth.core.SigningKeyStore
import java.util.Date

/**
 * The hosted service's signing keys, in `oauth.signingKeys`.
 *
 * The adapter, not the lifecycle — generating, choosing and publishing live in
 * [org.sempods.auth.core.SigningKeys], the same class the pod server and the identity service use.
 * What is here is the part that differs per service: which collection, and what a row looks like.
 *
 * **Encryption at rest stays on this side of the port.** [SigningKeyDao] puts the private JWK
 * through [org.sempods.mcp.crypto.SecretCipher] because this deployment already holds other
 * people's pod tokens under the same envelope, and a signing key beside them in plaintext would be
 * the weakest row in the database. That is a property of this store, not of the port: the other
 * two services store their keys in the clear and are right to.
 */
class McpSigningKeyStore(private val dao: SigningKeyDao) : SigningKeyStore {

  override fun loadAll(): List<String> = dao.findAll().map { it.jwk }

  override fun createInitial(jwk: String, kid: String, algorithm: String): Boolean =
    dao.createInitial(SigningKey(kid = kid, algorithm = algorithm, jwk = jwk, createdAt = Date()))
}
