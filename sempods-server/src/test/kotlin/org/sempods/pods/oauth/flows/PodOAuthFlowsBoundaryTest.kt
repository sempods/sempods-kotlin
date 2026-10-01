package org.sempods.pods.oauth.flows

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
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
 * The rules are separate so a failure says which one broke rather than only that something did.
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
  fun `the layer's contracts name no type a store or the issuer defines`() {
    // A type nested in a store or in the issuer is that class's vocabulary: a contract naming one is
    // tied to the class that persists or signs it. #154 decided that this, not a port per store, is
    // the persistence boundary. It holds the contract — what a non-private member takes, returns or
    // holds — and not what a method body calls.
    classes().that().resideInAPackage(LAYER)
      .should(nameNoStoreTypeInTheirContract)
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

    /** Nested, at any depth, in a store, a rows class or the issuer — the classes that persist or sign. */
    private fun JavaClass.isStoreType(): Boolean {
      if (simpleName == "Companion") return false
      return generateSequence(enclosingClass.orElse(null)) { it.enclosingClass.orElse(null) }
        .any { it.simpleName.endsWith("Store") || it.simpleName.endsWith("Rows") || it.simpleName == "PodTokenIssuer" }
    }

    private val nameNoStoreTypeInTheirContract =
      object : ArchCondition<JavaClass>("name no type a store or the issuer defines in a non-private member") {
        override fun check(item: JavaClass, events: ConditionEvents) {
          // Every class a signature involves, type arguments included: `Result<Store.Rotated>` names
          // the store's type as surely as `Store.Rotated` does.
          val named = item.fields.filterNot { it.modifiers.contains(JavaModifier.PRIVATE) }
            .map { it.description to it.type.allInvolvedRawTypes } +
            item.codeUnits.filterNot { it.modifiers.contains(JavaModifier.PRIVATE) || it.modifiers.contains(JavaModifier.SYNTHETIC) }
              .map { unit -> unit.description to (unit.parameterTypes + unit.returnType).flatMap { it.allInvolvedRawTypes } }
          named.forEach { (member, types) ->
            types.filter { it.isStoreType() }.forEach { type ->
              events.add(SimpleConditionEvent.violated(item, "$member names ${type.name}"))
            }
          }
        }
      }

    /** A rule that matches nothing is a rule that holds nothing. */
    private fun ArchRule.check(classes: JavaClasses) = allowEmptyShould(false).check(classes)
  }
}
