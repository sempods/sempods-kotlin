package org.sempods.pods.contexts

import org.sempods.SempodsUriBuilder.Companion.CONTEXT_PATH_PREFIX
import java.net.URI

/**
 * What a caller may name a context, applied wherever one comes into existence.
 *
 * Two producers create contexts: the management route (`PodContextsEndpoint`, used by apps holding
 * a `#manage` scope) and the consent dialog (`PodAuthEndpoint`, used by the pod owner during an app
 * authorization). They used to disagree about far more than naming — the dialog built IRIs straight
 * under the pod root from free user input while the route prefixed them — which is how a live pod
 * ended up carrying three different shapes. Both now mint
 * `<pod>/_system/contexts/<path>` and both validate here, so the rules cannot drift again.
 *
 * The rules are deliberately permissive about *names* and strict about *structure*:
 *
 * - **Free naming is the norm.** A pod owner calls a context `privat` or `2026-sommer`; that is
 *   their pod's own working area, and nothing about it is the server's business.
 * - **Type names are reserved.** [DELEGATION_TYPES] mark subtrees delegated to someone else — an
 *   app's sandbox today, a guest's area later. They are handed out by the control plane, so an
 *   owner may not claim one, and a type root is never created through these routes.
 * - **`_system` is reserved outright.** It is kept free so a context IRI can later carry
 *   `<context-iri>/_system/<operation>` for per-context operations (shape registration, per-context
 *   grants). Context names and operation names are both open sets; without a reserved separator
 *   they eventually collide, and a name banned after the fact would break pods already using it.
 *
 * The two halves are deliberately separate, because they apply at different times:
 *
 * - **[rejectionReason] — may this *name* be created.** On creation only. Reading and deleting must
 *   keep working for everything that exists: contexts predating these rules, and the type roots the
 *   control plane sets up. A rule that made an existing context unreadable, or a root undeletable,
 *   would be a one-way door.
 * - **[resolve] — can this be a context IRI at all.** Everywhere, and also on stored strings during
 *   migration. A name may be disallowed *today* and still have to stay addressable; a string that
 *   no route can reach was never a context, whenever it was written.
 */
object ContextPathRules {

  /** The one delegation type with a producer. Named because the migration has to rebuild it. */
  const val APPS_TYPE = "apps"

  /**
   * Path segments that mark a delegated subtree. Reserved as *first* segment: `apps` is live,
   * `users` is held for the guest case so the name cannot be taken by something else first.
   *
   * A type means delegation, not ownership — `apps/notes` is not "belongs to the notes app" but
   * "the area it may work in". That is also why the pod owner's own contexts carry no type: there
   * is nothing to delegate, the pod is already theirs.
   */
  val DELEGATION_TYPES = setOf(APPS_TYPE, "users")

  /** Types with an implementation behind them. `users` is reserved but has no producer yet. */
  val IMPLEMENTED_TYPES = setOf(APPS_TYPE)

  /** Reserved as the separator between a context IRI and future per-context operations. */
  const val RESERVED_SEGMENT = "_system"

  /**
   * The form [rejectionReason] and the IRI builders expect: trimmed, no leading or trailing slash.
   *
   * Shared with the rules rather than left to each caller, because the two must agree on what
   * counts as the same path. The consent dialog resolves a submitted permission back to the context
   * it created by comparing these strings — `public/tasks` and ` /public/tasks/ ` have to meet.
   */
  fun normalize(path: String): String = path.trim().trimStart('/').trimEnd('/')

  /**
   * Relative-path segments. Rejected because a context IRI is an identity, not a route: two
   * different strings must not name the same context, and `<prefix>/a/../b` would — worse, a `GET`
   * on it arrives at `<prefix>/b`, so the row could never be addressed at all.
   *
   * On the management route Jetty collapses these before the endpoint sees them. The consent dialog
   * receives a *form value*, which nothing normalizes on the way in — that asymmetry is why the
   * check belongs here rather than being left to the servlet container.
   */
  private val RELATIVE_SEGMENTS = setOf(".", "..")

