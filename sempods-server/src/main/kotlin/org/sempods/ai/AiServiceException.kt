package org.sempods.ai

class AiServiceException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
