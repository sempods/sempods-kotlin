package org.sempods.pods.grants

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PodScopeValidatorTest {

  private val validator = PodScopeValidator()
  private val podBaseUrl = "https://sempods.org/my-pod/"

  @Test
  fun `should accept context read scope inside pod`() {
    val result = validator.validate(
      scope = "https://sempods.org/my-pod/_system/contexts/apps/todo/tasks#read",
      podBaseUrl = podBaseUrl,
    )

    val context = assertIs<ScopeValidationResult.Context>(result)
    assertEquals(ScopePermission.read, context.permission)
  }

  @Test
  fun `the context namespace is not a context, nor is anything above it`() {
    // `covers()` reads a scope's context URI as a subtree root, so every path-ancestor of the
    // namespace is a pod-wide wildcard. `<pod>#manage` was already refused; these were not, and
    // `docs/auth/service-clients.md` promised a service client is confined to a subtree.
    for (root in listOf(
      "https://sempods.org/my-pod/_system/contexts",
      "https://sempods.org/my-pod/_system/contexts/",
      "https://sempods.org/my-pod/_system",
      "https://sempods.org/my-pod",
      "https://sempods.org/my-pod/",
    )) {
      assertIs<ScopeValidationResult.Invalid>(
        validator.validate(scope = "$root#manage", podBaseUrl = podBaseUrl),
        "'$root#manage' would cover every context on the pod",
      )
    }
  }

  @Test
  fun `a sibling of the namespace is still a context, because only ancestors are wildcards`() {
    // The rule is "no ancestor of the context namespace", not "must be under it". A URI beside the
    // namespace covers nothing through `covers()`, so refusing it would narrow what a context may
    // be named — a different change, and one that would retire the older shapes
    // `ContextPathRules` records. Pinned so the rule is not quietly widened later.
    assertIs<ScopeValidationResult.Context>(
      validator.validate(scope = "https://sempods.org/my-pod/_system/media#read", podBaseUrl = podBaseUrl),
    )
    assertIs<ScopeValidationResult.Context>(
      validator.validate(scope = "https://sempods.org/my-pod/contexts/legacy#read", podBaseUrl = podBaseUrl),
    )
  }

  @Test
  fun `should accept oidc scope`() {
    val result = validator.validate(
      scope = "openid",
      podBaseUrl = podBaseUrl,
    )

    val oidc = assertIs<ScopeValidationResult.Oidc>(result)
    assertEquals("openid", oidc.scope)
  }

  @Test
  fun `should reject scope without permission separator`() {
    val result = validator.validate(
      scope = "https://sempods.org/my-pod/_system/contexts/apps/todo/tasks",
      podBaseUrl = podBaseUrl,
    )

    assertIs<ScopeValidationResult.Invalid>(result)
  }

  @Test
  fun `should reject context outside pod`() {
    val result = validator.validate(
      scope = "https://sempods.org/other-pod/_system/contexts/apps/todo/tasks#write",
      podBaseUrl = podBaseUrl,
    )

    assertIs<ScopeValidationResult.Invalid>(result)
  }

  @Test
  fun `should accept the installer scope as a feature scope`() {
    val result = validator.validate(scope = SERVICE_CLIENTS_INSTALL_SCOPE, podBaseUrl = podBaseUrl)

    val feature = assertIs<ScopeValidationResult.Feature>(result)
    assertEquals(SERVICE_CLIENTS_INSTALL_SCOPE, feature.scope)
  }

  @Test
  fun `a context whose path reads like the installer scope is still a context`() {
    // The literal has no `#`, so the grammar keeps the two apart on its own. Pinned because a
    // classification that reached for the substring instead would silently widen a context grant
    // into an installation authority.
    val result = validator.validate(
      scope = "https://sempods.org/my-pod/_system/contexts/$SERVICE_CLIENTS_INSTALL_SCOPE#read",
      podBaseUrl = podBaseUrl,
    )

    assertIs<ScopeValidationResult.Context>(result)
  }

  @Test
  fun `the privileged feature scopes are feature scopes`() {
    // The narrower set is read where a rule has to name it; a value in it that the validator did
    // not recognise would be dropped from every token before that rule ever ran.
    assertTrue(PodScopeValidator.featureScopes.containsAll(PodScopeValidator.privilegedFeatureScopes))
  }
}
