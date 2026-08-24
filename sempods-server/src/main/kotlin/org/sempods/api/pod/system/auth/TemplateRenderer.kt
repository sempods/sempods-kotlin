package org.sempods.api.pod.system.auth

import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

/**
 * Renders Thymeleaf templates from `classpath:/templates/`.
 */
class TemplateRenderer {

  private val engine: TemplateEngine = TemplateEngine().apply {
    setTemplateResolver(ClassLoaderTemplateResolver().apply {
      prefix = "templates/"
      suffix = ".html"
      templateMode = TemplateMode.HTML
      characterEncoding = "UTF-8"
    })
  }

  fun render(template: String, variables: Map<String, Any>): String {
    val ctx = Context()
    ctx.setVariables(variables)
    return engine.process(template, ctx)
  }
}
