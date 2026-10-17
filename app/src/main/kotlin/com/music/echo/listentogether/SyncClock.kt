package echo.music.iad1tya.listentogether

import kotlin.math.max

/**
 * Estimates the offset between this device's wall clock and the Listen
 * Together server's clock, so playback positions stamped in server time can
 * be projected onto "now" accurately.
 *
 * Why this matters: when the host presses play, the relay broadcasts the
 * track position together with the server time at which it was true. A guest
 * that projects that position forward using its own unsynchronised wall
 * clock inherits the full clock skew between the two devices — seconds of
 * error on many phones — and starts (and stays) noticeably behind or ahead.
 *
 * The estimate is built from every server timestamp that crosses the wire
 * (room state, sync state, playback actions). Each sample assumes the
 * timestamp was true half a round-trip ago; the sample observed with the
 * lowest RTT is the most trustworthy, so it wins until it grows stale.
 *
 * Pure Kotlin (no Android types) so it can be unit-tested on the JVM.
 */
class SyncClock(
  private val maxSampleAgeMs: Long = DEFAULT_MAX_SAMPLE_AGE_MS,
  private val maxAcceptableRttMs: Long = DEFAULT_MAX_ACCEPTABLE_RTT_MS,
) {
  private var bestOffsetMs: Long? = null
  private var bestRttMs: Long = Long.MAX_VALUE
  private var bestSampleAtLocalMs: Long = 0L

  /**
   * Records that [serverTimestampMs] (server clock) was observed at
   * [localReceiptMs] (local clock). [rttMs] is the latest measured
   * round-trip time to the server, or null when unknown.
   */
  @Synchronized
  fun onServerTimestamp(serverTimestampMs: Long, localReceiptMs: Long, rttMs: Long?) {
    if (serverTimestampMs <= 0L) return
    if (rttMs != null && rttMs > maxAcceptableRttMs) return
    // A non-positive RTT means "not measured yet" (the client reports 0
    // before the first pong). Such samples carry no transit information:
    // they may seed an estimate, but must never outrank a measured sample
    // or displace one.
    val measured = rttMs != null && rttMs > 0L
    val oneWayMs = if (measured) rttMs / 2 else 0L
    val offset = serverTimestampMs + oneWayMs - localReceiptMs
    val sampleRtt = if (measured) rttMs else Long.MAX_VALUE
    val stale = localReceiptMs - bestSampleAtLocalMs > maxSampleAgeMs
    if (bestOffsetMs == null || stale || sampleRtt <= bestRttMs) {
      bestOffsetMs = offset
      bestRttMs = sampleRtt
      bestSampleAtLocalMs = localReceiptMs
    }
  }

  /** Current best estimate of the server's wall-clock time. */
  @Synchronized fun serverNowMs(localNowMs: Long): Long = localNowMs + (bestOffsetMs ?: 0L)

  @Synchronized fun hasEstimate(): Boolean = bestOffsetMs != null

  /**
   * Projects a position that was true at [serverTimestampMs] (server clock)
   * forward to now. When paused, or when no timestamp is available, the
   * position is returned unchanged. Never returns a negative position, and
   * clock weirdness (a timestamp in the future) never rewinds the track.
   */
  fun adjustedPositionMs(
    positionMs: Long,
    serverTimestampMs: Long?,
    isPlaying: Boolean,
    localNowMs: Long,
  ): Long {
    if (!isPlaying) return max(0L, positionMs)
    val timestamp = serverTimestampMs ?: return max(0L, positionMs)
    if (timestamp <= 0L) return max(0L, positionMs)
    val elapsedMs = serverNowMs(localNowMs) - timestamp
    return max(0L, positionMs + max(0L, elapsedMs))
  }

  @Synchronized
  fun reset() {
    bestOffsetMs = null
    bestRttMs = Long.MAX_VALUE
    bestSampleAtLocalMs = 0L
  }

  companion object {
    const val DEFAULT_MAX_SAMPLE_AGE_MS = 5 * 60 * 1000L
    const val DEFAULT_MAX_ACCEPTABLE_RTT_MS = 2_000L
  }
}
