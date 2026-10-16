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

  override fun recommendations(limit: Int): List<RemoteTrack> {
    val page =
      runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) { YouTube.home().getOrNull() }
      }
        ?: throw RemoteException(
          "RECOMMENDATIONS_FAILED",
          502,
          "Recommendations failed. Check the phone's connection and try again.",
        )
    val tracks =
      page.sections
        .flatMap { it.items }
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
    val resolved =
      runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
          // Prefer the Safari-decodable AAC/M4A rendition.
          val extracted =
            try {
              InnerTubeXResolver.extract(trackId, maxKbps = 160, requireM4a = true)
            } catch (e: Exception) {
              Timber.tag(TAG).w(e, "M4A resolve failed for $trackId; trying any audio")
              null
            }
              ?: try {
                InnerTubeXResolver.extract(trackId, maxKbps = 160, requireM4a = false)
              } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Resolve failed for $trackId")
                null
              }
          if (extracted != null) {
            val mime = extracted.mimeType.substringBefore(';').trim().ifEmpty { "audio/mp4" }
            ResolvedSource(
              trackId = trackId,
              upstreamUrl = extracted.url,
              contentType = mime,
              expiresAtMs = upstreamExpiryMs(extracted.url),
              contentLength = null,
              headers = extracted.headers,
              resolverClient = extracted.clientName,
            )
          } else {
            newPipeFallback(trackId)
          }
        }
      }
    return resolved ?: throw RemoteException.resolutionFailed()
  }

  /**
   * Last-resort resolution through the BravePipe/NewPipe extractor the app
   * already ships (the same fallback its own player uses). Prefers m4a
   * itags (Safari), then any audio itag.
   */
  private fun newPipeFallback(trackId: String): ResolvedSource? {
    val streams =
      try {
        YouTube.getNewPipeStreamUrls(trackId)
      } catch (e: Exception) {
        Timber.tag(TAG).w(e, "NewPipe fallback failed for $trackId")
        return null
      }
    if (streams.isEmpty()) return null
    val byItag = streams.toMap()
    val m4aItag = M4A_ITAGS.firstOrNull { byItag.containsKey(it) }
    val chosenItag = m4aItag ?: byItag.keys.firstOrNull { it in KNOWN_AUDIO_ITAGS } ?: return null
    val url = byItag[chosenItag] ?: return null
    val headers =
      runCatching {
        InnerTubeXResolver.headersFor(url)
          ?: echo.music.iad1tya.utils.PlayerClient.forStreamUrl(url).mediaHeaders()
      }.getOrDefault(emptyMap())
    return ResolvedSource(
      trackId = trackId,
      upstreamUrl = url,
      contentType = if (m4aItag != null) "audio/mp4" else "audio/webm",
      expiresAtMs = upstreamExpiryMs(url),
      contentLength = null,
      headers = headers,
      resolverClient = "newpipe-fallback",
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
    private val M4A_ITAGS = listOf(141, 140, 139)
    private val KNOWN_AUDIO_ITAGS = setOf(141, 140, 139, 251, 250, 249, 171, 172, 234, 233)
  }
}
