# Soundwave privacy summary

This policy describes the current Soundwave source copy. Soundwave does not provide a Soundwave-owned account or backend in this project copy. Network services used by optional integrations are operated by their respective providers and have separate privacy policies.

## Accounts and sign-in

An account is not required to open the app or browse by default. YouTube Music account-based browsing is disabled by default. You may choose to sign in to supported third-party services from Settings; information shared during those sign-ins is handled by the selected provider.

## Listening history and local data

Playback events, listening history, search history, and playlists are saved in the app's local `song.db` database; preferences are stored separately in app-private storage. `song.db` is excluded from Android cloud backup and device-to-device transfer. The optional manual Backup & Restore feature can export the database if you explicitly create a backup; that file is then handled by the location or app you choose. Local data is removed when app data is cleared or the app is uninstalled.

YouTube Music history sync is optional and defaults to off. If you sign in and enable it, actions may be sent to YouTube Music. Other optional integrations such as Last.fm or ListenBrainz can transmit listening information when configured and enabled.

## AI features

Cloud AI prompt features and AI lyric translation have been removed from Soundwave. The app does not send prompts to OpenRouter, DeepL, or another AI provider through those removed features. Ordinary lyrics fetching and other music-provider requests are separate network activity.

## Network services

The app may contact YouTube Music and enabled non-AI integrations to search, stream, fetch lyrics and artwork, synchronize data when opted in, or support features such as listen-together. Those services receive the requests and network information required to respond. Listen Together currently fetches server configuration from a public raw file hosted in the upstream Echo-Music GitHub repository and then connects to the selected room server. Review each provider's own privacy terms before connecting an account.

This source copy includes no upstream Google Services configuration and has no Soundwave-owned analytics service. A build owner who supplies a Soundwave-owned Google Services configuration may enable Firebase components according to that build's configuration.

## Your choices

- Use the app without signing in.
- Keep YouTube Music account browsing and history sync disabled, or enable them manually in Settings.
- Disable or disconnect optional integrations in Settings.
- Clear local app data or uninstall the app to remove locally stored Soundwave data.

## Open-source notices

Soundwave is based on GPL-3.0-licensed source. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for retained source and third-party notices.
