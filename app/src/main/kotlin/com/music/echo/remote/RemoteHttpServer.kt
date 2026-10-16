package echo.music.iad1tya.remote

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Embedded HTTP server for the Remote Web Player ("SoundWave Bridge").
 *
 * Pure JVM (no Android imports) so it can be unit-tested off-device. The
 * Android layer supplies a [MusicProvider] (search/resolve), an
 * [UpstreamFetcher] (opens the resolved audio URL) and the LAN player page.
 *
 * API (all JSON, errors as `{"error":{"code","message"}}`):
 * - `GET  /` / `/remote.html`  standalone LAN player page (no secrets in it)
 * - `GET  /api/health`         public liveness + API version (no auth)
 * - `POST /api/pair`           `{code}` -> session token (rate limited)
 * - `GET  /api/session`        session info (Bearer)
 * - `DELETE /api/session`      revoke own session (Bearer)
 * - `GET  /api/search?q=`      search (Bearer)
 * - `GET  /api/track/{id}`     track metadata (Bearer)
 * - `POST /api/resolve`        `{trackId}` -> expiring stream path (Bearer)
 * - `GET|HEAD /api/stream/{grantId}`  audio bytes, Range-capable (Bearer or
 *   `?t=` token — an <audio> element cannot send headers)
 * - `GET|POST /api/state`      the web player's own playback state (Bearer)
 *
 * Invariants: the upstream audio URL and its headers never leave the phone;
 * nothing here can reach the mobile player; every browser request must come
 * from an allow-listed origin (CORS is enforced in addition to auth).
 */
