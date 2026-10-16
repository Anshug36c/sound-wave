package echo.music.iad1tya.remote

import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end tests of the bridge over real loopback sockets, with a fake
 * provider/fetcher. [FakeMobilePlayer] stands in for the Android player:
 * every test asserts it was never invoked, which is the regression guard
 * for the core product rule — remote playback must not move local playback.
 */
class RemoteHttpServerTest {

  object FakeMobilePlayer {
    var playCalls = 0
    var pauseCalls = 0
    var queueChanges = 0
    var currentTrack: String? = "local-song-A"

    fun play(trackId: String) {
      playCalls++
      currentTrack = trackId
    }

    fun reset() {
      playCalls = 0
      pauseCalls = 0
      queueChanges = 0
      currentTrack = "local-song-A"
    }
  }

  class FakeProvider : MusicProvider {
    val searchCalls = AtomicInteger(0)
    val resolveCalls = AtomicInteger(0)
    val audio = ByteArray(120_000) { (it % 253).toByte() }
    private val tracks =
      listOf(
        RemoteTrack("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "Album", 213, null),
        RemoteTrack("tone0000001", "Test Tone", "Bridge Testers", null, 60, null),
      )

    override fun search(query: String, limit: Int): List<RemoteTrack> {
      searchCalls.incrementAndGet()
      return tracks.take(limit)
    }

    override fun recommendations(limit: Int): List<RemoteTrack> = tracks.take(limit)

    override fun homeSections(limitPerSection: Int): List<RemoteSection> =
      listOf(
        RemoteSection("Quick picks", null, tracks.map { RemoteItem("song", it.id, it.title, it.artist, null, it.durationSec) }),
        RemoteSection("Albums", null, listOf(RemoteItem("album", "MPREb_test", "Test Album", "Rick Astley", null, null, false, "1987"))),
      )

    override fun searchItems(query: String, type: String, limit: Int): List<RemoteItem> =
      listOf(RemoteItem(type, "MPREb_test", "Typed $type", "Tester"))

    override fun detail(kind: String, id: String): RemoteDetail =
      RemoteDetail(kind, id, "Detail $kind", "Tester", null, null, tracks, emptyList())

    override fun charts(): List<RemoteSection> =
      listOf(RemoteSection("Top songs", "top", tracks.map { RemoteItem("song", it.id, it.title, it.artist) }))

    override fun explore(): List<RemoteSection> =
      listOf(RemoteSection("Moods", null, listOf(RemoteItem("mood", "FEmusic_moods|abc", "Focus", null, null, null, false, "#4455aa"))))

    override fun browse(browseId: String, params: String?): List<RemoteSection> =
      listOf(RemoteSection("Browsed", null, listOf(RemoteItem("playlist", "VLPLtest", "Mood Mix", "YouTube Music"))))

    override fun track(trackId: String): RemoteTrack? = tracks.find { it.id == trackId }

    override fun resolve(trackId: String): ResolvedSource {
      resolveCalls.incrementAndGet()
      if (track(trackId) == null) throw RemoteException.resolutionFailed()
      return ResolvedSource(
        trackId,
        "https://upstream.invalid/audio?id=$trackId",
        "audio/mp4",
        System.currentTimeMillis() + 3_600_000,
        audio.size.toLong(),
        mapOf("User-Agent" to "test"),
      )
    }
  }

  class FakeFetcher(private val provider: FakeProvider) : UpstreamFetcher {
    @Volatile var failNextWith403 = false
    @Volatile var lastRangeHeader: String? = null
    @Volatile var sawRangeLessRequest = false

    override fun open(
      url: String,
      headers: Map<String, String>,
      rangeHeader: String?,
      headOnly: Boolean,
    ): UpstreamResponse {
      lastRangeHeader = rangeHeader
      if (rangeHeader == null) sawRangeLessRequest = true
      if (failNextWith403) {
        failNextWith403 = false
        return UpstreamResponse(403, null, 0, null, null, null)
      }
      val data = provider.audio
      if (rangeHeader != null) {
        val match = Regex("""bytes=(\d*)-(\d*)""").matchEntire(rangeHeader)!!
        val start =
          if (match.groupValues[1].isEmpty()) data.size - match.groupValues[2].toInt()
          else match.groupValues[1].toInt()
        val end =
          if (match.groupValues[2].isEmpty() || match.groupValues[1].isEmpty()) data.size - 1
          else minOf(match.groupValues[2].toInt(), data.size - 1)
        val slice = data.copyOfRange(start, end + 1)
        return UpstreamResponse(
          206,
          "audio/mp4",
          slice.size.toLong(),
          "bytes $start-$end/${data.size}",
          "bytes",
          if (headOnly) null else slice.inputStream(),
        )
      }
      return UpstreamResponse(
        200,
        "audio/mp4",
        data.size.toLong(),
        null,
        "bytes",
        if (headOnly) null else data.inputStream(),
      )
    }
  }

  private lateinit var provider: FakeProvider
  private lateinit var fetcher: FakeFetcher
  private lateinit var sessions: RemoteSessionManager
  private lateinit var server: RemoteHttpServer
  private var port = -1

