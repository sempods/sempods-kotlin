package org.sempods.mcp.audit

import org.sempods.mcp.persist.AuditEvent
import org.sempods.mcp.persist.AuditEventType
import org.sempods.mcp.persist.AuditLogDao
import org.sempods.mcp.persist.PodKey
import org.sempods.commons.ratelimit.FakeClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure unit — the DAO is mocked; the sampling clock is hand-cranked. */
class AuditLogTest {

  private val user = "https://id.test/e/alice"

  @Test fun `an audit failure is swallowed - the emit never propagates`() {
    val dao = mockk<AuditLogDao>()
    every { dao.append(any()) } throws RuntimeException("mongo down")
    val audit = AuditLog(dao, retentionDays = 90)
    // Must not throw:
    audit.podConnected(user, "default", "https://pods.test/a", ok = true)
    audit.toolCall(user, "default", "find", listOf("https://pods.test/a"), outcome = "ok")
    verify(exactly = 2) { dao.append(any()) }
  }

  @Test fun `expiresAt is ts plus the configured retention`() {
    val dao = mockk<AuditLogDao>(relaxed = true)
    val clock = FakeClock(nowMs = 1_000_000)
    val audit = AuditLog(dao, retentionDays = 2, clock = clock)
    val captured = slot<AuditEvent>()
    audit.podDisconnected(user, "default", "https://pods.test/a")
    verify { dao.append(capture(captured)) }
    assertEquals(clock.nowMs, captured.captured.ts.time)
    assertEquals(clock.nowMs + 2L * 24 * 60 * 60 * 1000, captured.captured.expiresAt.time)
  }

  @Test fun `podTokenRefreshed carries the full pod key`() {
    val dao = mockk<AuditLogDao>(relaxed = true)
    val audit = AuditLog(dao, retentionDays = 90)
    val captured = slot<AuditEvent>()
    audit.podTokenRefreshed(PodKey(user, "private", "https://pods.test/a"), ok = false, detail = "identity_drift")
    verify { dao.append(capture(captured)) }
    with(captured.captured) {
      assertEquals(AuditEventType.POD_TOKEN_REFRESH, type)
      assertEquals(user, this.user)
      assertEquals("private", profile)
      assertEquals("https://pods.test/a", pod)
      assertEquals("error", outcome)
      assertEquals("identity_drift", detail)
    }
  }

  @Test fun `rateLimited is sampled to one row per user-profile per minute`() {
    val dao = mockk<AuditLogDao>(relaxed = true)
    val clock = FakeClock(nowMs = 1_000_000)
    val audit = AuditLog(dao, retentionDays = 90, clock = clock)
    repeat(5) { audit.rateLimited(user, "default") }
    verify(exactly = 1) { dao.append(match { it.type == AuditEventType.RATE_LIMITED }) }
    // A different profile has its own sampling bucket:
    audit.rateLimited(user, "private")
    verify(exactly = 2) { dao.append(match { it.type == AuditEventType.RATE_LIMITED }) }
    // The next minute admits one more row for the hammered profile:
    clock.nowMs += 60_000
    repeat(3) { audit.rateLimited(user, "default") }
    verify(exactly = 3) { dao.append(match { it.type == AuditEventType.RATE_LIMITED }) }
  }
}
