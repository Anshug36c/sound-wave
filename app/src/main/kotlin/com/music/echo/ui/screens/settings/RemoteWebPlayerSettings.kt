package echo.music.iad1tya.ui.screens.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import echo.music.iad1tya.LocalPlayerAwareWindowInsets
import echo.music.iad1tya.R
import echo.music.iad1tya.remote.RemoteBridge
import echo.music.iad1tya.remote.RemoteBridgeService
import echo.music.iad1tya.remote.SshTunnelManager
import echo.music.iad1tya.ui.component.IconButton
import echo.music.iad1tya.ui.utils.backToMain
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/**
 * Settings for the Remote Web Player bridge.
 *
 * The copy here is deliberate: this screen must never imply that SoundWave's
 * own player is involved. Music is searched/resolved on the phone and
 * *played in the browser*; remote sessions cannot see or change local
 * playback, its queue or its notification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteWebPlayerSettings(
  navController: NavController,
  scrollBehavior: TopAppBarScrollBehavior,
) {
  val context = LocalContext.current
  val status by RemoteBridge.status.collectAsState()
  val scrollState = rememberScrollState()

  var portText by rememberSaveable(status.port) { mutableStateOf(status.port.toString()) }
  val sessions = remember(status.sessionCount, status.running) { RemoteBridge.sessions() }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* bridge works either way */ }

  LaunchedEffect(Unit) {
    while (true) {
      RemoteBridge.refreshStatus()
      delay(3000)
    }
  }

  fun setEnabled(enabled: Boolean) {
    if (enabled) {
      if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
      ) {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
      try {
        RemoteBridgeService.start(context)
      } catch (e: Exception) {
        Toast.makeText(context, "Could not start the bridge: ${e.message}", Toast.LENGTH_LONG).show()
      }
    } else {
      RemoteBridgeService.stop(context)
    }
  }

  fun copy(label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(scrollState)
        .padding(horizontal = 16.dp)
        .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
  ) {
    Spacer(Modifier.height(8.dp))

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
      Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("Enable Remote Web Player", fontWeight = FontWeight.SemiBold)
            Text(
              "Lets your browser search SoundWave's music and play it on that device. " +
                "SoundWave's own player, queue and notification are never touched.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = status.enabled, onCheckedChange = { setEnabled(it) })
        }

        if (status.enabled) {
          Spacer(Modifier.height(12.dp))
          Text(
            when {
              status.running -> "Bridge is running"
              status.lastError.isNotEmpty() -> "Bridge failed: ${status.lastError}"
              else -> "Bridge is starting…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color =
              if (status.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
          )
        }
      }
    }

    if (status.enabled && status.running) {
      Spacer(Modifier.height(16.dp))
      Text("Connect a browser", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(8.dp))

      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
          Text("Pairing code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              status.pairingCode.chunked(3).joinToString(" "),
              style = MaterialTheme.typography.headlineMedium,
              fontFamily = FontFamily.Monospace,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { RemoteBridge.rotatePairing() }) { Text("New code") }
          }
          val remainingMs = (status.pairingExpiresAtMs - System.currentTimeMillis()).coerceAtLeast(0)
          Text(
            "Expires in ${remainingMs / 60000}:${"%02d".format((remainingMs / 1000) % 60)} · single use · " +
              "5 wrong tries lock pairing for a minute",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          if (status.lanUrls.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Same Wi-Fi (fastest)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            status.lanUrls.forEach { url ->
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(url, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { copy("SoundWave LAN address", url) }) { Text("Copy") }
              }
            }
            Text(
              "On your iPhone, open this address in Safari and enter the code. " +
                "The page is served by SoundWave itself, so no internet is needed. " +
                "If it does not load, both devices must share one Wi-Fi without client isolation.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val qr = remember(status.lanUrls.firstOrNull()) { status.lanUrls.firstOrNull()?.let { qrBitmap(it) } }
            if (qr != null) {
              Spacer(Modifier.height(12.dp))
              Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "QR code for the LAN address",
                modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)),
              )
            }
          } else {
            Spacer(Modifier.height(12.dp))
            Text(
              "No LAN address yet — connect this phone to Wi-Fi. The tunnel below still works.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      }

      Spacer(Modifier.height(16.dp))
      Text("From anywhere (free tunnel)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(8.dp))
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Free remote tunnel", fontWeight = FontWeight.SemiBold)
              Text(
                "SoundWave dials out over SSH (no account, no port forwarding) and gets a public HTTPS address. " +
                  "The address changes every session; pairing is still required, so the address alone opens nothing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = status.tunnelEnabled,
              onCheckedChange = { RemoteBridge.setTunnelEnabled(context, it) },
            )
          }

          if (status.tunnelEnabled) {
            Spacer(Modifier.height(8.dp))
            SshTunnelManager.Provider.values().forEach { provider ->
              Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                  selected = RemoteBridge.tunnelProvider(context) == provider,
                  onClick = { RemoteBridge.setTunnelProvider(context, provider) },
                )
                Text(provider.label, style = MaterialTheme.typography.bodyMedium)
              }
            }
            Text(
              "Auto-fallback is on: if one service is unreachable, SoundWave tries the other.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            when (status.tunnel.phase) {
              SshTunnelManager.Phase.CONNECTED -> {
                val url = status.tunnel.publicUrl.orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(url, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                  TextButton(onClick = { copy("SoundWave tunnel address", url) }) { Text("Copy") }
                }
                Text(
                  "Open your SoundWave website, choose Phone, paste this address and the pairing code. Works on mobile data too.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val qr = remember(url) { if (url.isNotEmpty()) qrBitmap(url) else null }
                if (qr != null) {
                  Spacer(Modifier.height(12.dp))
                  Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR code for the tunnel address",
                    modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)),
                  )
                }
              }
              SshTunnelManager.Phase.CONNECTING, SshTunnelManager.Phase.BACKOFF ->
                Text(
                  status.tunnel.message.ifEmpty { "Connecting…" },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              SshTunnelManager.Phase.ERROR ->
                Text(
                  status.tunnel.message.ifEmpty { "Tunnel failed." },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                )
              SshTunnelManager.Phase.OFF -> Text("Tunnel is off.", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }

      Spacer(Modifier.height(16.dp))
      Text("Paired browsers (${sessions.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(8.dp))
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
          if (sessions.isEmpty()) {
            Text("No browser is paired right now.", style = MaterialTheme.typography.bodyMedium)
          } else {
            sessions.forEach { session ->
              Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Column(Modifier.weight(1f)) {
                    Text(session.label, fontWeight = FontWeight.SemiBold)
                    Text(
                      "Paired ${DateFormat.getTimeInstance().format(Date(session.createdAtMs))} · " +
                        "expires ${DateFormat.getTimeInstance().format(Date(session.expiresAtMs))}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    session.remoteState.track?.let { track ->
                      Text(
                        "Web player: ${track.title} — ${track.artist}${if (session.remoteState.playing) " (playing)" else " (paused)"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                      )
                    }
                  }
                  TextButton(onClick = { RemoteBridge.revokeSession(session.token.takeLast(4)); Toast.makeText(context, "Session revoked", Toast.LENGTH_SHORT).show() }) {
                    Text("Revoke")
                  }
                }
                Spacer(Modifier.height(8.dp))
              }
            }
            OutlinedButton(onClick = { RemoteBridge.revokeAllSessions() }, modifier = Modifier.fillMaxWidth()) {
              Text("Revoke all browsers")
            }
          }
        }
      }

      Spacer(Modifier.height(16.dp))
      Text("Advanced", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(8.dp))
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
              value = portText,
              onValueChange = { value -> portText = value.filter { it.isDigit() }.take(5) },
              label = { Text("Bridge port") },
              singleLine = true,
              modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(
              onClick = {
                val port = portText.toIntOrNull()
                if (port == null || port !in 1024..65535) {
                  Toast.makeText(context, "Port must be 1024–65535", Toast.LENGTH_SHORT).show()
                } else {
                  RemoteBridge.setPort(context, port)
                  Toast.makeText(context, "Bridge port set to $port", Toast.LENGTH_SHORT).show()
                }
              },
            ) { Text("Apply") }
          }
          Spacer(Modifier.height(12.dp))
          Text(
            "Good to know: keep SoundWave installed and the bridge on while listening; Android may stop the " +
              "bridge after about 6 hours (system limit for this service type) or when the phone sleeps hard. " +
              "Tunnel addresses are free and change per session — that is the no-account trade-off, not a bug. " +
              "Nothing here can start, pause or change music playing on this phone itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Spacer(Modifier.height(16.dp))
      Text("Keeps stopping or logging the browser out?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(8.dp))
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
          Text(
            "Some phones (Xiaomi/Redmi, Oppo, Vivo, OnePlus) kill background apps aggressively, which stops the bridge and drops the tunnel. Allow SoundWave to run without battery restrictions, and keep it out of "deep sleep" lists:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(12.dp))
          OutlinedButton(
            onClick = {
              val intent =
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + context.packageName),
                  )
                  .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
              try {
                context.startActivity(intent)
              } catch (e: Exception) {
                try {
                  context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                      .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                  )
                } catch (e2: Exception) {
                  Toast.makeText(context, "Open battery settings for SoundWave manually", Toast.LENGTH_LONG).show()
                }
              }
            },
            modifier = Modifier.fillMaxWidth(),
          ) { Text("Allow background activity for SoundWave") }
          Spacer(Modifier.height(8.dp))
          Text(
            "Paired browsers stay paired across bridge/app restarts — if you still see "session expired", pair once more after updating the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else if (status.enabled) {
      Spacer(Modifier.height(16.dp))
      Text("Starting the bridge… if this persists, toggle it off and on.", style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(Modifier.height(24.dp))
    Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)))
  }

  TopAppBar(
    title = { Text("Remote Web Player") },
    navigationIcon = {
      IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
      }
    },
    scrollBehavior = scrollBehavior,
  )
}

private fun qrBitmap(content: String, size: Int = 720): Bitmap? =
  try {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
      for (y in 0 until size) {
        bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
      }
    }
    bitmap
  } catch (e: Exception) {
    null
  }
