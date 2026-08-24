package org.sempods.commons.config

import java.io.File
import java.net.URI
import java.net.URISyntaxException
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Where configuration is read from.
 *
 * A process cannot set its own environment variables — by the time `main` runs, `System.getenv`
 * is fixed. So configuration resolves from the environment **first** and from system properties
 * as a fallback. Properties can be set with `-D` on the command line, or loaded from a file at
 * startup via [loadFile], which is what lets a local run be configured without editing a shell
 * profile or an IDE run configuration.
 *
 * The environment always wins. A deployment that sets a real value cannot be shadowed by a stray
 * property, and `VAR=… ./gradlew run` still overrides a loaded file.
 *
 * This object knows nothing about *which* file — that is a deployment decision and belongs at the
 * process entry point (see `BackendStarter`).
 */
// TODO: most call sites should build a typed config object at the entry point and have it
//  injected, the way SempodsAuthConfig / SempodsMcpConfig already do — a global lookup is a
//  hidden dependency. What cannot move that way is the class-init tier: `SempodsModule.config`
//  and its siblings are companion `val`s evaluated before any injector exists, so those keep
//  reading through here.
object Env {

  private val logger = KotlinLogging.logger {}

  fun get(name: String): String? =
    System.getenv(name)?.takeIf(String::isNotBlank)
      ?: System.getProperty(name)?.takeIf(String::isNotBlank)

  fun get(name: String, default: String): String = get(name) ?: default

  /**
   * The value exactly as set — `null` only when the name is genuinely absent.
   *
   * [get] treats a blank value as unset, which is what you want of a credential: `FOO=` is a
   * forgotten value, not a configured one. For a *list*, though, the two differ: an explicitly
   * empty `SEMPODS_AUTH_ISSUERS` means "trust no issuer", and collapsing that into "unset" would
   * hand the operator the default they were disabling.
   */
  fun getRaw(name: String): String? = System.getenv(name) ?: System.getProperty(name)

  /**
   * Whether this process runs outside a deployment.
   *
   * Defined in this module rather than in any one application framework, so that a service which
   * has none — `sempods-auth`, `sempods-mcp` — can state "required in production" at all. `GAE_ENV`
   * is legacy App Engine and stays in the rule only because deployments still set it.
   */
  val isDevelopment: Boolean by lazy {
    val gaeEnv = get("GAE_ENV")
    val onGae = gaeEnv != null && gaeEnv != "localdev"
    !onGae && get("PRODUCTION") != "true"
  }

  /**
   * A setting a deployment must provide, with a stand-in for local work.
   *
   * Fails the boot rather than the first request that needs it: a missing credential otherwise
   * surfaces as a broken feature hours later, at a point that says nothing about the cause.
   *
   * @param reason completes the sentence "must be set in production — …", so it should say what
   *   breaks without it, not repeat the variable's name.
   */
  fun requiredInProduction(name: String, developmentFallback: String, reason: String): String =
    requiredInProductionOrNull(name, reason) ?: developmentFallback

  /**
   * As [requiredInProduction], but leaves the stand-in to the caller: `null` in development.
   *
   * For settings whose development value is not a plain string — a derived key, a parsed
   * structure — where passing it as a parameter would mean encoding it just to decode it again.
   */
  fun requiredInProductionOrNull(name: String, reason: String): String? {
    get(name)?.let { return it }
    check(isDevelopment) { "$name must be set in production — $reason" }
    logger.warn { "$name is not set — falling back to a development value. $reason" }
    return null
  }

  /**
   * A set of settings that only makes sense complete — a client id with its secret, or the four
   * values of an Apple signing key.
   *
   * Returns `null` when none is set (a feature simply not configured, which is legitimate) and
   * also when only *some* are: a half-configured group is a deployment mistake, so it is reported
   * and then treated as absent rather than half-enabled.
   *
   * @param label names the group in that report, e.g. `"google login"`.
   * @param lookup where the values come from; injectable so a test can state the whole
   *   environment it means instead of inheriting whatever the machine exports.
   */
  fun group(
    label: String,
    vararg names: String,
    lookup: (String) -> String? = ::get,
  ): Map<String, String>? {
    val present = names.associateWith { lookup(it) }.filterValues { it != null }
    if (present.isEmpty()) return null
    if (present.size < names.size) {
      // Names only — the missing ones are the point, and this goes to a log.
      logger.warn {
        "$label is only partly configured and stays disabled. Missing: " +
            names.filterNot { it in present.keys }.sorted().joinToString(", ")
      }
      return null
    }
    @Suppress("UNCHECKED_CAST")
    return present as Map<String, String>
  }

  /**
   * A numeric setting — a port, a limit, a timeout.
   *
   * A value that is set but unparseable **fails the boot**. Falling back to the default would be
   * worse than crashing: everything around the process (compose, a reverse proxy) is configured
   * for the value someone meant to set, so a typo would leave the process listening where nobody
   * is looking, with nothing in the log to say why.
   */
  fun int(name: String, default: Int): Int {
    val raw = get(name) ?: return default
    return raw.toIntOrNull()
      ?: throw IllegalStateException("$name must be a whole number, got '$raw'")
  }

  /**
   * A base URL, normalised to a trailing slash and checked for being absolute http(s).
   *
   * Both halves matter wherever the address is **persisted**: sempods mints pod IRIs by appending
   * to its base URL, so `https://example.org` without the slash would write
   * `https://example.orgalice/…` into resource subjects and named graphs — identities that are
   * expensive to take back. A wrong-but-well-formed address is a deployment decision; a malformed
   * one is a typo, and it stops the boot.
   */
  fun baseUrl(name: String, default: String): String =
    normalizeBaseUrl(name, get(name) ?: default)

