package org.sempods.api.pod.system.meta

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.commons.okhttp.TestHttpClient
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

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
}
