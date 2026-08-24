package org.sempods.admin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * Unit coverage for the admin-authority implementation and the `SEMPODS_ADMIN_CLIENTS` parser.
 * The fail-closed behaviour end-to-end (503 on the wire) is covered by `AdminPodsEndpointHttpTest`.
 */
class StaticCredentialAdminAuthorizerTest {

  private val authorizer = StaticCredentialAdminAuthorizer(
    mapOf(
      "notes-app" to "sc_notes-app-secret",
      "ops" to "sc_ops-secret",
    ),
  )

  @Test
  fun `an unconfigured authorizer reports NotConfigured, never Denied and never Authorized`() {
    val unconfigured = StaticCredentialAdminAuthorizer(emptyMap())

    // Same verdict with and without a credential: the gap is the deployment's, not the caller's,
    // and consumers must turn it into 503 rather than 401.
    assertEquals(AdminAuthorization.NotConfigured, unconfigured.authorize(null))
    assertEquals(AdminAuthorization.NotConfigured, unconfigured.authorize("Bearer sc_anything"))
  }

  @Test
  fun `each configured secret authorizes as its own client`() {
    assertEquals(
      AdminAuthorization.Authorized("notes-app"),
      authorizer.authorize("Bearer sc_notes-app-secret"),
    )
    assertEquals(
      AdminAuthorization.Authorized("ops"),
      authorizer.authorize("Bearer sc_ops-secret"),
    )
  }

  @Test
  fun `surrounding whitespace in the header does not change the verdict`() {
    assertEquals(
      AdminAuthorization.Authorized("ops"),
      authorizer.authorize("  Bearer   sc_ops-secret  "),
    )
  }

  @Test
  fun `the scheme name is matched case-insensitively, the secret is not`() {
    // RFC 7235 §2.1: scheme names are case-insensitive, so a normalising gateway must not turn a
    // valid credential into a 401. The secret stays an exact byte comparison.
    assertEquals(AdminAuthorization.Authorized("ops"), authorizer.authorize("bearer sc_ops-secret"))
    assertEquals(AdminAuthorization.Authorized("ops"), authorizer.authorize("BEARER sc_ops-secret"))
    assertEquals(AdminAuthorization.Denied, authorizer.authorize("Bearer SC_OPS-SECRET"))
  }

  @Test
  fun `missing, malformed and wrong credentials are denied`() {
    assertEquals(AdminAuthorization.Denied, authorizer.authorize(null))
    assertEquals(AdminAuthorization.Denied, authorizer.authorize(""))
    assertEquals(AdminAuthorization.Denied, authorizer.authorize("Bearer"))
    assertEquals(AdminAuthorization.Denied, authorizer.authorize("Bearer "))
    // wrong scheme — a client id is not a credential
    assertEquals(AdminAuthorization.Denied, authorizer.authorize("Basic sc_ops-secret"))
    assertEquals(AdminAuthorization.Denied, authorizer.authorize("sc_ops-secret"))
    // wrong secret, and a prefix of a valid one
    assertEquals(AdminAuthorization.Denied, authorizer.authorize("Bearer sc_wrong"))
    assertEquals(AdminAuthorization.Denied, authorizer.authorize("Bearer sc_ops-secre"))
    assertEquals(AdminAuthorization.Denied, authorizer.authorize("Bearer sc_ops-secretX"))
  }

  @Test
  fun `parseClients reads a comma-separated clientId colon secret list`() {
    assertEquals(
      mapOf("notes-app" to "sc_a", "ops" to "sc_b"),
      StaticCredentialAdminAuthorizer.parseClients(" notes-app:sc_a , ops:sc_b "),
    )
  }

  @Test
  fun `parseClients keeps colons inside the secret`() {
    assertEquals(
      mapOf("notes-app" to "sc_a:b:c"),
      StaticCredentialAdminAuthorizer.parseClients("notes-app:sc_a:b:c"),
    )
  }

  @Test
  fun `parseClients yields an empty map for an absent or blank value`() {
    assertEquals(emptyMap(), StaticCredentialAdminAuthorizer.parseClients(null))
    assertEquals(emptyMap(), StaticCredentialAdminAuthorizer.parseClients("   "))
  }

  @Test
  fun `parseClients rejects malformed entries instead of silently dropping them`() {
    // A dropped credential would look exactly like a working configuration until the operator
    // it belonged to tried to use it, so this has to be loud at boot.
    assertThrows<IllegalStateException> { StaticCredentialAdminAuthorizer.parseClients("notes-app") }
    assertThrows<IllegalStateException> { StaticCredentialAdminAuthorizer.parseClients(":sc_a") }
    assertThrows<IllegalStateException> { StaticCredentialAdminAuthorizer.parseClients("notes-app:") }
    assertThrows<IllegalStateException> { StaticCredentialAdminAuthorizer.parseClients("ok:sc_a,broken") }
    assertThrows<IllegalStateException> { StaticCredentialAdminAuthorizer.parseClients("dup:sc_a,dup:sc_b") }
  }

  @Test
  fun `parseClients rejects two clients sharing one secret`() {
    // The dangerous typo: both entries work, requests are attributed to whichever comes first, and
    // rotating that client's secret does not revoke it — the old value still authorizes through
    // the other entry.
    assertThrows<IllegalStateException> {
      StaticCredentialAdminAuthorizer.parseClients("operator:sc_same,notes-app:sc_same")
    }
    // Distinct secrets are of course fine, including when one is a prefix of the other.
    assertEquals(
      mapOf("operator" to "sc_same", "notes-app" to "sc_same2"),
      StaticCredentialAdminAuthorizer.parseClients("operator:sc_same,notes-app:sc_same2"),
    )
  }
}
