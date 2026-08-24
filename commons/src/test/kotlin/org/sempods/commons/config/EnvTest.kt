package org.sempods.commons.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvTest {

  @TempDir
  lateinit var tempDir: File

  private val touchedProperties = mutableSetOf<String>()

  @AfterEach
  fun clearProperties() {
    touchedProperties.forEach(System::clearProperty)
    touchedProperties.clear()
  }

  private fun property(name: String, value: String) {
    touchedProperties.add(name)
    System.setProperty(name, value)
  }

  private fun envFile(content: String): File =
    File(tempDir, "local.env").apply { writeText(content) }

  @Test
  fun `a property is used when the environment has nothing`() {
    property("ENVTEST_TEST_ONLY", "from-property")
    assertEquals("from-property", Env.get("ENVTEST_TEST_ONLY"))
  }

  @Test
  fun `the environment wins over a property`() {
    // Whatever this machine happens to export — the point is only that both sources are populated.
    val (name, value) = System.getenv().entries.first { it.value.isNotBlank() }
    property(name, "$value-shadowed-by-a-property")

    assertEquals(value, Env.get(name), "an exported variable must not be shadowed by a property")
  }

  @Test
  fun `an unset name is null, and blank counts as unset`() {
    assertNull(Env.get("ENVTEST_TEST_MISSING"))
    property("ENVTEST_TEST_BLANK", "   ")
    assertNull(Env.get("ENVTEST_TEST_BLANK"), "a blank value is not a configured value")
    assertEquals("fallback", Env.get("ENVTEST_TEST_BLANK", "fallback"))
  }

  @Test
  fun `loadFile applies plain assignments`() {
    touchedProperties.add("ENVTEST_FILE_A")
    touchedProperties.add("ENVTEST_FILE_B")

    val applied = Env.loadFile(envFile("ENVTEST_FILE_A=one\nENVTEST_FILE_B=two\n"))

    assertEquals(2, applied)
    assertEquals("one", Env.get("ENVTEST_FILE_A"))
    assertEquals("two", Env.get("ENVTEST_FILE_B"))
  }

  @Test
  fun `loadFile skips comments, blanks and malformed lines`() {
    touchedProperties.add("ENVTEST_FILE_KEPT")

    val applied = Env.loadFile(
      envFile(
        """
        # a comment
          # an indented comment

        no-equals-sign
        =value-without-a-name
        ENVTEST_FILE_EMPTY=
        ENVTEST_FILE_KEPT=kept
        """.trimIndent(),
      ),
    )

    assertEquals(1, applied)
    assertEquals("kept", Env.get("ENVTEST_FILE_KEPT"))
    assertNull(Env.get("ENVTEST_FILE_EMPTY"))
  }

  @Test
  fun `loadFile strips quotes and keeps equals signs inside a value`() {
    touchedProperties.add("ENVTEST_FILE_QUOTED")
    touchedProperties.add("ENVTEST_FILE_BASE64")

    Env.loadFile(
      envFile(
        "ENVTEST_FILE_QUOTED=\"quoted value\"\n" +
            "ENVTEST_FILE_BASE64=c2VjcmV0Cg==\n",
      ),
    )

    assertEquals("quoted value", Env.get("ENVTEST_FILE_QUOTED"))
    assertEquals("c2VjcmV0Cg==", Env.get("ENVTEST_FILE_BASE64"), "a value may contain '='")
  }

  @Test
  fun `loadFile does not overwrite what is already set`() {
    // -D on the command line beats the file; the file is the weakest source of the three.
    property("ENVTEST_FILE_EXISTING", "from-property")

    val applied = Env.loadFile(envFile("ENVTEST_FILE_EXISTING=from-file\n"))

    assertEquals(0, applied)
    assertEquals("from-property", Env.get("ENVTEST_FILE_EXISTING"))
  }

  @Test
  fun `a blank existing value does not block the file`() {
    // "Blank means unset" has to hold in both places. If the guard only tested for presence, an
    // exported `FOO=` would suppress the file entry *and* still resolve to nothing — the name
    // would end up less configured than if the shell had said nothing at all.
    property("ENVTEST_FILE_BLANK", "   ")

    val applied = Env.loadFile(envFile("ENVTEST_FILE_BLANK=from-file\n"))

    assertEquals(1, applied)
    assertEquals("from-file", Env.get("ENVTEST_FILE_BLANK"))
  }

  @Test
  fun `loadFile leaves an exported variable alone`() {
    val (name, value) = System.getenv().entries.first { it.value.isNotBlank() }

    val applied = Env.loadFile(envFile("$name=from-file\n"))

    assertEquals(0, applied)
    assertEquals(value, Env.get(name))
  }

  @Test
  fun `a missing file is not an error`() {
    assertEquals(0, Env.loadFile(File(tempDir, "does-not-exist.env")))
  }

  // ── group ────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `a group is either whole or absent`() {
    val complete = mapOf("A" to "1", "B" to "2")

    assertEquals(complete, Env.group("test group", "A", "B", lookup = complete::get))
    assertNull(Env.group("test group", "A", "B", lookup = emptyMap<String, String>()::get))
  }

  @Test
  fun `a partly configured group counts as absent, not as half`() {
    // Enabling a feature on half its settings fails later, at the first use, far from the cause.
    assertNull(Env.group("test group", "A", "B", lookup = mapOf("A" to "1")::get))
  }

  // ── int ──────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `a number is read, and its absence yields the default`() {
    property("ENVTEST_PORT", "9090")

    assertEquals(9090, Env.int("ENVTEST_PORT", default = 8080))
    assertEquals(8080, Env.int("ENVTEST_PORT_MISSING", default = 8080))
  }

  @Test
  fun `a set but unparseable number stops the boot`() {
    // Falling back would be worse than crashing: compose and the reverse proxy are configured for
    // the value someone meant to set, so `809O` would leave the process listening on 8080 with
    // nothing in the log to say why.
    property("ENVTEST_PORT_TYPO", "809O")

    assertFailsWith<IllegalStateException> { Env.int("ENVTEST_PORT_TYPO", default = 8080) }
  }

  // ── baseUrl ──────────────────────────────────────────────────────────────────────────────

  @Test
  fun `a base URL always ends in a slash`() {
    // sempods appends pod names to this and persists the result, so a missing slash would write
    // `https://example.orgalice/…` into resource subjects and named graphs.
    property("ENVTEST_URL", "https://example.org")

    assertEquals("https://example.org/", Env.baseUrl("ENVTEST_URL", default = "https://fallback.invalid/"))
  }

  @Test
  fun `the default is normalised too`() {
    assertEquals("https://fallback.invalid/", Env.baseUrl("ENVTEST_URL_MISSING", default = "https://fallback.invalid"))
  }

  @Test
  fun `surrounding whitespace and extra slashes are cleaned up`() {
    property("ENVTEST_URL_MESSY", "  https://example.org//  ")

    assertEquals("https://example.org/", Env.baseUrl("ENVTEST_URL_MESSY", default = "https://fallback.invalid/"))
  }

  @Test
  fun `a malformed base URL stops the boot`() {
    property("ENVTEST_URL_RELATIVE", "example.org")
    assertFailsWith<IllegalStateException> { Env.baseUrl("ENVTEST_URL_RELATIVE", default = "https://f.invalid/") }

    property("ENVTEST_URL_SCHEME", "ftp://example.org")
    assertFailsWith<IllegalStateException> { Env.baseUrl("ENVTEST_URL_SCHEME", default = "https://f.invalid/") }

    property("ENVTEST_URL_NOHOST", "https:///path")
    assertFailsWith<IllegalStateException> { Env.baseUrl("ENVTEST_URL_NOHOST", default = "https://f.invalid/") }
  }

  @Test
  fun `a base URL may carry a path but not a query, fragment or credentials`() {
    // A prefix that other paths are appended to. `https://example.org?x=1` would normalise to
    // `…?x=1/` and then mint pod IRIs like `https://example.org?x=1/alice/…`.
    property("ENVTEST_URL_PATH", "https://example.org/pods")
    assertEquals("https://example.org/pods/", Env.baseUrl("ENVTEST_URL_PATH", default = "https://f.invalid/"))

    property("ENVTEST_URL_QUERY", "https://example.org?x=1")
    assertFailsWith<IllegalStateException> { Env.baseUrl("ENVTEST_URL_QUERY", default = "https://f.invalid/") }

    property("ENVTEST_URL_FRAGMENT", "https://example.org#top")
    assertFailsWith<IllegalStateException> { Env.baseUrl("ENVTEST_URL_FRAGMENT", default = "https://f.invalid/") }

    // Credentials in a base URL would end up inside persisted IRIs.
    property("ENVTEST_URL_USERINFO", "https://user:secret@example.org")
    assertFailsWith<IllegalStateException> { Env.baseUrl("ENVTEST_URL_USERINFO", default = "https://f.invalid/") }
  }

  // ── list ─────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `a list is split, trimmed and cleaned of blanks`() {
    property("ENVTEST_LIST", " a , b ,, c ")

    assertEquals(listOf("a", "b", "c"), Env.list("ENVTEST_LIST"))
  }

  @Test
  fun `a list accepts several separators and is empty when unset`() {
    property("ENVTEST_LIST_MIXED", "a, b c")

    assertEquals(listOf("a", "b", "c"), Env.list("ENVTEST_LIST_MIXED", ',', ' '))
    assertEquals(emptyList(), Env.list("ENVTEST_LIST_MISSING"))
  }

  @Test
  fun `an absent list falls back, a configured-empty one stays empty`() {
    // The distinction carries a real decision: `SEMPODS_AUTH_ISSUERS=` means "trust no issuer".
    // Collapsing it into "unset" would hand back the very default the operator was disabling.
    assertEquals(listOf("fallback"), Env.list("ENVTEST_LIST_ABSENT", default = "fallback"))

    property("ENVTEST_LIST_EMPTY", "")
    assertEquals(emptyList(), Env.list("ENVTEST_LIST_EMPTY", default = "fallback"))

    property("ENVTEST_LIST_SEPARATORS_ONLY", " , ")
    assertEquals(emptyList(), Env.list("ENVTEST_LIST_SEPARATORS_ONLY", default = "fallback"))
  }

  @Test
  fun `getRaw keeps a blank value that get discards`() {
    property("ENVTEST_RAW_BLANK", "")

    assertNull(Env.get("ENVTEST_RAW_BLANK"), "blank is unset when asking whether it is configured")
    assertEquals("", Env.getRaw("ENVTEST_RAW_BLANK"), "…but the value itself is still readable")
    assertNull(Env.getRaw("ENVTEST_RAW_ABSENT"))
  }

  // ── pairs ────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `pairs keeps separators inside the value`() {
    // Secrets contain colons; only the first one splits.
    assertEquals(
      mapOf("notes-app" to "sc_a:b:c"),
      Env.pairsOf("notes-app:sc_a:b:c", label = "TEST"),
    )
  }

  @Test
  fun `a malformed pair stops the boot instead of vanishing`() {
    // A typo that silently drops one operator's credential is worse than refusing to start.
    assertFailsWith<IllegalStateException> { Env.pairsOf("no-separator", label = "TEST") }
    assertFailsWith<IllegalStateException> { Env.pairsOf(":value", label = "TEST") }
    assertFailsWith<IllegalStateException> { Env.pairsOf("key:", label = "TEST") }
    assertFailsWith<IllegalStateException> { Env.pairsOf("ok:v,broken", label = "TEST") }
    assertFailsWith<IllegalStateException> { Env.pairsOf("dup:a,dup:b", label = "TEST") }

    assertEquals(emptyMap(), Env.pairsOf(null, label = "TEST"))
    assertEquals(emptyMap(), Env.pairsOf("  ", label = "TEST"))
  }

  // ── requiredInProduction ─────────────────────────────────────────────────────────────────

  @Test
  fun `a configured value is used regardless of environment`() {
    property("ENVTEST_REQUIRED", "configured")

    assertEquals(
      "configured",
      Env.requiredInProduction("ENVTEST_REQUIRED", developmentFallback = "fallback", reason = "r"),
    )
  }

  @Test
  fun `an unset value falls back in development`() {
    // The suite runs in a development environment, which is the branch under test here. The
    // production branch is a `check` on the same flag and cannot be exercised in-process.
    assertTrue(Env.isDevelopment, "precondition: tests run outside a deployment")

    assertEquals(
      "fallback",
      Env.requiredInProduction("ENVTEST_REQUIRED_MISSING", developmentFallback = "fallback", reason = "r"),
    )
    assertNull(Env.requiredInProductionOrNull("ENVTEST_REQUIRED_MISSING", reason = "r"))
  }
}
