# AGENT.md — Soundwave

## Project scope

- Active implementation target: this Soundwave source copy.
- Keep `/home/user/Echo-Music/` unchanged; it is the original checkout.
- The app's installable ID is `com.soundwave.music`; internal Kotlin namespace remains `echo.music.iad1tya`.
- Preserve the broad non-AI feature set and GPL/third-party legal notices.

## Product requirements

- Account sign-in is optional; no mandatory login/onboarding gate.
- Listening history stays on-device by default.
- YouTube Music account browsing and history sync default to off; sync is opt-in.
- Remove cloud AI prompt features and AI lyric translation; do not reintroduce their UI, API calls, settings, dependencies, or permissions.
- Do not surface personal developer/contributor credits in app UI. Keep required source/license attribution.
- No Soundwave update feed is configured; the Updates page must remain informational and must not install APKs.
- Do not ship upstream-owned API/OAuth credentials or Google Services/Firebase configuration. Optional provider integrations must use Soundwave build configuration.

## Build and checks

Use JDK 21 and the Android SDK/NDK versions in Gradle. Preferred basic build:

```sh
./gradlew :app:assembleFossUniversalDebug
```

`foss` is the default flavor; `gms` adds Google Play Services/Cast support. Before editing a feature, inspect the current call sites, navigation, preferences, and resources. After edits, search for dangling resource/code references and run relevant Gradle checks when the SDK is available.

## Validation snapshot — 2026-10-02

- Parsed all 474 app resource/manifest XML files successfully.
- Kotlin `R.string`/`R.plurals` and XML `@string` checks found no missing references.
- Gradle assemble/tests and runtime checks were not run: this sandbox has OpenJDK 11 and no Android SDK installed; the project requires JDK 21, Android SDK 37, and the declared NDK.

## Legal and privacy

Preserve the GPL-3.0 license, applicable copyright headers, and third-party notices. Update `README.md`, `PRIVACY_POLICY.md`, and `NOTICE.md` whenever product behavior or network/data handling changes.
