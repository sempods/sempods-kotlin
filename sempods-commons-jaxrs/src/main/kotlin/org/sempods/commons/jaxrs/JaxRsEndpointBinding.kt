package org.sempods.commons.jaxrs

internal data class JaxRsEndpointBinding(
  val httpPort: Int,
  // TODO: only endpoints (BaseEndpoint)?
  val endpoints: Collection<Class<out Any>>,
)
