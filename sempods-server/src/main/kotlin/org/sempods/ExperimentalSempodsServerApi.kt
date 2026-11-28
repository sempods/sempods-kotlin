package org.sempods

/**
 * Marks what this server makes public for a module beside it, before the shape is settled.
 *
 * The optional modules (`:sempods-context-management` first) are separate Gradle modules, so what
 * they call here cannot be `internal`. Public is not stable, though: these declarations follow the
 * cuts being tried under [#139](https://github.com/sempods/sempods-kotlin/issues/139) and change
 * with them, without a deprecation cycle. A caller opts in with `-opt-in=org.sempods.ExperimentalSempodsServerApi`
 * and accepts that.
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "Experimental server API for the optional modules; it may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class ExperimentalSempodsServerApi
