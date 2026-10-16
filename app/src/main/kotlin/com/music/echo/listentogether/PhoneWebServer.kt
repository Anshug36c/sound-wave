package echo.music.iad1tya.listentogether

import android.content.Context
import android.os.Handler
import android.os.Looper
import echo.music.iad1tya.playback.PlayerConnection
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import timber.log.Timber

/** What the settings screen shows while the phone is acting as a server. */
data class PhoneWebState(
  val running: Boolean = false,
  val port: Int = 0,
  val code: String = "",
  val address: String = "",
  val error: String? = null,
)

/**
 * "Serve to web" — the phone becomes a tiny HTTP server on the local network so Soundwave Web can
 * play this phone's music (Mode A). No relay, no internet, no account: same Wi‑Fi only.
 *
 * Endpoints (all `/api/*` and `/api/audio` require `?code=<pairing code>`):
 * - `GET  /`                 the bundled website (`assets/web/`), so the browser page is served by
 *                            the phone itself. This matters because an https page is not allowed to
 *                            reach a plain-http device, so the page has to come from the phone.
 * - `GET  /api/state`        transport snapshot (playing, positionMs, durationMs, track, queue)
 * - `POST /api/action`       {action: play|pause|seek|next|prev|playTrack, positionMs?, index?}
 * - `GET  /api/audio`        the current local track's bytes, Range-capable, which the browser plays
 *
 * Player access: ExoPlayer must be touched on the main thread, so a main-thread sampler refreshes
 * [snapshot] 4x/second and every incoming action is posted to the main looper. Only `content:`/
 * `file:` sources (the user's own library) can be served; provider streams are refused.
 */
object PhoneWebServer {
  private const val TAG = "PhoneWebServer"
  private const val WEB_ROOT = "web"
  private const val DEFAULT_PORT = 8787
  private const val SAMPLE_INTERVAL_MS = 250L
  private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

  private data class Snapshot(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val uri: android.net.Uri? = null,
    val queue: List<Pair<String, String>> = emptyList(),
  )

  private val _state = MutableStateFlow(PhoneWebState())
  val state: StateFlow<PhoneWebState> = _state

  private val running = AtomicBoolean(false)
  private val snapshot = AtomicReference(Snapshot())
  private val main = Handler(Looper.getMainLooper())

  @Volatile private var connection: PlayerConnection? = null
  @Volatile private var appContext: Context? = null

  private var serverSocket: ServerSocket? = null
  private var acceptThread: Thread? = null

  private val sampler =
    object : Runnable {
      override fun run() {
        captureSnapshot()
        if (running.get()) main.postDelayed(this, SAMPLE_INTERVAL_MS)
      }
    }

  /** Called by [ListenTogetherManager.setPlayerConnection] so the server can drive the player. */
  fun attach(pc: PlayerConnection?) {
    connection = pc
    captureSnapshot()
  }

  fun start(context: Context, port: Int = DEFAULT_PORT) {
    if (running.get()) return
    val app = context.applicationContext
    appContext = app
    try {
      val socket = ServerSocket(port)
      serverSocket = socket
      running.set(true)
      _state.value =
        PhoneWebState(
          running = true,
          port = socket.localPort,
          code = newCode(),
          address = lanAddress(socket.localPort),
        )
      main.post(sampler)
      acceptThread =
        Thread {
          while (running.get()) {
            val client =
              try {
                socket.accept()
              } catch (e: Exception) {
                null
              }
            if (client == null) break
            val code = _state.value.code
            Thread { handle(app, client, code) }.apply { isDaemon = true }.start()
          }
        }
          .apply {
            isDaemon = true
            name = "PhoneWebServer"
            start()
          }
      Timber.tag(TAG).i("Serving Soundwave Web at ${_state.value.address}")
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Could not start the local web server")
      running.set(false)
      _state.value = PhoneWebState(error = e.message ?: "Could not start server")
    }
  }

  fun stop() {
    running.set(false)
    main.removeCallbacks(sampler)
    try {
      serverSocket?.close()
    } catch (_: Exception) {}
    acceptThread?.interrupt()
    serverSocket = null
    acceptThread = null
    _state.value = PhoneWebState()
    Timber.tag(TAG).i("Stopped")
  }

  // ---------------------------------------------------------------- HTTP plumbing

