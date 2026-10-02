# Soundwave

Soundwave is an Android music player based on the checked-out Echo-Music source. This is a separate project copy; the original `/home/user/Echo-Music` checkout is not modified.

## What changed for Soundwave

- The app is branded **Soundwave** and uses application ID `com.soundwave.music`.
- Account sign-in is optional. Browsing does not use a signed-in account by default.
- Listening history is stored in the app's local database. YouTube Music history sync is opt-in and defaults to off.
- Cloud AI prompt features have been removed, including AI playlist generation/modification, the recommendation worker, and AI lyric translation providers/settings. Lyrics fetching and local romanization remain.
- The in-app developer/contributor pages and personal support links have been removed. GPL and third-party notices are retained in the source.
- The inherited Echo release/update feed is not used. The Updates page explains that a Soundwave release feed has not yet been configured.

Optional YouTube, Spotify, Discord, Last.fm, and other non-AI integrations remain part of the source. Their availability depends on provider configuration and terms. Soundwave does not ship upstream Echo Discord/Last.fm credentials or the upstream Google Services/Firebase configuration. Discord and Last.fm sign-in remain unavailable until a build owner supplies Soundwave credentials in `local.properties` or the environment; Firebase-enabled builds must supply project-owned configuration. Core playback does not require these services.

## Build

Use JDK 21 and the Android SDK/NDK versions declared in the Gradle files. From the project root:

```sh
./gradlew :app:assembleFossUniversalDebug
```

The internal Kotlin/Android namespace is still `echo.music.iad1tya` to avoid a risky mass rename across the multi-module source. The installable application ID is `com.soundwave.music`.

## Local data and privacy defaults

- Listening history is stored in the local `song.db` database and excluded from Android cloud backup and device-to-device transfer. An optional manual backup can include it only when the user explicitly exports one.
- App preferences are stored locally on the device.
- YouTube Music account-based browsing and history sync are disabled by default; users can opt into supported account integrations in Settings.
- Soundwave's AI prompt and AI lyric-translation code paths have been removed.
- Streaming, lyrics, artwork, and optional third-party integrations still make requests to their respective providers.

See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) for the source-copy privacy summary.

## License and notices

This project retains the upstream GPL-3.0 license and source notices. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md). Soundwave remains subject to the GPL-3.0 terms and applicable third-party licenses.
