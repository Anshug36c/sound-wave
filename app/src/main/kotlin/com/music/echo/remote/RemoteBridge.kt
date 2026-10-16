package echo.music.iad1tya.remote

import android.content.Context
import android.content.SharedPreferences
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Owns the Remote Web Player runtime: the HTTP bridge, pairing/session
 * state and the optional free tunnel. Started and stopped exclusively via
 * [RemoteBridgeService] (a foreground service) — never by playback code.
 *
 * Isolation invariant: this object never references the mobile player. The
 * only shared pieces are [YouTubeMusicProvider]'s search/extraction calls,
 * which stop at a URL and cannot start, pause or requeue local playback.
 */
object RemoteBridge {

  data class Status(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val port: Int = RemoteHttpServer.DEFAULT_PORT,
    val lanUrls: List<String> = emptyList(),
    val pairingCode: String = "",
    val pairingExpiresAtMs: Long = 0L,
    val tunnelEnabled: Boolean = false,
    val tunnel: SshTunnelManager.State = SshTunnelManager.State(),
    val sessionCount: Int = 0,
    val remoteState: RemotePlaybackReport? = null,
    val lastError: String = "",
  )

  private const val PREFS = "soundwave_remote_bridge"
  private const val KEY_ENABLED = "enabled"
  private const val KEY_PORT = "port"
  private const val KEY_TUNNEL_ENABLED = "tunnel_enabled"
  private const val KEY_TUNNEL_PROVIDER = "tunnel_provider"
  private const val KEY_SESSION_STATE = "session_state_json"

  private val _status = MutableStateFlow(Status())
  val status: StateFlow<Status> = _status.asStateFlow()

  private var appContext: Context? = null
  private var server: RemoteHttpServer? = null
  private var sessionManager: RemoteSessionManager? = null
  private var tunnelManager: SshTunnelManager? = null
  private var pageBytes: ByteArray? = null

  private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

  fun tunnelProvider(context: Context): SshTunnelManager.Provider {
    val name = prefs(context).getString(KEY_TUNNEL_PROVIDER, SshTunnelManager.Provider.LOCALHOST_RUN.name)
    return runCatching { SshTunnelManager.Provider.valueOf(name ?: "") }
      .getOrDefault(SshTunnelManager.Provider.LOCALHOST_RUN)
  }

