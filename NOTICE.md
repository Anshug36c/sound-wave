# Notices

Soundwave is a separate project copy based on the Echo-Music source from <https://github.com/EchoMusicApp/Echo-Music>.

Upstream base history:

| Soundwave version | Upstream base | Upstream commit |
| --- | --- | --- |
| 1.0.0 | between v1.4 and v1.4.1 | `957bff9becbe8fa2e1762218c1eeecb979621e10` |
| 1.1.0 | release **v1.4.3** | `51d649640364b7da668054fdb68d504a12d9a410` |

The v1.4.3 sync added the upstream `:domain`, `:metadata`, `:dsp-core`, `:audio-dsp`, and
`:usbaudio` modules. Those modules carry their own upstream copyright headers, license files, and
third-party notices, which remain part of the source distribution.

The source retains the applicable upstream copyright headers, the GPL-3.0 license, and third-party notices. Those notices remain part of the legal/source distribution; they are not presented as in-app developer or contributor credits. See [LICENSE](LICENSE), the license files/notices in the individual modules, and the bundled-font notices below.

## Bundled fonts

The app bundles the following fonts under the SIL Open Font License, Version 1.1:

- Google Sans Flex — Copyright 2015 The Google Sans Flex Authors (<https://github.com/googlefonts/googlesans-flex>).
- Roboto Flex — Copyright 2017 The Roboto Flex Project Authors (<https://github.com/TypeNetwork/Roboto-Flex>).
- Outfit — Copyright 2021 The Outfit Project Authors (<https://github.com/Outfitio/Outfit-Fonts>).
- Plus Jakarta Sans — Copyright 2020 The Plus Jakarta Sans Project Authors (<https://github.com/tokotype/PlusJakartaSans>).

The complete font-specific license texts are included in `app/src/main/assets/font-licenses/` and are also packaged in the APK.

Soundwave modifications include rebranding, removal of cloud AI prompt and AI lyric-translation
features, removal of the upstream updater/release feed and its `REQUEST_INSTALL_PACKAGES`
permission, replacement of hardcoded upstream provider credentials with build-time configuration,
optional-account/local-history defaults, and removal of in-app developer/contributor credit
surfaces. The offline Local Taste Engine from upstream is retained.
