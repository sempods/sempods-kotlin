package org.sempods

import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.ontologies.Ontologies
import org.sempods.rdf.toIri
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the store does with a resource model: `putResourceModel` writes the statements a model
 * carries under the contexts the model names, `getResourceInContext` projects back onto one of
 * them, and `patchResource` replaces a single context while the resource's other contexts survive.
 *
 * On [org.sempods.pods.PodFacade] rather than over HTTP, deliberately. These are assertions about
 * storage, and the transport is narrower than the store: it accepts only contexts registered inside
 * the pod namespace, and several fixtures here use neither. Routed over the wire, each of these
 * would quietly become an assertion about what the HTTP surface exposes.
 */
class PodFacadeResourceTest : SempodsIntegrationTest() {

  @Test
  fun `putResourceModel stores every context the model names`() {
    val pod = sempodsTestFactory.newPod().name

    val subject = URI("https://localhost:8080/item-${randomId()}")
    val contextA = URI("https://localhost:8080/context-a-${randomId()}").toIri()
    val contextB = URI("https://localhost:8080/context-b-${randomId()}").toIri()

    val nameInA = "name-a-${randomId()}"
    val nameInB = "name-b-${randomId()}"

    val model = LinkedHashModel()
    model.addEvent(subject.toIri(), contextA, nameInA)
    model.addEvent(subject.toIri(), contextB, nameInB)

    assertTrue(podFacade.putResourceModel(podName = pod, resourceUri = subject, model = model))

    val inA = assertNotNull(podFacade.getResourceInContext(pod, subject, URI(contextA.stringValue())))
    assertEquals(setOf(nameInA), inA.names(subject.toIri()))
    assertTrue(
      inA.contains(subject.toIri(), RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, contextA),
      "the type statement belongs to the context it was written under",
    )

    val inB = assertNotNull(podFacade.getResourceInContext(pod, subject, URI(contextB.stringValue())))
    assertEquals(setOf(nameInB), inB.names(subject.toIri()))
  }

  @Test
  fun `patchResource replaces the target context and leaves the others alone`() {
    val pod = sempodsTestFactory.newPod().name

    val subject = URI("https://localhost:8080/item-${randomId()}")
    val target = URI("https://localhost:8080/context-target-${randomId()}")
    val untouched = URI("https://localhost:8080/context-untouched-${randomId()}")

    val nameInTarget = "name-target-${randomId()}"
    val nameInUntouched = "name-untouched-${randomId()}"

    val initial = LinkedHashModel()
    initial.addEvent(subject.toIri(), target.toIri(), nameInTarget)
    initial.addEvent(subject.toIri(), untouched.toIri(), nameInUntouched)
    podFacade.putResourceModel(podName = pod, resourceUri = subject, model = initial)

    val replacement = LinkedHashModel()
    val replacedName = "replaced-${randomId()}"
    replacement.addEvent(subject.toIri(), target.toIri(), replacedName)

    assertTrue(
      podFacade.patchResource(
        podName = pod,
        resourceUri = subject,
        contextUri = target,
        replacementModel = replacement,
      ),
    )

    val inTarget = assertNotNull(podFacade.getResourceInContext(pod, subject, target))
    assertEquals(
      setOf(replacedName),
      inTarget.names(subject.toIri()),
      "the old value of the patched context is gone rather than doubled",
    )

    val inUntouched = assertNotNull(podFacade.getResourceInContext(pod, subject, untouched))
    assertEquals(
      setOf(nameInUntouched),
      inUntouched.names(subject.toIri()),
      "a write to one context does not reach the resource's other contexts",
    )
  }

  private fun Model.addEvent(subject: IRI, context: IRI, name: String) {
    add(subject, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
    add(subject, Ontologies.SCHEMA_ORG.Properties.name, Values.literal(name), context)
  }

  private fun Model.names(subject: IRI): Set<String> =
    getStatements(subject, Ontologies.SCHEMA_ORG.Properties.name, null)
      .map { it.`object`.stringValue() }
      .toSet()
}
