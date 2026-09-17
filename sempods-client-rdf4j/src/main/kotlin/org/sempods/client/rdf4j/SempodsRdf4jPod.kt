package org.sempods.client.rdf4j

import org.sempods.client.core.SempodsPod

/**
 * A pod's RDF as RDF4J models, on a [SempodsPod] that already exists.
 *
 * ```java
 * SempodsRdf4jPod rdf = new SempodsRdf4jPod(pod);
 * SempodsReadOptions inTasks = SempodsReadOptions.of(SempodsContextSelection.of(tasks));
 *
 * SempodsResponse<Model> read = rdf.resources().getModel(event, inTasks);
 * Model model = read.getBody();
 * model.add(Values.iri(event), Values.iri("https://schema.org/name"), Values.literal("Renamed"), Values.iri(tasks));
 * rdf.resources().put(event, model, SempodsWriteOptions.inContext(tasks).withIfMatch(read.getHeaders().get("ETag")));
 * ```
 *
 * **Only the body changes.** Every call is the operation of [pod]'s endpoint group, so authentication,
 * the resend, admission, the deadline and the transport are the ones a raw call on the same pod gets,
 * and so are the status and headers of an answer. A raw call and a model call can share one pod.
 */
class SempodsRdf4jPod(
  val pod: SempodsPod,
) {

  private val resourcesGroup = SempodsRdf4jResources(pod.resources())

  private val subjectsGroup = SempodsRdf4jSubjects(pod.subjects())

  private val slotsGroup = SempodsRdf4jSlots(pod.slots())

  private val contextsGroup = SempodsRdf4jContexts(pod.contexts())

  private val sparqlGroup = SempodsRdf4jSparql(pod.sparql())

  /** Resources the pod hosts, at their own addresses, as models. */
  fun resources(): SempodsRdf4jResources = resourcesGroup

  /** Any subject by its IRI, through the System route, as models. */
  fun subjects(): SempodsRdf4jSubjects = subjectsGroup

  /** The values of one predicate on a subject, read as a model and written from RDF4J values. */
  fun slots(): SempodsRdf4jSlots = slotsGroup

  /** The context registry's answers, as models. */
  fun contexts(): SempodsRdf4jContexts = contextsGroup

  /** CONSTRUCT and DESCRIBE graphs as models, and SELECT results as binding sets. */
  fun sparql(): SempodsRdf4jSparql = sparqlGroup
}