  private fun normalizeBaseUrl(name: String, raw: String): String {
    val trimmed = raw.trim()
    val uri = try {
      URI(trimmed)
    } catch (e: URISyntaxException) {
      throw IllegalStateException("$name must be a valid URL, got '$raw'", e)
    }
    check(uri.isAbsolute && (uri.scheme == "http" || uri.scheme == "https")) {
      "$name must be an absolute http(s) URL, got '$raw'"
    }
    check(!uri.host.isNullOrBlank()) { "$name must name a host, got '$raw'" }
    // A base URL is a prefix that other paths are appended to, so anything that can only come
    // last is a mistake here: `https://example.org?x=1` would normalise to `…?x=1/` and then mint
    // pod IRIs like `https://example.org?x=1/alice/…`. A path is fine — a server may well live
    // under one — but a query, a fragment or embedded credentials are not.
    check(uri.rawQuery == null) { "$name must not carry a query string, got '$raw'" }
    check(uri.rawFragment == null) { "$name must not carry a fragment, got '$raw'" }
    check(uri.rawUserInfo == null) { "$name must not embed credentials, got '$raw'" }
    return trimmed.trimEnd('/') + "/"
  }

  /**
   * A separated list in one setting, e.g. `SEMPODS_AUTH_ISSUERS`. Blank entries are dropped.
   *
   * @param default used only when the name is **absent**. A name that is set but empty yields an
   *   empty list — "configured to nothing" is a decision, and [getRaw] is what keeps it apart
   *   from "not configured".
   */
  fun list(name: String, vararg separators: Char = charArrayOf(','), default: String? = null): List<String> =
    (getRaw(name) ?: default)
      ?.split(*separators)
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?: emptyList()

  /**
   * A `key:value,key:value` map in one setting, e.g. `SEMPODS_ADMIN_CLIENTS`.
   *
   * A malformed entry **throws** instead of being skipped: a typo that silently drops one
   * operator's credential is worse than a server that refuses to start. Only the first
   * [keyValueSeparator] splits, so a value may contain it — but no value may contain
   * [entrySeparator].
   *
   * A repeated **key** throws here; a repeated **value** does not, and deliberately stays with
   * the callers that reject it (`StaticCredentialAdminAuthorizer.parseClients` and the
   * equivalent on any other admin authority). Two keys mapping to one value is only wrong when the
   * value is a credential, where it makes the key unidentifiable and rotation a no-op; for a
   * pair map generally — aliases onto one target, say — it is the normal case, so pulling the
   * check in here would forbid legitimate configurations for every future caller.
   */
  fun pairs(name: String): Map<String, String> = pairsOf(get(name), label = name)

  fun pairsOf(
    raw: String?,
    label: String,
    entrySeparator: Char = ',',
    keyValueSeparator: Char = ':',
  ): Map<String, String> {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
    val result = LinkedHashMap<String, String>()
    value.split(entrySeparator).forEach { entry ->
      val trimmed = entry.trim()
      if (trimmed.isEmpty()) return@forEach
      val separator = trimmed.indexOf(keyValueSeparator)
      check(separator > 0) {
        "malformed $label entry (expected 'key${keyValueSeparator}value'): '$trimmed'"
      }
      val key = trimmed.substring(0, separator).trim()
      val entryValue = trimmed.substring(separator + 1).trim()
      check(key.isNotEmpty() && entryValue.isNotEmpty()) {
        "malformed $label entry (blank key or value): '$trimmed'"
      }
      check(result.put(key, entryValue) == null) {
        "duplicate $label key: '$key'"
      }
    }
    return result
  }

  /**
   * Loads `NAME=value` lines from [file] into system properties and returns how many were applied.
   *
   * **Call this before anything reads configuration.** Several values are companion `val`s
   * evaluated on class initialization — a module's development-environment flag among them — so a
   * later load arrives too late to matter.
   *
   * Blank lines and `#` comments are skipped, surrounding double quotes are stripped, and a value
   * may itself contain `=`. A name that already **resolves** is left alone, which is what keeps
   * the precedence above intact. A missing file is not an error.
   */
  fun loadFile(file: File): Int {
    if (!file.isFile) return 0
    var applied = 0
    file.readLines()
      .map(String::trim)
      .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
      .forEach { line ->
        val name = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim().removeSurrounding("\"")
        if (name.isEmpty() || value.isEmpty()) return@forEach
        // Asking [get] rather than re-testing the two sources: a `FOO=` exported blank is "unset"
        // there, so re-testing for mere presence here would let it block the file and leave the
        // name resolving to nothing at all. One definition of "already configured", not two.
        if (get(name) != null) return@forEach
        System.setProperty(name, value)
        applied++
      }
    // Count and path only — the file holds credentials, and this line goes to a log.
    logger.info { "Applied $applied setting(s) from ${file.path}" }
    return applied
  }

  /**
   * Walks up from the working directory looking for [relativePath], and [loadFile]s the first hit.
   * Returns how many settings were applied — `0` when nothing matched.
   *
   * The walk exists because an IDE may start a process in a module directory rather than at the
   * repository root. In a container nothing matches, so this is a no-op there.
   *
   * **Which** file a deployment overlays is the deployment's own decision, so the caller passes the
   * path; this only knows how to look for one. Call it as the first statement of `main` — several
   * configuration values are companion `val`s evaluated on class initialization, so a later load
   * arrives too late to matter.
   */
  fun loadFileWalkingUp(relativePath: String, maxParentLookups: Int = 4): Int {
    var directory: File? = File("").absoluteFile
    repeat(maxParentLookups) {
      val candidate = directory?.resolve(relativePath)
      if (candidate != null && candidate.isFile) return loadFile(candidate)
      directory = directory?.parentFile
    }
    return 0
  }
}