  private fun handle(app: Context, client: Socket, code: String) {
    try {
      client.use { sock ->
        sock.soTimeout = 30_000
        val out = sock.getOutputStream()
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val rawPath = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
          val line = reader.readLine() ?: break
          if (line.isEmpty()) break
          val idx = line.indexOf(':')
          if (idx > 0) {
            headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
          }
        }

        val qIdx = rawPath.indexOf('?')
        val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
        val params = if (qIdx >= 0) parseQuery(rawPath.substring(qIdx + 1)) else emptyMap()

        if (path.startsWith("/api") && params["code"] != code) {
          sendText(out, 401, "bad code")
          return
        }

        when {
          path == "/api/state" -> sendJson(out, stateJson())
          path == "/api/action" && method == "POST" -> {
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body =
              if (len > 0) {
                val buf = CharArray(len)
                var read = 0
                while (read < len) {
                  val n = reader.read(buf, read, len - read)
                  if (n <= 0) break
                  read += n
                }
                String(buf, 0, read)
              } else {
                ""
              }
            applyAction(body)
            sendJson(out, stateJson())
          }
          path == "/api/audio" -> sendAudio(app, out, headers["range"])
          else -> sendStatic(app, out, path)
        }
      }
    } catch (e: Exception) {
      Timber.tag(TAG).d("Connection ended: ${e.message}")
    }
  }

  private fun sendHead(out: OutputStream, status: String, contentType: String, extra: String = "") {
    val head = "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nConnection: close\r\n${extra}\r\n"
    out.write(head.toByteArray(Charsets.UTF_8))
    out.flush()
  }

  private fun sendText(out: OutputStream, code: Int, body: String) {
    val status = if (code == 200) "200 OK" else "$code Error"
    val bytes = body.toByteArray(Charsets.UTF_8)
    sendHead(out, status, "text/plain; charset=utf-8", "Content-Length: ${bytes.size}\r\n")
    out.write(bytes)
    out.flush()
  }

  private fun sendJson(out: OutputStream, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    sendHead(out, "200 OK", "application/json", "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\n")
    out.write(bytes)
    out.flush()
  }

  private fun sendStatic(app: Context, out: OutputStream, path: String) {
    var rel = path.removePrefix("/")
    if (rel.isEmpty()) rel = "index.html"
    if (rel.contains("..")) {
      sendText(out, 404, "not found")
      return
    }
    val bytes = readAsset(app, "$WEB_ROOT/$rel") ?: readAsset(app, "$WEB_ROOT/index.html")
    if (bytes == null) {
      sendText(out, 500, "The website bundle is missing from this build (assets/$WEB_ROOT).")
      return
    }
    sendHead(out, "200 OK", contentTypeFor(rel), "Content-Length: ${bytes.size}\r\n")
    out.write(bytes)
    out.flush()
  }

  private fun readAsset(app: Context, name: String): ByteArray? =
    try {
      app.assets.open(name).use { it.readBytes() }
    } catch (e: Exception) {
      null
    }

  private fun contentTypeFor(name: String): String =
    when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
      "html" -> "text/html; charset=utf-8"
      "js", "mjs" -> "application/javascript"
      "css" -> "text/css"
      "json", "webmanifest" -> "application/json"
      "svg" -> "image/svg+xml"
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      "ico" -> "image/x-icon"
      "woff2" -> "font/woff2"
      "wav" -> "audio/wav"
      else -> "application/octet-stream"
    }

  /** Streams the current local track, honouring HTTP Range so the browser can seek. */
  private fun sendAudio(app: Context, out: OutputStream, range: String?) {
    val uri = snapshot.get().uri
    val scheme = uri?.scheme
    if (uri == null || (scheme != "content" && scheme != "file")) {
      sendText(out, 404, "This track cannot be streamed (only local library files can).")
      return
    }
    val resolver = app.contentResolver
    val mime = resolver.getType(uri) ?: "audio/mpeg"
    val size =
      try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
      } catch (e: Exception) {
        -1L
      }
    val input =
      try {
        resolver.openInputStream(uri)
      } catch (e: Exception) {
        null
      }
    if (input == null) {
      sendText(out, 404, "Could not open the track.")
      return
    }

    input.use { ins ->
      var start = 0L
      var end = if (size > 0) size - 1 else -1L
      var partial = false
      if (range != null && size > 0) {
        val m = Regex("bytes=(\\d*)-(\\d*)").find(range)
        if (m != null) {
          val g = m.groupValues
          val s = g[1].toLongOrNull()
          val e = g[2].toLongOrNull()
          start = s ?: 0L
          end = e ?: (size - 1)
          if (start >= size || start > end) {
            sendHead(out, "416 Range Not Satisfiable", mime, "Content-Range: bytes */$size\r\n")
            return
          }
          partial = start > 0
        }
      }

      var toSkip = start
      while (toSkip > 0) {
        val n = ins.skip(toSkip)
        if (n > 0) {
          toSkip -= n
        } else {
          if (ins.read() < 0) break
          toSkip--
        }
      }

      if (partial && size > 0) {
        sendHead(
          out,
          "206 Partial Content",
          mime,
          "Accept-Ranges: bytes\r\nContent-Range: bytes $start-$end/$size\r\n" +
            "Content-Length: ${end - start + 1}\r\nCache-Control: no-store\r\n",
        )
      } else {
        val len = if (size > 0) "Content-Length: ${size - start}\r\n" else ""
        sendHead(out, "200 OK", mime, "Accept-Ranges: bytes\r\n${len}Cache-Control: no-store\r\n")
      }

      val buf = ByteArray(64 * 1024)
      var remaining = if (end >= 0) end - start + 1 else Long.MAX_VALUE
      while (remaining > 0) {
        val want = if (remaining < buf.size) remaining.toInt() else buf.size
        val n = ins.read(buf, 0, want)
        if (n <= 0) break
        out.write(buf, 0, n)
        remaining -= n
      }
      out.flush()
    }
  }

  // ---------------------------------------------------------------- player bridge

  private fun stateJson(): String {
    val s = snapshot.get()
    val track = JSONObject().put("id", s.trackId).put("title", s.title).put("artist", s.artist)
    val queue = org.json.JSONArray()
    s.queue.forEach { (id, title) ->
      queue.put(JSONObject().put("id", id).put("title", title).put("artist", ""))
    }
    return JSONObject()
      .put("playing", s.playing)
      .put("positionMs", s.positionMs)
      .put("durationMs", s.durationMs)
      .put("volume", 1.0)
      .put("track", if (s.trackId.isEmpty()) JSONObject.NULL else track)
      .put("queue", queue)
      .toString()
  }

  private fun applyAction(body: String) {
    val json =
      try {
        JSONObject(body)
      } catch (e: Exception) {
        return
      }
    val action = json.optString("action")
    val positionMs = json.optLong("positionMs", -1L)
    val index = json.optInt("index", -1)
    main.post {
      val pc = connection ?: return@post
      try {
        when (action) {
          "play" -> pc.play()
          "pause" -> pc.pause()
          "seek" -> if (positionMs >= 0) pc.seekTo(positionMs)
          "next" -> pc.seekToNext()
          "prev" -> pc.seekToPrevious()
          "playTrack" -> if (index >= 0) pc.player.seekTo(index, 0)
        }
        captureSnapshot()
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Action '$action' failed")
      }
    }
  }

  /** Must run on the main thread: reads ExoPlayer state. */
  private fun captureSnapshot() {
    val pc = connection ?: return
    try {
      val player = pc.player
      val meta = pc.mediaMetadata.value
      val playerDuration = player.duration
      val durationMs =
        when {
          playerDuration > 0 -> playerDuration
          meta != null && meta.duration > 0 -> meta.duration.toLong() * 1000L
          else -> 0L
        }
      val position = player.currentPosition.coerceAtLeast(0L)
      snapshot.set(
        Snapshot(
          playing = pc.isPlaying.value,
          positionMs = position,
          durationMs = durationMs,
          trackId = meta?.id ?: "",
          title = meta?.title ?: "",
          artist = meta?.artists?.joinToString(", ") { it.name } ?: "",
          uri = player.currentMediaItem?.localConfiguration?.uri,
          queue =
            (0 until player.mediaItemCount).mapNotNull { i ->
              val item = player.getMediaItemAt(i)
              val id = item.mediaId
              val t = item.mediaMetadata.title?.toString() ?: id
              if (id.isEmpty()) null else Pair(id, t)
            },
        )
      )
    } catch (e: Exception) {
      Timber.tag(TAG).d("Snapshot failed: ${e.message}")
    }
  }

  // ---------------------------------------------------------------- helpers

  private fun newCode(): String = (1..6).map { CODE_ALPHABET[Random.nextInt(CODE_ALPHABET.length)] }.joinToString("")

  private fun lanAddress(port: Int): String {
    try {
      val ifaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
      for (iface in ifaces) {
        if (iface.isLoopback || !iface.isUp) continue
        val addrs = java.util.Collections.list(iface.inetAddresses)
        for (addr in addrs) {
          if (addr is Inet4Address && addr.isSiteLocalAddress) {
            return "http://${addr.hostAddress}:$port"
          }
        }
      }
    } catch (e: Exception) {
      Timber.tag(TAG).d("No LAN address: ${e.message}")
    }
    return "http://localhost:$port"
  }

  private fun parseQuery(query: String): Map<String, String> =
    query
      .split("&")
      .mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx <= 0) null
        else
          java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
            java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
      }
      .toMap()
}
