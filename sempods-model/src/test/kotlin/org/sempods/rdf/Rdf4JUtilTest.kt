package org.sempods.rdf

import org.sempods.rdf.Rdf4JUtil
import org.eclipse.rdf4j.model.util.ModelBuilder
import org.junit.jupiter.api.Test
import java.time.Instant

class Rdf4JUtilTest {

  @Test
  fun `instant literal should work`() {
    ModelBuilder()
      .add("http://example.org/s", "http://example.org/p", Rdf4JUtil.literal(Instant.now()))
      .build()
  }
}
