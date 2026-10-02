# Soundwave build setup

This guide covers building the Soundwave source copy. The app can be built and opened without connecting an account.

## Requirements

- JDK 21
- Android SDK platform/build tools required by the Gradle configuration (compile SDK 37)
- Android NDK `27.0.12077973`
- Gradle wrapper included in this project

Copy `local.properties.template` to `local.properties` and set `sdk.dir` to your Android SDK directory. Do not commit `local.properties` or provider credentials.

## Build

From the project root:

```sh
./gradlew :app:assembleFossUniversalDebug
```

The `foss` flavor avoids Google Play Services/Cast. The `gms` flavor includes Cast support. The inherited upstream Google Services/Firebase configuration is intentionally not included; supply a Soundwave-owned `app/google-services.json` only if your build should enable Firebase:

```sh
./gradlew :app:assembleGmsUniversalDebug
```

The installed application ID is `com.soundwave.music`. The internal Kotlin namespace remains `echo.music.iad1tya` to avoid a risky mass rename across the multi-module source.

## Optional provider configuration

Third-party integrations remain optional. Empty provider settings do not block building or using the app.

Set these in `local.properties` or as environment variables if you have credentials for your own Soundwave build:

```properties
LASTFM_API_KEY=
LASTFM_SECRET=
DISCORD_APPLICATION_ID=
# Optional; defaults to discord-<DISCORD_APPLICATION_ID> when an ID is supplied.
DISCORD_REDIRECT_SCHEME=
```

Register the matching Discord redirect URI with your Discord application. Last.fm and Discord sign-in remain unavailable in an unconfigured build; Soundwave does not ship the upstream Echo provider credentials. Google/Firebase services are only included when the corresponding project configuration is supplied.

## Product defaults

- No account is required to start using the app.
- YouTube Music account-based browsing is off by default.
- Listening history remains in the local on-device database.
- YouTube Music history sync is opt-in and off by default.
- Cloud AI prompts, AI playlist features, and AI lyric translation have been removed.
- Automatic Soundwave release checks are not configured; the in-app Updates page is informational only.

For privacy details, see [PRIVACY_POLICY.md](PRIVACY_POLICY.md). For source/license attribution, see [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE).
