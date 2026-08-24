package org.sempods.ai.sem.prompts

import com.google.inject.Inject
import com.google.inject.Injector

class SempodsPromptBuilderFactory @Inject constructor(
  private val injector: Injector,
) {

  fun newBuilder(): SempodsPromptBuilder {
    return SempodsPromptBuilder()
      .also(injector::injectMembers)
  }
}
