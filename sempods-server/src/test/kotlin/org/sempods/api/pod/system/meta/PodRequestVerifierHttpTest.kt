package org.sempods.api.pod.system.meta

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.api.assertPodBearerChallenge
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.oauth.spi.PodAccessToken
import org.sempods.pods.oauth.spi.PodRequestVerifier
import org.sempods.pods.oauth.spi.PodResourceRequest
import org.sempods.pods.oauth.spi.PodTokenAuthentication
import org.sempods.pods.oauth.spi.PodTokenRejection
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The resource routes depend on the [PodRequestVerifier] boundary and on nothing behind it: another
 * implementation, wired without a change to an endpoint or to the grant policy, decides who is
 * calling. This proves the boundary — it is not a production replacement for the engine.
 */
class PodRequestVerifierHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  private fun url(podName: String) = "${SempodsModule.config.apiBaseUrl}$podName/_system/meta/date-modified"

  @Test
  fun `a verifier that answers no token makes the caller anonymous whatever the bearer says`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    // The real verifier turns this bearer into a 401; the alternative decides otherwise.
    requestVerifier.answerWith { _, _ -> PodTokenAuthentication.NoToken }

    val response = http.prepareGet(url(pod.name)).addHeader("Authorization", "Bearer not-a-real-jwt").execute()

    assertEquals(200, response.statusCode, response.responseBody)
  }

  // doc-example:start rejecting-verifier
  @Test
  fun `a verifier that rejects is answered with the pod's bearer challenge`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    requestVerifier.answerWith { _, _ -> PodTokenAuthentication.Rejected(PodTokenRejection.invalidToken) }

    assertPodBearerChallenge(http.prepareGet(url(pod.name)).execute(), pod.name)
  }
  // doc-example:end rejecting-verifier

  @Test
  fun `the verifier is handed the method, the externally known target and the headers`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val seen = CopyOnWriteArrayList<PodResourceRequest>()
    requestVerifier.answerWith { request, _ -> seen += request; PodTokenAuthentication.NoToken }

    http.prepareGet(url(pod.name)).addHeader("Authorization", "Bearer abc").execute()

    val request = seen.single()
    assertEquals("GET", request.method)
    assertEquals(url(pod.name), request.target.toString())
    assertEquals(listOf("Bearer abc"), request.header("authorization"))
  }

  @Test
  fun `the target keeps the path and query encoded as the client sent them`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val seen = CopyOnWriteArrayList<PodResourceRequest>()
    requestVerifier.answerWith { request, _ ->
      seen += request
      PodTokenAuthentication.Rejected(PodTokenRejection.invalidToken)
    }
    val target = "${SempodsModule.config.apiBaseUrl}${pod.name}/notes/a%3Bb%C3%A4?context=x%26y"

    http.preparePut(target).addHeader("Content-Type", "application/json").setBody("{}").execute()

    assertEquals(target, seen.single().target.toString())
  }

  @Test
  fun `a client session from the setup reaches its verifier, and seeding does not`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val seen = CopyOnWriteArrayList<PodResourceRequest>()
    requestVerifier.answerWith { request, _ -> seen += request; PodTokenAuthentication.NoToken }

    assertEquals(200, podAs(pod.name, bearer = "not-a-real-jwt").metadata().dateModified().status)
    assertEquals(listOf("Bearer not-a-real-jwt"), seen.single().header("authorization"))

    podAccess.podFor(pod.name).metadata().dateModified()
    assertEquals(1, seen.size, "the seeding session reached the setup's verifier")
  }

  @Test
  fun `a caller another verifier vouches for is authorized by the grant store, on REST and SPARQL alike`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val (granted, withheld) = listOf("granted", "withheld").map { path ->
      sempodsUriBuilder.buildContext(pod.name, path).also {
        podContextsDao.create(podId = checkNotNull(pod.id), contextUri = it.toString(), label = null, description = null, createdBy = "test")
      }
    }
    val webId = "https://id.test/${randomId()}"
    // The grant rows; the token it returns is never sent, since the verifier below decides alone.
    mintScopedToken(pod.name, listOf("$granted#read"), webId = webId)
    val resource = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/notes/${randomId()}")
    val name = Values.iri("https://schema.org/name")
    podFacade.putResourceModel(
      podName = pod.name,
      resourceUri = resource,
      model = LinkedHashModel().apply {
        add(Values.iri(resource.toString()), name, Values.literal("In granted"), Values.iri(granted.toString()))
        add(Values.iri(resource.toString()), name, Values.literal("In withheld"), Values.iri(withheld.toString()))
      },
    )
    requestVerifier.answerWith { _, _ ->
      PodTokenAuthentication.Verified(
        PodAccessToken(
          clientId = "did:web:test.example",
          sub = webId,
          clientType = null,
          scopeValues = emptySet(),
          jti = randomId(),
          issuedAt = Instant.now(),
        ),
      )
    }
    val caller = podAs(pod.name, bearer = "whatever-the-verifier-says")

    val read = caller.resources().getText(resource.toString())
    assertEquals(200, read.status)
    assertTrue("In granted" in read.body.orEmpty(), read.body)
    assertFalse("In withheld" in read.body.orEmpty(), read.body)

    val names = caller.sparql().select("SELECT ?name WHERE { <$resource> <$name> ?name }")
    assertEquals(listOf("In granted"), names.body?.column("name")?.map { it.value })
  }

  @Test
  fun `without an answer of its own a setup verifies with the real verifier`() = withSetup {
    val pod = sempodsTestFactory.newPod()

    assertPodBearerChallenge(
      http.prepareGet(url(pod.name)).addHeader("Authorization", "Bearer not-a-real-jwt").execute(),
      pod.name,
    )
  }
}
