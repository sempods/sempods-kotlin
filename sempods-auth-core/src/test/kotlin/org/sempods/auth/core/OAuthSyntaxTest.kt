package org.sempods.auth.core

import com.nimbusds.jwt.JWTClaimsSet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scope grammar, with the emphasis on [OAuthSyntax.scopeClaimValues] — the accessor that reads a
 * *token*. The request-parameter half is exercised throughout the flow tests; what has no other
 * witness is that both claim spellings are read and that a malformed one cannot widen the answer.
 */
class OAuthSyntaxTest {

  @Test
  fun `a scope parameter splits on spaces and tabs, and drops the gaps`() {
    assertEquals(setOf("read", "write"), OAuthSyntax.parseScope("read write"))
    assertEquals(setOf("read", "write"), OAuthSyntax.parseScope("  read \t write  "))
    assertEquals(emptySet(), OAuthSyntax.parseScope(null))
    assertEquals(emptySet(), OAuthSyntax.parseScope("   "))
  }

  @Test
  fun `a token's scopes are the union of both spellings`() {
    // Neither claim is the canonical one — the pod mints `scope`, other paths in this repository
    // have minted `scp`, and a reader that knew only one would drop authority it was handed.
    assertEquals(setOf("read"), scopesOf(claims { claim("scope", "read") }))
    assertEquals(setOf("read"), scopesOf(claims { claim("scp", listOf("read")) }))
    assertEquals(
      setOf("manage", "read", "write"),
      scopesOf(claims { claim("scp", listOf("manage", "read")); claim("scope", "read write") }),
    )
  }

  @Test
  fun `the union keeps the order it first saw a value in`() {
    // A set, but an ordered one: the values reach a log line and a token, and a scope list that
    // reshuffles between two reads of the same token is a diff nobody can explain.
    assertEquals(
      listOf("b", "a", "c"),
      scopesOf(claims { claim("scp", listOf("b", "a")); claim("scope", "a c") }).toList(),
    )
  }

  @Test
  fun `a claim of the wrong type says nothing, rather than throwing or inventing values`() {
    // Nimbus' typed getters throw on these, and the read happens while deciding whether a token is
    // acceptable at all — an exception there is a 500 from a public endpoint, not a "no".
    assertEquals(emptySet(), scopesOf(claims { claim("scope", listOf("read")) }))
    assertEquals(emptySet(), scopesOf(claims { claim("scp", "read write") }))
    // A non-string entry voids the whole array rather than being stringified into a scope. Fewer
    // scopes is the safe direction to be wrong in.
    assertEquals(emptySet(), scopesOf(claims { claim("scp", listOf("read", 1)) }))
  }

  @Test
  fun `a token that claims no scopes has none`() {
    assertEquals(emptySet(), scopesOf(claims { subject("someone") }))
    assertEquals(emptySet(), scopesOf(claims { claim("scope", "") }))
  }

  private fun scopesOf(claims: JWTClaimsSet): Set<String> = OAuthSyntax.scopeClaimValues(claims)

  private fun claims(build: JWTClaimsSet.Builder.() -> Unit): JWTClaimsSet =
    JWTClaimsSet.Builder().apply(build).build()
}
