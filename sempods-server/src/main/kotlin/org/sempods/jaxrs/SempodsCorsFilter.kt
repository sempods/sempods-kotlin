package org.sempods.jaxrs

import com.google.inject.Inject
import org.sempods.commons.jaxrs.filters.CorsFilter
import org.sempods.SempodsConfig

/**
 * The generic [CorsFilter] with the pod server's allowlist.
 *
 * The list arrives from [SempodsConfig] rather than being derived from a set of registered
 * applications: there is one application here, and that derivation was the last thing tying the pod
 * server's CORS policy to an application framework's configuration model. Same origins, stated once
 * — see `SempodsConfig.deriveCorsOrigins`.
 */
class SempodsCorsFilter @Inject constructor(
  config: SempodsConfig,
) : CorsFilter(allowedOrigins = config.corsOrigins)