  @Synchronized
  fun start(context: Context) {
    val app = context.applicationContext
    appContext = app
    val preferences = prefs(app)
    val port = preferences.getInt(KEY_PORT, RemoteHttpServer.DEFAULT_PORT)
    if (server?.isRunning == true) {
      refreshStatus()
      return
    }
    try {
      val sessions =
        sessionManager
          ?: RemoteSessionManager().also { manager ->
            sessionManager = manager
            // Sessions survive process death (OEM battery killers restart
            // the app process; a paired browser must not be logged out by
            // that). Stored only in app-private preferences.
            prefs(app).getString(KEY_SESSION_STATE, null)?.let { saved ->
              manager.importState(saved)
            }
            manager.onStateChanged = {
              try {
                prefs(app).edit().putString(KEY_SESSION_STATE, manager.exportState()).apply()
              } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not persist remote sessions")
              }
            }
          }
      // A pairing code must exist from the moment the bridge is up, so the
      // user can pair without first opening this settings screen.
      sessions.currentPairingCode()
      val page = loadPage(app)
      val httpServer =
        RemoteHttpServer(
          port = port,
          provider = YouTubeMusicProvider(),
          sessionManager = sessions,
          fetcher = OkHttpUpstreamFetcher(),
          pageProvider = { page },
          log = { message -> Timber.tag(TAG).d(message) },
        )
      httpServer.start()
      server = httpServer

      if (preferences.getBoolean(KEY_TUNNEL_ENABLED, false)) {
        startTunnelLocked(app, port)
      } else {
        tunnelManager?.stop()
      }
      preferences.edit().putBoolean(KEY_ENABLED, true).apply()
      refreshStatus()
      Timber.tag(TAG).i("Remote Web Player on :$port")
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Failed to start Remote Web Player")
      _status.value = _status.value.copy(enabled = isEnabled(app), running = false, lastError = e.message ?: "Failed to start")
    }
  }

  @Synchronized
  fun stop(context: Context? = null) {
    val app = appContext
    tunnelManager?.stop()
    tunnelManager = null
    try {
      server?.stop()
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Bridge stop failed")
    }
    server = null
    if (app != null) prefs(app).edit().putBoolean(KEY_ENABLED, false).apply()
    _status.value = Status(enabled = false, port = _status.value.port)
  }

  @Synchronized
  fun restart(context: Context) {
    val wasEnabled = isEnabled(context)
    stop(context)
    if (wasEnabled) start(context)
  }

  @Synchronized
  fun setPort(context: Context, port: Int) {
    prefs(context).edit().putInt(KEY_PORT, port).apply()
    if (server?.isRunning == true) restart(context) else refreshStatus()
  }

  @Synchronized
  fun setTunnelEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_TUNNEL_ENABLED, enabled).apply()
    if (server?.isRunning == true) {
      if (enabled) startTunnelLocked(context.applicationContext, server!!.port) else tunnelManager?.stop()
    }
    refreshStatus()
  }

  @Synchronized
  fun setTunnelProvider(context: Context, provider: SshTunnelManager.Provider) {
    prefs(context).edit().putString(KEY_TUNNEL_PROVIDER, provider.name).apply()
    if (server?.isRunning == true && prefs(context).getBoolean(KEY_TUNNEL_ENABLED, false)) {
      startTunnelLocked(context.applicationContext, server!!.port)
    }
    refreshStatus()
  }

  private fun startTunnelLocked(app: Context, port: Int) {
    tunnelManager?.stop()
    val manager =
      SshTunnelManager(onState = { tunnelState ->
        _status.value = _status.value.copy(tunnel = tunnelState, running = server?.isRunning == true, lanUrls = lanUrls(port))
      })
    tunnelManager = manager
    manager.start(port, tunnelProvider(app), autoFallback = true)
  }

  @Synchronized
  fun rotatePairing(): Pair<String, Long>? {
    val sessions = sessionManager ?: return null
    val result = sessions.rotatePairing()
    refreshStatus()
    return result
  }

  @Synchronized
  fun revokeAllSessions() {
    sessionManager?.revokeAll()
    refreshStatus()
  }

  @Synchronized
  fun revokeSession(tokenSuffix: String) {
    val sessions = sessionManager ?: return
    sessions.listSessions().firstOrNull { it.token.endsWith(tokenSuffix) }?.let { sessions.revoke(it.token) }
    refreshStatus()
  }

  fun sessions(): List<RemoteSessionManager.Session> = sessionManager?.listSessions().orEmpty()

  /** Re-read dynamic state (LAN addresses change with the network). */
  @Synchronized
  fun refreshStatus() {
    val app = appContext
    val port =
      app?.let { prefs(it).getInt(KEY_PORT, RemoteHttpServer.DEFAULT_PORT) }
        ?: server?.port
        ?: _status.value.port
    val sessions = sessionManager
    val (code, expires) = sessions?.currentPairingCode() ?: ("" to 0L)
    _status.value =
      Status(
        enabled = app?.let { isEnabled(it) } ?: _status.value.enabled,
        running = server?.isRunning == true,
        port = port,
        lanUrls = if (server?.isRunning == true) lanUrls(port) else emptyList(),
        pairingCode = code,
        pairingExpiresAtMs = expires,
        tunnelEnabled = app?.let { prefs(it).getBoolean(KEY_TUNNEL_ENABLED, false) } ?: false,
        tunnel = tunnelManager?.state ?: SshTunnelManager.State(),
        sessionCount = sessions?.listSessions()?.size ?: 0,
        remoteState = sessions?.latestRemoteState(),
        lastError = "",
      )
  }

  fun lanUrls(port: Int): List<String> {
    val addresses = ArrayList<String>()
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
      for (iface in interfaces.toList()) {
        if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
        for (address in iface.inetAddresses.toList()) {
          if (address is Inet4Address && !address.isLoopbackAddress) {
            val ip = address.hostAddress ?: continue
            val entry = "http://$ip:$port"
            // Wi-Fi first: it is the address the iPhone can actually reach.
            if (iface.name.startsWith("wlan") || iface.name.startsWith("wifi")) {
              addresses.add(0, entry)
            } else {
              addresses.add(entry)
            }
          }
        }
      }
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Could not enumerate LAN addresses")
    }
    return addresses.distinct()
  }

  private fun loadPage(context: Context): ByteArray {
    pageBytes?.let { return it }
    val bytes =
      context.assets.open("remote.html").use { it.readBytes() }
    pageBytes = bytes
    return bytes
  }

  private const val TAG = "RemoteBridge"
}
