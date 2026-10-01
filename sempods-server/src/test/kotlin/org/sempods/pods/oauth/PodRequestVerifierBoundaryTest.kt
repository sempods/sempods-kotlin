package org.sempods.pods.oauth

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the request-verification contract may name.
 *
 * [PodRequestVerifier] and the values it takes and returns are the boundary an alternative
 * verification is written against. They hold only if none of them names an HTTP framework, a
 * protocol library, a database driver or a stored row — the same vocabulary rule
 * `PodOAuthFlowsBoundaryTest` holds for the application layer. This reads the compiled classes, so a
 * type used inside a method body counts as much as one in a signature.
 *
 * The contract is named class by class, not by package: `org.sempods.pods.oauth` also holds the
 * stores and [PodTokenAuthenticator], which name Nimbus and the driver by definition.
 */
class PodRequestVerifierBoundaryTest {

  @Test
  fun `the verification contract names no HTTP framework, protocol library or database type`() {
    noClasses().that(inContract)
      .should().dependOnClassesThat().resideInAnyPackage(
        "jakarta..",
        "io.ktor..",
        "org.eclipse.jetty..",
        "com.nimbusds..",
        "com.mongodb..",
        "org.bson..",
      )
      .because("an alternative verifier is written against values, not against one engine's types")
      .check(classes)
  }

  @Test
  fun `the verification contract names no stored row`() {
    noClasses().that(inContract)
      .should().dependOnClassesThat().haveSimpleNameEndingWith("Dbo")
      .because("a stored row is this deployment's shape, not a contract")
      .check(classes)
  }

  @Test
  fun `the rules have something to hold`() {
    // Fails closed: a rule whose subject was renamed away would pass vacuously.
    assertEquals(CONTRACT, classes.filter { inContract.test(it) }.map { it.name.substringAfterLast('.').substringBefore('$') }.toSet())
  }

  private companion object {
    /** The types that cross the boundary, by simple name. */
    private val CONTRACT = setOf(
      "PodRequestVerifier",
      "PodResourceRequest",
      "PodTokenAuthentication",
      "PodTokenRejection",
      "PodAccessToken",
    )

    private val classes: JavaClasses = ClassFileImporter()
      .withImportOption(ImportOption.DoNotIncludeTests())
      .importPackages("org.sempods.pods.oauth")

    private val inContract = object : com.tngtech.archunit.base.DescribedPredicate<JavaClass>("are part of the verification contract") {
      override fun test(input: JavaClass): Boolean =
        input.packageName == "org.sempods.pods.oauth" && input.name.substringAfterLast('.').substringBefore('$') in CONTRACT
    }

    private fun ArchRule.check(classes: JavaClasses) = allowEmptyShould(false).check(classes)
  }
}
