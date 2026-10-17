package echo.music.iad1tya.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest's playback position is only as good as its estimate of the
 * server's clock. These tests pin the estimation and projection behaviour
 * that keeps guests starting (and staying) where the host actually is.
 */
class SyncClockTest {

  @Test
  fun noEstimateMeansNoAdjustment() {
    val clock = SyncClock()
    assertFalse(clock.hasEstimate())
    // Without a server timestamp, position is returned unchanged.
    assertEquals(10_000L, clock.adjustedPositionMs(10_000L, null, isPlaying = true, localNowMs = 50_000L))
    assertEquals(50_000L, clock.serverNowMs(50_000L))
  }

  @Test
  fun skewedGuestClockIsCorrected() {
    val clock = SyncClock()
    // Server clock is 4s ahead of the guest's clock. A server timestamp of
    // 104_000 arrives when the guest's clock reads 100_000 (RTT 100ms, so
    // the timestamp was true ~50ms before receipt).
    clock.onServerTimestamp(serverTimestampMs = 104_000L, localReceiptMs = 100_000L, rttMs = 100L)
    assertTrue(clock.hasEstimate())
    // Guest local 101_000 == server ~105_050.
    assertEquals(105_050L, clock.serverNowMs(101_000L))
    // Position 20_000 was true at server 104_000; 1.05s of playback later
    // the room is at 21_050 — with the old local-clock math the skewed
    // guest would have computed a negative elapsed and started at 20_000.
    assertEquals(
      21_050L,
      clock.adjustedPositionMs(20_000L, serverTimestampMs = 104_000L, isPlaying = true, localNowMs = 101_000L),
    )
  }

  @Test
  fun pausedPositionIsNeverProjected() {
    val clock = SyncClock()
    clock.onServerTimestamp(serverTimestampMs = 104_000L, localReceiptMs = 100_000L, rttMs = 100L)
    assertEquals(
      20_000L,
      clock.adjustedPositionMs(20_000L, serverTimestampMs = 104_000L, isPlaying = false, localNowMs = 130_000L),
    )
  }

  @Test
  fun futureTimestampNeverRewinds() {
    val clock = SyncClock()
    clock.onServerTimestamp(serverTimestampMs = 100_000L, localReceiptMs = 100_000L, rttMs = 0L)
    // Timestamp "in the future" relative to server-now must not rewind.
    assertEquals(
      5_000L,
      clock.adjustedPositionMs(5_000L, serverTimestampMs = 120_000L, isPlaying = true, localNowMs = 101_000L),
    )
    assertEquals(0L, clock.adjustedPositionMs(-500L, serverTimestampMs = null, isPlaying = false, localNowMs = 0L))
  }

  @Test
  fun lowestRttSampleWinsUntilStale() {
    val clock = SyncClock(maxSampleAgeMs = 10_000L)
    // A sloppy high-RTT sample first: offset = 100_000 + 900 - 100_000 = 900.
    clock.onServerTimestamp(serverTimestampMs = 100_000L, localReceiptMs = 100_000L, rttMs = 1800L)
    assertEquals(900L, clock.serverNowMs(100_000L) - 100_000L)
    // A cleaner sample replaces it: offset = 102_000 + 50 - 101_000 = 1050.
    clock.onServerTimestamp(serverTimestampMs = 102_000L, localReceiptMs = 101_000L, rttMs = 100L)
    assertEquals(1050L, clock.serverNowMs(101_000L) - 101_000L)
    // An even worse sample shortly after must not displace the good one.
    clock.onServerTimestamp(serverTimestampMs = 104_000L, localReceiptMs = 102_000L, rttMs = 1500L)
    assertEquals(1050L, clock.serverNowMs(102_000L) - 102_000L)
    // But once the good sample is stale, the next sample takes over.
    clock.onServerTimestamp(serverTimestampMs = 120_000L, localReceiptMs = 115_000L, rttMs = 1500L)
    assertEquals(120_000L + 750L - 115_000L, clock.serverNowMs(115_000L) - 115_000L)
  }

  @Test
  fun absurdRttSamplesAreIgnored() {
    val clock = SyncClock()
    clock.onServerTimestamp(serverTimestampMs = 100_000L, localReceiptMs = 100_000L, rttMs = 60_000L)
    assertFalse(clock.hasEstimate())
  }

  @Test
  fun unmeasuredRttDoesNotPinTheEstimate() {
    val clock = SyncClock(maxSampleAgeMs = 10_000L)
    // Before the first pong the client reports RTT 0 ("unmeasured"). That
    // sample seeds an estimate (offset 1000, no transit compensation)...
    clock.onServerTimestamp(serverTimestampMs = 101_000L, localReceiptMs = 100_000L, rttMs = 0L)
    assertEquals(1000L, clock.serverNowMs(100_000L) - 100_000L)
    // ...but the first measured sample must immediately refine it
    // (offset = 103_000 + 100 - 102_000 = 1100).
    clock.onServerTimestamp(serverTimestampMs = 103_000L, localReceiptMs = 102_000L, rttMs = 200L)
    assertEquals(1100L, clock.serverNowMs(102_000L) - 102_000L)
    // And a later unmeasured sample must not displace the measured one.
    clock.onServerTimestamp(serverTimestampMs = 120_000L, localReceiptMs = 103_000L, rttMs = null)
    assertEquals(1100L, clock.serverNowMs(103_000L) - 103_000L)
  }

  @Test
  fun resetClearsEstimate() {
    val clock = SyncClock()
    clock.onServerTimestamp(serverTimestampMs = 100_000L, localReceiptMs = 99_000L, rttMs = 100L)
    assertTrue(clock.hasEstimate())
    clock.reset()
    assertFalse(clock.hasEstimate())
  }
}
