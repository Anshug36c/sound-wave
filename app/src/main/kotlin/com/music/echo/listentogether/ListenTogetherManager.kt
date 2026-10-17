package echo.music.iad1tya.listentogether

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.ListenTogetherSyncVolumeKey
import echo.music.iad1tya.extensions.currentMetadata
import echo.music.iad1tya.extensions.metadata
import echo.music.iad1tya.extensions.toMediaItem
import echo.music.iad1tya.models.MediaMetadata
import echo.music.iad1tya.models.MediaMetadata.Album
import echo.music.iad1tya.models.MediaMetadata.Artist
import echo.music.iad1tya.playback.PlayerConnection
import echo.music.iad1tya.utils.dataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Listen Together engine (rewritten).
 *
 * Product rules, enforced here and nowhere else:
 * 1. **Same song for everyone.** The host broadcasts track id + queue +
 *    position (stamped in server time). Guests load that exact track id,
 *    project the position onto "now" through [SyncClock] (which compensates
 *    clock skew and transit delay), and mirror play/pause/seek. Small drift
 *    is converged at ±5% playback speed; larger drift is corrected by a seek.
 * 2. **Only the host controls.** Guests cannot send playback actions, their
 *    local transport is blocked while in a room ([PlayerConnection]
 *    `shouldBlockPlaybackChanges` + the guest UI gate), and participant
 *    control no longer exists as a concept.
 * 3. **A room exits only when the user exits.** Network drops, relay
 *    failures and reconnect exhaustion never tear the session down: the
 *    client keeps retrying, the guest keeps playing the current song, and
 *    on reconnect everyone resyncs to the host. Cleanup happens on manual
 *    leave/disconnect, or when the host removes this user (kick).
 *
 * Guests never wait on room-wide buffer coordination: a guest starts the
 * moment *its own* player is ready, at the projected host position. That
 * removes the old "buffer complete never arrived -> paused forever" class
 * of bugs; BUFFER_READY is still reported for relay bookkeeping.
 */
