package org.sempods.api.pod.system.auth

import org.sempods.auth.core.SigningKeyStore

/**
 * The pod server's signing keys, in `oauth.signingKeys`.
 *
 * The adapter, not the lifecycle — generating, choosing and publishing live in
 * [org.sempods.auth.core.SigningKeys], the same class the identity service and the hosted MCP
 * service use. What is here is the part that differs per service: which collection, and what a
 * row looks like in it.
 *
 * The stored JWK carries its **private** parameters, so a process can both sign with it and
 * publish the public half at `{pod}/_system/auth/jwks.json`. It is not encrypted at rest,
 * consistent with the rest of this deployment's secrets — the hosted MCP service's adapter
 * encrypts because that deployment holds other people's pod tokens beside its own key, which is a
 * property of that store rather than of this port.
 *
 * [loadAll] reads every key rather than only the unretired ones ([OAuthSigningKeyDao.findActive]),
 * because the JWKS has to keep publishing a retired key until the tokens it signed have expired.
 * Which of them *signs* is `SigningKeys`' choice, and it takes the newest.
 */
class PodSigningKeyStore(private val dao: OAuthSigningKeyDao) : SigningKeyStore {

  override fun loadAll(): List<String> = dao.findAll().map { it.jwk }

  override fun createInitial(jwk: String, kid: String, algorithm: String): Boolean =
    dao.createInitial(OAuthSigningKeyDbo(kid = kid, algorithm = algorithm, jwk = jwk))
}
