package org.sempods.auth.core

import com.nimbusds.oauth2.sdk.util.URIUtils
import java.net.URI

/**
 * What may appear as one of RFC 7591's informational client URLs — `client_uri`, `logo_uri`,
 * `tos_uri`, `policy_uri`.
 *
 * These are not addresses the server sends anything to; they are addresses it stores, hands back at
 * registration, and puts in front of a person deciding whether to trust an app. `logo_uri` in
 * particular exists to be rendered. A registration is self-service, so every one of them is a
 * string a stranger chose, and `javascript:` is a valid URI.
 *
 * Not [RedirectUri]'s question, and deliberately a separate one. That check forbids a fragment and
 * the response's own query parameters because a redirect *target* carries a response; a logo behind
 * `…/logo.png?v=2#icon` is an ordinary URL and refusing it would be an invented rule.
 *
 * **Asked again wherever one of these values is read**, and not only where it arrives. A repeat
 * registration returns the stored row untouched — the dynamic client store discards submitted
 * metadata on a fingerprint hit — so a row that predates this rule would otherwise keep handing its
 * value back for the life of the client. Checking on read is what makes the answer the same for
 * every row, without a migration to get there.
 *
 * Two stages, in the order that keeps them honest:
 *
 * 1. **The SDK's scheme rule**, `URIUtils.ensureSchemeIsHTTPSorHTTP` — the same call its own
 *    `ClientMetadata` makes for `client_uri`, `tos_uri` and `policy_uri`. Applying it here applies
 *    it to `logo_uri` too, which that class measurably does **not**: in 11.38.2 it accepts
 *    `logo_uri: "javascript:alert(1)"` and `data:text/html,…` while refusing both for the other
 *    three. The one field whose whole purpose is to be rendered is the one it leaves open.
 * 2. **This project's own restriction on plain `http`**, which the SDK does not make: cleartext
 *    only where it cannot leave the machine. [RedirectUri.isLoopback] answers that, so what counts
 *    as loopback has one owner and one set of spellings. `URIUtils.isLocalHost` agrees with it on
 *    every case tried; the reason to keep ours is that rule's documented refusal to resolve a name,
 *    not a disagreement about the answer.
 */
object ClientMetadataUri {

  fun isValid(uri: String): Boolean {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
    if (!parsed.isAbsolute) return false
    runCatching { URIUtils.ensureSchemeIsHTTPSorHTTP(parsed) }.getOrElse { return false }
    val host = parsed.host?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return parsed.scheme?.lowercase() != "http" || RedirectUri.isLoopback(host)
  }
}
