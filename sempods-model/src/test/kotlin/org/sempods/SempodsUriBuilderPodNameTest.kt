package org.sempods

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SempodsUriBuilderPodNameTest {

  @Test
  fun `test valid pod names`() {
    val podNames = listOf("hello-world", "ab1cd", "a-b-c-d-3e-f-5")
    podNames.forEach {
      SempodsUriBuilder.checkPodName(it)
    }
  }

  @Test
  fun `test invalid pod names`() {
    val podNames = listOf("--abc", "abcdef-", "abc--defghi")
    podNames.forEach {
      assertThrows<IllegalArgumentException> {
        SempodsUriBuilder.checkPodName(it)
      }
    }
  }
}
