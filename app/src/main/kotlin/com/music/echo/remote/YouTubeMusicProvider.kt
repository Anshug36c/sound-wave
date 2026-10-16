package echo.music.iad1tya.remote

import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
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


  override fun homeSections(limitPerSection: Int): List<RemoteSection> {
    val page =
      runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(BROWSE_TIMEOUT_MS) { YouTube.home().getOrNull() }
      } ?: throw browseFailed("home feed")
    return page.sections.mapNotNull { section ->
      val items = section.items.mapNotNull { it.toRemoteItem() }.take(limitPerSection)
      if (items.isEmpty()) null else RemoteSection(section.title, section.label, items)
    }
  }

  override fun searchItems(query: String, type: String, limit: Int): List<RemoteItem> {
    val filter =
      when (type) {
        "album" -> YouTube.SearchFilter.FILTER_ALBUM
        "artist" -> YouTube.SearchFilter.FILTER_ARTIST
        "playlist" -> YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST
        else -> YouTube.SearchFilter.FILTER_SONG
      }
    val result =
      runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) { YouTube.search(query, filter).getOrNull() }
      } ?: throw RemoteException("SEARCH_FAILED", 502, "Search failed. Check the phone's connection and try again.")
    return result.items.mapNotNull { it.toRemoteItem() }.distinctBy { it.type + it.id }.take(limit)
  }

  override fun detail(kind: String, id: String): RemoteDetail {
    return when (kind) {
      "album" -> {
        val page =
          runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(BROWSE_TIMEOUT_MS) { YouTube.album(id).getOrNull() }
          } ?: throw browseFailed("album")
        val songs = page.songs.map { it.toRemoteTrack() }
        songs.forEach { trackCache[it.id] = it }
        val sections = buildList {
          if (page.otherVersions.isNotEmpty()) {
            add(RemoteSection("Other versions", null, page.otherVersions.mapNotNull { it.toRemoteItem() }))
          }
          if (page.releasesForYou.isNotEmpty()) {
            add(RemoteSection("You might also like", null, page.releasesForYou.mapNotNull { it.toRemoteItem() }))
          }
        }
        RemoteDetail(
          kind = "album",
          id = id,
          title = page.album.title,
          subtitle = page.album.artists?.joinToString(", ") { it.name },
          description = page.description,
          thumbnailUrl = page.album.thumbnail,
          songs = songs,
          sections = sections,
        )
      }
      "artist" -> {
        val page =
          runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(BROWSE_TIMEOUT_MS) { YouTube.artist(id).getOrNull() }
          } ?: throw browseFailed("artist")
        val sections =
          page.sections.mapNotNull { section ->
            val items = section.items.mapNotNull { it.toRemoteItem() }
            if (items.isEmpty()) null else RemoteSection(section.title, null, items)
          }
        val songs =
          page.sections.flatMap { it.items }.filterIsInstance<SongItem>().distinctBy { it.id }
            .map { it.toRemoteTrack() }
        songs.forEach { trackCache[it.id] = it }
        RemoteDetail(
          kind = "artist",
          id = id,
          title = page.artist.title,
          subtitle = page.subscriberCountText ?: page.monthlyListenerCount,
          description = page.description,
          thumbnailUrl = page.artist.thumbnail,
          songs = songs,
          sections = sections,
        )
      }
      else -> {
        val page =
          runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(BROWSE_TIMEOUT_MS) { YouTube.playlist(id).getOrNull() }
          } ?: throw browseFailed("playlist")
        val songs = page.songs.map { it.toRemoteTrack() }
        songs.forEach { trackCache[it.id] = it }
        val sections = buildList {
          val related = page.related.orEmpty().mapNotNull { it.toRemoteItem() }
          if (related.isNotEmpty()) add(RemoteSection("Related", null, related))
        }
        RemoteDetail(
          kind = "playlist",
          id = id,
          title = page.playlist.title,
          subtitle = page.playlist.author?.name,
          thumbnailUrl = page.playlist.thumbnail,
          songs = songs,
          sections = sections,
        )
      }
    }
  }

  override fun charts(): List<RemoteSection> {
    val page =
      runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(BROWSE_TIMEOUT_MS) { YouTube.getChartsPage().getOrNull() }
      } ?: throw browseFailed("charts")
    return page.sections.mapNotNull { section ->
      val items = section.items.mapNotNull { it.toRemoteItem() }
      if (items.isEmpty()) null else RemoteSection(section.title, section.chartType.name.lowercase(), items)
    }
  }

  override fun explore(): List<RemoteSection> {
    val page =
      runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(BROWSE_TIMEOUT_MS) { YouTube.explore().getOrNull() }
      } ?: throw browseFailed("explore")
    val sections = mutableListOf<RemoteSection>()
    if (page.newReleaseAlbums.isNotEmpty()) {
      sections +=
        RemoteSection("New releases", null, page.newReleaseAlbums.mapNotNull { it.toRemoteItem() })
    }
    page.moodAndGenres.forEach { group ->
      val items =
        group.items.map { item ->
          RemoteItem(
            type = "mood",
            id = item.endpoint.browseId + "|" + (item.endpoint.params ?: ""),
            title = item.title,
            extra = "#" + (item.stripeColor and 0xFFFFFF).toString(16).padStart(6, '0'),
          )
        }
      if (items.isNotEmpty()) sections += RemoteSection(group.title, null, items)
    }
    return sections
  }

  override fun browse(browseId: String, params: String?): List<RemoteSection> {
    val result =
      runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(BROWSE_TIMEOUT_MS) { YouTube.browse(browseId, params).getOrNull() }
      } ?: throw browseFailed("browse")
    return result.items.mapNotNull { group ->
      val items = group.items.mapNotNull { it.toRemoteItem() }
      if (items.isEmpty()) null else RemoteSection(group.title ?: result.title ?: "Browse", null, items)
    }
  }

  private fun browseFailed(what: String): RemoteException =
    RemoteException("BROWSE_FAILED", 502, "SoundWave could not load the $what. Check the phone's connection and try again.")

  private fun SongItem.toRemoteTrack(): RemoteTrack =
    RemoteTrack(
      id = id,
      title = title,
      artist = artists.joinToString(", ") { it.name },
      album = album?.name,
      durationSec = duration,
      thumbnailUrl = thumbnail.takeIf { it.isNotBlank() },
      explicit = explicit,
    )

  private fun YTItem.toRemoteItem(): RemoteItem? =
    when (this) {
      is SongItem ->
        RemoteItem("song", id, title, artists.joinToString(", ") { it.name }, thumbnail, duration, explicit, album?.name)
      is AlbumItem ->
        RemoteItem("album", browseId, title, artists?.joinToString(", ") { it.name }, thumbnail, null, explicit, year?.toString())
      is ArtistItem -> RemoteItem("artist", id, title, null, thumbnail)
      is PlaylistItem ->
        RemoteItem("playlist", id, title, author?.name, thumbnail, null, false, songCountText)
      else -> null
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
    private const val BROWSE_TIMEOUT_MS = 45_000L
    private const val METADATA_TIMEOUT_MS = 20_000L
    private const val RESOLVE_TIMEOUT_MS = 60_000L
    private const val DEFAULT_EXPIRY_MS = 5 * 60 * 60 * 1000L
    private val EXPIRY_REGEX = Regex("""[?&]expire=(\d+)""")
    private val M4A_ITAGS = listOf(141, 140, 139)
    private val KNOWN_AUDIO_ITAGS = setOf(141, 140, 139, 251, 250, 249, 171, 172, 234, 233)
  }
}
