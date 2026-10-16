# Remote Web Player — listen on your iPhone, powered by SoundWave

SoundWave can act as a **music provider** for the
[Soundwave Web](https://anshug36c.github.io/soundwave-site/) site: the phone
searches YouTube Music and resolves a playable source, and the **browser**
plays it. SoundWave's own Android player is never started, paused, re-queued
or otherwise touched by a web session — the two players are fully
independent and can play different songs at the same time.

No accounts, no paid infrastructure, no port forwarding.

## How it works

```
 iPhone Safari                                Android (SoundWave)
 ┌──────────────────────┐                     ┌────────────────────────────┐
 │ Soundwave Web        │   pair (6-digit)    │ RemoteBridgeService        │
 │ "Phone" tab          │ ──────────────────► │  ├─ RemoteHttpServer :8930 │
 │ own <audio> + queue  │ ◄────────────────── │  ├─ YouTubeMusicProvider   │
 └─────────┬────────────┘   search / resolve  │  │    (InnerTubeX, M4A 1st)│
           │                  / stream (Range) │  └─ SshTunnelManager (JSch)│
           │ audio bytes                       └─────────────┬──────────────┘
           ▼                                               │ SSH reverse
      plays HERE                                           ▼ tunnel (outbound)
                                        localhost.run / serveo (free, no account)
                                               https://<random>.lhr.life
```

Two connection paths share the same bridge API:

| Mode | Path | When |
| --- | --- | --- |
| **LAN player** | Safari opens `http://<phone-ip>:8930` — SoundWave serves a small player page itself | Same Wi-Fi, fastest, no internet |
| **Tunnel (Pages site)** | The Phone tab of the GitHub Pages site calls the bridge's public `https://…lhr.life` address | Different networks (e.g. iPhone on mobile data) |

Why two paths? A secure `https://` page is forbidden by every browser from
calling a plain `http://192.168…` address (mixed content), and Safari offers
no override. So for same-Wi-Fi, the phone serves the player page itself
(same-origin `http`, perfectly legal); the Pages site works through the
HTTPS tunnel, which also works on the same Wi-Fi — just via the internet.

## Setup

### Same Wi-Fi (LAN)

1. Android: SoundWave → Settings → **Remote Web Player** → enable.
2. iPhone Safari: open the LAN address shown there (or scan the QR code).
3. Enter the 6-digit pairing code. Search, tap a song — it plays on the iPhone.

If the address never loads, your router probably isolates Wi-Fi clients
(common on guest networks); use the tunnel mode instead.

### Different networks (free tunnel)

1. Android: enable Remote Web Player **and** the free tunnel. SoundWave
   dials out over SSH and shows a fresh `https://…lhr.life` (or serveo)
   address — no signup, no payment method, nothing to install.
2. iPhone: open the Soundwave Web site → **Phone** tab → paste the tunnel
   address and the pairing code → Connect.
3. Search and play. Audio streams browser-side; the phone only resolves
   and relays.

The tunnel address changes every session (that's the no-account trade-off,
not a bug). Pairing is still required: the public address alone opens
nothing.

## Pairing & security

- Pairing code: 6 digits, 10-minute expiry, single use, generated the
  moment the bridge starts. 5 wrong attempts lock the source IP out for a
  minute (25 globally).
- A successful pair issues a random 256-bit session token (12 h, max 4
  sessions). Tokens live only in the browser's `sessionStorage` and in
  phone memory — never in the website's code or storage that survives the
  tab. Revoke any session from the phone, or Disconnect in the browser.
- Resolved audio is served only through expiring stream grants
  (`/api/stream/<random>`, 20 min, bound to your session). The signed
  upstream URL and its headers never leave the phone, and there is no
  general-purpose proxy or file access.
- Browser origins are allow-listed (the GitHub Pages origin, local dev
  origins, or the bridge's own origin); other sites cannot use your bridge
  even with a leaked URL.
- Rate limits: pairing lockouts, 30 searches + 30 resolves per minute per
  session, max 3 concurrent audio streams.
- When the phone can only resolve an Opus/WebM source (rare), the web
  player warns you: iPhone Safari before iOS 18.4 cannot decode WebM/Opus
  in `<audio>`. SoundWave asks its extractor for AAC/M4A first precisely
  for Safari.

## Independence guarantees (and how they're tested)

- The bridge lives in `echo.music.iad1tya.remote`; it imports nothing from
  the playback layer. Resolution reuses `InnerTubeXResolver` — the same
  extractor the local player uses — but stops at the URL.
- `RemoteIsolationTest` fails the build if the bridge ever imports the
  player layer, stops requesting M4A first, or the playback layer starts
  depending on the bridge.
- `RemoteHttpServerTest` runs the whole flow over real sockets with a fake
  provider and asserts a stand-in "mobile player" recorded **zero** calls.
- Web playback state is reported one-way (`POST /api/state`) so the phone
  can show "Web player: …" — it is stored per session and never read by
  local playback.

## Android lifecycle

- The bridge runs in its own foreground service (`RemoteBridgeService`,
  data-sync type) with a persistent notification and a Turn-off action.
  If you left it enabled, SoundWave tries to resume it on next launch.
- Android 15 may stop a data-sync foreground service after ~6 hours;
  toggling the bridge resumes instantly. Deep device sleep can still pause
  streaming — keep the phone awake/charging for long sessions.
- LAN address changes (new Wi-Fi) are picked up automatically; the tunnel
  reconnects with backoff and flips between localhost.run and serveo if
  one is unreachable.

## Free-tier facts (verified 2026-10-16)

| Service | Cost | Account | Limits that matter |
| --- | --- | --- | --- |
| localhost.run | Free forever tier | None (`nokey@`) | New random domain per connection; traffic speed-limited; HTTPS included |
| serveo | Free tier | None | 3 concurrent tunnels; availability can fluctuate; custom subdomains when free |
| Cloudflare Quick Tunnel | Free | None | Needs the `cloudflared` Go binary — cannot be embedded in the Android app; 200 in-flight requests; dev-only positioning |
| Tailscale Funnel | Free Personal plan | Account + Tailscale app required | Non-configurable bandwidth limits; needs the separate VPN app, so not built in. You can still point any tunnel you run yourself at port 8930 |
| ngrok free | Free tier | Account + token required | Account requirement conflicts with the no-account goal; not used |

Nothing here requires a VPS, TURN server, or payment method.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| LAN address won't open on iPhone | Same Wi-Fi? Router client isolation/guest network? Use tunnel mode. |
| Pages site says "browser blocked this address" | You pasted an `http://` LAN address into the `https://` site. Use the tunnel `https://` address there, or open the LAN address directly in Safari. |
| Tunnel stuck on "Contacting…" | Outbound SSH (port 22) may be blocked on this network; SoundWave retries and falls back to the other provider automatically. Try another network. |
| Song starts then dies / 403 | The signed source expired; the bridge re-resolves once automatically. Press play again if it persists. |
| Silent on iPhone, plays elsewhere | Opus/WebM-only source on older iOS (the player warns about this). Pick another track, or update iOS (18.4+ plays WebM/Opus). |
| "Session expired" / keeps disconnecting | Your phone is probably killing SoundWave in the background (common on Xiaomi/Oppo/Vivo): use Settings → Remote Web Player → "Allow background activity for SoundWave", and re-pair once after updating. Paired sessions now survive app/process restarts. |
| Song won't start but search works | The bridge re-resolves and retries automatically; if a specific track always fails while others play, it is that track's source — try another track and report it. |

## Known limitations

- YouTube extraction behaviour is inherited unchanged from SoundWave; if a
  track can't be resolved for local playback, the bridge can't either.
- Bridge streaming relays through the phone: the phone needs enough
  bandwidth for one upload + one download per web listener.
- Only YouTube Music tracks are provided remotely in this version; local
  files/downloads on the phone are not exposed.
- The tunnel is a third-party free service; if both providers are down,
  LAN mode keeps working but remote mode waits and retries.
