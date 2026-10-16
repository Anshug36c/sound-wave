package echo.music.iad1tya.remote

/** A track as exchanged with the web player. Never carries a playable URL. */
data class RemoteTrack(
  val id: String,
  val title: String,
  val artist: String,
  val album: String? = null,
  val durationSec: Int? = null,
  val thumbnailUrl: String? = null,
  val explicit: Boolean = false,
) {
  fun toJson(): Map<String, Any?> =
    MiniJson.obj(
      "id" to id,
      "title" to title,
      "artist" to artist,
      "album" to album,
      "durationSec" to durationSec,
      "thumbnailUrl" to thumbnailUrl,
      "explicit" to explicit,
    )

  companion object {
    fun fromJson(map: Map<String, Any?>): RemoteTrack? {
      val id = map.objString("id") ?: return null
      return RemoteTrack(
        id = id,
        title = map.objString("title") ?: "",
        artist = map.objString("artist") ?: "",
        album = map.objString("album"),
        durationSec = map.objLong("durationSec")?.toInt(),
        thumbnailUrl = map.objString("thumbnailUrl"),
        explicit = map.objBool("explicit") ?: false,
      )
    }
  }
}

/**
 * A resolved upstream audio source. [upstreamUrl] and [headers] live only
 * inside the phone: the bridge proxies the bytes, the web player only ever
 * sees an expiring `/api/stream/<grant>` path on the phone itself.
 */
data class ResolvedSource(
  val trackId: String,
  val upstreamUrl: String,
  val contentType: String,
  /** Epoch millis after which [upstreamUrl] is expected to be rejected upstream. */
  val expiresAtMs: Long,
  val contentLength: Long? = null,
  val headers: Map<String, String> = emptyMap(),
  val resolverClient: String = "unknown",
) {
  /** True when the format is one iPhone Safari can decode natively in <audio>. */
  val safariCompatible: Boolean
    get() =
      contentType.startsWith("audio/mp4") ||
        contentType.startsWith("audio/mpeg") ||
        contentType.startsWith("audio/aac") ||
        contentType.startsWith("audio/wav") ||
        contentType.startsWith("audio/x-wav")
}

/** What the web player reports about its own (remote-only) playback state. */
data class RemotePlaybackReport(
  val track: RemoteTrack? = null,
  val queue: List<RemoteTrack> = emptyList(),
  val queueIndex: Int = -1,
  val playing: Boolean = false,
  val positionMs: Long = 0,
  val updatedAtMs: Long = 0,
) {
  fun toJson(): Map<String, Any?> =
    MiniJson.obj(
      "track" to track?.toJson(),
      "queue" to queue.map { it.toJson() },
      "queueIndex" to queueIndex,
      "playing" to playing,
      "positionMs" to positionMs,
      "updatedAtMs" to updatedAtMs,
    )

  companion object {
    fun fromJson(map: Map<String, Any?>, nowMs: Long): RemotePlaybackReport {
      val queue =
        map.objList("queue").orEmpty().mapNotNull { item ->
          (item as? Map<*, *>)?.let {
            @Suppress("UNCHECKED_CAST")
            RemoteTrack.fromJson(it as Map<String, Any?>)
          }
        }
      return RemotePlaybackReport(
        track = map.objMap("track")?.let { RemoteTrack.fromJson(it) },
        queue = queue.take(MAX_REPORTED_QUEUE),
        queueIndex = map.objLong("queueIndex")?.toInt() ?: -1,
        playing = map.objBool("playing") ?: false,
        positionMs = (map.objLong("positionMs") ?: 0L).coerceAtLeast(0L),
        updatedAtMs = nowMs,
      )
    }

    const val MAX_REPORTED_QUEUE = 100
  }
}

/** Structured bridge error. [code] is stable for clients; [message] is safe to display. */
class RemoteException(
  val code: String,
  val httpStatus: Int,
  message: String,
) : Exception(message) {
  companion object {
    fun unauthorized(message: String = "Missing or expired session. Pair again."): RemoteException =
      RemoteException("UNAUTHORIZED", 401, message)

    fun pairingInvalid(message: String = "Incorrect pairing code."): RemoteException =
      RemoteException("PAIRING_INVALID", 401, message)

    fun pairingExpired(
      message: String = "Pairing code expired. Ask SoundWave for a new code."
    ): RemoteException = RemoteException("PAIRING_EXPIRED", 401, message)

    fun rateLimited(message: String = "Too many requests. Wait a moment and retry."): RemoteException =
      RemoteException("RATE_LIMITED", 429, message)

    fun notFound(message: String = "Not found."): RemoteException =
      RemoteException("NOT_FOUND", 404, message)

    fun validation(message: String): RemoteException =
      RemoteException("VALIDATION", 400, message)

    fun resolutionFailed(
      message: String = "SoundWave could not resolve a playable source for this track."
    ): RemoteException = RemoteException("RESOLUTION_FAILED", 502, message)

    fun upstreamExpired(
      message: String = "The audio source expired. SoundWave will re-resolve it; press play again."
    ): RemoteException = RemoteException("UPSTREAM_EXPIRED", 502, message)

    fun busy(message: String = "SoundWave is busy with other streams. Try again shortly."): RemoteException =
      RemoteException("SERVER_BUSY", 503, message)

    fun originDenied(
      message: String = "This website origin is not allowed to use the bridge."
    ): RemoteException = RemoteException("ORIGIN_DENIED", 403, message)

    fun methodNotAllowed(): RemoteException =
      RemoteException("METHOD_NOT_ALLOWED", 405, "Method not allowed.")
  }

  fun toJson(): Map<String, Any?> =
    MiniJson.obj("error" to MiniJson.obj("code" to code, "message" to (message ?: code)))
}
