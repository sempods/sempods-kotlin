package org.sempods.client

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType

/** `POST {pod}/_system/auth/register`, where both registration profiles are served. */
internal const val REGISTER_ROUTE = "_system/auth/register"

@get:JvmSynthetic
internal val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

/** An OAuth scope parameter's value as its scopes, in order and without repeats (RFC 6749 §3.3). */
@JvmSynthetic
internal fun scopesOf(text: String?): Set<String> = text.orEmpty().split(' ').filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

/** [scopes] as one scope parameter's value. */
@JvmSynthetic
internal fun scopeText(scopes: Collection<String>): String = scopes.joinToString(" ")