@Singleton
class ListenTogetherManager
@Inject
constructor(
  private val client: ListenTogetherClient,
  @ApplicationContext private val context: Context,
) {
  companion object {
    private const val TAG = "ListenTogetherManager"

    /** While paused (or starting), seek if the position is off by more than this. */
    private const val POSITION_TOLERANCE_MS = 1000L

    /** While playing, hard-seek only beyond this; inside it, nudge speed instead. */
    private const val PLAYBACK_TOLERANCE_MS = 1250L
    private const val SOFT_DRIFT_MIN_MS = 250L
    private const val SOFT_DRIFT_SPEED = 0.05f

    private const val READY_WAIT_TIMEOUT_MS = 12_000L
    private const val HEARTBEAT_INTERVAL_MS = 5000L
  }

  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  // ------------------------------------------------------------------ state

  private var playerConnection: PlayerConnection? = null
  private var hostListenerRegistered = false

  /** True while this device is applying a host-driven change (echo guard). */
  @Volatile private var applyingRemote = false

  private var lastHostTrackId: String? = null
  private var lastHostIsPlaying: Boolean? = null

  /** Track the guest is currently loading/playing, to drop stale applies. */
  private var applyGeneration = 0

  private var previousMuteState: Boolean? = null

  private val syncClock = SyncClock()
  private val syncHostVolumeEnabled = MutableStateFlow(true)
  private var lastSyncedVolume: Float? = null

  private var eventCollectorJob: Job? = null
  private var stateCollectorJobs: List<Job> = emptyList()
  private var heartbeatJob: Job? = null
  private var queueObserverJob: Job? = null
  private var volumeObserverJob: Job? = null
  private var applyJob: Job? = null

  // ------------------------------------------------------------- public API

  val connectionState = client.connectionState
  val roomState = client.roomState
  val role = client.role
  val userId = client.userId
  val pendingJoinRequests = client.pendingJoinRequests
  val bufferingUsers = client.bufferingUsers
  val logs = client.logs
  val events = client.events
  val blockedUsernames = client.blockedUsernames
  val pendingSuggestions = client.pendingSuggestions
  val rtt = client.rtt

  private val _chatMessages = MutableStateFlow<List<ChatMessagePayload>>(emptyList())
  val chatMessages = _chatMessages

  val isInRoom: Boolean
    get() = client.isInRoom

  val isHost: Boolean
    get() = client.isHost

  val hasPersistedSession: Boolean
    get() = client.hasPersistedSession

  /** Host-only control: participant control was removed by design. */
  val allowParticipantControl: Boolean
    get() = false

  val canControlMusic: Boolean
    get() = !isInRoom || isHost

  val isGuestPlaybackRestricted: Boolean
    get() = isInRoom && !isHost

  val guestPlaybackRestricted =
    combine(client.roomState, client.role) { state, role ->
        state != null && role == RoomRole.GUEST
      }
      .stateIn(scope, SharingStarted.WhileSubscribed(5000), false)

  init {
    initialize()
    observePreferences()
  }

  // ------------------------------------------------------------ player hook

  fun setPlayerConnection(connection: PlayerConnection?) {
    Timber.tag(TAG).d("setPlayerConnection: ${connection != null}, isInRoom: $isInRoom")
    val old = playerConnection
    if (hostListenerRegistered && old != null) {
      try {
        old.player.removeListener(hostListener)
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Error removing host listener")
      }
      hostListenerRegistered = false
    }
    old?.shouldBlockPlaybackChanges = null
    old?.onSkipPrevious = null
    old?.onSkipNext = null
    old?.onRestartSong = null

    playerConnection = connection
    refreshEngine()
  }

  /**
   * (Re)wires everything that depends on role/room membership:
   * host broadcasting vs guest blocking. Idempotent; called on every
   * role/room/player change.
   */
  private fun refreshEngine() {
    val connection = playerConnection ?: return

    // Guests' local transport is inert in a room (rule 2). The manager's
    // own applies bypass this via allowInternalSync / direct player calls.
    connection.shouldBlockPlaybackChanges = { isInRoom && !isHost }

    connection.onSkipPrevious = {
      if (isHost && isInRoom && !applyingRemote) {
        guardedSend { client.sendPlaybackAction(PlaybackActions.SKIP_PREV) }
      }
    }
    connection.onSkipNext = {
      if (isHost && isInRoom && !applyingRemote) {
        guardedSend { client.sendPlaybackAction(PlaybackActions.SKIP_NEXT) }
      }
    }
    connection.onRestartSong = {
      if (isHost && isInRoom && !applyingRemote) {
        guardedSend { client.sendPlaybackAction(PlaybackActions.SEEK, position = 1L) }
      }
    }

    if (isInRoom && isHost) {
      if (!hostListenerRegistered) {
        try {
          connection.player.addListener(hostListener)
          hostListenerRegistered = true
        } catch (e: Exception) {
          Timber.tag(TAG).e(e, "Failed to add host listener")
        }
      }
      startQueueObserver()
      startVolumeObserver()
      startHeartbeat()
    } else {
      if (hostListenerRegistered) {
        try {
          connection.player.removeListener(hostListener)
        } catch (e: Exception) {
          Timber.tag(TAG).e(e, "Failed to remove host listener")
        }
        hostListenerRegistered = false
      }
      stopQueueObserver()
      stopVolumeObserver()
      stopHeartbeat()
    }
  }

  // -------------------------------------------------------------- lifecycle

  fun initialize() {
    Timber.tag(TAG).d("Initializing ListenTogetherManager")
    eventCollectorJob?.cancel()
    eventCollectorJob =
      scope.launch {
        client.events.collect { event ->
          try {
            handleEvent(event)
          } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error handling event: $event")
          }
        }
      }

    // Called from both MainActivity and the ViewModel: rebuild, never stack.
    stateCollectorJobs.forEach { it.cancel() }
    stateCollectorJobs =
      listOf(
        scope.launch {
          roomState.collect { state ->
            val lastUpdate = state?.lastUpdate ?: return@collect
            if (lastUpdate > 0) {
              syncClock.onServerTimestamp(
                lastUpdate,
                System.currentTimeMillis(),
                client.rtt.value,
              )
            }
          }
        },
        scope.launch {
          role.collect { refreshEngine() }
        },
        scope.launch {
          roomState.map { it != null }.distinctUntilChanged().collect { refreshEngine() }
        },
      )
  }

  private fun observePreferences() {
    scope.launch {
      context.dataStore.data
        .map {
          (try {
            it[ListenTogetherSyncVolumeKey]
          } catch (e: Exception) {
            null
          }) ?: true
        }
        .distinctUntilChanged()
        .collect { enabled -> syncHostVolumeEnabled.value = enabled }
    }
  }

  /**
   * Tears down local room state. ONLY called for manual leave/disconnect
   * and kicks (rule 3) — never for network or relay failures.
   */
  private fun cleanup() {
    applyJob?.cancel()
    applyJob = null
    stopHeartbeat()
    stopQueueObserver()
    stopVolumeObserver()
    if (hostListenerRegistered) {
      try {
        playerConnection?.player?.removeListener(hostListener)
      } catch (_: Exception) {
      }
      hostListenerRegistered = false
    }
    restoreGuestMuteState()
    setGuestPlaybackSpeed(1f)
    lastHostTrackId = null
    lastHostIsPlaying = null
    applyingRemote = false
    ++applyGeneration
    _chatMessages.value = emptyList()
  }

  // ---------------------------------------------------------------- events

  private fun handleEvent(event: ListenTogetherEvent) {
    when (event) {
      is ListenTogetherEvent.RoomCreated -> {
        Timber.tag(TAG).d("Room created: ${event.roomCode}")
        refreshEngine()
        // If something is already playing, the room starts on it.
        broadcastCurrentTrack()
      }
      is ListenTogetherEvent.JoinApproved -> {
        Timber.tag(TAG).d("Join approved for room: ${event.roomCode}")
        saveMuteStateOnJoin()
        syncClock.onServerTimestamp(
          event.state.lastUpdate,
          System.currentTimeMillis(),
          client.rtt.value,
        )
        applyRoomState(event.state)
        applyHostVolumeIfNeeded(event.state.volume)
        refreshEngine()
      }
      is ListenTogetherEvent.PlaybackSync -> {
        event.action.serverTime?.let {
          syncClock.onServerTimestamp(it, System.currentTimeMillis(), client.rtt.value)
        }
        if (!isHost) handlePlaybackSync(event.action)
      }
      is ListenTogetherEvent.SyncStateReceived -> {
        syncClock.onServerTimestamp(
          event.state.lastUpdate,
          System.currentTimeMillis(),
          client.rtt.value,
        )
        if (!isHost) {
          applyRoomState(
            RoomState(
              roomCode = roomState.value?.roomCode ?: "",
              hostId = roomState.value?.hostId ?: "",
              users = roomState.value?.users ?: emptyList(),
              currentTrack = event.state.currentTrack,
              isPlaying = event.state.isPlaying,
              // Raw position + its server timestamp; applyRoomState projects.
              position = event.state.position,
              lastUpdate = event.state.lastUpdate,
              volume = event.state.volume ?: 1f,
              queue = event.state.queue ?: emptyList(),
            )
          )
          applyHostVolumeIfNeeded(event.state.volume)
        }
      }
      is ListenTogetherEvent.UserJoined -> {
        // New listener: push the host's current song + transport to the room.
        if (isHost) broadcastCurrentTrack()
      }
      is ListenTogetherEvent.Reconnected -> {
        Timber.tag(TAG).d("Reconnected to room ${event.roomCode}, isHost=${event.isHost}")
        refreshEngine()
        if (event.isHost) {
          // Republish if the room's idea of the song differs from ours.
          val metadata = playerConnection?.player?.currentMetadata
          if (metadata != null && metadata.id != event.state.currentTrack?.id) {
            broadcastCurrentTrack()
          } else {
            broadcastPlayState()
          }
        } else {
          // Guest: land exactly where the host is now — never exit, resync.
          syncClock.onServerTimestamp(
            event.state.lastUpdate,
            System.currentTimeMillis(),
            client.rtt.value,
          )
          applyRoomState(event.state)
          applyHostVolumeIfNeeded(event.state.volume)
        }
      }
      is ListenTogetherEvent.HostChanged -> {
        Timber.tag(TAG).d("Host changed: ${event.newHostName}")
        refreshEngine()
        if (event.newHostId == userId.value) broadcastCurrentTrack()
      }
      is ListenTogetherEvent.LocalSuggestionApproved -> {
        try {
          val mediaItem = event.payload.trackInfo.toMediaMetadata().toMediaItem()
          playerConnection?.playNext(mediaItem)
        } catch (e: Exception) {
          Timber.tag(TAG).e(e, "Error adding approved suggestion")
        }
      }
      is ListenTogetherEvent.Kicked -> {
        // The host removed us: that's a deliberate act, so leave cleanly.
        Timber.tag(TAG).d("Kicked from room: ${event.reason}")
        cleanup()
      }
      is ListenTogetherEvent.ConnectionError -> {
        // Rule 3: a failed connection NEVER exits the room. The client is
        // retrying with backoff; we keep the session (and the music) and
        // resync when it lands.
        Timber.tag(TAG).e("Connection error (staying in room): ${event.error}")
      }
      is ListenTogetherEvent.Disconnected -> {
        Timber.tag(TAG).d("Disconnected (staying in room; reconnect will resync)")
      }
      is ListenTogetherEvent.Reconnecting -> {
        Timber.tag(TAG).d("Reconnecting: attempt ${event.attempt}")
      }
      is ListenTogetherEvent.ChatMessageReceived -> {
        _chatMessages.value = _chatMessages.value + event.payload
      }
      else -> {}
    }
  }

  // -------------------------------------------------------- host broadcast

  private val hostListener =
    object : Player.Listener {
      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (applyingRemote || !isHost || !isInRoom) return
        val player = playerConnection?.player ?: return
        val trackId = player.currentMediaItem?.mediaId
        if (trackId != null && trackId != lastHostTrackId) {
          broadcastCurrentTrack() // includes the play state
        } else {
          broadcastPlayState()
        }
      }

      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (applyingRemote || !isHost || !isInRoom) return
        if (mediaItem == null) return
        if (mediaItem.mediaId == lastHostTrackId) return
        broadcastCurrentTrack()
      }

      override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
      ) {
        if (applyingRemote || !isHost || !isInRoom) return
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
          guardedSend {
            client.sendPlaybackAction(PlaybackActions.SEEK, position = newPosition.positionMs)
          }
        }
      }
    }

  /** Sends the host's current song (TrackInfo + queue) to the room. */
  private fun broadcastCurrentTrack() {
    if (!isHost || !isInRoom) return
    val connection = playerConnection ?: return
    val player = connection.player
    val metadata = player.currentMetadata ?: return
    lastHostTrackId = metadata.id
    val queue =
      try {
        connection.queueWindows.value.map { it.toTrackInfo() }
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to read queue for broadcast")
        null
      }
    val queueTitle =
      try {
        connection.queueTitle.value
      } catch (e: Exception) {
        null
      }
    guardedSend {
      client.sendPlaybackAction(
        PlaybackActions.CHANGE_TRACK,
        trackInfo = metadata.toTrackInfo(),
        queue = queue,
        queueTitle = queueTitle,
      )
    }
    broadcastPlayState()
  }

  /** Sends PLAY/PAUSE at the host's current position, when it changed. */
  private fun broadcastPlayState() {
    if (!isHost || !isInRoom) return
    val player = playerConnection?.player ?: return
    val playing = player.playWhenReady
    val position = player.currentPosition
    guardedSend {
      client.sendPlaybackAction(
        if (playing) PlaybackActions.PLAY else PlaybackActions.PAUSE,
        position = position,
      )
    }
    lastHostIsPlaying = playing
  }

  // ----------------------------------------------------------- guest apply

  private fun handlePlaybackSync(action: PlaybackActionPayload) {
    val connection = playerConnection ?: return
    val player = connection.player
    val now = System.currentTimeMillis()

    when (action.action) {
      PlaybackActions.PLAY -> {
        val target =
          syncClock.adjustedPositionMs(action.position ?: 0L, action.serverTime, true, now)
        if (player.playWhenReady) {
          val drift = target - player.currentPosition
          when {
            kotlin.math.abs(drift) > PLAYBACK_TOLERANCE_MS -> {
              setGuestPlaybackSpeed(1f)
              connection.seekTo(target)
            }
            else -> applyGuestDriftCorrection(drift)
          }
        } else {
          if (kotlin.math.abs(player.currentPosition - target) > POSITION_TOLERANCE_MS) {
            connection.seekTo(target)
          }
          connection.play()
        }
      }
      PlaybackActions.PAUSE -> {
        val target = action.position ?: 0L
        setGuestPlaybackSpeed(1f)
        if (player.playWhenReady) connection.pause()
        if (kotlin.math.abs(player.currentPosition - target) > POSITION_TOLERANCE_MS) {
          connection.seekTo(target)
        }
      }
      PlaybackActions.SEEK -> {
        // Seeks are always honoured (never debounced away), projected
        // through the server clock like everything else.
        val target =
          syncClock.adjustedPositionMs(
            action.position ?: 0L,
            action.serverTime,
            player.playWhenReady,
            now,
          )
        if (kotlin.math.abs(player.currentPosition - target) > POSITION_TOLERANCE_MS) {
          setGuestPlaybackSpeed(1f)
          connection.seekTo(target)
        }
      }
      PlaybackActions.CHANGE_TRACK -> {
        val track = action.trackInfo ?: return
        setGuestPlaybackSpeed(1f)
        loadGuestQueue(
          currentTrack = track,
          queue = action.queue,
          queueTitle = action.queueTitle,
          positionMs = 0L,
          playing = false,
        )
      }
      PlaybackActions.SKIP_NEXT -> {
        setGuestPlaybackSpeed(1f)
        connection.seekToNext()
      }
      PlaybackActions.SKIP_PREV -> {
        setGuestPlaybackSpeed(1f)
        connection.seekToPrevious()
      }
      PlaybackActions.QUEUE_ADD -> {
        val track = action.trackInfo ?: return
        val mediaItem = track.toMediaMetadata().toMediaItem()
        connection.allowInternalSync = true
        try {
          if (action.insertNext == true) connection.playNext(mediaItem)
          else connection.addToQueue(mediaItem)
        } finally {
          connection.allowInternalSync = false
        }
      }
      PlaybackActions.QUEUE_REMOVE -> {
        val removeId = action.trackId
        if (!removeId.isNullOrEmpty()) {
          for (i in (player.currentMediaItemIndex + 1) until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == removeId) {
              player.removeMediaItem(i)
              break
            }
          }
        }
      }
      PlaybackActions.QUEUE_CLEAR -> {
        val from = player.currentMediaItemIndex + 1
        if (player.mediaItemCount > from) {
          player.removeMediaItems(from, player.mediaItemCount - from)
        }
      }
      PlaybackActions.SYNC_QUEUE -> {
        val queue = action.queue ?: return
        syncGuestQueue(queue, action.queueTitle)
      }
      PlaybackActions.SET_VOLUME -> applyHostVolumeIfNeeded(action.volume)
    }
  }

  /** Applies a full room snapshot (join / reconnect / sync-state). */
  private fun applyRoomState(state: RoomState) {
    val track = state.currentTrack
    if (track == null) {
      playerConnection?.pause()
      return
    }
    val position =
      syncClock.adjustedPositionMs(
        state.position,
        state.lastUpdate,
        state.isPlaying,
        System.currentTimeMillis(),
      )
    loadGuestQueue(
      currentTrack = track,
      queue = state.queue,
      queueTitle = null,
      positionMs = position,
      playing = state.isPlaying,
    )
  }

  /**
   * Loads the host's exact song (and queue) and lands at [positionMs].
   * Starts as soon as THIS player is ready — at the position projected to
   * that moment — and never waits on other guests.
   */
  private fun loadGuestQueue(
    currentTrack: TrackInfo,
    queue: List<TrackInfo>?,
    queueTitle: String?,
    positionMs: Long,
    playing: Boolean,
  ) {
    val connection = playerConnection ?: return
    val generation = ++applyGeneration
    applyJob?.cancel()
    applyJob =
      scope.launch {
        applyingRemote = true
        connection.allowInternalSync = true
        try {
          val player = connection.player
          val items =
            if (!queue.isNullOrEmpty()) {
              queue.map { it.toMediaMetadata().toMediaItem() }
            } else {
              listOf(currentTrack.toMediaMetadata().toMediaItem())
            }
          var startIndex = items.indexOfFirst { it.mediaId == currentTrack.id }
          if (startIndex < 0) startIndex = 0
          player.setMediaItems(items, startIndex, positionMs)
          try {
            connection.service.queueTitle = queueTitle ?: "Listen Together"
          } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set queue title")
          }
          connection.pause()

          // Wait for our own first frame, compensating for the wait: the
          // host kept playing, so land where it is when we actually start.
          val waitStartMs = System.currentTimeMillis()
          var waitedMs = 0L
          while (player.playbackState != Player.STATE_READY && waitedMs < READY_WAIT_TIMEOUT_MS) {
            if (generation != applyGeneration) return@launch
            delay(50)
            waitedMs += 50
          }
          if (generation != applyGeneration) return@launch

          val driftMs = if (playing) System.currentTimeMillis() - waitStartMs else 0L
          player.seekTo(positionMs + driftMs)
          if (playing) connection.play() else connection.pause()
          client.sendBufferReady(currentTrack.id)
        } catch (e: Exception) {
          Timber.tag(TAG).e(e, "Error applying guest state")
        } finally {
          connection.allowInternalSync = false
          delay(200)
          if (generation == applyGeneration) applyingRemote = false
        }
      }
  }

  /** Replaces the guest's upcoming queue with the host's, keeping the current song. */
  private fun syncGuestQueue(queue: List<TrackInfo>, queueTitle: String?) {
    val connection = playerConnection ?: return
    val player = connection.player
    val items = queue.map { it.toMediaMetadata().toMediaItem() }
    val currentId = player.currentMediaItem?.mediaId
    val newIndex = if (currentId != null) items.indexOfFirst { it.mediaId == currentId } else -1
    val position = player.currentPosition
    val wasPlaying = player.playWhenReady
    connection.allowInternalSync = true
    try {
      if (newIndex >= 0) player.setMediaItems(items, newIndex, position)
      else player.setMediaItems(items)
      try {
        connection.service.queueTitle = queueTitle
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to set queue title (sync queue)")
      }
    } finally {
      connection.allowInternalSync = false
    }
    if (wasPlaying && !player.playWhenReady) connection.play()
  }

  // ------------------------------------------------------------- drift help

  private fun applyGuestDriftCorrection(driftMs: Long) {
    val speed =
      when {
        kotlin.math.abs(driftMs) < SOFT_DRIFT_MIN_MS -> 1f
        driftMs > 0 -> 1f + SOFT_DRIFT_SPEED
        else -> 1f - SOFT_DRIFT_SPEED
      }
    setGuestPlaybackSpeed(speed)
  }

  private fun setGuestPlaybackSpeed(speed: Float) {
    if (isHost) return
    try {
      val player = playerConnection?.player ?: return
      if (kotlin.math.abs(player.playbackParameters.speed - speed) > 0.001f) {
        player.setPlaybackSpeed(speed)
      }
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Failed to set guest playback speed")
    }
  }

  // --------------------------------------------------------------- observers

  private fun startHeartbeat() {
    if (heartbeatJob?.isActive == true) return
    heartbeatJob =
      scope.launch {
        while (isInRoom && isHost) {
          delay(HEARTBEAT_INTERVAL_MS)
          val player = playerConnection?.player ?: continue
          if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
            guardedSend {
              client.sendPlaybackAction(PlaybackActions.PLAY, position = player.currentPosition)
            }
          }
        }
      }
  }

  private fun stopHeartbeat() {
    heartbeatJob?.cancel()
    heartbeatJob = null
  }

  private fun startQueueObserver() {
    if (queueObserverJob?.isActive == true) return
    queueObserverJob =
      scope.launch {
        playerConnection
          ?.queueWindows
          ?.map { windows -> windows.map { it.toTrackInfo() } }
          ?.distinctUntilChanged()
          ?.collect { tracks ->
            if (!isHost || !isInRoom || applyingRemote) return@collect
            delay(500)
            val title =
              try {
                playerConnection?.queueTitle?.value
              } catch (e: Exception) {
                null
              }
            guardedSend {
              client.sendPlaybackAction(
                PlaybackActions.SYNC_QUEUE,
                queue = tracks,
                queueTitle = title,
              )
            }
          }
      }
  }

  private fun stopQueueObserver() {
    queueObserverJob?.cancel()
    queueObserverJob = null
  }

  private fun startVolumeObserver() {
    if (volumeObserverJob?.isActive == true) return
    volumeObserverJob =
      scope.launch {
        playerConnection?.service?.playerVolume?.collect { volume ->
          if (!isHost || !isInRoom || !syncHostVolumeEnabled.value) return@collect
          val normalized = volume.coerceIn(0f, 1f)
          val last = lastSyncedVolume
          if (last != null && kotlin.math.abs(last - normalized) < 0.01f) return@collect
          lastSyncedVolume = normalized
          client.sendPlaybackAction(PlaybackActions.SET_VOLUME, volume = normalized)
        }
      }
  }

  private fun stopVolumeObserver() {
    volumeObserverJob?.cancel()
    volumeObserverJob = null
    lastSyncedVolume = null
  }

  private fun applyHostVolumeIfNeeded(volume: Float?) {
    if (!syncHostVolumeEnabled.value || isHost || !isInRoom) return
    val connection = playerConnection ?: return
    val target = volume?.coerceIn(0f, 1f) ?: return
    try {
      connection.service.playerVolume.value = target
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Failed to apply host volume")
    }
  }

  // ------------------------------------------------------------------- mute

  private fun saveMuteStateOnJoin() {
    val connection = playerConnection ?: return
    if (previousMuteState == null) {
      previousMuteState = connection.isMuted.value
    }
  }

  private fun restoreGuestMuteState() {
    val connection = playerConnection ?: return
    val saved = previousMuteState ?: return
    try {
      connection.setMuted(saved)
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Failed to restore mute state")
    }
    previousMuteState = null
  }

  // ---------------------------------------------------------------- sending

  /**
   * Sends a host action. Rule 2 lives here: only the host (or nobody, when
   * not in a room) may control playback — guests' sends simply do not exist.
   * No echo guard is needed around sends: the guest-apply path is the only
   * place [applyingRemote] is raised, and hosts ignore their own broadcasts.
   */
  private fun guardedSend(action: () -> Unit) {
    if (!canControlMusic) return
    action()
  }

  // ------------------------------------------------------------- conversions

  private fun MediaMetadata.toTrackInfo(): TrackInfo {
    val durationMs = if (duration > 0) duration.toLong() * 1000 else 180_000L
    return TrackInfo(
      id = id,
      title = title,
      artist = artists.joinToString(", ") { it.name },
      album = album?.title,
      duration = durationMs,
      thumbnail = thumbnailUrl,
      suggestedBy = suggestedBy,
    )
  }

  private fun androidx.media3.common.Timeline.Window.toTrackInfo(): TrackInfo {
    val metadata =
      mediaItem.metadata
        ?: return TrackInfo(id = "unknown", title = "Unknown", artist = "Unknown", duration = 0)
    return metadata.toTrackInfo()
  }

  private fun TrackInfo.toMediaMetadata(): MediaMetadata =
    MediaMetadata(
      id = id,
      title = title,
      artists = listOf(Artist(id = "", name = artist)),
      album = if (album != null) Album(id = "", title = album) else null,
      duration = (duration / 1000).toInt(),
      thumbnailUrl = thumbnail,
      suggestedBy = suggestedBy,
    )

  // -------------------------------------------------------------- public ops

  fun connect() = client.connect()

  /** Manual exit (rule 3): the only user-facing way a session ends. */
  fun disconnect() {
    cleanup()
    client.disconnect()
  }

  fun createRoom(username: String) = client.createRoom(username)

  fun joinRoom(roomCode: String, username: String) = client.joinRoom(roomCode, username)

  /** Manual exit (rule 3). */
  fun leaveRoom() {
    cleanup()
    client.leaveRoom()
  }

  fun approveJoin(userId: String) = client.approveJoin(userId)

  fun rejectJoin(userId: String, reason: String? = null) = client.rejectJoin(userId, reason)

  fun kickUser(userId: String, reason: String? = null) = client.kickUser(userId, reason)

  fun blockUser(username: String) = client.blockUser(username)

  fun unblockUser(username: String) = client.unblockUser(username)

  fun getBlockedUsernames(): Set<String> = blockedUsernames.value

  fun transferHost(newHostId: String) = client.transferHost(newHostId)

  fun updateRoomSettings(allowParticipantControl: Boolean) =
    client.updateRoomSettings(allowParticipantControl)

  fun sendTrackChange(metadata: MediaMetadata) {
    if (!isHost || !isInRoom) return
    lastHostTrackId = metadata.id
    val queue =
      try {
        playerConnection?.queueWindows?.value?.map { it.toTrackInfo() }
      } catch (e: Exception) {
        null
      }
    guardedSend {
      client.sendPlaybackAction(
        PlaybackActions.CHANGE_TRACK,
        trackInfo = metadata.toTrackInfo(),
        queue = queue,
        queueTitle = playerConnection?.queueTitle?.value,
      )
    }
  }

  /** Guests ask the relay for a fresh snapshot (e.g. after a reconnect). */
  fun requestSync() {
    if (isHost || !isInRoom) return
    client.requestSync()
  }

  fun clearLogs() = client.clearLogs()

  fun suggestTrack(track: TrackInfo) = client.suggestTrack(track)

  fun approveSuggestion(suggestionId: String) {
    if (!isHost) return
    client.approveSuggestion(suggestionId)
  }

  fun rejectSuggestion(suggestionId: String, reason: String? = null) =
    client.rejectSuggestion(suggestionId, reason)

  fun forceReconnect() = client.forceReconnect()

  fun getPersistedRoomCode(): String? = client.getPersistedRoomCode()

  fun getSessionAge(): Long = client.getSessionAge()

  fun sendChatMessage(message: String, replyTo: RepliedMessage? = null) {
    if (message.isBlank()) return
    client.sendChatMessage(message, replyTo)
  }
}
