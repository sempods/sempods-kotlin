package org.sempods.commons.utils

/**
 * Substring extraction by delimiter rather than by index.
 *
 * It started life among HTML crawler helpers and has nothing to do with crawling: the pod server
 * uses it to read a path segment out of a URI it minted itself. The rest of that helper — HTML
 * unescaping, sanitising, whitespace folding — stays where it is used.
 */
object StringUtil {

  /**
   * The text between [startPattern] and [endPattern], or `null` if either is absent.
   *
   * A `null` [startPattern] starts at the beginning, a `null` [endPattern] runs to the end.
   * With [includeStartPattern] the delimiters are part of the result.
   */
  fun extract(
    text: String?,
    startPattern: String? = null,
    endPattern: String? = null,
    includeStartPattern: Boolean = false,
    ignoreCase: Boolean = false,
  ): String? {
    if (text == null) {
      return null
    }
    val startPatternLength = startPattern?.length ?: 0
    val startIdx = if (startPattern == null) {
      0
    } else {
      text.indexOf(startPattern, ignoreCase = ignoreCase)
    }
    if (startIdx == -1) {
      return null
    }
    if (endPattern == null) {
      return if (includeStartPattern) {
        text.substring(startIdx)
      } else {
        text.substring(startIdx + startPatternLength)
      }
    }
    val endIdx = text.indexOf(endPattern, startIdx + startPatternLength, ignoreCase = ignoreCase)
    return if (endIdx == -1) {
      null
    } else {
      if (includeStartPattern) {
        text.substring(startIdx, endIdx + endPattern.length)
      } else {
        text.substring(startIdx + startPatternLength, endIdx)
      }
    }
  }

  /**
   * Splits a comma-separated parameter value, dropping blanks and trimming each item.
   *
   * `null` for absent or blank input — callers distinguish "not given" from "given as empty".
   *
   * No caller in the published set, and kept anyway: query parameters on live endpoints are parsed
   * with it, and `StringUtilTest` pins the behaviour of the `Splitter` chain it replaced. A tested
   * utility in a library module with the callers outside it is what a library looks like.
   */
  fun parseList(value: String?): List<String>? {
    if (value.isNullOrBlank()) return null
    return value.split(',')
      .map(String::trim)
      .filter(String::isNotEmpty)
  }

  /** [parseList] as a set. */
  fun parseSet(value: String?): Set<String>? = parseList(value)?.toSet()
}

fun String.extract(
  startPattern: String? = null,
  endPattern: String? = null,
  includeStartPattern: Boolean = false,
): String? = StringUtil.extract(
  text = this,
  startPattern = startPattern,
  endPattern = endPattern,
  includeStartPattern = includeStartPattern,
)
