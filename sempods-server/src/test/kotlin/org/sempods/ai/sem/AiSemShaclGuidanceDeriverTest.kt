package org.sempods.ai.sem

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSemShaclGuidanceDeriverTest {

  private val deriver = AiSemShaclGuidanceDeriver()

  @Test
  fun `derive should extract SHACL classes and property descriptions`() {
    val shacl = """
      @prefix sh: <http://www.w3.org/ns/shacl#> .
      @prefix schema: <https://schema.org/> .
      @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
      <urn:shape:Action> a sh:NodeShape ;
        sh:targetClass schema:Action ;
        sh:property [
          sh:path schema:name ;
          sh:datatype xsd:string ;
          sh:description "Short title of the task."@en ;
        ] ;
        sh:property [
          sh:path schema:description ;
          sh:datatype xsd:string ;
          sh:description "A description of the item."@en ;
        ] .
    """.trimIndent()

    val derived = deriver.derive(
      shaclSyntax = "text/turtle",
      shaclData = shacl,
    )

    assertNotNull(derived)
    assertNotNull(derived.instructions)
    assertTrue(derived.instructions.contains("https://schema.org/Action"))
    assertTrue(derived.instructions.contains("https://schema.org/name"))
    assertNotNull(derived.terms)
    assertEquals(
      "Short title of the task.",
      derived.terms.get("https://schema.org/name").get("description").asText(),
    )
    assertEquals(
      "A description of the item.",
      derived.terms.get("https://schema.org/description").get("description").asText(),
    )
    assertEquals(
      "http://www.w3.org/2001/XMLSchema#string",
      derived.terms.get("https://schema.org/name").get("datatype").asText(),
    )
  }

  @Test
  fun `derive should return null for unsupported SHACL syntax`() {
    val derived = deriver.derive(
      shaclSyntax = "application/xml",
      shaclData = "<xml/>",
    )
    assertNull(derived)
  }
}
