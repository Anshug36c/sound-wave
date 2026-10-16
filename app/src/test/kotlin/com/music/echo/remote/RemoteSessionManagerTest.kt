package echo.music.iad1tya.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteSessionManagerTest {

  private fun expectRemote(code: String, block: () -> Unit) {
    try {
      block()
      fail("Expected RemoteException($code)")
    } catch (e: RemoteException) {
      assertEquals(code, e.code)
    }
  }

  @Test
  fun pairingIssuesSingleUseCodeAndSession() {
    var now = 1_000_000L
    val manager = RemoteSessionManager(clock = { now })
    val (code, _) = manager.currentPairingCode()
    assertEquals(6, code.length)
    assertTrue(code.all { it.isDigit() })

    expectRemote("PAIRING_INVALID") { manager.pair("1.1.1.1", "000000", "ua") }

    val session = manager.pair("1.1.1.1", code, "ua")
    assertEquals(64, session.token.length)

    // The used code must not pair a second browser.
    expectRemote("PAIRING_INVALID") { manager.pair("1.1.1.1", code, "ua") }

    assertEquals(session.token, manager.authenticate(session.token).token)

    now += RemoteSessionManager.SESSION_TTL_MS + 1
    expectRemote("UNAUTHORIZED") { manager.authenticate(session.token) }
  }

  @Test
  fun pairingLocksOutAfterRepeatedFailuresPerIp() {
    val manager = RemoteSessionManager(clock = { 5_000_000L })
    val (code, _) = manager.currentPairingCode()
    repeat(RemoteSessionManager.MAX_PAIRING_FAILURES_PER_IP) {
      expectRemote("PAIRING_INVALID") { manager.pair("9.9.9.9", "111111", "ua") }
    }
    // Even the correct code is refused during the lockout window.
    expectRemote("RATE_LIMITED") { manager.pair("9.9.9.9", code, "ua") }
    // …but a different network is unaffected.
    manager.pair("8.8.8.8", code, "ua")
  }

  @Test
  fun expiredPairingCodeIsRejected() {
    var now = 2_000_000L
    val manager = RemoteSessionManager(clock = { now })
    val (code, _) = manager.currentPairingCode()
    now += RemoteSessionManager.PAIRING_CODE_TTL_MS + 1
    expectRemote("PAIRING_EXPIRED") { manager.pair("1.1.1.1", code, "ua") }
  }

  @Test
  fun sessionsAndPairingCodeSurviveExportImport() {
    var now = 4_000_000L
    val first = RemoteSessionManager(clock = { now })
    val (code, _) = first.currentPairingCode()
    val session = first.pair("1.1.1.1", code, "ua")
    val (nextCode, _) = first.currentPairingCode()

    // Simulate the app process being killed and restarted.
    val restored = RemoteSessionManager(clock = { now })
    restored.importState(first.exportState())

    assertEquals(session.token, restored.authenticate(session.token).token)
    assertEquals("pairing code must survive a process restart", nextCode, restored.currentPairingCode().first)

    now += RemoteSessionManager.SESSION_TTL_MS + 1
    val late = RemoteSessionManager(clock = { now })
    late.importState(first.exportState())
    expectRemote("UNAUTHORIZED") { late.authenticate(session.token) }
  }

  @Test
  fun streamGrantsAreBoundToTheirSession() {
    val manager = RemoteSessionManager(clock = { 3_000_000L })
    val (code, _) = manager.currentPairingCode()
    val alice = manager.pair("1.1.1.1", code, "ua")
    val source =
      ResolvedSource("track1", "https://upstream.invalid/x", "audio/mp4", Long.MAX_VALUE)
    val grant = manager.createGrant(alice, source)
    assertEquals(grant.id, manager.grant(alice, grant.id).id)

    val (code2, _) = manager.currentPairingCode()
    val mallory = manager.pair("2.2.2.2", code2, "ua")
    expectRemote("UNAUTHORIZED") { manager.grant(mallory, grant.id) }

    manager.revoke(alice.token)
    expectRemote("UNAUTHORIZED") { manager.authenticate(alice.token) }
  }
}
