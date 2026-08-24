package org.sempods.mcp.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BearerChallengeTest {

  @Test
  fun `the challenge names realm, error, resource and metadata in that order`() {
    assertEquals(
      "Bearer realm=\"alice\", error=\"invalid_token\", " +
        "resource=\"https://sempods.org/alice\", " +
        "resource_metadata=\"https://sempods.org/alice/.well-known/oauth-protected-resource\"",
      BearerChallenge.forResource(
        realm = "alice",
        resource = "https://sempods.org/alice",
        resourceMetadataUrl = "https://sempods.org/alice/.well-known/oauth-protected-resource",
      ),
    )
  }

  @Test
  fun `the metadata url is independent of the resource url`() {
    // The hosted service's named profiles put the profile after the well-known segment, not before
    // it — so deriving one from the other would be wrong for exactly that case.
    assertEquals(
      "Bearer realm=\"sempods-mcp\", error=\"invalid_token\", " +
        "resource=\"https://mcp.sempods.org/work\", " +
        "resource_metadata=\"https://mcp.sempods.org/.well-known/oauth-protected-resource/work\"",
      BearerChallenge.forResource(
        realm = "sempods-mcp",
        resource = "https://mcp.sempods.org/work",
        resourceMetadataUrl = "https://mcp.sempods.org/.well-known/oauth-protected-resource/work",
      ),
    )
  }
}
