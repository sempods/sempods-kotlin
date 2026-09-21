package org.sempods.pods.oauth.flows

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the pod's OAuth application layer may name.
 *
 * #215 asks one thing of these classes: that consent and the token exchanges decide what they
 * decide without an HTTP framework, a protocol library or a database driver in their vocabulary.
 * An interface cannot say that — an interface describes what a class hands back, and this is about
 * what it reaches for. A driver type used inside a method body breaks the rule as surely as one in
 * a signature, which is why this reads the compiled classes rather than the source.
 *
 * The three rules are separate so a failure says which one broke rather than only that something
 * did.
 */
class PodOAuthFlowsBoundaryTest {

  @Test
  fun `the application layer names no HTTP framework, protocol library or database driver`() {
    noClasses().that().resideInAPackage(LAYER)
      .should().dependOnClassesThat().resideInAnyPackage(
        "jakarta..",
        "io.ktor..",
        "org.eclipse.jetty..",
        "com.nimbusds..",
        "com.mongodb..",
        "org.bson..",
      )
      .because(
        "an alternative transport or store can only be put under a layer that names neither; " +
            "keep the type at the adapter and hand this layer a domain value",
      )
      .check(layer)
  }

  @Test
  fun `the application layer does not reach up into the layer that binds HTTP`() {
    // `docs/architecture/module-layering.md` §"Dependency Direction": Endpoint → Facade →
    // Repository, and nothing below reaches back. This is that rule, not a second type rule — it
    // is what caught `PodTokenExchange` holding a Mongo-backed store through the endpoint package.
    noClasses().that().resideInAPackage(LAYER)
      .should().dependOnClassesThat().resideInAPackage("org.sempods.api..")
      .because("the endpoint holds the request and this holds the decision")
      .check(layer)
  }

  @Test
  fun `the application layer names no stored row`() {
    // A `…Dbo` is this implementation's document, whatever package it sits in: naming one writes
    // its field order and its driver into the contract (`docs/concepts/modularity.md` §"The pattern").
    noClasses().that().resideInAPackage(LAYER)
      .should().dependOnClassesThat().haveSimpleNameEndingWith("Dbo")
      .orShould().dependOnClassesThat().haveSimpleNameEndingWith("DboFields")
      .because("a stored row is this deployment's shape, not a contract")
      .check(layer)
  }

  @Test
  fun `the rules have something to hold`() {
    // Fails closed. A rule whose subject moved away passes vacuously, and the move is exactly the
    // change that would need it most — `allowEmptyShould(false)` below says the same thing to
    // ArchUnit, and this says it to whoever reads the failure.
    assertTrue(
      layer.any { it.packageName == LAYER.removeSuffix("..").trimEnd('.') },
      "no compiled class under $LAYER — if the application layer moved, point this test at it",
    )
  }

  private companion object {
    private const val LAYER = "org.sempods.pods.oauth.flows.."

    /**
     * The layer's own classes, without the test sources that sit in the same package: this file
     * names ArchUnit and JUnit, and neither is the subject.
     */
    private val layer: JavaClasses = ClassFileImporter()
      .withImportOption(ImportOption.DoNotIncludeTests())
      .importPackages(LAYER.removeSuffix(".."))

    /** A rule that matches nothing is a rule that holds nothing. */
    private fun ArchRule.check(classes: JavaClasses) = allowEmptyShould(false).check(classes)
  }
}
