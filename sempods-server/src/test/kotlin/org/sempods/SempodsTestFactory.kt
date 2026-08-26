package org.sempods

import com.google.inject.Inject
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.ontologies.Ontologies
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.rdf.toIri
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import java.net.URI

class SempodsTestFactory {

  @Inject
  private lateinit var podDao: PodDao

  @Inject
  private lateinit var podFacade: PodFacade

  @Inject
  private lateinit var sempodsUriBuilder: SempodsUriBuilder

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  @Inject
  private lateinit var podAccess: SempodsTestPodAccess

  /**
   * A pod owner: an email address, and that is genuinely all a pod knows about one.
   *
   * This used to be a full user row from an application framework's users collection. Every one of
   * the 57 call sites read exactly one field off it, `.email`, because that is what
   * [WebIdUriDeriver.deriveFromEmail] takes and `PodDbo.owner` stores. So the user domain was
   * deleted from this suite rather than reimplemented in it.
   */
  data class TestOwner(val email: String)

  fun newOwner(): TestOwner = TestOwner(email = "test-${randomId()}@test.com")

  internal fun newPod(
    name: String = "test-${randomId()}",
    ownerUser: TestOwner = newOwner(),
    createPublicContext: Boolean = true,
  ): PodDbo {
    val podDbo = podDao.insert(name = name, owner = webIdUriDeriver.deriveFromEmail(ownerUser.email))

    // TODO: creating additional context should be separated from pod creation,
    // to avoid side effects in tests that don't need it
    if (createPublicContext) {
      podFacade.createContext(
        podName = podDbo.name,
        contextUri = publicContextUri(podDbo.name),
        public = true,
        label = "",
        description = null,
      )
    }

    return podDbo
  }

  /**
   * The public context [newPod] creates — asked of the factory that created it, rather than
   * discovered by listing the pod's public contexts and taking the first.
   *
   * That listing was never the question a test wanted answered: it returns whatever happens to be
   * public, so a second public context would make the answer depend on set iteration order, and it
   * routed a fixture lookup through the pod service surface. A test that means "the context my
   * fixture set up" says so here.
   *
   * Only meaningful for a pod created with `createPublicContext = true`; for the others no such
   * context exists and this URI addresses nothing.
   */
  fun publicContextUri(podName: String): URI =
    sempodsUriBuilder.buildContext(podName, PUBLIC_CONTEXT_PATH)

  /**
   * An event resource URI in [podName]'s LOD namespace.
   *
   * The suite's one definition of the `events/` segment. It is a fixture convention and nothing
   * the pod server knows: the pod serves whatever path a resource was written under, so a test
   * that needs a *different* shape mints one with [SempodsUriBuilder.buildResourceUri] directly
   * rather than by extending this.
   */
  fun eventUri(podName: String, eventId: String = randomId()): URI =
    sempodsUriBuilder.buildResourceUri(podName, "events/$eventId")

  /**
   * Plants a `schema:Event` in [context] over HTTP and returns its URI.
   *
   * **Over the wire, not through the store**, and that is the whole reason this exists rather than
   * a `podFacade.put…` beside it: both halves of a test have to cross the surface a client crosses,
   * or a call that only works in-process passes here and fails at deploy time (`docs/testing.md`
   * §"Seeding a pod"). The tests whose *subject* is the store take [PodFacade] instead.
   *
   * A plain [org.eclipse.rdf4j.model.Model] rather than a typed projection: what almost every test
   * needs of a seeded event is a resource of a known type carrying a name it can grep the response
   * for. The three optional predicates are what this suite actually asserts on; a test wanting more
   * builds its own model and calls [org.sempods.client.SempodsPodClient.putResource].
   *
   * @param location an outgoing `schema:location` edge to another resource — for the deletion and
   *   reference tests, which need one resource to name another.
   */
  fun seedEvent(
    pod: String,
    eventUri: URI = eventUri(pod),
    context: URI,
    name: String? = null,
    description: String? = null,
    location: URI? = null,
  ): URI {
    val subject = eventUri.toIri()
    val contextIri = context.toIri()
    val model = LinkedHashModel()
    model.setNamespace(Ontologies.SCHEMA_ORG.NS)
    model.add(subject, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, contextIri)
    name?.let { model.add(subject, Ontologies.SCHEMA_ORG.Properties.name, Values.literal(it), contextIri) }
    description?.let {
      model.add(subject, Ontologies.SCHEMA_ORG.Properties.description, Values.literal(it), contextIri)
    }
    location?.let {
      model.add(subject, Ontologies.SCHEMA_ORG.Properties.location, it.toIri(), contextIri)
    }

    podAccess.clientFor(pod).putResource(resourceUri = eventUri, contextUri = context, model = model)
    return eventUri
  }

  companion object {

    /** Context path [newPod] registers as public. */
    private const val PUBLIC_CONTEXT_PATH = "tests/public"
  }
}
