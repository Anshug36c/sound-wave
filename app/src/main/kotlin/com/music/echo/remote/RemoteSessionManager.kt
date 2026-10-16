package echo.music.iad1tya.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Pairing, session credentials and stream grants for the Remote Web Player.
 *
 * Security model:
 * - The phone shows a 6-digit pairing code (10 min TTL, single use). Pairing
 *   is only accepted while the bridge is enabled and a code is active.
 * - A successful pair exchanges the code for a random 256-bit session token
 *   (12 h TTL, max [MAX_SESSIONS], revocable from the phone or by the web
 *   client itself). The token is never embedded in the website.
 * - Resolving a track mints a stream grant: a random, session-bound,
 *   expiring capability id. Only the grant id leaves the phone.
 * - Failed pairing is rate limited per source IP and globally, so the
 *   6-digit code cannot be brute-forced through the tunnel.
 *
 * This class knows nothing about the mobile player; remote playback state
 * reported by the browser is stored here only so the phone UI can show
 * "Web player: <track>" without touching local playback.
 */
class RemoteSessionManager(
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val random: SecureRandom = SecureRandom(),
) {

  data class Session(
    val token: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    @Volatile var lastSeenAtMs: Long,
    @Volatile var remoteState: RemotePlaybackReport = RemotePlaybackReport(),
    @Volatile var userAgent: String = "",
  ) {
    /** Token prefix safe to show in the phone UI for identification. */
    val label: String get() = "session …" + token.takeLast(4)

    fun toJson(): Map<String, Any?> =
      MiniJson.obj(
        "createdAtMs" to createdAtMs,
        "expiresAtMs" to expiresAtMs,
        "lastSeenAtMs" to lastSeenAtMs,
        "label" to label,
        "remoteState" to remoteState.toJson(),
      )
  }

  data class StreamGrant(
    val id: String,
    val sessionToken: String,
    val trackId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    @Volatile var source: ResolvedSource,
  )

  private val sessions = ConcurrentHashMap<String, Session>()
  private val grants = ConcurrentHashMap<String, StreamGrant>()

  @Volatile private var pairingCode: String? = null
  @Volatile private var pairingExpiresAtMs: Long = 0L

  private data class AttemptWindow(var count: Int = 0, var lockedUntilMs: Long = 0L)

  private val attemptsByIp = ConcurrentHashMap<String, AttemptWindow>()
  @Volatile private var globalFailures = 0
  @Volatile private var globalLockedUntilMs = 0L

  // ---------------------------------------------------------------- pairing

  /** The active pairing code, generating a fresh one when missing/expired. */
  @Synchronized
  fun currentPairingCode(): Pair<String, Long> {
    val now = clock()
    val existing = pairingCode
    if (existing != null && pairingExpiresAtMs > now) return existing to pairingExpiresAtMs
    return rotatePairingLocked(now)
  }

  /** Force a new pairing code (invalidates the previous one). */
  @Synchronized
  fun rotatePairing(): Pair<String, Long> = rotatePairingLocked(clock())

  private fun rotatePairingLocked(now: Long): Pair<String, Long> {
    val code = (100000 + random.nextInt(900000)).toString()
    pairingCode = code
    pairingExpiresAtMs = now + PAIRING_CODE_TTL_MS
    return code to pairingExpiresAtMs
  }

  /**
   * Exchange [code] for a session. Throws [RemoteException] with
   * PAIRING_INVALID / PAIRING_EXPIRED / RATE_LIMITED on failure.
   */
  @Synchronized
  fun pair(sourceIp: String, code: String?, userAgent: String): Session {
    val now = clock()
    sweepLocked(now)
    if (now < globalLockedUntilMs) throw RemoteException.rateLimited(PAIRING_LOCKED_MESSAGE)
    val window = attemptsByIp.computeIfAbsent(sourceIp) { AttemptWindow() }
    if (now < window.lockedUntilMs) throw RemoteException.rateLimited(PAIRING_LOCKED_MESSAGE)

    val active = pairingCode
    when {
      active == null || pairingExpiresAtMs <= now -> {
        registerPairingFailure(window, now)
        throw RemoteException.pairingExpired()
      }
      code == null || code.length != 6 || !code.all { it.isDigit() } -> {
        registerPairingFailure(window, now)
        throw RemoteException.pairingInvalid("Enter the 6-digit code shown in SoundWave.")
      }
      !constantTimeEquals(active, code) -> {
        registerPairingFailure(window, now)
        throw RemoteException.pairingInvalid()
      }
    }

    // Single use: a fresh code is required for the next pairing.
    rotatePairingLocked(now)
    attemptsByIp.remove(sourceIp)
    globalFailures = 0

    val token = randomToken()
    val session =
      Session(
        token = token,
        createdAtMs = now,
        expiresAtMs = now + SESSION_TTL_MS,
        lastSeenAtMs = now,
        userAgent = userAgent.take(120),
      )
    sessions[token] = session
    while (sessions.size > MAX_SESSIONS) {
      val oldest = sessions.values.minByOrNull { it.createdAtMs } ?: break
      revokeLocked(oldest.token)
    }
    return session
  }

  private fun registerPairingFailure(window: AttemptWindow, now: Long) {
    window.count += 1
    if (window.count >= MAX_PAIRING_FAILURES_PER_IP) {
      window.lockedUntilMs = now + PAIRING_LOCKOUT_MS
      window.count = 0
    }
    globalFailures += 1
    if (globalFailures >= MAX_GLOBAL_PAIRING_FAILURES) {
      globalLockedUntilMs = now + PAIRING_LOCKOUT_MS
      globalFailures = 0
    }
  }

  // ---------------------------------------------------------------- sessions

  fun authenticate(token: String?): Session {
    if (token.isNullOrBlank()) throw RemoteException.unauthorized()
    val now = clock()
    val session = sessions[token] ?: throw RemoteException.unauthorized()
    if (session.expiresAtMs <= now) {
      sessions.remove(token)
      throw RemoteException.unauthorized("Session expired. Pair again.")
    }
    session.lastSeenAtMs = now
    return session
  }

  fun findSession(token: String?): Session? {
    if (token.isNullOrBlank()) return null
    val session = sessions[token] ?: return null
    if (session.expiresAtMs <= clock()) {
      sessions.remove(token)
      return null
    }
    return session
  }

  fun listSessions(): List<Session> {
    sweep()
    return sessions.values.sortedBy { it.createdAtMs }
  }

  fun revoke(token: String) {
    synchronized(this) { revokeLocked(token) }
  }

  fun revokeAll() {
    synchronized(this) {
      sessions.clear()
      grants.clear()
    }
  }

  private fun revokeLocked(token: String) {
    sessions.remove(token)
    grants.values.removeIf { it.sessionToken == token }
  }

  // ---------------------------------------------------------------- grants

  fun createGrant(session: Session, source: ResolvedSource): StreamGrant {
    val now = clock()
    val grant =
      StreamGrant(
        id = randomToken(),
        sessionToken = session.token,
        trackId = source.trackId,
        createdAtMs = now,
        expiresAtMs = now + STREAM_GRANT_TTL_MS,
        source = source,
      )
    grants[grant.id] = grant
    // Keep the grant table small: drop expired grants and cap per-session grants.
    grants.values.removeIf { it.expiresAtMs <= now }
    val mine = grants.values.filter { it.sessionToken == session.token }.sortedBy { it.createdAtMs }
    if (mine.size > MAX_GRANTS_PER_SESSION) {
      mine.take(mine.size - MAX_GRANTS_PER_SESSION).forEach { grants.remove(it.id) }
    }
    return grant
  }

  fun grant(session: Session, grantId: String?): StreamGrant {
    if (grantId.isNullOrBlank()) throw RemoteException.notFound("Unknown stream.")
    val grant = grants[grantId] ?: throw RemoteException.notFound("Unknown or expired stream.")
    if (grant.sessionToken != session.token) throw RemoteException.unauthorized()
    if (grant.expiresAtMs <= clock()) {
      grants.remove(grantId)
      throw RemoteException.upstreamExpired("This stream link expired. Resolve the track again.")
    }
    return grant
  }

  // ---------------------------------------------------------------- remote state

  fun updateRemoteState(session: Session, report: RemotePlaybackReport) {
    session.remoteState = report
    session.lastSeenAtMs = clock()
  }

  /** Latest remote playback across sessions, for the phone UI. */
  fun latestRemoteState(): RemotePlaybackReport? =
    sessions.values
      .map { it.remoteState }
      .filter { it.updatedAtMs > 0 }
      .maxByOrNull { it.updatedAtMs }

  // ---------------------------------------------------------------- misc

  fun sweep() {
    synchronized(this) { sweepLocked(clock()) }
  }

  private fun sweepLocked(now: Long) {
    sessions.values.removeIf { it.expiresAtMs <= now }
    grants.values.removeIf { it.expiresAtMs <= now }
    attemptsByIp.values.removeIf { it.lockedUntilMs <= now && it.count == 0 }
  }

  private fun randomToken(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }

  private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

  companion object {
    const val PAIRING_CODE_TTL_MS = 10 * 60 * 1000L
    const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L
    const val STREAM_GRANT_TTL_MS = 20 * 60 * 1000L
    const val MAX_SESSIONS = 4
    const val MAX_GRANTS_PER_SESSION = 32
    const val MAX_PAIRING_FAILURES_PER_IP = 5
    const val MAX_GLOBAL_PAIRING_FAILURES = 25
    const val PAIRING_LOCKOUT_MS = 60 * 1000L
    const val PAIRING_LOCKED_MESSAGE =
      "Too many incorrect pairing attempts. Wait a minute, then try again."
  }
}
