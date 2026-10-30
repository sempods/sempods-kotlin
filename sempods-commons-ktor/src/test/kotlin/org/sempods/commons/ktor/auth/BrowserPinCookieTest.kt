package org.sempods.commons.ktor.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pin cookie's name follows the sign-in's `state`, and a `state` that cannot be one names none. */
class BrowserPinCookieTest {

  private val pin = BrowserPinCookie(namePrefix = "pin_", path = "/cb", secure = true, maxAge = Duration.ofMinutes(15))

  private val state = "AbC-def_0123456789abcdefghijklmnopqrstuvwxy"

  @Test
  fun `a pin is set, read back and withdrawn under the state's name`() = testApplication {
    application {
      routing {
        get("/start") { pin.set(call, state, "the-pin"); call.respondText("") }
        get("/cb") {
          val presented = pin.presented(call, call.request.queryParameters["state"])
          pin.clear(call, call.request.queryParameters["state"])
          call.respondText(presented ?: "none")
        }
      }
    }

    val set = client.get("/start").headers.getAll(HttpHeaders.SetCookie).orEmpty().single()
    assertTrue(set.startsWith("pin_$state=the-pin;"), set)
    assertTrue("Max-Age=900" in set && "Path=/cb" in set && "HttpOnly" in set && "SameSite=Lax" in set, set)

    val back = client.get("/cb?state=$state") { header(HttpHeaders.Cookie, "pin_$state=the-pin") }
    assertEquals("the-pin", back.bodyAsText())
    assertTrue(back.headers.getAll(HttpHeaders.SetCookie).orEmpty().single().contains("Max-Age=0"))
  }

  @Test
  fun `a state that cannot be a cookie name names no cookie, and nothing is rendered for it`() = testApplication {
    application {
      routing {
        get("/cb") {
          val state = call.request.queryParameters["state"]
          pin.clear(call, state)
          call.respondText(pin.presented(call, state) ?: "none")
        }
      }
    }

    for (junk in listOf(null, "", "a;b", "a=b", "a%20b", "%C3%A4", "x".repeat(65))) {
      val response = client.get(if (junk == null) "/cb" else "/cb?state=$junk")
      assertEquals(HttpStatusCode.OK, response.status, "state '$junk'")
      assertEquals("none", response.bodyAsText(), "state '$junk'")
      assertNull(response.headers[HttpHeaders.SetCookie], "state '$junk'")
    }
  }
}
