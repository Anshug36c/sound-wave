# AGENT.md — Soundwave

## Project scope

- Active implementation target: this Soundwave source copy.
- Soundwave tracks the Echo-Music upstream source. The current sync target is the official
  upstream release **v1.4.3** (upstream commit `51d649640364b7da668054fdb68d504a12d9a410`).
- The app's installable ID is `com.soundwave.music`; the internal Kotlin/Android namespace remains
  `echo.music.iad1tya`. Relative manifest component names (`.App`, `.widget.…`, `.playback.…`)
  resolve against the **namespace**, not the application ID, so both must stay as they are.
- Preserve the broad non-AI feature set and the GPL/third-party legal notices.

## Product requirements

- Account sign-in is optional; no mandatory login/onboarding gate.
- Listening history stays on-device by default.
- YouTube Music account browsing and history sync default to off; sync is opt-in.
- Remove cloud AI prompt features and AI lyric translation; do not reintroduce their UI, API calls,
  settings, dependencies, or permissions. The **offline** Local Taste Engine is not an AI prompt
  feature and is intentionally kept.
- Do not surface personal developer/contributor credits in app UI. Keep required source/license
  attribution.
- No Soundwave update feed is configured; the Updates page must remain informational and must not
  install APKs. The `REQUEST_INSTALL_PACKAGES` permission and the updater/download-manager code
  therefore stay removed.
- Do not ship upstream-owned API/OAuth credentials or Google Services/Firebase configuration.
  Optional provider integrations must use Soundwave build configuration (`local.properties` or
  environment), read through `configuredValue(...)` in `app/build.gradle.kts`. Never hardcode a
  provider key in a tracked file.

## Module layout

Soundwave is a multi-module Gradle build. Modules added by the v1.4.3 sync are marked *(new)*.

| Module | Responsibility |
| --- | --- |
| `:app` | UI (Compose), navigation, `MusicService`, widgets, DI wiring |
| `:core` | Room database (`MusicDatabase`, version 48), entities, DAOs, preferences |
| `:playback` | Queue/automix plumbing, `ChunkedDataSource`, cutoff guard, crossfade scheduler |
| `:domain` *(new)* | Pure-Kotlin models, repository contracts, use cases |
| `:metadata` *(new)* | Deterministic 5-stage metadata cleaning pipeline |
| `:dsp-core` *(new)* | Pure-Kotlin biquad filter, parametric EQ models, AutoEq parser |
| `:audio-dsp` *(new)* | Media3 audio processors and the `DspController` contract |
| `:usbaudio` *(new)* | Bit-perfect USB-DAC output (UAC1/UAC2), includes native C++ (`cmake`) |
| `:innertube` | YouTube Music InnerTube API models/pages |
| `:lyrics`, `:lrclib`, `:kugou`, `:betterlyrics`, `:paxsenixlyrics`, `:simpmusic`, `:youlyplus`, `:shazamkit`, `:unison` | Lyrics providers and matching |
| `:canvas`, `:applecanvas`, `:echomusiccanvas`, `:artistvideo` | Player/ambient artwork backdrops |

`:core` re-exports `:metadata` and `:domain` with `api(project(...))`, so `:app` consumes them
transitively and must not add direct dependencies on them.

Two package roots coexist and both are correct: most code lives in `echo.music.iad1tya.*` while some
newer files use `com.music.echo.*`. Neither matches the directory layout
(`app/src/main/kotlin/com/music/echo/...`) or the `applicationId`. Kotlin allows this, so **import
by the declared package, never by the directory path**, and do not "fix" one root to match the other
— a mass rename would break every relative manifest component name and all existing imports.

## Build and checks

Use JDK 21 plus the Android SDK/NDK versions declared in Gradle
(`compileSdk 37`, `ndkVersion 28.2.13676358`, CMake `3.22.1`).

```sh
# Flavor dimensions are (abi, variant) in that order, so the task name is Universal+Foss.
./gradlew :app:assembleUniversalFossDebug
```

`foss` is the default variant; `gms` adds Google Play Services/Cast support. Before editing a
feature, inspect its current call sites, navigation routes, preferences, and resources. After
edits, search for dangling resource/code references and run the relevant Gradle checks.

## Recommendation query safety

`DatabaseDao.quickPicks` and `forgottenFavorites` use a bound, per-invocation random seed to order
song row IDs deterministically (including the Quick Picks fallback seeds). Do not use SQLite
`RANDOM()` in queries that return Room relations: Room resets and re-executes the parent statement
while building relations, so a randomized `LIMIT` can return different IDs and crash with
`NoSuchElementException` from the generated relation map even inside `@Transaction`. A new
invocation gets a new seed, preserving refresh variety without a schema migration.

## Testing

Unit tests cover database migrations, recommendation relations, scoring algorithms, repository
persistence, caching, the DSP pipeline, and navigation/routing helpers.

- **Pure logic** — `app/src/test/.../generate/LocalTasteScoringTest.kt` (scoring, skip penalty,
  recency, time-of-day bonuses, candidate filter), `RecommendationScoringTest.kt` (Last.fm additive
  bonuses), `dsp-core/src/test/.../BiquadFilterTest.kt`, `ParametricEQParserTest.kt`,
  `metadata/src/test/.../MetadataCleanerTest.kt`.
- **Database** — `core/src/test/.../db/Migration46To47Test.kt`, `Migration47To48Test.kt`
  (v47 → v48 `taste_profile`), `RecommendationRelationsTest.kt` (100+ candidates, artistless songs,
  hidden-song filtering, fallback without playback history, variation across seeds).
