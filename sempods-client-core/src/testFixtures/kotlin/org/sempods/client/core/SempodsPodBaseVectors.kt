package org.sempods.client.core

/**
 * What a pod base URL may and may not be, as data.
 *
 * Shared as a fixture rather than written twice because two places enforce the same two
 * requirements: this client, and the pod server's own configuration validation
 * ([#56](https://github.com/sempods/sempods-kotlin/issues/56)) over `SEMPODS_PUBLIC_BASE_URL`. Two
 * tables would agree on the day the second was written and not much longer — the spellings of a
 * dot segment alone (`%2e%2e`, `.%2e`, a backslash separator) are not a list anyone closes twice.
 *
 * A server is stricter about one thing this is silent on: whether the address is somewhere the
 * deployment may reach. That is
 * [org.sempods.client.core.net.SempodsUrlPolicy]'s question, not the specification's.
 */
object SempodsPodBaseVectors {

  /** Bases every conforming implementation accepts. */
  val accepted: List<String> = listOf(
    "https://pods.example/alice",
    "https://pods.example/pods/alice",
    "https://pods.example:8443/alice",
    "http://localhost:8090/alice",
    "http://127.0.0.1:8090/alice",
    "http://[::1]:8090/alice",
    "https://pods.example/alice-archive",
    "https://pods.example",
  )

  /** Accepted, and stored as the canonical spelling on the right — SPS-CORE-019's trailing slash. */
  val canonicalized: List<Pair<String, String>> = listOf(
    "https://pods.example/alice/" to "https://pods.example/alice",
    "https://pods.example/pods/alice/" to "https://pods.example/pods/alice",
    "https://pods.example/" to "https://pods.example",
  )

  /** Refused, each paired with the requirement it breaks. */
  val refused: List<Pair<String, String>> = listOf(
    "/alice" to "SPS-CORE-019 absolute",
    "pods.example/alice" to "SPS-CORE-019 absolute",
    "https://pods.example/alice?tenant=a" to "SPS-CORE-019 query",
    "https://pods.example/alice#frag" to "SPS-CORE-019 fragment",
    "https://user:pw@pods.example/alice" to "SPS-CORE-019 userinfo",
    "http://pods.example/alice" to "SPS-CORE-019 http off loopback",
    "ftp://pods.example/alice" to "SPS-CORE-019 scheme",
    "https://pods.example/a/../b" to "SPS-CORE-020 dot segment",
    "https://pods.example/a/./b" to "SPS-CORE-020 dot segment",
    "https://pods.example/%2e%2e/b" to "SPS-CORE-020 percent-encoded octet",
    "https://pods.example/%61lice" to "SPS-CORE-020 percent-encoded octet",
    "https://pods.example/a\\b" to "SPS-CORE-020 backslash",
  )
}
