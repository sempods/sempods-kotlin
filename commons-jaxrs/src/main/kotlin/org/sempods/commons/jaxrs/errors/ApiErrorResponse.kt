package org.sempods.commons.jaxrs.errors

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

data class ApiErrorResponse(

  @field:[JsonProperty JsonInclude(JsonInclude.Include.NON_EMPTY)]
  val errors: List<ApiErrorDto> = emptyList(),
)

data class ApiErrorDto(

  @field:JsonProperty
  val code: String,

  @field:[JsonProperty JsonInclude(JsonInclude.Include.NON_EMPTY)]
  val message: String? = null,

  @field:[JsonProperty JsonInclude(JsonInclude.Include.NON_EMPTY)]
  val parameters: Map<String, Any> = emptyMap(),
)
