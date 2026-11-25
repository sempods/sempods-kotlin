package org.sempods.pods.oauth

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * The JVM methods 0.2 published for issuing a service token: a binary compiled against it links
 * against these, the default-argument bridge a Kotlin caller uses among them.
 */
class PodTokenIssuerSignatureTest {

  @Test
  fun `the service-token methods of 0_2 keep their descriptors`() {
    val issuer = PodTokenIssuer::class.java
    val long = Long::class.javaPrimitiveType

    assertNotNull(issuer.getMethod("issueServiceToken", String::class.java, String::class.java, Set::class.java, long))
    assertNotNull(
      issuer.getMethod(
        "issueServiceToken\$default",
        issuer, String::class.java, String::class.java, Set::class.java, long, Int::class.javaPrimitiveType, Any::class.java,
      ),
    )
  }
}