- **Playback** — `playback/src/test/.../ChunkingDataSourceTest.kt`, `PlaybackCutoffGuardTest.kt`,
  `crossfade/EqualPowerCurveTest.kt`, `crossfade/AdaptivePreArmManagerTest.kt`,
  `crossfade/StandbyCrossfadeSchedulerTest.kt`, `audio-dsp/src/test/...` processors and controller.
- **Repositories / UI state** — `app/src/test/.../generate/SongPlayStatsRepositoryTest.kt`,
  `TasteProfileProviderTest.kt`, `GenresRepositoryTest.kt`, `GenerateViewModelTest.kt`,
  `RecommendationEngineTest.kt`, `GenerationStatusTest.kt`.
- **Error handling / routing** — `app/src/test/.../utils/ActivityResultLauncherExtTest.kt` (picker
  launch failures) and `PlaylistNavigationTest.kt` (malformed/encoded playlist IDs).

Soundwave ships no AI lyric-translation providers, so the upstream `:lyrics` translation tests are
not part of this build and `:lyrics` has no unit tests.

```sh
./gradlew --continue \
  :domain:testDebugUnitTest :metadata:testDebugUnitTest :dsp-core:testDebugUnitTest \
  :audio-dsp:testDebugUnitTest :playback:testDebugUnitTest :core:testDebugUnitTest \
  :app:testUniversalFossDebugUnitTest
```

`.github/workflows/build-apk.yml` runs those unit tests and then assembles the FOSS universal
debug APK on pushes to `main` and `sync/**`, on pull requests to `main`, and on manual dispatch.
It installs `platforms;android-37.0`, `ndk;28.2.13676358`, and `cmake;3.22.1`; keep those in step
with `app/build.gradle.kts` and `usbaudio/build.gradle.kts` or the native build fails.

## Local Taste Engine

The Local Taste Engine (`echo.music.iad1tya.generate`) provides offline, account-free algorithmic
recommendation and smart-mix generation. It performs no LLM/API prompt round trip.

- **Database (v47, v48)**
  - v47 introduces `song_play_stats` (keyed by normalized
    `trackKey = "${title.trim()}|${artist.trim()}".lowercase()`) and `recommendation_exclusions`;
    the auto-migration backfills legacy playback history without data loss.
  - v48 re-introduces `taste_profile` (`TasteProfileEntity`, `TasteProfileDao`) storing Last.fm top
    artists, tracks, and genre snapshots.
- **Optional Last.fm taste signals** — `LastFmTasteApi` (Ktor) calls the Last.fm methods
  `track.getsimilar`, `artist.getsimilar`, `artist.gettoptags`, `user.gettopartists`,
  `user.gettoptracks`, and `user.gettoptags`; every call returns `Result` and degrades to an empty
  list when the API key is blank or the artist/track is empty. `TasteProfileProvider` keeps a
  1-hour TTL snapshot
  (in-memory plus Room) and falls back to 30-day local history when Last.fm is unavailable or not
  configured. `GenresRepository` caches track genres and weighted genre preferences for 1 hour.
- **Ranking** — `RecommendationEngine` applies additive bonuses (`+2.0f` similar track, `+1.0f`
  similar artist, `+1.5f` user top-genre match, `+1.0f` candidate genre match, capped at `+5.5f`),
  seed expansion, exclusion filtering, and YouTube catalog resolution. `:innertube`
  `fetchTasteSignals` extracts candidate genre/mood signals from chip filters and shelf headers.
- **Playback hooks** — wired in `MusicService.kt` through `onPlaybackStatsReady` (listened duration
  and completion ratios) and `onMediaItemTransition` (skip detection).
- **Scoring** — the pure function `scoreStats` applies play weights, heavy skip penalties (`-3.0`),
  liked bonuses (`+5.0`), 7-day recency boosts (`+1.5`), and time-of-day momentum
  (morning/night `+1.3x`).
- **Status coordination** — `GenerationStatus` is a cross-screen `StateFlow<GenerationState>`
  (`Idle`, `Running`, `Done`, `Failed`) shared by `GenerateViewModel` and the quick-action dialog.
- **UI and navigation** — `GenerateScreen.kt` is reachable at the `generate` route and from the
  Library Playlists tab, and navigates to `local_playlist/{playlistId}` on success. The Library FAB
  offers "Create from Taste", driven by `CreateFromTasteDialog` and `CreateFromTasteViewModel`.
  Soundwave replaces upstream's `CreateAiPlaylistViewModel` with `CreateFromTasteViewModel`, which
  exposes only the offline taste path.
- **Exclusions** — `SongMenu.kt` offers "Never recommend again" / "Allow in recommendations",
  persisted to `RecommendationExclusionEntity`.

## Ambient Mode Canvas

Ambient Mode keeps the existing album-art square and layers muted Canvas artwork inside that same
clipped square. The normal album art remains underneath as the fallback; Canvas follows playback
state and is non-interactive.

## Extension Sources

`ExtensionManager` (`com.music.echo.extensions`) resolves third-party content plugins, and
`ExtensionSettingsScreen` is reachable at `settings/extensions`. Addon URLs are resolved defensively:
a failing addon must not break search or playback for the built-in providers.

## Legal and privacy

Preserve the GPL-3.0 license, applicable copyright headers, and third-party notices. Update
`README.md`, `PRIVACY_POLICY.md`, and `NOTICE.md` whenever product behavior or network/data
handling changes.
