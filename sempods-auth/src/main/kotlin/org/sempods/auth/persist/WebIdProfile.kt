package org.sempods.auth.persist

import java.util.Date

/**
 * Stored in `webIdProfiles` collection.
 * `_id` is the full primary WebID URI.
 *
 * Two namespaces:
 *   - EMAIL: https://id.sempods.org/e/<sha256(normalize(email))>  — created at grant-by-email or OIDC login with email
 *   - OIDC:  https://id.sempods.org/oidc/<sha256(normalize(iss+":"+sub))> — fallback when OIDC provider gives no email
 *
 * Common case: OIDC login with email → same URI as grant-by-email → no sameAs needed.
 * sameAs / linkedIdentities: used when linking across namespaces (e.g. OIDC-no-email user links email later).
 *
 * Only id.sempods.org creates and modifies profiles.
 */
data class WebIdProfile(
  val id: String,
  val namespace: WebIdNamespace,
  val displayName: String,
  val createdAt: Date,
  val linkedIdentities: List<String> = emptyList(),
)

enum class WebIdNamespace { EMAIL, OIDC }
