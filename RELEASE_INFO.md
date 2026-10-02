# Soundwave release status

Soundwave is currently a source project copy; no Soundwave release feed or download channel is configured. The in-app Updates page is informational and does not check for or install updates.

The Gradle default version is `1.0.0` (`versionCode` 1). Treat that as a development baseline, not a published release.

## Before publishing a release

1. Choose a Soundwave-owned release host and publish a release policy/feed.
2. Configure release signing locally; never commit keystores or signing passwords.
3. Configure any provider integrations with Soundwave-owned credentials and registered redirect URIs.
4. Review privacy, provider terms, attribution, and third-party notices.
5. Build and test the intended flavor/ABI combination, then update the version code/name and release notes.
