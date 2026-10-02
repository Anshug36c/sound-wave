# Contributing to Soundwave

Thanks for helping improve Soundwave. This repository is a separate project copy based on GPL-3.0-licensed upstream source; keep applicable copyright headers, license files, and third-party notices intact.

## Before changing code

- Read [README.md](README.md), [SETUP.md](SETUP.md), and [NOTICE.md](NOTICE.md).
- Keep the original `/home/user/Echo-Music` checkout unchanged when working in the provided workspace.
- Keep account sign-in optional, listening history local by default, and YouTube Music sync opt-in.
- Do not reintroduce cloud AI prompt/translation features or user-facing developer/contributor credit pages.
- Do not add upstream-owned API keys, OAuth IDs, signing keys, or secrets to source control.

## Verify changes

Run the narrowest relevant checks, then build the FOSS universal debug variant when the Android SDK is available:

```sh
./gradlew :app:assembleFossUniversalDebug
```

Describe behavior changes, privacy/network effects, and any provider configuration required in the pull request or change notes. Preserve required GPL and third-party attributions even when removing in-app credit surfaces.

No Soundwave-owned public issue or contribution service is configured in this source copy yet.
