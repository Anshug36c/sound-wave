package echo.music.iad1tya.listentogether

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Audio bridge, host side.
 *
 * Listen Together normally syncs playback *state* only, so a guest that doesn't own a track
 * (e.g. Soundwave Web in a browser) can't hear it. When the host opts in, this publisher reads the
 * currently playing **local** track's bytes and pushes them over the same Listen Together
 * WebSocket as `audio_manifest` + `audio_chunk` frames, which the web guest decodes and plays.
 *
 * Safety/privacy: only `content:`/`file:` sources (the user's own library) are ever published;
 * provider/http streams (YouTube etc.) are skipped. Publishing stops on track change, leave, or
 * disconnect.
 */
object AudioBridgePublisher {
  private const val TAG = "AudioBridge"
  private const val CHUNK = 64 * 1024

  private var job: Job? = null
  private var currentTrackId: String? = null

  fun stop() {
    job?.cancel()
    job = null
    currentTrackId = null
  }

  fun publishIfLocal(
    context: Context,
    client: ListenTogetherClient,
    trackId: String,
    uri: Uri?,
    durationMs: Long,
  ) {
    if (job?.isActive == true && currentTrackId == trackId) return
    stop()

    val scheme = uri?.scheme
    if (scheme != "content" && scheme != "file") {
      Timber.tag(TAG).d("Source is not local ($scheme); audio bridge skipped")
      return
    }

    job =
      CoroutineScope(Dispatchers.IO).launch {
        try {
          val resolver = context.contentResolver
          val size = resolver.openFileDescriptor(uri!!, "r")?.use { it.statSize } ?: -1L
          if (size <= 0) {
            Timber.tag(TAG).w("Could not stat $uri; audio bridge skipped")
            return@launch
          }
          val mime = resolver.getType(uri) ?: guessMime(uri)
          client.sendAudioManifest(trackId, mime, size, durationMs)
          currentTrackId = trackId

          var seq = 0
          resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
              val n = input.read(buf)
              if (n <= 0) break
              client.sendAudioChunk(seq, buf.copyOf(n))
              seq++
            }
          }
          Timber.tag(TAG).i("Published $seq chunks ($size bytes) for $trackId")
        } catch (e: Exception) {
          Timber.tag(TAG).e(e, "Audio publish failed")
        }
      }
  }

  private fun guessMime(uri: Uri): String =
    when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
      "mp3" -> "audio/mpeg"
      "m4a", "aac" -> "audio/mp4"
      "flac" -> "audio/flac"
      "ogg", "opus" -> "audio/ogg"
      "wav" -> "audio/wav"
      else -> "audio/mpeg"
    }
}
