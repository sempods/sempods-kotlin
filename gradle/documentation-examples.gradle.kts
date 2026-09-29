// Snippets name their compiled source region; copying code without its test must fail CI too.
// The marker format is docs/agents/documentation-strategy.md#checking-examples.
//
// Every `.md` file is scanned, outside build output and `.claude/worktrees`, because module READMEs
// carry as many examples as root `docs/`. KDoc is not scanned; its examples rely on review.
// Common indentation is ignored, since a region sits indented inside a test and the fence does not.
// A region must exist exactly once, or the snippet could match a copy the test no longer runs.
// Only a page marked `<!-- doc-examples: checked -->` fails on an unmarked fence: older pages adopt
// the marker when their examples are revised. A match proves the text only; the ordinary `test`
// task runs the region. `testDocExampleChecker` runs the scanner on fixtures first, so a scanner
// that accepts everything fails the build.
data class ExampleScan(val failures: List<String>, val checked: Int, val illustrative: Int)

fun scanExamples(repositoryRoot: File, documents: Sequence<File>): ExampleScan {
  val marker = Regex("""<!-- doc-example: (.+) -->""")
  val fence = Regex("""\s*(`{3,}|~{3,})([^\s]*)\s*""")
  val failures = mutableListOf<String>()
  var checked = 0
  var illustrative = 0
  documents.forEach { document ->
    val lines = document.readLines()
    val strict = lines.any { it == "<!-- doc-examples: checked -->" }
    var i = 0
    while (i < lines.size) {
      val annotation = marker.matchEntire(lines[i].trim())?.groupValues?.get(1)
      val opening = fence.matchEntire(lines[i])
      if (annotation != null && (i + 1 >= lines.size || fence.matchEntire(lines[i + 1]) == null)) {
        failures += "${document.relativeTo(repositoryRoot)}:${i + 1}: example marker must precede a fenced block"
      }
      if (opening == null) { i++; continue }
      val location = "${document.relativeTo(repositoryRoot)}:${i + 1}"
      val reference = if (i > 0) marker.matchEntire(lines[i - 1].trim())?.groupValues?.get(1) else null
      val delimiter = opening.groupValues[1]
      val language = opening.groupValues[2].lowercase()
      val start = ++i
      while (i < lines.size && lines[i].trim() != delimiter) i++
      if (i == lines.size && (strict || reference != null)) failures += "$location: unclosed example fence"
      if (reference == null) {
        if (strict && language in setOf("java", "kotlin", "bash", "sh", "json", "js", "javascript", "http", "turtle")) {
          failures += "$location: missing doc-example source or illustrative explanation"
        }
      } else if (reference.startsWith("illustrative; ")) {
        if (reference.removePrefix("illustrative; ").isBlank()) failures += "$location: missing review explanation"
        illustrative++
      } else {
        val path = reference.substringBefore('#')
        val region = reference.substringAfter('#', "")
        val source = File(repositoryRoot, path).normalize()
        if (region.isBlank() || !source.toPath().startsWith(repositoryRoot.toPath()) || !source.isFile) {
          failures += "$location: invalid source $reference"
        } else {
          val sourceLines = source.readLines()
          val starts = sourceLines.indices.filter { sourceLines[it].trim() == "// doc-example:start $region" }
          val ends = sourceLines.indices.filter { sourceLines[it].trim() == "// doc-example:end $region" }
          if (starts.size != 1 || ends.size != 1 || starts.single() >= ends.single()) {
            failures += "$location: expected one source region $reference"
          } else {
            val expected = sourceLines.subList(starts.single() + 1, ends.single()).joinToString("\n").trimIndent()
            val actual = lines.subList(start, i).joinToString("\n").trimIndent()
            if (actual != expected) failures += "$location: snippet differs from $reference"
            checked++
          }
        }
      }
      i++
    }
  }
  return ExampleScan(failures, checked, illustrative)
}

val testDocExampleChecker = tasks.register("testDocExampleChecker") {
  group = "verification"
  description = "Checks snippet discovery, matching and failure diagnostics with isolated fixtures."
  doLast {
    val fixtureRoot = temporaryDir
    val source = File(fixtureRoot, "Example.kt")
    source.writeText("// doc-example:start sample\n  val value = 1\n// doc-example:end sample\n")
    val document = File(fixtureRoot, "guide.md")
    fun scan(text: String): ExampleScan {
      document.writeText(text)
      return scanExamples(fixtureRoot, sequenceOf(document))
    }
    val valid = "<!-- doc-example: Example.kt#sample -->\n```kotlin\nval value = 1\n```\n"
    val matched = scan(valid)
    check(matched.failures.isEmpty() && matched.checked == 1)
    check(scan(valid.replace("val value", "    val value")).failures.isEmpty())
    val illustrative = scan("<!-- doc-example: illustrative; reviewed fixture -->\n```http\nGET /example\n```\n")
    check(illustrative.failures.isEmpty() && illustrative.illustrative == 1)
    val cases = mapOf(
      "snippet differs" to valid.replace("value = 1", "value = 2"),
      "expected one source region" to valid.replace("#sample", "#missing"),
      "invalid source" to valid.replace("Example.kt", "Missing.kt"),
      "unclosed example fence" to valid.removeSuffix("```\n"),
      "example marker must precede" to valid.replace("-->\n", "-->\n\n"),
      "missing doc-example source" to "<!-- doc-examples: checked -->\n```kotlin\nval value = 1\n```\n",
    )
    cases.forEach { (message, text) ->
      check(scan(text).failures.any { message in it }) { "The checker accepted the $message fixture" }
    }
    source.appendText("// doc-example:start sample\nval duplicate = 2\n// doc-example:end sample\n")
    check(scan(valid).failures.any { "expected one source region" in it })
    logger.lifecycle("Documentation example checker: positive fixtures and seven failure cases passed.")
  }
}

val checkDocExamples = tasks.register("checkDocExamples") {
  group = "verification"
  description = "Discovers documentation snippets and checks them against their test source regions."
  dependsOn(testDocExampleChecker)
  val repositoryRoot = rootDir
  doLast {
    val skipped = setOf("build", ".git", ".gradle", ".idea", "node_modules")
    val documents = repositoryRoot.walkTopDown().onEnter {
      it.name !in skipped && it != File(repositoryRoot, ".claude/worktrees")
    }.filter { it.isFile && it.extension == "md" }
    val result = scanExamples(repositoryRoot, documents)
    check(result.failures.isEmpty()) { result.failures.joinToString("\n") }
    logger.lifecycle("Documentation examples: ${result.checked} source comparisons, ${result.illustrative} explicitly illustrative blocks.")
  }
}

tasks.named("checkDocLinks") { dependsOn(checkDocExamples) }