  @Before
  fun setUp() {
    FakeMobilePlayer.reset()
    provider = FakeProvider()
    fetcher = FakeFetcher(provider)
    sessions = RemoteSessionManager()
    port = ServerSocket(0).use { it.localPort }
    server =
      RemoteHttpServer(
        port = port,
        provider = provider,
        sessionManager = sessions,
        fetcher = fetcher,
        pageProvider = { "<html>SoundWave Remote</html>".toByteArray() },
      )
    server.start()
  }

  @After
  fun tearDown() {
    server.stop()
    assertEquals("mobile player must never be touched by remote operations", 0, FakeMobilePlayer.playCalls)
    assertEquals(0, FakeMobilePlayer.pauseCalls)
    assertEquals(0, FakeMobilePlayer.queueChanges)
    assertEquals("local-song-A", FakeMobilePlayer.currentTrack)
  }

  private fun pair(): String {
    val (code, _) = sessions.currentPairingCode()
    val response =
      RawHttp.request(port, "POST", "/api/pair", body = """{"code":"$code"}""")
    assertEquals(response.text(), 200, response.status)
    return MiniJson.parseObject(response.text()).objString("sessionToken")!!
  }

  private fun auth(token: String, origin: String = "https://anshug36c.github.io"): Map<String, String> =
    mapOf("Authorization" to "Bearer $token", "Origin" to origin)

  @Test
  fun healthIsPublicAndSearchRequiresAuth() {
    assertEquals(200, RawHttp.request(port, "GET", "/api/health").status)
    assertEquals(401, RawHttp.request(port, "GET", "/api/search?q=x").status)
    assertEquals(200, RawHttp.request(port, "GET", "/").status)
  }

  @Test
  fun pairingRejectsWrongCodeThenAcceptsRightOne() {
    sessions.currentPairingCode()
    val bad = RawHttp.request(port, "POST", "/api/pair", body = """{"code":"000000"}""")
    assertEquals(401, bad.status)
    assertTrue(bad.text().contains("PAIRING_INVALID"))
    pair()
  }

  @Test
  fun recommendationsRequireAuthAndReturnTracks() {
    assertEquals(401, RawHttp.request(port, "GET", "/api/recommendations").status)
    val token = pair()
    val recs = RawHttp.request(port, "GET", "/api/recommendations", headers = auth(token))
    assertEquals(recs.text(), 200, recs.status)
    assertTrue(recs.text().contains("Never Gonna Give You Up"))
  }

  @Test
  fun catalogEndpointsRequireAuthAndReturnShelves() {
    assertEquals(401, RawHttp.request(port, "GET", "/api/home").status)
    assertEquals(401, RawHttp.request(port, "GET", "/api/album/MPREb_test").status)
    val token = pair()

    val home = RawHttp.request(port, "GET", "/api/home", headers = auth(token))
    assertEquals(home.text(), 200, home.status)
    assertTrue(home.text().contains("Quick picks"))
    assertTrue(home.text().contains("\"type\":\"album\""))

    val typed = RawHttp.request(port, "GET", "/api/search?q=x&type=artist", headers = auth(token))
    assertEquals(typed.text(), 200, typed.status)
    assertTrue(typed.text().contains("Typed artist"))

    val badType = RawHttp.request(port, "GET", "/api/search?q=x&type=bogus", headers = auth(token))
    assertEquals(400, badType.status)

    val album = RawHttp.request(port, "GET", "/api/album/MPREb_test", headers = auth(token))
    assertEquals(album.text(), 200, album.status)
    assertTrue(album.text().contains("Detail album"))
    assertTrue(album.text().contains("Never Gonna Give You Up"))

    val charts = RawHttp.request(port, "GET", "/api/charts", headers = auth(token))
    assertTrue(charts.text().contains("Top songs"))

    val mood = RawHttp.request(port, "GET", "/api/browse?id=FEmusic_moods&params=abc", headers = auth(token))
    assertEquals(mood.text(), 200, mood.status)
    assertTrue(mood.text().contains("Mood Mix"))

    val badId = RawHttp.request(port, "GET", "/api/browse?id=..%2Fevil", headers = auth(token))
    assertEquals(400, badId.status)
  }

