package org.sempods.api.pod.system.find

import org.junit.jupiter.api.Test
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.spec.PodRef
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindDownscopeTest {

  private val pod = PodRef(uri = URI("https://pods.example/demo"), name = "demo", owner = "owner")
  private val notes = URI("https://pods.example/demo/notes")
  private val tasks = URI("https://pods.example/demo/tasks")
  private val credentials = SempodsCredentials(pod = pod, restrictedContexts = setOf(notes, tasks))

  @Test
  fun `an absent list is pod-wide within the readable contexts`() {
    assertNull(FindDownscope.resolve(credentials, null))
  }

  @Test
  fun `a relative context resolves against the pod's URI and an absolute one is taken as it is`() {
    assertEquals(setOf(notes), FindDownscope.resolve(credentials, listOf("notes")))
    assertEquals(setOf(notes), FindDownscope.resolve(credentials, listOf("/notes")))
    assertEquals(setOf(notes, tasks), FindDownscope.resolve(credentials, listOf("notes", tasks.toString())))
  }

  @Test
  fun `a malformed context, one outside the pod and one with a fragment are dropped silently`() {
    assertEquals(
      setOf(notes),
      FindDownscope.resolve(
        credentials,
        listOf("notes", "not a context", "https://other.example/demo/tasks", "tasks#part", "https://pods.example/demo-other/tasks"),
      ),
    )
  }

  @Test
  fun `a context the caller cannot read is dropped, so a list of only those is an empty scope`() {
    assertEquals(setOf(tasks), FindDownscope.resolve(credentials, listOf("tasks", "private")))
    assertEquals(emptySet(), FindDownscope.resolve(credentials, listOf("private")))
  }

  @Test
  fun `a list of only blank entries is an empty scope, not pod-wide`() {
    assertEquals(emptySet(), FindDownscope.resolve(credentials, listOf("")))
    assertEquals(emptySet(), FindDownscope.resolve(credentials, listOf(" ", "")))
    assertEquals(emptySet(), FindDownscope.resolve(credentials, emptyList()))
  }

  @Test
  fun `unrestricted credentials keep every well-formed context in the pod`() {
    val unrestricted = credentials.copy(restrictedContexts = null)
    assertEquals(setOf(notes, URI("https://pods.example/demo/private")), FindDownscope.resolve(unrestricted, listOf("notes", "private", "x#y")))
  }
}
