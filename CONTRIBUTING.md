# Contributing to Soundwave

Thanks for helping improve Soundwave. This repository is a separate project copy based on GPL-3.0-licensed upstream source; keep applicable copyright headers, license files, and third-party notices intact.

## Before changing code

- Read [README.md](README.md), [SETUP.md](SETUP.md), and [NOTICE.md](NOTICE.md).
- Upstream is [Echo-Music](https://github.com/EchoMusicApp/Echo-Music); this copy currently tracks upstream release v1.4.3. Do not vendor upstream branding or credentials back in.
- Keep account sign-in optional, listening history local by default, and YouTube Music sync opt-in.
- Do not reintroduce cloud AI prompt/translation features or user-facing developer/contributor credit pages.
- Do not add upstream-owned API keys, OAuth IDs, signing keys, or secrets to source control.

## Verify changes

Run the narrowest relevant checks, then build the FOSS universal debug variant when the Android SDK is available:

```sh
./gradlew :app:assembleUniversalFossDebug
```

Also run the unit tests for the modules you touched (see [SETUP.md](SETUP.md) for the full list);
`.github/workflows/build-apk.yml` runs them plus the APK build on `main`, `sync/**`, and pull
requests.

Describe behavior changes, privacy/network effects, and any provider configuration required in the pull request or change notes. Preserve required GPL and third-party attributions even when removing in-app credit surfaces.

No Soundwave-owned public issue or contribution service is configured in this source copy yet.
