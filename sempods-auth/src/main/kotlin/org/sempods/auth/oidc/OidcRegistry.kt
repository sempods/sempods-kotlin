package org.sempods.auth.oidc

import com.google.inject.Inject

/**
 * Holds all registered OIDC providers, keyed by [OidcProviderClient.name].
 *
 * Providers are contributed via Guice's [com.google.inject.multibindings.Multibinder].
 * Each module (or future loader) adds bindings independently — the registry just collects them.
 *
 * Usage in routing: providers[name] — null if provider not registered.
 */
class OidcRegistry @Inject constructor(
  providers: Set<@JvmSuppressWildcards OidcProviderClient>,
) {
  val providers: Map<String, OidcProviderClient> = providers.associateBy { it.name }
}
