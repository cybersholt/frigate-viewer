# Changelog — kotlin-rewrite branch

All changes relative to the original React Native fork on `master`.

---

## Live streaming

Three selectable modes via Settings → Live stream option:

| Mode | How | Latency | Notes |
|------|-----|---------|-------|
| WebRTC | go2rtc signaling WS → PeerConnection | sub-second | Default. Uses the active server's auth OkHttp client. |
| RTSP | Media3 ExoPlayer via `RtspMediaSource` | ~1–2 s | Forces TCP. Credentials URL-encoded into `rtsp://user:pass@host/cam`. |
| Snapshot | 800 ms Coil poll of `latest.jpg` | ~1 s | Fallback for environments that can't reach RTSP/WebRTC. |

### Fullscreen
- Toggle button bottom-right on every live tile.
- Hides the app bottom nav bar (Cameras / Events / Settings) via `LocalFullScreenMode` CompositionLocal.
- Hides Android system bars (status + nav) via `WindowInsetsControllerCompat`; restores on exit or dispose.
- Optional auto-rotate to landscape when fullscreen (Settings → Auto landscape).
- Back gesture / button exits fullscreen before navigating away.

### Audio
- Both RTSP and WebRTC tiles default to **muted**.
- Mute/unmute button bottom-left on every live tile.
- RTSP: `ExoPlayer.volume = 0f / 1f`.
- WebRTC: audio transceiver added (`RECV_ONLY`); `AudioTrack.setEnabled()` on toggle.

### RTSP host override
- Server form has an optional "RTSP host" field — set to LAN IP when the domain doesn't forward port 8554.
- Falls back to the HTTP host when blank.
- Useful when Frigate runs in Docker and RTSP is port-mapped differently from HTTP.

### RTSP draw fix
- `PlayerView` is always in the composition tree; loading overlay sits on top.
- Previously showed a black screen until the device rotated because ExoPlayer rendered to a surface that didn't exist at `STATE_READY` time.

---

## Camera grid

- **Auto-refresh** at 1 / 3 / 5 / 10 / 30 s intervals (configurable).
- **No-blink refresh** — `FrigateImage` keeps the previous frame as Coil `placeholder` while the next one loads. Camera grid tiles opt out of disk cache (`diskCache = false`) so timestamped URLs don't accumulate stale entries.
- **Bounding boxes** — `?bbox=1` overlay on `latest.jpg` when enabled.
- **Grid columns** — 1× or 2× (configurable).
- **Sub-stream** — uses `<camera>_sub` stream if available in go2rtc (lower bandwidth for the live tile).

---

## Events

- Event list backed by Room (`CachedEvent` table) — shows offline cache while network refresh is in flight.
- Filters: camera and label dropdowns, backed by `EventsViewModel`.
- Retain / delete actions via Frigate API, mirrored to Room.
- Pull-to-refresh.
- Photo preference: snapshot vs thumbnail per-event row (Settings → Photo preference).

### Event detail
- Full snapshot image.
- Clip playback via Media3 ExoPlayer + `OkHttpDataSource` (uses auth client, no extra credentials needed).

### Image caching
- Coil `ImageLoader` is `@Singleton`, wired to the active server's auth-aware OkHttp client.
- Explicit **256 MB disk cache** (`image_cache/`) — event images survive app restarts.
- **20 % memory cache** for fast in-process hits.
- Live camera tiles and snapshot tiles set `diskCachePolicy = DISABLED` so they never write to disk.

---

## Authentication

| Mode | Credential | Storage |
|------|-----------|---------|
| None | — | — |
| Basic | `user:pass` | `CredentialStore` (Tink AEAD) |
| Frigate | JWT bearer token | `CredentialStore` |

- `CredentialStore` uses Android Keystore-wrapped Tink AEAD; never plaintext in DataStore.
- `AuthInterceptor` attaches the right header per request.
- `TokenRefreshAuth` retries on 401 with re-login for Frigate auth.
- Per-server self-signed cert support via pinned PEM (`TrustConfig`).
- RTSP credentials URL-encoded into the stream URI (URL-safe encoding via `URLEncoder`).

---

## Appearance

- Material 3 dynamic color via `material-kolor`.
- Theme mode: System / Light / Dark.
- AMOLED black mode.
- Accent color picker (6 presets).
- Palette style (TonalSpot, Vibrant, Fidelity, etc.).
- Wallpaper color extraction (Android 12+).

---

## Settings

| Setting | Effect |
|---------|--------|
| Live stream option | webrtc / rtsp / snapshot |
| RTSP port | Per-server, default 8554 |
| RTSP host override | LAN IP when domain can't reach port |
| Prefer sub stream | Uses `<cam>_sub` go2rtc stream |
| Auto landscape | Rotate on fullscreen |
| Show bounding boxes | `?bbox=1` on snapshots |
| Hide event image | Hide last-event snapshot below live tile |
| Auto-refresh snapshots | Grid auto-refresh toggle |
| Refresh interval | 1 / 3 / 5 / 10 / 30 s |
| Grid columns | 1× or 2× |
| Photo preference | snapshot / thumbnail in event rows |

---

## Bugs fixed in this branch (vs prior Gemini work)

| Bug | Fix |
|-----|-----|
| `AbstractMethodError` on RTSP/WebRTC open | Removed `userSettingsRepository()` from `WebRtcEntryPoint`; thread `autoLandscapeOnStream` and `showBoundingBoxes` through `CamerasUiState` instead |
| RTSP black screen until rotation | `PlayerView` always in tree; overlay on top |
| Camera grid / snapshot blink on refresh | Coil `placeholder = lastPainter` holds previous frame |
| Fullscreen doesn't hide app nav bar | `LocalFullScreenMode` CompositionLocal; `AppRoot` hides nav when true |
| Fullscreen doesn't hide system bars | `WindowInsetsControllerCompat.hide(systemBars())` |
| RTSP race condition — player starts before auth URL resolved | `produceState<String?>(null)` initial value; tile renders spinner until non-null |
| Snapshot crossfade / blink effect | Global `ImageLoader` `crossfade(false)`; per-request policy |
| WebRTC retry empty Bearer header | Guard: `if (token.isNotEmpty())` before setting header |

---

## Not yet done

- UI cleanup (some rough edges from prior AI-assisted iterations).
- Notification / MQTT service polish.
- Push notification deep-link to event detail.
- Multiple server support in the camera grid (currently shows active server only).
