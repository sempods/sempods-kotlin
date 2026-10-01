package org.sempods

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a seam's contract may name, for every `spi` package this module can see.
 *
 * An `spi` package holds the types an alternative implementation is written against
 * (`docs/naming.md` §4). It is only a boundary if none of them names an HTTP framework, a
 * protocol library, a database driver or a stored row — otherwise the implementation it was meant to
 * hide comes along with it. This reads the compiled classes, so a type used inside a method body
 * counts as much as one in a signature.
 *
 * By package rather than by name, so a seam that gains an `spi` package is held from its first class.
 */
class SpiBoundaryTest {

  @Test
  fun `a seam's contract names no HTTP framework, protocol library or database type`() {
    noClasses().that().resideInAPackage(SPI)
      .should().dependOnClassesThat().resideInAnyPackage(
        "jakarta..",
        "io.ktor..",
        "org.eclipse.jetty..",
        "com.nimbusds..",
        "com.mongodb..",
        "org.bson..",
      )
      .because("an alternative implementation is written against values, not against one engine's types")
      .check(classes)
  }

  @Test
  fun `a seam's contract names no stored row and no implementation`() {
    noClasses().that().resideInAPackage(SPI)
      .should().dependOnClassesThat().haveSimpleNameEndingWith("Dbo")
      .orShould().dependOnClassesThat().resideInAPackage("..impls..")
      .because("a stored row or an implementation is one deployment's choice, not the contract")
      .check(classes)
  }

  @Test
  fun `the rules have something to hold`() {
    // Fails closed: a rule over packages that no longer exist would pass vacuously.
    assertTrue(
      classes.any { it.packageName.endsWith(".spi") },
      "no compiled class in an spi package under org.sempods — if the contracts moved, point this test at them",
    )
  }

  private companion object {
    private const val SPI = "..spi.."

    /**
     * Every production class under `org.sempods` on this module's classpath — its own and its
     * siblings', which Gradle hands over as jars. The test sources name ArchUnit and JUnit and are
     * not the subject.
     */
    private val classes: JavaClasses = ClassFileImporter()
      .withImportOption(ImportOption.DoNotIncludeTests())
      .importPackages("org.sempods")

    private fun ArchRule.check(classes: JavaClasses) = allowEmptyShould(false).check(classes)
  }
}
