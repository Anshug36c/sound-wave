# Soundwave

Soundwave is an Android music player maintained as an independent source copy of
[Echo-Music](https://github.com/EchoMusicApp/Echo-Music), with its own branding, its own
privacy defaults, and no cloud AI prompt features.

**Current upstream sync target:** Echo-Music release **v1.4.3**
(upstream commit `51d649640364b7da668054fdb68d504a12d9a410`, published 2026-10-11).
Soundwave version `1.1.0` (`versionCode` 2) is the first release built on that base.

## What changed for Soundwave

- The app is branded **Soundwave** and uses application ID `com.soundwave.music`.
- Account sign-in is optional. Browsing does not use a signed-in account by default.
- Listening history is stored in the app's local database. YouTube Music history sync is opt-in and
  defaults to off.
- Cloud AI prompt features have been removed, including AI playlist generation/modification, the
  AI recommendation worker, and the AI lyric-translation providers/settings. Lyrics fetching and
  local romanization remain. The **offline** Local Taste Engine is kept — it scores your own
  listening history and makes no AI/API prompt call.
- The in-app developer/contributor pages and personal support links have been removed. GPL and
  third-party notices are retained in the source.
- The inherited Echo release/update feed is not used, and the `REQUEST_INSTALL_PACKAGES` permission
  is not requested. The Updates page explains that a Soundwave release feed has not been configured.
- Provider credentials are never hardcoded. Last.fm, Discord, GitHub OAuth, and similar keys are
  read from `local.properties` or the environment at build time.

Optional YouTube, Spotify, Discord, Last.fm, and other non-AI integrations remain part of the
source. Their availability depends on provider configuration and terms. Soundwave does not ship
upstream Echo Discord/Last.fm credentials or the upstream Google Services/Firebase configuration.
Discord and Last.fm sign-in remain unavailable until a build owner supplies Soundwave credentials
in `local.properties` or the environment; Firebase-enabled builds must supply project-owned
configuration. Core playback does not require these services.

## What the v1.4.3 sync brought in

### Playback reliability

- **Premature-EOF hardening** in the chunked data source, with retry and preserved upstream
  exceptions, so truncated or expired stream URLs fail cleanly instead of stalling.
- **`PlaybackCutoffGuard`** state machine plus early cutoff recovery and a canonical duration
  anchor, to stop tracks ending silently short.
- **Crossfade rework**: `StandbyCrossfadeScheduler`, `EqualPowerCurve`, and `AdaptivePreArmManager`
  reduce UI/audio desync during crossfade and secondary-player prebuffering.
- **Volume-drop / paused-on-reconnect fix** when the network recovers.
- **Recents swipe fix**: playback is properly paused and state saved when the app is cleared from
  recents.
- **Equalizer processor lifecycle fix**: the custom equalizer audio processor is unregistered on
  player release (resource-leak fix).
- Queue clearing now routes through confirmation with undo, and validates shuffle order.

### Audio architecture

- New `:dsp-core` (pure-Kotlin biquad filter, parametric EQ models, AutoEq parser) and `:audio-dsp`
  (Media3 processors behind a `DspController` contract) modules; the equalizer code moved out of
  `:playback` and the service now depends on the contract rather than concrete processors.
- **AutoEq integration** with 8850+ profiles.
- New `:usbaudio` module for **bit-perfect external USB-DAC output** (UAC1/UAC2, native
  isochronous streaming with feedback clock sync, 64-bit software volume). Includes C++ sources
  built with CMake, so an NDK is required.
- **Varispeed** playback option (pitch linked to speed).

### Metadata and search

- New `:metadata` module with a deterministic 5-stage cleaning pipeline, connected to InnerTube
  `SongItem` and the Room `Song` entity. It preserves full cleaned artists in lyrics search queries
  (including names with ampersands and conjunctions), which improves lyrics matching.
- **Extension Sources** for third-party content plugins, with more reliable addon track resolution
  and search UX.
- **Music DNA**: track backstory lookup with Wikipedia fallbacks for regional or obscure tracks.

### Recommendations

- New `:domain` module (pure-Kotlin models, repository contracts, use cases) with repository
  adapters in `:core`.
- **Local Taste Engine** (offline): play/skip statistics, exclusions, genre awareness, taste
  snapshots, "Create from Taste", and a Generate screen. Database migrated to **version 48**
  (v47 adds `song_play_stats` and `recommendation_exclusions`; v48 re-adds `taste_profile`), with
  auto-migrations that backfill existing listening history without data loss.
- **Crash fix** in Quick Picks / Forgotten Favorites: randomized Room relation queries no longer use
  SQLite `RANDOM()`, which could return different IDs between Room's parent and relation passes and
  crash with `NoSuchElementException`. Ordering now uses a bound per-invocation seed.

### UI and stability

- **Crash fixes for system pickers** (`ActivityNotFoundException`, `SecurityException`) across
  Storage, Backup and Restore, Font Selection, Equalizer, and Playlist screens, via a shared
  `launchSafely` helper that shows a message instead of closing the app.
- **Playlist navigation hardening**: playlist IDs are validated and decoded before routing, so
  malformed or encoded IDs go to the right screen instead of crashing.
- New home-screen widgets (adaptive, modern, showcase, slim, square, vintage, vinyl) and an
  adaptive queue widget.
- Overhauled haptics with granular controls and scroll-boundary vibration, in a dedicated submenu.
- Ambient Mode canvas layering, Apple Music style home carousel dimensions, Material You dialog and
  menu refinements, and lyrics blur/centering fixes.
- Settings search indexes many more entries, and a settings-search hit whose destination is not part
  of this build now shows a message instead of crashing.
- **Custom font loading** now works from the font picker.

Soundwave-specific corrections made during the sync: the floating navigation bar stays enabled by
default, sharing a track points at `music.youtube.com` rather than the upstream share host, the
notification icon and deep-link scheme remain Soundwave's, and the Settings screen grouping indices
were fixed for Soundwave's item list (upstream's indices assumed an AI entry that this build does
not have).

