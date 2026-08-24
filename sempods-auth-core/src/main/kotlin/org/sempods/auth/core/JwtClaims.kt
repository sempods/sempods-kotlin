package org.sempods.auth.core

import com.nimbusds.jwt.JWTClaimsSet

/**
 * Reading a claim whose type the sender chose.
 *
 * `JWTClaimsSet.parse` validates the *registered* claims — `iss`, `sub`, `exp` and friends are
 * rejected there if they carry the wrong JSON type, so a claims set that exists at all has sound
 * ones. **Custom claims are not checked**, and Nimbus' typed getters throw `ParseException` on
 * them: `{"client_id": {}}` parses happily and blows up on the read.
 *
 * That matters because the read usually happens while deciding whether a token is acceptable at
 * all. An exception there does not become "no" — it becomes a 500 from a public endpoint, driven
 * by a value anybody can put in a cookie or an `Authorization` header, and no signature has been
 * checked yet at that point.
 *
 * So: a claim of the wrong type is the same answer as a claim that is absent. Both mean "this
 * token does not say that".
 */
fun JWTClaimsSet.stringClaimOrNull(name: String): String? =
  runCatching { getStringClaim(name) }.getOrNull()

/** As [stringClaimOrNull], for a claim that should carry a list of strings. */
fun JWTClaimsSet.stringListClaimOrNull(name: String): List<String>? =
  runCatching { getStringListClaim(name) }.getOrNull()
