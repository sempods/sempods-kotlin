package org.sempods.auth.api.provider

import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationErrorResponse
import com.nimbusds.oauth2.sdk.AuthorizationSuccessResponse
import com.nimbusds.oauth2.sdk.ErrorObject
import com.nimbusds.oauth2.sdk.ResponseMode
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.id.State
import org.sempods.auth.core.RedirectUri

/**
 * The two answers this provider sends to a client's address: the code from the provider callback,
 * and an error from `/authorize` or that callback. Built by the SDK, so the parameter names, the
 * encoding and the presence of `state` follow the specification rather than this file.
 *
 * Both name [issuer] as `iss` (RFC 9207), as the discovery document announces, and both start from
 * [RedirectUri.withoutIssuer]. The address must already have been checked against the client, and a
 * `state` is passed only when the client sent a non-blank one.
 */
internal object AuthorizationResponses {

  fun code(redirectUri: String, code: String, state: String?, issuer: String): String =
    AuthorizationSuccessResponse(
      RedirectUri.withoutIssuer(redirectUri),
      AuthorizationCode(code),
      null,
      state?.let { State(it) },
      Issuer(issuer),
      ResponseMode.QUERY,
    ).toURI().toString()

  fun error(redirectUri: String, error: ErrorObject, state: String?, issuer: String): String =
    AuthorizationErrorResponse(
      RedirectUri.withoutIssuer(redirectUri),
      error,
      state?.let { State(it) },
      Issuer(issuer),
      ResponseMode.QUERY,
    ).toURI().toString()
}
