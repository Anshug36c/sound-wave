package echo.music.iad1tya.remote

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import echo.music.iad1tya.utils.InnerTubeXResolver
import echo.music.iad1tya.utils.YTPlayerUtils
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * [MusicProvider] backed by SoundWave's existing YouTube Music search and
 * InnerTubeX stream extraction — the same resolution the local player uses,
 * minus playback. Extraction stops at a signed URL; the bridge proxies the
 * bytes, so the browser never sees the URL and the phone never plays a sound
 * for a remote request.
 *
 * M4A/AAC is requested first because iPhone Safari cannot decode WebM/Opus
 * in an <audio> element on older iOS versions; Opus is only a fallback and
 * is reported honestly via [ResolvedSource.safariCompatible].
 */
class YouTubeMusicProvider : MusicProvider {

  private val trackCache = ConcurrentHashMap<String, RemoteTrack>()

  override fun search(query: String, limit: Int): List<RemoteTrack> {
    val result =
      runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
          YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
        }
      } ?: throw RemoteException("SEARCH_FAILED", 502, "Search failed. Check the phone's connection and try again.")

    val tracks =
      result.items
        .filterIsInstance<SongItem>()
        .distinctBy { it.id }
        .take(limit)
        .map { item ->
          RemoteTrack(
            id = item.id,
            title = item.title,
            artist = item.artists.joinToString(", ") { it.name },
            album = item.album?.name,
            durationSec = item.duration,
            thumbnailUrl = item.thumbnail.takeIf { it.isNotBlank() },
            explicit = item.explicit,
          )
        }
    tracks.forEach { trackCache[it.id] = it }
    return tracks
  }

  override fun track(trackId: String): RemoteTrack? {
    trackCache[trackId]?.let { return it }
    val details =
      runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(METADATA_TIMEOUT_MS) {
          YTPlayerUtils.playerResponseForMetadata(trackId).getOrNull()?.videoDetails
        }
      } ?: return null
    val track =
      RemoteTrack(
        id = trackId,
        title = details.title ?: "",
        artist = details.author ?: "",
        durationSec = details.lengthSeconds.toIntOrNull(),
        thumbnailUrl =
          details.thumbnail.thumbnails.maxByOrNull { it.width ?: 0 }?.url,
      )
    if (track.title.isNotEmpty()) trackCache[trackId] = track
    return track
  }

  override fun resolve(trackId: String): ResolvedSource {
    val extracted =
      runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
          // Prefer the Safari-decodable AAC/M4A rendition.
          val m4a =
            try {
              InnerTubeXResolver.extract(trackId, maxKbps = 160, requireM4a = true)
            } catch (e: Exception) {
              Timber.tag(TAG).w(e, "M4A resolve failed for $trackId; trying any audio")
              null
            }
          m4a
            ?: try {
              InnerTubeXResolver.extract(trackId, maxKbps = 160, requireM4a = false)
            } catch (e: Exception) {
              Timber.tag(TAG).w(e, "Resolve failed for $trackId")
              null
            }
        }
      } ?: throw RemoteException.resolutionFailed()

    val mime = extracted.mimeType.substringBefore(';').trim().ifEmpty { "audio/mp4" }
    return ResolvedSource(
      trackId = trackId,
      upstreamUrl = extracted.url,
      contentType = mime,
      expiresAtMs = upstreamExpiryMs(extracted.url),
      contentLength = null,
      headers = extracted.headers,
      resolverClient = extracted.clientName,
    )
  }

  /** googlevideo signs expiry into the URL (`expire=` epoch seconds). */
  private fun upstreamExpiryMs(url: String): Long {
    val match = EXPIRY_REGEX.find(url)
    val epochSec = match?.groupValues?.get(1)?.toLongOrNull()
    return if (epochSec != null && epochSec > 0) epochSec * 1000L
    else System.currentTimeMillis() + DEFAULT_EXPIRY_MS
  }

  companion object {
    private const val TAG = "RemoteProvider"
    private const val SEARCH_TIMEOUT_MS = 25_000L
    private const val METADATA_TIMEOUT_MS = 20_000L
    private const val RESOLVE_TIMEOUT_MS = 60_000L
    private const val DEFAULT_EXPIRY_MS = 5 * 60 * 60 * 1000L
    private val EXPIRY_REGEX = Regex("""[?&]expire=(\d+)""")
  }
}
