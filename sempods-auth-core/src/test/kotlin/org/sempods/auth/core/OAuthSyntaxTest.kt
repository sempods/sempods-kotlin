package org.sempods.auth.core

import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.openid.connect.sdk.Prompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The scope grammar, with the emphasis on [OAuthSyntax.scopeClaimValues] — the accessor that reads a
 * *token*. The request-parameter half is exercised throughout the flow tests; what has no other
 * witness is that both claim spellings are read and that a malformed one cannot widen the answer.
 */
class OAuthSyntaxTest {

  @Test
  fun `a scope parameter splits on spaces, and on nothing else`() {
    // The grammar is the SDK's; these are the answers three services depend on, so an upgrade that
    // changes one of them fails here rather than quietly changing what a pod accepts.
    assertEquals(setOf("read", "write"), OAuthSyntax.parseScope("read write"))
    // Repeated spaces collapse: the grammar names one separator and a formatting slip is not
    // distinguishable from it. A tab is not a separator and not a legal character inside a scope
    // token either, so it stays where it was and makes the value it sits in an unknown scope.
    assertEquals(setOf("read", "write"), OAuthSyntax.parseScope("  read  write  "))
    assertEquals(setOf("read\twrite"), OAuthSyntax.parseScope("read\twrite"))
    assertEquals(emptySet(), OAuthSyntax.parseScope(null))
    assertEquals(emptySet(), OAuthSyntax.parseScope("   "))
  }

  @Test
  fun `a prompt parameter is case sensitive, as the specification says`() {
    assertEquals(setOf("none"), OAuthSyntax.parsePrompt("none"))
    // `NONE` is not `none`. Folding it would apply the strictest rule the parameter has to a
    // request that did not ask for it — and the value survives parsing, to be judged by whoever
    // reads it, because an unrecognised prompt is not something either specification refuses.
    assertEquals(setOf("NONE"), OAuthSyntax.parsePrompt("NONE"))
    assertEquals(setOf("login", "somethingNew"), OAuthSyntax.parsePrompt("login somethingNew"))
  }

  @Test
  fun `the prompt parser keeps what the OAuth SDK refuses, on purpose`() {
    // `prompt` is the half that cannot be delegated, and these are the two reasons.
    //
    // The SDK refuses a value it does not know. Refusing is a policy rather than conformance —
    // neither specification asks for it — and it would have answered `prompt=create` with an error
    // for as long as the SDK version in use predated the registration of that value.
    assertEquals(setOf("login", "somethingNew"), OAuthSyntax.parsePrompt("login somethingNew"))
    assertFailsWith<ParseException> { Prompt.parse("login somethingNew") }

    // And it refuses the contradiction at parse time, where this keeps the set and lets
    // [OAuthSyntax.isContradictoryPrompt] name it. The verdict is the same; only one of the two can
    // also tell a contradiction from a typo, which is what the endpoint answers differently.
    assertTrue(OAuthSyntax.isContradictoryPrompt(OAuthSyntax.parsePrompt("none consent")))
    assertFailsWith<ParseException> { Prompt.parse("none consent") }
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
