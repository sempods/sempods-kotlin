package org.sempods.ai

class AiSemValidationException(
  val code: String,
  message: String,
  val status: Int = 422,
) : RuntimeException(message)
