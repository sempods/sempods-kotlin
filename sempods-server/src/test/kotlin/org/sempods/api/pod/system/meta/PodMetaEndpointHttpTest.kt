package org.sempods.api.pod.system.meta

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.client.SempodsOkHttp
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsSession
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.commons.okhttp.TestHttpClient
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodMetaEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podDao: PodDao

  private val objectMapper = ObjectMapper()

  private fun dateModifiedUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/_system/meta/date-modified"

  private fun get(podName: String) =
    http.prepareGet(dateModifiedUrl(podName)).execute()

  @Test
  fun `returns the stored dateModified once a write has been recorded`() {
    val pod = sempodsTestFactory.newPod()
    val stamp = Instant.parse("2026-05-20T10:15:30Z")
    podDao.updateLastModifiedAt(name = pod.name, lastModifiedAt = stamp)

    val response = get(pod.name)

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
    val value = objectMapper.readTree(response.responseBody).path("dateModified").asText()
    assertEquals(stamp, Instant.parse(value))
  }

  @Test
  fun `returns 404 for an unknown pod`() {
    val response = get("does-not-exist-pod")

    assertEquals(404, response.statusCode)
  }

  /** The client core against the served route, so the route string the core carries cannot drift from this one. */
  @Test
  fun `the client core reads existence and dateModified from this route`() {
    val pod = sempodsTestFactory.newPod()
    val stamp = Instant.parse("2026-05-20T10:15:30.123Z")
    podDao.updateLastModifiedAt(name = pod.name, lastModifiedAt = stamp)
    val client = SempodsOkHttp.install(OkHttpClient.Builder()).build()
    fun metadata(podName: String) =
      SempodsPod(SempodsSession(SempodsPodBase.of("${SempodsModule.config.apiBaseUrl}$podName")), client).metadata()

    try {
      assertTrue(metadata(pod.name).exists())
      assertEquals(stamp, metadata(pod.name).dateModified().body?.dateModified)
      assertFalse(metadata("does-not-exist-pod").exists())
    } finally {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
  }
}
