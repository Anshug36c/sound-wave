package echo.music.iad1tya.remote

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Free, no-account public tunnel for the bridge: an SSH reverse port
 * forward dialed OUT from the phone (no port forwarding, no account, no
 * binary). localhost.run is primary, serveo is the fallback; both assign a
 * random HTTPS URL per session, terminate TLS, and forward to the bridge.
 *
 * The tunnel only ever forwards to 127.0.0.1:<bridge port>, and the bridge
 * still requires pairing + session auth, so the public URL alone grants
 * nothing. Implemented with JSch (pure Java, BSD) because Android has no
 * system SSH client and desktop tunnel binaries cannot run inside the app.
 */
class SshTunnelManager(
  private val onState: (State) -> Unit = {},
) {
  enum class Provider(val host: String, val user: String, val label: String) {
    LOCALHOST_RUN("localhost.run", "nokey", "localhost.run"),
    SERVEO("serveo.net", "soundwave", "serveo"),
  }

  enum class Phase { OFF, CONNECTING, CONNECTED, ERROR, BACKOFF }

  data class State(
    val phase: Phase = Phase.OFF,
    val provider: Provider? = null,
    val publicUrl: String? = null,
    val message: String = "",
    val connectedAtMs: Long = 0L,
  )

  @Volatile var state: State = State()
    private set

  private val enabled = AtomicBoolean(false)
  private var worker: Thread? = null
  @Volatile private var session: Session? = null

  fun start(localPort: Int, provider: Provider, autoFallback: Boolean) {
    stop()
    enabled.set(true)
    worker =
      Thread({ runLoop(localPort, provider, autoFallback) }, "soundwave-tunnel").apply {
        isDaemon = true
        start()
      }
  }

  fun stop() {
    enabled.set(false)
    try {
      session?.disconnect()
    } catch (_: Exception) {
    }
    session = null
    worker?.interrupt()
    worker = null
    publish(State(phase = Phase.OFF))
  }

  private fun runLoop(localPort: Int, first: Provider, autoFallback: Boolean) {
    var provider = first
    var failures = 0
    while (enabled.get()) {
      try {
        publish(State(Phase.CONNECTING, provider, message = "Contacting ${provider.label}…"))
        val url = connectOnce(localPort, provider)
        failures = 0
        publish(
          State(Phase.CONNECTED, provider, publicUrl = url, connectedAtMs = System.currentTimeMillis())
        )
        // Hold until the session drops or we are stopped.
        while (enabled.get() && session?.isConnected == true) {
          Thread.sleep(2_000)
        }
        if (enabled.get()) throw IllegalStateException("Tunnel connection closed by the server.")
      } catch (e: Exception) {
        if (!enabled.get()) break
        failures++
        Timber.tag(TAG).w(e, "Tunnel via ${provider.label} failed (attempt $failures)")
        if (autoFallback && failures % 2 == 0) {
          provider = if (provider == Provider.LOCALHOST_RUN) Provider.SERVEO else Provider.LOCALHOST_RUN
        }
        val waitMs = minOf(60_000L, 5_000L * (1L shl minOf(failures - 1, 3)))
        publish(
          State(
            Phase.BACKOFF,
            provider,
            message = "${e.message ?: "Tunnel failed"} — retrying via ${provider.label} in ${waitMs / 1000}s",
          )
        )
        try {
          Thread.sleep(waitMs)
        } catch (_: InterruptedException) {
          break
        }
      } finally {
        try {
          session?.disconnect()
        } catch (_: Exception) {
        }
        session = null
      }
    }
    publish(State(phase = Phase.OFF))
  }

  /** Connect, forward, and wait until the service prints our public URL. */
  private fun connectOnce(localPort: Int, provider: Provider): String {
    val jsch = JSch()
    val s = jsch.getSession(provider.user, provider.host, 22)
    s.setConfig("StrictHostKeyChecking", "no")
    s.setConfig("PreferredAuthentications", "publickey,none")
    s.serverAliveInterval = 15_000
    s.setTimeout(30_000)
    session = s
    try {
      s.connect(30_000)
    } catch (e: Exception) {
      // Some endpoints want a (any) key instead of none-auth: retry once with
      // an ephemeral in-memory key that never leaves the device.
      Timber.tag(TAG).d("None-auth failed on ${provider.label}, retrying with ephemeral key: ${e.message}")
      try {
        s.disconnect()
      } catch (_: Exception) {
      }
      val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048)
      val priv = ByteArrayOutputStream()
      val pub = ByteArrayOutputStream()
      keyPair.writePrivateKey(priv)
      keyPair.writePublicKey(pub, "soundwave-remote")
      jsch.addIdentity("soundwave-ephemeral", priv.toByteArray(), pub.toByteArray(), null)
      val retry = jsch.getSession(provider.user, provider.host, 22)
      retry.setConfig("StrictHostKeyChecking", "no")
      retry.serverAliveInterval = 15_000
      retry.setTimeout(30_000)
      session = retry
      retry.connect(30_000)
      return finishConnect(retry, localPort, provider)
    }
    return finishConnect(s, localPort, provider)
  }

  private fun finishConnect(s: Session, localPort: Int, provider: Provider): String {
    s.setPortForwardingR(80, "127.0.0.1", localPort)
    val channel = s.openChannel("shell") as ChannelShell
    // Do not request a pty: localhost.run's SSH server never replies to
    // channel requests (pty/shell), which makes JSch throw
    // "channel request: timeout" even though the session and its banner
    // stream work fine. Open without a pty and tolerate that exception.
    channel.setPty(false)
    val input = channel.inputStream
    try {
      channel.connect(10_000)
    } catch (e: Exception) {
      Timber.tag(TAG).d("Shell request unanswered by ${provider.label} (expected there): ${e.message}")
    }
    val deadline = System.currentTimeMillis() + URL_WAIT_MS
    val text = StringBuilder()
    val buffer = ByteArray(4096)
    while (System.currentTimeMillis() < deadline) {
      if (!enabled.get()) throw IllegalStateException("Tunnel stopped.")
      if (!s.isConnected) throw IllegalStateException("Tunnel connection closed by the server.")
      val available = input.available()
      if (available > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size, available))
        if (read < 0) break
        text.append(String(buffer, 0, read, Charsets.UTF_8))
        findPublicUrl(text.toString(), provider)?.let { return it }
      } else {
        Thread.sleep(200)
      }
    }
    throw IllegalStateException("The tunnel service did not assign a URL in time.")
  }

  private fun findPublicUrl(text: String, provider: Provider): String? {
    // Prefer the explicit assignment lines each service prints; bare URLs in
    // banners include help/console links that are NOT our tunnel.
    SERVEO_FORWARD_REGEX.find(text)?.let { return it.groupValues[1] }
    LOCALHOST_RUN_REGEX.find(text)?.let { return "https://" + it.groupValues[1] }
    for (match in URL_REGEX.findAll(text)) {
      val host = match.groupValues[1].lowercase()
      val subdomain = host.substringBefore('.')
      if (subdomain in BANNER_LINK_SUBDOMAINS) continue
      val accepted =
        when (provider) {
          Provider.LOCALHOST_RUN ->
            host.endsWith(".lhr.life") || host.endsWith(".lhr.rocks") || host.endsWith(".localhost.run")
          Provider.SERVEO -> host.endsWith(".serveo.net")
        }
      if (accepted && host.count { it == '.' } >= 2) return "https://$host"
    }
    return null
  }

  private fun publish(newState: State) {
    state = newState
    onState(newState)
  }

  companion object {
    private const val TAG = "RemoteTunnel"
    private const val URL_WAIT_MS = 60_000L
    private val URL_REGEX = Regex("""https://([a-zA-Z0-9.-]+)""")
    private val SERVEO_FORWARD_REGEX = Regex("""Forwarding HTTP traffic from (https://\S+)""")
    private val LOCALHOST_RUN_REGEX =
      Regex("""([a-zA-Z0-9.-]+\.(?:lhr\.life|lhr\.rocks)) tunneled with tls termination""")
    private val BANNER_LINK_SUBDOMAINS = setOf("console", "www", "admin", "docs", "dashboard", "status")
  }
}
