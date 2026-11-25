package org.sempods.api.system.admin.pods

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.WebApplicationException
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.sempods.SempodsFacade
import org.sempods.admin.AdminAuthorization
import org.sempods.admin.AdminAuthorizer
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import kotlin.test.assertEquals

/**
 * The owner-checked delete when another delete of the same pod removes its row first — the
 * interleaving an HTTP test cannot place: both read the row, and only one removes it.
 */
class AdminPodsEndpointTest {

  private val webIdUriDeriver = WebIdUriDeriver("https://id.example")
  private val owner = webIdUriDeriver.deriveFromEmail("alice@example.org")
  private val podId = ObjectId()
  private val checked = PodDbo(id = podId, name = "alice", owner = owner)

  private val sempodsFacade = mockk<SempodsFacade>()
  private val podDao = mockk<PodDao>()

  private val endpoint = AdminPodsEndpoint(
    sempodsFacade = sempodsFacade,
    podDao = podDao,
    podFacade = mockk(),
    webIdUriDeriver = webIdUriDeriver,
    podServiceClientProvisioning = mockk(),
    sempodsUriBuilder = mockk(),
    adminAuthorizer = object : AdminAuthorizer {
      override fun authorize(authorizationHeader: String?) = AdminAuthorization.Authorized("admin")
    },
  )

  private fun delete(): Int = try {
    endpoint.deletePod(authorization = "Bearer admin", pod = "alice", owners = listOf(owner)).status
  } catch (e: WebApplicationException) {
    e.response.status
  }

  @Test
  fun `the delete that removes the row answers 204`() {
    every { podDao.fetchByName("alice") } returns checked
    every { sempodsFacade.deletePod(podId) } returns true

    assertEquals(204, delete())
  }

  @Test
  fun `the delete that finds the row gone answers 404 when no pod holds the name`() {
    every { podDao.fetchByName("alice") } returnsMany listOf(checked, null)
    every { sempodsFacade.deletePod(podId) } returns false

    assertEquals(404, delete())
  }

  @Test
  fun `the delete that finds the row gone answers 412 when a pod was created under the name since`() {
    every { podDao.fetchByName("alice") } returnsMany listOf(checked, PodDbo(id = ObjectId(), name = "alice", owner = owner))
    every { sempodsFacade.deletePod(podId) } returns false

    assertEquals(412, delete())
  }

  @Test
  fun `a refused delete deletes nothing`() {
    every { podDao.fetchByName("alice") } returns checked.copy(owner = webIdUriDeriver.deriveFromEmail("bob@example.org"))

    assertEquals(412, delete())
    verify(exactly = 0) { sempodsFacade.deletePod(any<ObjectId>()) }
  }
}
