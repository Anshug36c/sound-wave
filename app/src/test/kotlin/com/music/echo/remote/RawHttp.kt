package echo.music.iad1tya.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket

/** Tiny raw-socket HTTP client for tests (no restricted-header surprises). */
object RawHttp {
  class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun text(): String = String(body, Charsets.UTF_8)
    fun header(name: String): String? = headers[name.lowercase()]
  }

  fun request(
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
  ): Response {
    Socket("127.0.0.1", port).use { socket ->
      socket.soTimeout = 15_000
      val out = socket.getOutputStream()
      val bodyBytes = body?.toByteArray(Charsets.UTF_8)
      val head = StringBuilder()
      head.append("$method $path HTTP/1.1\r\n")
      head.append("Host: 127.0.0.1:$port\r\n")
      head.append("Connection: close\r\n")
      for ((k, v) in headers) head.append("$k: $v\r\n")
      if (bodyBytes != null) {
        head.append("Content-Type: application/json\r\n")
        head.append("Content-Length: ${bodyBytes.size}\r\n")
      }
      head.append("\r\n")
      out.write(head.toString().toByteArray(Charsets.UTF_8))
      if (bodyBytes != null) out.write(bodyBytes)
      out.flush()

      val input = BufferedInputStream(socket.getInputStream())
      val statusLine = readLine(input) ?: return Response(-1, emptyMap(), ByteArray(0))
      val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
      val responseHeaders = LinkedHashMap<String, String>()
      while (true) {
        val line = readLine(input) ?: break
        if (line.isEmpty()) break
        responseHeaders[line.substringBefore(':').trim().lowercase()] =
          line.substringAfter(':', "").trim()
      }
      val contentLength = responseHeaders["content-length"]?.toIntOrNull()
      val bodyOut = ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      if ((method == "HEAD").not()) {
        if (contentLength != null) {
          var remaining = contentLength
          while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            bodyOut.write(buffer, 0, read)
            remaining -= read
          }
        } else {
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            bodyOut.write(buffer, 0, read)
          }
        }
      }
      return Response(status, responseHeaders, bodyOut.toByteArray())
    }
  }

  private fun readLine(input: BufferedInputStream): String? {
    val out = ByteArrayOutputStream()
    var readAny = false
    while (true) {
      val b = input.read()
      if (b < 0) return if (readAny) out.toString("UTF-8") else null
      readAny = true
      if (b == '\n'.code) break
      if (b != '\r'.code) out.write(b)
    }
    return out.toString("UTF-8")
  }
}
