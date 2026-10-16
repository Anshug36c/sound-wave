package echo.music.iad1tya.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import echo.music.iad1tya.MainActivity
import echo.music.iad1tya.R
import timber.log.Timber

/**
 * Foreground service hosting the Remote Web Player bridge.
 *
 * Data-sync type: the bridge moves audio bytes for the browser; it does not
 * play anything on this device, so the media-playback service (MusicService)
 * is left completely alone. Note Android 15 may stop a data-sync FGS after
 * ~6 hours — the settings screen says so, and re-enabling resumes instantly.
 */
class RemoteBridgeService : Service() {

  private var networkCallback: ConnectivityManager.NetworkCallback? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
    registerNetworkCallback()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      RemoteBridge.stop(this)
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }
    RemoteBridge.start(this)
    val notification = buildNotification()
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Could not start remote bridge foreground service")
      stopSelf()
      return START_NOT_STICKY
    }
    return START_STICKY
  }

  override fun onDestroy() {
    unregisterNetworkCallback()
    super.onDestroy()
  }

  private fun buildNotification(): Notification {
    val status = RemoteBridge.status.value
    val text =
      when {
        status.tunnel.publicUrl != null -> status.tunnel.publicUrl
        status.lanUrls.isNotEmpty() -> status.lanUrls.first()
        else -> "Open SoundWave settings for connection details"
      }
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val stopIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, RemoteBridgeService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_soundwave_notification)
      .setContentTitle("Remote Web Player is on")
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nMusic plays in the web browser — SoundWave's own player is untouched."))
      .setContentIntent(openIntent)
      .addAction(0, "Turn off", stopIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
  }

  private fun createChannel() {
    val manager = getSystemService(NotificationManager::class.java)
    val channel =
      NotificationChannel(CHANNEL_ID, "Remote Web Player", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while the Remote Web Player bridge is available to your browser"
      }
    manager.createNotificationChannel(channel)
  }

  private fun registerNetworkCallback() {
    try {
      val connectivity = getSystemService(ConnectivityManager::class.java)
      val callback =
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            RemoteBridge.refreshStatus()
            refreshNotification()
          }

          override fun onLost(network: Network) {
            RemoteBridge.refreshStatus()
            refreshNotification()
          }
        }
      connectivity.registerDefaultNetworkCallback(callback)
      networkCallback = callback
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Could not register network callback")
    }
  }

  private fun unregisterNetworkCallback() {
    try {
      val connectivity = getSystemService(ConnectivityManager::class.java)
      networkCallback?.let { connectivity.unregisterNetworkCallback(it) }
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Could not unregister network callback")
    }
    networkCallback = null
  }

  private fun refreshNotification() {
    try {
      getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Could not refresh remote bridge notification")
    }
  }

  companion object {
    private const val TAG = "RemoteBridgeService"
    private const val CHANNEL_ID = "remote_web_player"
    private const val NOTIFICATION_ID = 0x5AFE
    const val ACTION_START = "echo.music.iad1tya.remote.START"
    const val ACTION_STOP = "echo.music.iad1tya.remote.STOP"

    fun start(context: Context) {
      ContextCompat.startForegroundService(
        context,
        Intent(context, RemoteBridgeService::class.java).setAction(ACTION_START),
      )
    }

    fun stop(context: Context) {
      context.startService(Intent(context, RemoteBridgeService::class.java).setAction(ACTION_STOP))
    }
  }
}