class RemoteHttpServer(
  val port: Int,
  private val provider: MusicProvider,
  private val sessionManager: RemoteSessionManager,
  private val fetcher: UpstreamFetcher,
  private val pageProvider: () -> ByteArray,
  private val allowedOrigins: Set<String> = DEFAULT_ALLOWED_ORIGINS,
  private val log: (String) -> Unit = {},
) {
  private val running = AtomicBoolean(false)
  private var serverSocket: ServerSocket? = null
  @Volatile private var executor = newExecutor()

  private fun newExecutor() =
    Executors.newCachedThreadPool { runnable ->
      Thread(runnable, "soundwave-bridge").apply { isDaemon = true }
    }
  private val streamPermits = Semaphore(MAX_CONCURRENT_STREAMS)
  private val apiBuckets = HashMap<String, RateBucket>()

  val isRunning: Boolean get() = running.get()

  val actualPort: Int get() = serverSocket?.localPort ?: port

  fun start(bindAddress: InetAddress? = null) {
    if (!running.compareAndSet(false, true)) return
    val socket = ServerSocket(port, 50, bindAddress)
    serverSocket = socket
    executor.execute {
      while (running.get()) {
        try {
          val client = socket.accept()
          executor.execute { handleQuietly(client) }
        } catch (e: IOException) {
          if (running.get()) log("bridge accept failed: ${e.message}")
        }
      }
    }
    log("bridge listening on port ${socket.localPort}")
  }

  fun stop() {
    if (!running.compareAndSet(true, false)) return
    try {
      serverSocket?.close()
    } catch (_: IOException) {
    }
    serverSocket = null
    executor.shutdownNow()
  }

  // -------------------------------------------------------------- plumbing

  private fun handleQuietly(client: Socket) {
    try {
      client.soTimeout = SOCKET_TIMEOUT_MS
      handle(client)
    } catch (e: Exception) {
      log("bridge connection error: ${e.javaClass.simpleName}: ${e.message}")
    } finally {
      try {
        client.close()
      } catch (_: IOException) {
      }
    }
  }

  private fun handle(client: Socket) {
    val input = BufferedInputStream(client.getInputStream())
    val output = BufferedOutputStream(client.getOutputStream())
    val request = readRequest(input) ?: return
    try {
      route(client, request, output)
    } catch (e: RemoteException) {
      log("bridge ${request.method} ${request.path} -> ${e.httpStatus} ${e.code}")
      writeJson(output, e.httpStatus, e.toJson(), request)
    } catch (e: IOException) {
      throw e
    } catch (e: Exception) {
      log("bridge handler error: ${e.javaClass.simpleName}: ${e.message}")
      writeJson(
        output,
        500,
        RemoteException("INTERNAL", 500, "Internal bridge error.").toJson(),
        request,
      )
    } finally {
      output.flush()
    }
  }

  private fun route(client: Socket, request: Request, output: OutputStream) {
    val path = request.path

    if (request.method == "OPTIONS") {
      handlePreflight(request, output)
      return
    }

    when {
      path == "/" || path == "/remote.html" -> {
        requireMethod(request, "GET")
        val page = pageProvider()
        writeBytes(output, 200, "text/html; charset=utf-8", page, request, cacheable = false)
      }
      path == "/favicon.ico" -> writeBytes(output, 204, "image/x-icon", ByteArray(0), request)
      path == "/api/health" -> {
        requireMethod(request, "GET")
        writeJson(
          output,
          200,
          MiniJson.obj(
            "ok" to true,
            "service" to "soundwave-remote",
            "apiVersion" to API_VERSION,
            "pairingRequired" to true,
            "nowMs" to System.currentTimeMillis(),
          ),
          request,
        )
      }
      path == "/api/pair" -> {
        requireMethod(request, "POST")
        requireAllowedOrigin(request, isStream = false)
        val body = request.jsonBody()
        val session =
          sessionManager.pair(
            sourceIp = client.inetAddress?.hostAddress ?: "unknown",
            code = body.objString("code"),
            userAgent = request.header("user-agent") ?: "",
          )
        writeJson(
          output,
          200,
          MiniJson.obj(
            "sessionToken" to session.token,
            "expiresAtMs" to session.expiresAtMs,
            "label" to session.label,
          ),
          request,
        )
      }
      path.startsWith("/api/stream/") -> {
        try {
          client.soTimeout = STREAM_SOCKET_TIMEOUT_MS
        } catch (_: Exception) {
        }
        handleStream(request, output, path.removePrefix("/api/stream/"))
      }
      path.startsWith("/api/") -> handleAuthedApi(request, output, path)
      else -> throw RemoteException.notFound()
    }
  }

  private fun handleAuthedApi(request: Request, output: OutputStream, path: String) {
    val session = authenticate(request)
    requireAllowedOrigin(request, isStream = false)
    when {
      path == "/api/session" && request.method == "GET" ->
        writeJson(
          output,
          200,
          MiniJson.obj(
            "session" to session.toJson(),
            "apiVersion" to API_VERSION,
            "remoteState" to session.remoteState.toJson(),
          ),
          request,
        )
      path == "/api/session" && request.method == "DELETE" -> {
        sessionManager.revoke(session.token)
        writeJson(output, 200, MiniJson.obj("ok" to true), request)
      }
      path == "/api/search" -> {
        requireMethod(request, "GET")
        rateLimit(session.token, "search", SEARCH_PER_MINUTE)
        val query = request.queryParam("q")?.trim().orEmpty()
        if (query.isEmpty() || query.length > MAX_QUERY_LENGTH) {
          throw RemoteException.validation("Search query must be 1-$MAX_QUERY_LENGTH characters.")
        }
        val limit = (request.queryParam("limit")?.toIntOrNull() ?: 20).coerceIn(1, 30)
        val tracks = provider.search(query, limit)
        writeJson(
          output,
          200,
          MiniJson.obj("query" to query, "results" to tracks.map { it.toJson() }),
          request,
        )
      }
      path.startsWith("/api/track/") -> {
        requireMethod(request, "GET")
        val trackId = path.removePrefix("/api/track/")
        validateTrackId(trackId)
        val track = provider.track(trackId) ?: throw RemoteException.notFound("Unknown track.")
        writeJson(output, 200, MiniJson.obj("track" to track.toJson()), request)
      }
      path == "/api/resolve" -> {
        requireMethod(request, "POST")
        rateLimit(session.token, "resolve", RESOLVE_PER_MINUTE)
        val body = request.jsonBody()
        val trackId = body.objString("trackId") ?: throw RemoteException.validation("trackId is required.")
        validateTrackId(trackId)
        val source = provider.resolve(trackId)
        val grant = sessionManager.createGrant(session, source)
        writeJson(
          output,
          200,
          MiniJson.obj(
            "track" to provider.track(trackId)?.toJson(),
            "streamPath" to "/api/stream/${grant.id}",
            "contentType" to source.contentType,
            "safariCompatible" to source.safariCompatible,
            "streamExpiresAtMs" to grant.expiresAtMs,
            "upstreamExpiresAtMs" to source.expiresAtMs,
            "resolverClient" to source.resolverClient,
          ),
          request,
        )
      }
      path == "/api/state" && request.method == "GET" ->
        writeJson(output, 200, MiniJson.obj("remoteState" to session.remoteState.toJson()), request)
      path == "/api/state" && request.method == "POST" -> {
        val report = RemotePlaybackReport.fromJson(request.jsonBody(), System.currentTimeMillis())
        sessionManager.updateRemoteState(session, report)
        writeJson(output, 200, MiniJson.obj("ok" to true), request)
      }
      else -> throw RemoteException.notFound()
    }
  }

  // -------------------------------------------------------------- streaming

  private fun handleStream(request: Request, output: OutputStream, grantIdRaw: String) {
    if (request.method != "GET" && request.method != "HEAD") throw RemoteException.methodNotAllowed()
    val grantId = grantIdRaw.substringBefore('/').trim()
    if (grantId.isEmpty()) throw RemoteException.notFound("Unknown stream.")
    // <audio> cannot send Authorization; accept the session token as `?t=`.
    val token = request.bearerToken() ?: request.queryParam("t")
    val session = sessionManager.authenticate(token)
    val grant = sessionManager.grant(session, grantId)
    val rangeHeader = validatedRange(request.header("range"))

    if (!streamPermits.tryAcquire()) throw RemoteException.busy()
    try {
      streamGrant(request, output, grant, rangeHeader)
    } finally {
      streamPermits.release()
    }
  }

  private fun streamGrant(
    request: Request,
    output: OutputStream,
    grant: RemoteSessionManager.StreamGrant,
    rangeHeader: String?,
  ) {
    var source = grant.source
    val upstreamRange = rangeHeader ?: "bytes=0-"
    var upstream = openUpstream(source, upstreamRange, request.method == "HEAD")
    if ((upstream.status == 403 || upstream.status == 410) && request.method == "GET") {
      // Signed URLs expire; re-resolve once and retry before bothering the user.
      upstream.close()
      val refreshed =
        try {
          provider.resolve(grant.trackId)
        } catch (e: Exception) {
          null
        }
      if (refreshed != null) {
        grant.source = refreshed
        source = refreshed
        upstream = openUpstream(source, upstreamRange, headOnly = false)
      }
    }
    upstream.use { response ->
      when (response.status) {
        200, 206 -> {
          var status = response.status
          var contentLength = response.contentLength
          var contentRange = response.contentRange
          // A range-less client gets a normal 200 full response even though
          // we asked upstream for bytes=0- (which answers 206).
          if (rangeHeader == null && response.status == 206 && contentRange != null) {
            val total = contentRange.substringAfter('/', "").toLongOrNull()
            val start = contentRange.removePrefix("bytes ").substringBefore('-').toLongOrNull()
            if (total != null && start == 0L) {
              status = 200
              contentLength = total
              contentRange = null
            }
          }
          val headers = LinkedHashMap<String, String>()
          headers["Content-Type"] = response.contentType ?: source.contentType
          headers["Accept-Ranges"] = response.acceptRanges ?: "bytes"
          contentRange?.let { headers["Content-Range"] = it }
          headers["Cache-Control"] = "no-store"
          corsHeaders(request, isStream = true)?.let { headers["Access-Control-Allow-Origin"] = it }
          if (contentLength >= 0) headers["Content-Length"] = contentLength.toString()
          writeHead(output, status, headers)
          if (request.method == "GET" && response.body != null) {
            copyBody(response.body, output)
          }
        }
        416 -> {
          val headers = LinkedHashMap<String, String>()
          response.contentRange?.let { headers["Content-Range"] = it }
          headers["Content-Length"] = "0"
          writeHead(output, 416, headers)
        }
        403, 410 -> throw RemoteException.upstreamExpired()
        else ->
          throw RemoteException(
            "UPSTREAM_ERROR",
            502,
            "The audio source returned an error (${response.status}). Try again.",
          )
      }
    }
  }

  private fun openUpstream(source: ResolvedSource, rangeHeader: String?, headOnly: Boolean): UpstreamResponse =
    try {
      fetcher.open(source.upstreamUrl, source.headers, rangeHeader, headOnly)
    } catch (e: IOException) {
      throw RemoteException("UPSTREAM_UNREACHABLE", 502, "SoundWave could not reach the audio source.")
    }

  private fun copyBody(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(STREAM_BUFFER_BYTES)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      if (read > 0) {
        output.write(buffer, 0, read)
        output.flush()
      }
    }
  }

  /** Only single byte-ranges are supported (what media elements send). */
  private fun validatedRange(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val match = RANGE_REGEX.matchEntire(header.trim()) ?: throw RemoteException.validation("Unsupported Range header.")
    val start = match.groupValues[1]
    val end = match.groupValues[2]
    if (start.isEmpty() && end.isEmpty()) throw RemoteException.validation("Unsupported Range header.")
    if (start.isNotEmpty() && end.isNotEmpty()) {
      val s = start.toLongOrNull() ?: throw RemoteException.validation("Bad Range header.")
      val e = end.toLongOrNull() ?: throw RemoteException.validation("Bad Range header.")
      if (e < s) throw RemoteException.validation("Bad Range header.")
    }
    return "bytes=$start-$end"
  }

  // -------------------------------------------------------------- auth/cors

  private fun authenticate(request: Request): RemoteSessionManager.Session {
    requireAllowedOriginPreAuth(request)
    return sessionManager.authenticate(request.bearerToken())
  }

  /**
   * Origin gate applied before session lookup for API routes. A browser page
   * may only use the bridge when its origin is the GitHub Pages site, a local
   * dev origin, or the bridge itself (the LAN player page). Non-browser
   * clients send no Origin and rely on the bearer token alone.
   */
  private fun requireAllowedOriginPreAuth(request: Request) {
    val origin = request.header("origin") ?: return
    if (!isAllowedOrigin(origin, request)) throw RemoteException.originDenied()
  }

  private fun requireAllowedOrigin(request: Request, isStream: Boolean) {
    if (isStream) return // capability URL + token are the authorization here
    requireAllowedOriginPreAuth(request)
  }

  private fun isAllowedOrigin(origin: String, request: Request): Boolean {
    if (origin in allowedOrigins) return true
    // Same-origin: the LAN player page and tunnel-fronted page run on the
    // bridge's own host, so Origin host == Host header.
    val host = request.header("host") ?: return false
    val originHost = origin.substringAfter("://", "")
    return originHost == host
  }

  private fun corsHeaders(request: Request, isStream: Boolean): String? {
    val origin = request.header("origin") ?: return if (isStream) "*" else null
    return if (isAllowedOrigin(origin, request) || isStream) origin else null
  }

  private fun handlePreflight(request: Request, output: OutputStream) {
    val origin = request.header("origin")
    if (origin == null || !isAllowedOrigin(origin, request)) {
      throw RemoteException.originDenied()
    }
    val headers = LinkedHashMap<String, String>()
    headers["Access-Control-Allow-Origin"] = origin
    headers["Access-Control-Allow-Methods"] = "GET, POST, DELETE, HEAD, OPTIONS"
    headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type, Range"
    headers["Access-Control-Expose-Headers"] = "Content-Range, Accept-Ranges, Content-Length, Content-Type"
    headers["Access-Control-Max-Age"] = "600"
    headers["Vary"] = "Origin"
    if (request.header("access-control-request-private-network") == "true") {
      headers["Access-Control-Allow-Private-Network"] = "true"
    }
    headers["Content-Length"] = "0"
    writeHead(output, 204, headers)
  }

  private fun rateLimit(sessionToken: String, bucket: String, perMinute: Int) {
    val now = System.currentTimeMillis()
    synchronized(apiBuckets) {
      val key = "$sessionToken:$bucket"
      val window = apiBuckets.getOrPut(key) { RateBucket(now, 0) }
      if (now - window.windowStartMs >= 60_000) {
        window.windowStartMs = now
        window.count = 0
      }
      window.count += 1
      if (window.count > perMinute) throw RemoteException.rateLimited()
      if (apiBuckets.size > 512) {
        apiBuckets.entries.removeIf { now - it.value.windowStartMs >= 60_000 }
      }
    }
  }

  private class RateBucket(var windowStartMs: Long, var count: Int)

  // -------------------------------------------------------------- HTTP I/O

  private class Request(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray,
  ) {
    val path: String = target.substringBefore('?')
    private val query: Map<String, String> by lazy { parseQuery(target.substringAfter('?', "")) }

    fun header(name: String): String? = headers[name.lowercase()]

    fun queryParam(name: String): String? = query[name]

    fun bearerToken(): String? {
      val auth = header("authorization") ?: return null
      if (!auth.startsWith("Bearer ", ignoreCase = true)) return null
      return auth.substring(7).trim().ifEmpty { null }
    }

    fun jsonBody(): Map<String, Any?> {
      if (body.isEmpty()) throw RemoteException.validation("JSON body is required.")
      return MiniJson.parseObject(String(body, Charsets.UTF_8))
    }

    companion object {
      fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
          if (part.isEmpty()) continue
          val key = urlDecode(part.substringBefore('='))
          val value = urlDecode(part.substringAfter('=', ""))
          if (key.isNotEmpty()) result[key] = value
        }
        return result
      }

      private fun urlDecode(value: String): String =
        try {
          URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
          value
        }
    }
  }

  private fun readRequest(input: InputStream): Request? {
    val requestLine = readLine(input, MAX_LINE_BYTES) ?: return null
    if (requestLine.isBlank()) return null
    val parts = requestLine.split(' ')
    if (parts.size < 2) return null
    val method = parts[0].uppercase()
    val target = parts[1]

    val headers = LinkedHashMap<String, String>()
    var headerBytes = 0
    while (true) {
      val line = readLine(input, MAX_LINE_BYTES) ?: break
      if (line.isEmpty()) break
      headerBytes += line.length
      if (headerBytes > MAX_HEADER_BYTES) throw RemoteException.validation("Headers too large.")
      val name = line.substringBefore(':', "").trim().lowercase()
      val value = line.substringAfter(':', "").trim()
      if (name.isNotEmpty()) headers[name] = value
      if (headers.size > 100) throw RemoteException.validation("Too many headers.")
    }

    val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
    if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
      throw RemoteException.validation("Request body too large.")
    }
    val body = if (contentLength > 0) readExactly(input, contentLength.toInt()) else ByteArray(0)
    return Request(method, target, headers, body)
  }

  private fun readLine(input: InputStream, maxBytes: Int): String? {
    val out = ByteArrayOutputStream()
    var readAny = false
    while (out.size() <= maxBytes) {
      val b = input.read()
      if (b < 0) return if (readAny) out.toString("UTF-8") else null
      readAny = true
      if (b == '\n'.code) break
      if (b != '\r'.code) out.write(b)
    }
    if (out.size() > maxBytes) throw RemoteException.validation("Line too long.")
    return out.toString("UTF-8")
  }

  private fun readExactly(input: InputStream, count: Int): ByteArray {
    val body = ByteArray(count)
    var offset = 0
    while (offset < count) {
      val read = input.read(body, offset, count - offset)
      if (read < 0) break
      offset += read
    }
    return if (offset == count) body else body.copyOf(offset)
  }

  private fun writeJson(output: OutputStream, status: Int, json: Map<String, Any?>, request: Request?) {
    val bytes = MiniJson.stringify(json).toByteArray(Charsets.UTF_8)
    val headers = LinkedHashMap<String, String>()
    if (request != null) {
      corsHeaders(request, isStream = false)?.let {
        headers["Access-Control-Allow-Origin"] = it
        headers["Vary"] = "Origin"
      }
    }
    headers["Cache-Control"] = "no-store"
    writeResponse(output, status, "application/json; charset=utf-8", bytes, headers)
  }

  private fun writeBytes(
    output: OutputStream,
    status: Int,
    contentType: String,
    bytes: ByteArray,
    request: Request?,
    cacheable: Boolean = true,
  ) {
    val headers = LinkedHashMap<String, String>()
    if (!cacheable) headers["Cache-Control"] = "no-store"
    if (request != null && status == 200 && contentType.startsWith("text/html")) {
      corsHeaders(request, isStream = false)?.let { headers["Access-Control-Allow-Origin"] = it }
    }
    writeResponse(output, status, contentType, bytes, headers)
  }

  private fun writeResponse(
    output: OutputStream,
    status: Int,
    contentType: String,
    body: ByteArray,
    extraHeaders: Map<String, String>,
  ) {
    val headers = LinkedHashMap(extraHeaders)
    headers.putIfAbsent("Content-Type", contentType)
    headers["Content-Length"] = body.size.toString()
    writeHead(output, status, headers)
    output.write(body)
    output.flush()
  }

  private fun writeHead(output: OutputStream, status: Int, headers: Map<String, String>) {
    val head = StringBuilder()
    head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
    for ((name, value) in headers) {
      head.append(name).append(": ").append(value).append("\r\n")
    }
    head.append("Connection: close\r\n")
    head.append("\r\n")
    output.write(head.toString().toByteArray(Charsets.UTF_8))
    output.flush()
  }

  private fun reasonPhrase(status: Int): String =
    when (status) {
      200 -> "OK"
      204 -> "No Content"
      206 -> "Partial Content"
      400 -> "Bad Request"
      401 -> "Unauthorized"
      403 -> "Forbidden"
      404 -> "Not Found"
      405 -> "Method Not Allowed"
      416 -> "Range Not Satisfiable"
      429 -> "Too Many Requests"
      500 -> "Internal Server Error"
      502 -> "Bad Gateway"
      503 -> "Service Unavailable"
      else -> "Status"
    }

  private fun requireMethod(request: Request, method: String) {
    if (request.method != method) throw RemoteException.methodNotAllowed()
  }

  private fun validateTrackId(trackId: String) {
    if (trackId.isEmpty() || trackId.length > 32 || !trackId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
      throw RemoteException.validation("Invalid trackId.")
    }
  }

  companion object {
    const val API_VERSION = 1
    const val DEFAULT_PORT = 8930

    val DEFAULT_ALLOWED_ORIGINS =
      setOf(
        "https://anshug36c.github.io",
        "http://localhost:5173",
        "http://127.0.0.1:5173",
        "http://localhost:4173",
        "http://127.0.0.1:4173",
      )

    private const val MAX_LINE_BYTES = 8192
    private const val MAX_HEADER_BYTES = 32 * 1024
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val MAX_QUERY_LENGTH = 120
    private const val SOCKET_TIMEOUT_MS = 30_000
    private const val STREAM_SOCKET_TIMEOUT_MS = 120_000
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val MAX_CONCURRENT_STREAMS = 3
    private const val SEARCH_PER_MINUTE = 30
    private const val RESOLVE_PER_MINUTE = 30

    private val RANGE_REGEX = Regex("""bytes=(\d*)-(\d*)""")
  }
}
