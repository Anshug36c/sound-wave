package echo.music.iad1tya.listentogether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Known Listen Together relays, in failover order.
 *
 * The old code trusted exactly one URL — and replaced it wholesale from a
 * remote server.json — so a single dead or unreachable relay made the
 * whole feature look broken ("cannot connect"). Now there is always a
 * built-in list, the remote entry is *added* to it (never a replacement),
 * and [ListenTogetherClient] rotates through [candidates] automatically
 * when a server cannot be reached.
 *
 * [ListenTogetherServer.format] matters: the Go metroserver relays speak
 * protobuf and reject JSON outright, while the older community relay
 * speaks JSON. Each candidate carries the format it expects; the client
 * can still flip at runtime if a server disagrees.
 */
@Serializable
data class ListenTogetherServer(
  val name: String,
  val url: String,
  val location: String,
  val operator: String,
  /** Wire format this relay expects: "protobuf" or "json". */
  val format: String = FORMAT_PROTOBUF,
) {
  companion object {
    const val FORMAT_PROTOBUF = "protobuf"
    const val FORMAT_JSON = "json"
  }
}

object ListenTogetherServers {
  private const val SERVER_JSON_URL =
    "https://raw.githubusercontent.com/EchoMusicApp/Echo-Music/refs/heads/main/app/server.json"

  private val builtins =
    listOf(
      ListenTogetherServer(
        name = "Metrolist Server",
        url = "wss://metroserverx.meowery.eu/ws",
        location = "Poland",
        operator = "Metrolist",
        format = ListenTogetherServer.FORMAT_PROTOBUF,
      ),
      ListenTogetherServer(
        name = "Metrolist Server (mirror)",
        url = "wss://metrolist.caliph.dev/ws",
        location = "Global",
        operator = "Community",
        format = ListenTogetherServer.FORMAT_PROTOBUF,
      ),
      ListenTogetherServer(
        name = "Hugging Face Sync",
        url = "wss://devilmi-vivi-music-listen-together.hf.space/ws",
        location = "Global",
        operator = "Community",
        format = ListenTogetherServer.FORMAT_JSON,
      ),
    )

  private val _servers = MutableStateFlow(builtins)

  val serversFlow: StateFlow<List<ListenTogetherServer>> = _servers

  val servers: List<ListenTogetherServer>
    get() = _servers.value

  init {
    GlobalScope.launch(Dispatchers.IO) {
      try {
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(SERVER_JSON_URL).build()
        val response = client.newCall(request).execute()
        response.body?.string()?.let { jsonString ->
          val jsonObject = Json.parseToJsonElement(jsonString).jsonObject
          val url = jsonObject["serverUrl"]?.jsonPrimitive?.content ?: return@let
          if (_servers.value.none { it.url == url }) {
            val name = jsonObject["name"]?.jsonPrimitive?.content ?: "Community Server"
            val region = jsonObject["region"]?.jsonPrimitive?.content ?: "Global"
            val fetched =
              ListenTogetherServer(
                name = name,
                url = url,
                location = region,
                operator = "",
                format = ListenTogetherServer.FORMAT_PROTOBUF,
              )
            // An additional candidate, never a replacement (see class doc).
            _servers.value = listOf(fetched) + _servers.value
          }
        }
      } catch (e: Exception) {
        // Built-in list retained.
      }
    }
  }

  val defaultServerUrl: String
    get() = servers.first().url

  fun findByUrl(url: String): ListenTogetherServer? = servers.firstOrNull { it.url == url }

  /** Expected wire format for [url]; protobuf unless a server says otherwise. */
  fun formatFor(url: String): String =
    findByUrl(url)?.format ?: ListenTogetherServer.FORMAT_PROTOBUF

  /**
   * Failover order for a connection attempt: the preferred (saved or
   * user-chosen) server first, then every other known server.
   */
  fun candidates(preferredUrl: String?): List<ListenTogetherServer> {
    val all = servers
    if (preferredUrl.isNullOrBlank()) return all
    val preferred =
      findByUrl(preferredUrl)
        ?: ListenTogetherServer(
          name = "Saved server",
          url = preferredUrl,
          location = "",
          operator = "",
          format = ListenTogetherServer.FORMAT_PROTOBUF,
        )
    return listOf(preferred) + all.filter { it.url != preferredUrl }
  }
}
