package org.sempods.api.pod.system.contexts

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.DCTERMS
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDFS
import org.eclipse.rdf4j.model.vocabulary.SD
import org.eclipse.rdf4j.model.vocabulary.XSD
import org.sempods.commons.net.SempodsPodRoutes
import org.sempods.commons.net.SempodsVocabulary
import org.sempods.pods.contexts.persist.PodContextDbo
import org.sempods.pods.grants.EffectiveContextPermissions
import java.net.URI

/**
 * The context registry as RDF: what a context is (`SPS-CTX-032`), and which registered contexts the
 * requesting caller may read, write and manage (`SPS-CTX-033`, `SPS-CTX-034`).
 *
 * **Built from the registry alone** — the rows and the request's effective permissions. Nothing here
 * reads the graph, which is what makes the validator of `SPS-CTX-035` independent of a context's
 * contents: ordinary statements about a context IRI live in some context and are read through
 * `_system/resources/{b64url(iri)}`, where the description's `rdfs:seeAlso` points.
 *
 * **The catalogue joins rows and permissions here.** Effective permissions are keyed by the
 * credential's visible contexts, which can name a context whose registry row is gone; the rows are
 * the registry. Only this call site holds both, and joining them is what keeps `SPS-CTX-033`'s
 * "no absent Contexts" true by construction: a right can only be stated about a context the
 * catalogue also lists as a member. A manage root reaches registered descendants alone
 * (`PodContextPermissionResolver.expandManageCascade`), so no hypothetical context appears either.
 */
internal object PodContextRegistryRdf {

  /** `{pod}/_system/contexts`, the catalogue's own IRI. [podBaseUrl] ends in a slash. */
  fun catalogueIri(podBaseUrl: String): IRI = Values.iri(podBaseUrl.removeSuffix("/") + "/" + SempodsPodRoutes.CONTEXTS)

  /** One context's registry description, without caller permissions (`SPS-CTX-032`). */
  fun describe(row: PodContextDbo, podBaseUrl: String): Model {
    val context = Values.iri(row.contextUri)
    val model = LinkedHashModel()
    model.add(context, RDF.TYPE, SD.NAMED_GRAPH_CLASS)
    model.add(context, SD.NAME, context)
    model.add(context, PUBLIC, Values.literal(row.isPublic))
    row.label?.takeIf { it.isNotBlank() }?.let { model.add(context, RDFS.LABEL, Values.literal(it)) }
    row.description?.takeIf { it.isNotBlank() }?.let { model.add(context, DCTERMS.DESCRIPTION, Values.literal(it)) }
    model.add(context, DCTERMS.CREATED, Values.literal(row.createdAt.toString(), XSD.DATETIME))
    model.add(context, RDFS.SEEALSO, Values.iri(podBaseUrl.removeSuffix("/") + "/" + SempodsPodRoutes.resource(URI.create(row.contextUri))))
    return model
  }

  /** The caller's catalogue: every registered context they may see, with what they may do (`SPS-CTX-033`). */
  fun catalogue(podBaseUrl: String, rows: List<PodContextDbo>, effective: EffectiveContextPermissions): Model {
    val catalogue = catalogueIri(podBaseUrl)
    val model = LinkedHashModel()
    model.add(catalogue, RDF.TYPE, SD.GRAPH_COLLECTION)

    val visible = rows.filter { effective.byContext.containsKey(it.contextUri) }
    visible.forEach { model.add(catalogue, SD.NAMED_GRAPH_PROPERTY, Values.iri(it.contextUri)) }
    visible.forEach { row ->
      val permissions = effective.byContext.getValue(row.contextUri).permissions
      val context = Values.iri(row.contextUri)
      if ("read" in permissions) model.add(catalogue, READABLE_CONTEXT, context)
      if ("write" in permissions) model.add(catalogue, WRITABLE_CONTEXT, context)
      if ("manage" in permissions) model.add(catalogue, MANAGEABLE_CONTEXT, context)
    }
    return model
  }

  private val PUBLIC = Values.iri(SempodsVocabulary.PUBLIC)

  private val READABLE_CONTEXT = Values.iri(SempodsVocabulary.READABLE_CONTEXT)

  private val WRITABLE_CONTEXT = Values.iri(SempodsVocabulary.WRITABLE_CONTEXT)

  private val MANAGEABLE_CONTEXT = Values.iri(SempodsVocabulary.MANAGEABLE_CONTEXT)
}