  /**
   * Why this *name* may not be created — the policy half, and the half that applies on creation
   * only. Whether the path could be a context IRI at all is [resolve]; callers that create a
   * context need both, callers that address an existing one need only [resolve].
   */
  fun rejectionReason(path: String): String? {
    val segments = path.split('/')
    if (segments.any { it == RESERVED_SEGMENT }) {
      return "'$RESERVED_SEGMENT' is reserved and must not be a context path segment"
    }

    val first = segments.first()
    if (first !in DELEGATION_TYPES) {
      // An ordinary, owner-named context. Nothing further to check.
      return null
    }
    if (first !in IMPLEMENTED_TYPES) {
      return "'$first' is reserved for a future context type and cannot be used yet"
    }
    if (segments.size < 3) {
      return "'$path' is a type root; roots are created by the admin surface, " +
          "this route manages contexts below them"
    }
    return null
  }

  /**
   * The canonical context IRI for [path] under [podBaseUrl] (which must end in `/`), or why it
   * cannot be one.
   *
   * Separate from [rejectionReason] on purpose, and applied more widely. [rejectionReason] governs
   * what may come into *existence* and runs on creation only; this governs what can be a context
   * IRI at all, and holds for reading and deleting too — a string that is not addressable was never
   * a context, whenever it was written.
   *
   * What it rejects, all for the same reason — the row would exist and no route could reach it:
   *
   * - **An empty segment.** A trailing slash or `a//b`; `_system/contexts/{path}` never produces
   *   one, so nothing on the route side would ever match.
   * - **A relative segment.** `a/../b` is collapsed in transit, so a `GET` lands somewhere else.
   * - **A percent-encoded character.** Both producers get their path decoded, so the stored form
   *   is never what comes back in a later request.
   * - **A fragment.** `foo#bar` would produce `<pod>/_system/contexts/foo#bar`, which the scope
   *   grammar `<context-iri>#<permission>` cannot parse back — `…foo#bar#read` has two candidate
   *   split points and the wrong one wins.
   * - **A query.** Same addressability problem, and `?context=` on the resource routes would carry
   *   an IRI that re-parses into something else.
   *
   * The prefix is prepended here, never taken from the caller, so no path can escape the namespace
   * structurally.
   */
  fun resolve(podBaseUrl: String, path: String): ContextUriResolution {
    val normalized = normalize(path)
    addressabilityProblem(podBaseUrl, normalized)?.let { return ContextUriResolution.Rejected(it) }
    return ContextUriResolution.Resolved(URI("$podBaseUrl$CONTEXT_PATH_PREFIX$normalized"))
  }

  /**
   * The check behind [resolve], on [path] **taken literally**.
   *
   * The difference matters for stored data. [resolve] normalizes first, because a caller typing
   * `public/tasks/` means `public/tasks`. A context IRI that already *is* `…/public/tasks/` is a
   * different matter: there the trailing slash is part of the identity and precisely what makes the
   * row unreachable, so normalizing it away would report the defect as healthy. The migration asks
   * this question of what is in the registry; everything else goes through [resolve].
   *
   * [podBaseUrl] only supplies a parseable prefix for the syntax check — a bad path is bad under any
   * pod.
   */
  fun addressabilityProblem(podBaseUrl: String, path: String): String? {
    if (path.isEmpty()) {
      return "missing context path"
    }
    if (path.contains('%')) {
      // Percent-encoding survives the `URI` checks below — `foo%23bar` carries no fragment, and
      // `foo%2Fbar` is one segment — but not the round trip. Both producers receive their path
      // already decoded (`@PathParam` / `@FormParam`), so a stored `foo%23bar` is only ever asked
      // for as `foo#bar`, which is refused, and `foo%2Fbar` as `foo/bar`, which finds a different
      // row. A context nobody can ask for again is not a context.
      return "context path must not contain percent-encoded characters"
    }
    val segments = path.split('/')
    if (segments.any { it.isEmpty() }) {
      return "context path must not contain empty segments"
    }
    if (segments.any { it in RELATIVE_SEGMENTS }) {
      return "context path must not contain relative segments ('.' or '..')"
    }
    val uri = try {
      URI("$podBaseUrl$CONTEXT_PATH_PREFIX$path")
    } catch (_: Exception) {
      return "invalid context path"
    }
    if (uri.fragment != null) {
      return "context URI must not contain fragment"
    }
    if (uri.query != null) {
      return "context URI must not contain query"
    }
    return null
  }
}

/** Outcome of [ContextPathRules.resolve] — the IRI a path maps to, or why it maps to none. */
sealed interface ContextUriResolution {
  data class Resolved(val uri: URI) : ContextUriResolution
  data class Rejected(val reason: String) : ContextUriResolution
}
