package org.sempods.client.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Percent-encoding for the parts of a URL this client assembles. */
object SempodsUrlEncoding {

  /**
   * One path segment: [URLEncoder] plus the `+`→`%20` correction it does not make. `URLEncoder`
   * implements form encoding, where a space is `+`; in a path it is not.
   */
  @JvmStatic
  fun pathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  /** One `application/x-www-form-urlencoded` name or value, where `+` for a space is correct. */
  @JvmStatic
  fun formValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
