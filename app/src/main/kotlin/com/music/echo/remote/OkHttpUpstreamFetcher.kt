package echo.music.iad1tya.remote

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [UpstreamFetcher] over OkHttp. Runs synchronously on bridge worker
 * threads. Redirects are followed by OkHttp; the Range header (when the web
 * player seeks) is forwarded so googlevideo answers 206 and the bridge can
 * relay partial content without buffering the track.
 */
class OkHttpUpstreamFetcher(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(180, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .followRedirects(true)
      .build(),
) : UpstreamFetcher {

  override fun open(
    url: String,
    headers: Map<String, String>,
    rangeHeader: String?,
    headOnly: Boolean,
  ): UpstreamResponse {
    val builder = Request.Builder().url(url)
    for ((name, value) in headers) builder.header(name, value)
    if (rangeHeader != null) builder.header("Range", rangeHeader)
    if (headOnly) builder.head() else builder.get()

    val response = client.newCall(builder.build()).execute()
    val body = response.body
    if (headOnly) response.close()
    return UpstreamResponse(
      status = response.code,
      contentType = response.header("Content-Type") ?: body?.contentType()?.toString(),
      contentLength = body?.contentLength() ?: -1L,
      contentRange = response.header("Content-Range"),
      acceptRanges = response.header("Accept-Ranges"),
      body = if (headOnly) null else body?.byteStream(),
    )
  }
}