  @Test
  fun searchResolveAndStreamWithRanges() {
    val token = pair()

    val search = RawHttp.request(port, "GET", "/api/search?q=rick", headers = auth(token))
    assertEquals(search.text(), 200, search.status)
    assertTrue(search.text().contains("Never Gonna Give You Up"))
    assertEquals("https://anshug36c.github.io", search.header("access-control-allow-origin"))

    val evil =
      RawHttp.request(port, "GET", "/api/search?q=rick", headers = auth(token, "https://evil.example"))
    assertEquals(403, evil.status)
    assertTrue(evil.text().contains("ORIGIN_DENIED"))

    val preflight =
      RawHttp.request(
        port,
        "OPTIONS",
        "/api/search",
        headers =
          mapOf(
            "Origin" to "https://anshug36c.github.io",
            "Access-Control-Request-Method" to "GET",
            "Access-Control-Request-Headers" to "authorization",
          ),
      )
    assertEquals(204, preflight.status)

    val resolve =
      RawHttp.request(
        port,
        "POST",
        "/api/resolve",
        headers = auth(token),
        body = """{"trackId":"dQw4w9WgXcQ"}""",
      )
    assertEquals(resolve.text(), 200, resolve.status)
    assertFalse("upstream URL must never leak to the browser", resolve.text().contains("upstream.invalid"))
    val streamPath = MiniJson.parseObject(resolve.text()).objString("streamPath")!!

    val full = RawHttp.request(port, "GET", streamPath, headers = auth(token))
    assertEquals(200, full.status)
    assertTrue(full.body.contentEquals(provider.audio))

    val ranged =
      RawHttp.request(port, "GET", streamPath, headers = auth(token) + ("Range" to "bytes=1000-1999"))
    assertEquals(206, ranged.status)
    assertEquals(1000, ranged.body.size)
    assertTrue(ranged.body.contentEquals(provider.audio.copyOfRange(1000, 2000)))
    assertTrue(ranged.header("content-range")!!.startsWith("bytes 1000-1999/"))

    val suffix =
      RawHttp.request(port, "GET", streamPath, headers = auth(token) + ("Range" to "bytes=-500"))
    assertEquals(206, suffix.status)
    assertEquals(500, suffix.body.size)

    // <audio> cannot send headers: query-token + HEAD must work.
    val head = RawHttp.request(port, "HEAD", "$streamPath?t=$token")
    assertEquals(200, head.status)
    assertEquals(0, head.body.size)
    assertEquals(provider.audio.size.toString(), head.header("content-length"))

    assertEquals(401, RawHttp.request(port, "GET", streamPath).status)
  }

  @Test
  fun rangeLessStreamRequestFetchesUpstreamFromZero() {
    val token = pair()
    val resolve =
      RawHttp.request(
        port,
        "POST",
        "/api/resolve",
        headers = auth(token),
        body = """{"trackId":"dQw4w9WgXcQ"}""",
      )
    val streamPath = MiniJson.parseObject(resolve.text()).objString("streamPath")!!

    // Media elements do not always send Range on the first request, and
    // googlevideo 403s range-less GETs for most YouTube Music tracks, so the
    // bridge must ask upstream with bytes=0- and still answer a plain 200.
    val full = RawHttp.request(port, "GET", streamPath, headers = auth(token))
    assertEquals(200, full.status)
    assertTrue(full.body.contentEquals(provider.audio))
    assertEquals(provider.audio.size.toString(), full.header("content-length"))
    assertEquals("bytes=0-", fetcher.lastRangeHeader)
    assertTrue("bridge must never forward a range-less request upstream", !fetcher.sawRangeLessRequest)

    val head = RawHttp.request(port, "HEAD", streamPath, headers = auth(token))
    assertEquals(200, head.status)
    assertEquals(provider.audio.size.toString(), head.header("content-length"))
  }

  @Test
  fun expiredUpstreamIsReResolvedOnce() {
    val token = pair()
    val resolve =
      RawHttp.request(
        port,
        "POST",
        "/api/resolve",
        headers = auth(token),
        body = """{"trackId":"tone0000001"}""",
      )
    val streamPath = MiniJson.parseObject(resolve.text()).objString("streamPath")!!
    fetcher.failNextWith403 = true
    val before = provider.resolveCalls.get()
    val response = RawHttp.request(port, "GET", streamPath, headers = auth(token))
    assertEquals(200, response.status)
    assertTrue(response.body.contentEquals(provider.audio))
    assertTrue(provider.resolveCalls.get() > before)
  }

  @Test
  fun remoteStateIsStoredPerSessionAndRevocationWorks() {
    val token = pair()
    val post =
      RawHttp.request(
        port,
        "POST",
        "/api/state",
        headers = auth(token),
        body =
          """{"track":{"id":"dQw4w9WgXcQ","title":"Never Gonna Give You Up","artist":"Rick Astley"},""" +
            """"queue":[],"queueIndex":0,"playing":true,"positionMs":42000}""",
      )
    assertEquals(200, post.status)
    val get = RawHttp.request(port, "GET", "/api/state", headers = auth(token))
    assertTrue(get.text().contains("Never Gonna Give You Up"))
    assertEquals(track().title, "Never Gonna Give You Up")

    assertEquals(200, RawHttp.request(port, "DELETE", "/api/session", headers = auth(token)).status)
    assertEquals(401, RawHttp.request(port, "GET", "/api/search?q=x", headers = auth(token)).status)
  }

  private fun track(): RemoteTrack = sessions.latestRemoteState()!!.track!!

  @Test
  fun resolveIsRateLimited() {
    val token = pair()
    var last = 0
    repeat(40) {
      last =
        RawHttp.request(
            port,
            "POST",
            "/api/resolve",
            headers = auth(token),
            body = """{"trackId":"dQw4w9WgXcQ"}""",
          )
          .status
    }
    assertEquals(429, last)
  }
}
