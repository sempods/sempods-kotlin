package org.sempods.updates

/** A startup maintenance task that must tolerate repeated and partially completed runs. */
interface SempodsUpdate {

  val name: String

  /**
   * When `true`, the task runs synchronously before the server accepts requests.
   * Otherwise it runs on a daemon thread and may overlap with requests.
   * Choose `true` when requests depend on the task's work. A task that renames stored identifiers
   * is blocking: until it has run, requests for the new names answer 404 or 403. A failure is
   * logged and startup continues in either mode; completion of the attempt does not guarantee
   * success.
   */
  val blocking: Boolean get() = false

  fun run()
}
