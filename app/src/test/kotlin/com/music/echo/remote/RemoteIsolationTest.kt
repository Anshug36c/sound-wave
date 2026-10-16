package echo.music.iad1tya.remote

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Architecture regression guard for the #1 product rule:
 * remote (web) playback must never invoke or mutate the mobile player.
 *
 * The bridge package is checked to (a) never import the playback layer,
 * (b) resolve through the player-free InnerTubeX path with the
 * Safari-compatible format requested, and (c) remain unreferenced by the
 * mobile player classes themselves.
 */
class RemoteIsolationTest {

  private fun locateAppModule(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, "app/src/main/kotlin/com/music/echo/remote")
      if (candidate.isDirectory) return File(dir, "app")
      val direct = File(dir, "src/main/kotlin/com/music/echo/remote")
      if (direct.isDirectory) return dir
      dir = dir.parentFile
    }
    fail("Could not locate app module from ${System.getProperty("user.dir")}")
    throw AssertionError("unreachable")
  }

  @Test
  fun remotePackageNeverImportsPlaybackLayer() {
    val app = locateAppModule()
    val remoteDir = File(app, "src/main/kotlin/com/music/echo/remote")
    val kotlinFiles = remoteDir.listFiles { f -> f.extension == "kt" }.orEmpty()
    assertTrue("remote package should exist", kotlinFiles.isNotEmpty())

    val forbiddenImportPrefixes =
      listOf(
        "echo.music.iad1tya.playback",
        "androidx.media3.exoplayer",
        "androidx.media3.session",
        "echo.music.iad1tya.ui.player",
      )
    for (file in kotlinFiles) {
      file.readLines()
        .filter { it.trimStart().startsWith("import ") }
        .forEach { line ->
          for (prefix in forbiddenImportPrefixes) {
            assertTrue(
              "${file.name} must not import $prefix (remote playback stays off the mobile player)",
              !line.contains(prefix),
            )
          }
        }
      val text = file.readText()
      assertTrue(
        "${file.name} must not reference PlayerConnection",
        !text.contains("PlayerConnection"),
      )
    }
  }

  @Test
  fun resolverPrefersSafariCompatibleM4a() {
    val app = locateAppModule()
    val provider =
      File(app, "src/main/kotlin/com/music/echo/remote/YouTubeMusicProvider.kt").readText()
    assertTrue(provider.contains("InnerTubeXResolver.extract"))
    assertTrue(
      "Remote resolution must ask InnerTubeX for M4A/AAC first (iPhone Safari cannot decode WebM/Opus <audio>)",
      provider.contains("requireM4a = true"),
    )
  }

  @Test
  fun mobilePlayerNeverReferencesRemoteBridge() {
    val app = locateAppModule()
    val playbackDir = File(app, "src/main/kotlin/com/music/echo/playback")
    val offenders =
      playbackDir
        .walkTopDown()
        .filter { it.extension == "kt" }
        .filter { it.readText().contains("iad1tya.remote") }
        .map { it.name }
        .toList()
    assertTrue("Playback layer must not depend on the remote bridge: $offenders", offenders.isEmpty())
  }
}