## Build

Use JDK 21 and the Android SDK/NDK versions declared in the Gradle files
(`compileSdk 37`, `ndkVersion 28.2.13676358`, CMake `3.22.1`). From the project root:

```sh
./gradlew :app:assembleUniversalFossDebug
```

The flavor dimensions are `(abi, variant)` in that order, so the task name is `UniversalFoss`
(not `FossUniversal`). `foss` is the default variant; `gms` adds Google Play Services/Cast support.

The internal Kotlin/Android namespace is still `echo.music.iad1tya` to avoid a risky mass rename
across the multi-module source. The installable application ID is `com.soundwave.music`. Relative
manifest component names resolve against the namespace, so this split is required for the app's
services, receivers, and widgets to load.

Provider credentials are optional and read from `local.properties` or the environment; see
`local.properties.template`. A build with none of them set still compiles and runs — the affected
integrations simply report themselves as unconfigured.

## Tests

```sh
./gradlew --continue \
  :domain:test :metadata:test :dsp-core:test \
  :audio-dsp:testDebugUnitTest :usbaudio:testDebugUnitTest \
  :playback:testFossDebugUnitTest :core:testDebugUnitTest \
  :app:testUniversalFossDebugUnitTest
```

:domain, :metadata and :dsp-core are pure Kotlin/JVM modules, so their test task is `test`; the
Android library modules use `testDebugUnitTest` (`:playback` is flavor-qualified as
`testFossDebugUnitTest` because it declares foss/gms flavors). These are JVM/Robolectric unit tests covering database migrations, recommendation relations,
taste scoring, repository logic, the DSP pipeline, chunked-stream error handling, picker error
handling, and playlist routing. They do **not** exercise real streaming, scraping, or downloads —
those paths need a device or emulator and live provider responses.

## Continuous integration

`.github/workflows/build-apk.yml` runs the unit tests above and then assembles the FOSS universal
debug APK. It triggers on pushes to `main` and `sync/**`, on pull requests targeting `main`, and on
manual dispatch, and uploads the APK plus the test reports as artifacts.

## Local data and privacy defaults

- Listening history is stored in the local `song.db` database and excluded from Android cloud backup
  and device-to-device transfer. An optional manual backup can include it only when the user
  explicitly exports one.
- Play/skip statistics and taste snapshots used by the Local Taste Engine are derived from that same
  local history and stay in the local database.
- App preferences are stored locally on the device.
- YouTube Music account-based browsing and history sync are disabled by default; users can opt into
  supported account integrations in Settings.
- Soundwave's AI prompt and AI lyric-translation code paths have been removed.
- Streaming, lyrics, artwork, optional Last.fm taste signals, and optional third-party integrations
  still make requests to their respective providers.

See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) for the source-copy privacy summary.

## Known limitations

- No Soundwave release feed is configured, so the in-app Updates page is informational only.
- Discord and Last.fm sign-in require build-owner credentials; without them those integrations
  report themselves as unavailable.
- Bit-perfect USB-DAC output depends on the connected device exposing a UAC1/UAC2 interface and on
  Android granting USB access at runtime; it is not verifiable in unit tests.
- Extension Sources and Music DNA depend on third-party endpoints that Soundwave does not operate.

## License and notices

This project retains the upstream GPL-3.0 license and source notices. See [LICENSE](LICENSE) and
[NOTICE.md](NOTICE.md). Soundwave remains subject to the GPL-3.0 terms and applicable third-party
licenses.
