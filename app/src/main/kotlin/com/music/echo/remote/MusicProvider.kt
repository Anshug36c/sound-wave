package echo.music.iad1tya.remote

import java.io.InputStream

/**
 * Music search + source resolution for the remote web player.
 *
 * Implementations MUST NOT touch the mobile player (no local playback
 * engine, queue or service calls): resolving a stream URL is a pure
 * metadata operation. The Android implementation reuses the same
 * InnerTube/InnerTubeX extraction the local player uses, but stops at the
 * URL — playback happens in the browser, never on the phone.
 *
 * All methods are blocking and are called from bridge worker threads.
 */
interface MusicProvider {
  /** Search tracks. Throws [RemoteException] on failure. */
  fun search(query: String, limit: Int): List<RemoteTrack>

  /**
   * Home-feed recommendations (the same source the app's Home screen shows),
   * songs only. Throws [RemoteException] on failure.
   */
  fun recommendations(limit: Int): List<RemoteTrack>

  /** Metadata for a known track id, or null when unknown. */
  fun track(trackId: String): RemoteTrack?

  /**
   * Resolve a browser-playable source for [trackId]. Implementations should
   * prefer Safari-compatible AAC/M4A. Throws [RemoteException] on failure.
   */
  fun resolve(trackId: String): ResolvedSource
}

/** Opens upstream audio bytes for the streaming bridge. */
interface UpstreamFetcher {
  /**
   * Open [url] with [headers]. When [rangeHeader] is non-null it is forwarded
   * as the HTTP `Range` header (byte ranges are how the web player seeks).
   * With [headOnly] no body is returned.
   */
  fun open(
    url: String,
    headers: Map<String, String>,
    rangeHeader: String?,
    headOnly: Boolean,
  ): UpstreamResponse
}

class UpstreamResponse(
  val status: Int,
  val contentType: String?,
  /** Entity length for 200, range length for 206, -1 when unknown. */
  val contentLength: Long,
  val contentRange: String?,
  val acceptRanges: String?,
  val body: InputStream?,
) : AutoCloseable {
  override fun close() {
    try {
      body?.close()
    } catch (_: Exception) {
    }
  }
}
