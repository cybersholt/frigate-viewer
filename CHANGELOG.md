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
- Auto-rotate restores `SCREEN_ORIENTATION_UNSPECIFIED` on exit.
- Back gesture / button exits fullscreen before navigating away.
- Pinch-to-zoom on RTSP and WebRTC tiles (1×–5×, double-tap resets).

### Audio
- Both RTSP and WebRTC tiles default to **muted**.
- Mute/unmute button bottom-left on every live tile.
- RTSP: `ExoPlayer.volume = 0f / 1f`.
- WebRTC: audio transceiver added (`RECV_ONLY`); `AudioTrack.setEnabled()` on toggle.

### RTSP host override
- Server form has an optional "RTSP host" field — set to LAN IP when the domain doesn't forward port 8554.
- Falls back to the HTTP host when blank.

### Fullscreen controls
- Controls (`Modifier.navigationBarsPadding()`) sit above the Android gesture zone so they're reachable even in edge-to-edge fullscreen.

---

## Camera grid

- **Auto-refresh** at 1 / 3 / 5 / 10 / 30 s intervals (configurable).
- **No-blink refresh** — `FrigateImage` keeps the previous frame as Coil `placeholder` while the next one loads.
- **Bounding boxes** — `?bbox=1` overlay on `latest.jpg` when enabled.
- **Grid columns** — 1× / 2× / 3× (configurable).
- **Sub-stream** — uses `<camera>_sub` stream if available in go2rtc.
- **Skeleton loader** — shimmer tiles during cold-start; shows last-known cached snapshot at 50 % opacity under the shimmer so the grid doesn't flash empty.
- **Camera name pill** — top-left overlay on every tile (secondaryContainer color).
- **Swipe actions** (can be disabled in Settings):
  - Swipe right → label + zone filter chips that navigate straight to Events.
  - Swipe left → recent event thumbnail panel.
- **Hamburger menu** → "Edit Cameras" sheet: drag-to-reorder and per-camera hide/show toggle; order + hidden set persisted in DataStore.
- **Tap → Events deep-link** — camera tile tap fires a camera-filtered event list navigation.

### Local network switching
- Per-server optional "Local Server URL" (e.g. `http://192.168.1.10:5000`).
- One or more Wi-Fi SSIDs associated with each server; `WifiMonitor` (StateFlow) swaps the Retrofit base URL transparently when the device joins a matching SSID.
- "Add current Wi-Fi" button in server form (requires `ACCESS_FINE_LOCATION`).

---

## Events

- Event list backed by Room (`CachedEvent` table) — shows offline cache while network refresh is in flight.
- **Filter drawer** — ModalBottomSheet with Camera / Label / Zone multi-select checkboxes + Retained Only toggle; replaces the two old dropdown menus.
- **Segmented event pill** — type | score | zone segments, each a distinct color (person = blue, zone color hashed from name).
- Retain / delete actions via Frigate API, mirrored to Room.
- Pull-to-refresh.
- Photo preference: snapshot vs thumbnail per-event row.
- **Grid columns** — 1× / 2× / 3× (configurable).
- **Date format** — "descriptive" (`2 min ago`) or "numeric" (`06/05/26 15:32`).
- Events fetched up to 7 days / 2 000 limit; timeline zoom-out silently re-fetches up to 30 days.

### Event detail — 3-view system
Accessed via any event card; flag icon top-right cycles views:

| View | Content |
|------|---------|
| **Timeline** | Full-screen `HorizontalTimeline` — density waveform, tap/drag scrubber, red event blocks, day-abbreviated axis when zoomed > 24 h |
| **Events** | Filtered grid of events using full `EventCard` (16:9 thumbnail, relative time, red dot) |
| **Detail** | Snapshot + metadata + `EventDetailTimeline` (sub-events + expandable detail) |

- Date pill → `DatePickerDialog` to jump to a different day.
- Hamburger → Calendar item opens the date picker.
- `onNavigateToEvent` — navigates to adjacent events without destroying the back stack.
- Timeline auto-range: opens to a window that always shows the event (`initialRange = ageHours + 2h`, clamped 4–168 h).

### VOD / Clip player
- **VOD** — hourly HLS served by Frigate at `/vod/{yyyy}-{MM}/{dd}/{HH}/{camera}/index.m3u8`; seekable via the HorizontalTimeline scrubber.
- **Clip** — `/api/events/{id}/clip.mp4` plays directly; fast-start, no seeking required.
- Sub button (VOD only) appends `_sub` to camera name.
- Player states: `BUFFERING / READY / ERROR` with overlay indicators and a retry button on error.
- Deferred seek via `AtomicLong seekRef` — pending seek is applied on `STATE_READY` so scrubbing before buffering completes works.
- VOD path always in UTC (Frigate Docker filesystem is UTC-based).

### Image caching
- Coil `ImageLoader` is `@Singleton`, wired to the active server's auth-aware OkHttp client.
- **256 MB disk cache** (`image_cache/`) — event images survive app restarts.
- **20 % memory cache** for fast in-process hits.
- Live camera tiles use `diskWriteOnly = true` with a stable key `snap_<camera>` — each refresh overwrites the same entry; skeleton tiles read this key (`CachePolicy.ENABLED`) to show the last known frame.

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
- RTSP credentials URL-encoded into the stream URI.

---

## Appearance

- Material 3 dynamic color via `material-kolor 4.1.1`.
- Theme mode: System / Light / Dark.
- AMOLED black mode.
- **Accent color** — 20 preset swatches in a horizontal scrollable row; custom colors saved below presets with remove button; "+" opens `AddCustomColorSheet` (hex field + preset grid).
- **Palette style** — slide-out `ModalBottomSheet` (`PaletteStyleSheet`) showing name + full description + radio button for each of 9 styles (TonalSpot, Neutral, Vibrant, Expressive, Rainbow, FruitSalad, Monochrome, Fidelity, Content).
- **Contrast** — slider −100 → +100; maps to `contrastLevel: Double` on `DynamicMaterialTheme`.
- **Card shape** — slide-out `CardShapeSheet` grid with 7 presets (Sharp 0 dp → Pill 50 dp); applies a custom `Shapes` object to both `DynamicMaterialTheme` and `MaterialTheme` branches; camera tile borders follow the same shape.
- **Border width** — 0–8 dp discrete slider; provided via `LocalCardBorderWidth` CompositionLocal; camera tiles conditionally apply `.border()`.
- Wallpaper color extraction (Android 12+).
- **Status bar icon color** — `SideEffect` in `FrigateViewerTheme` sets `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars` from the effective dark theme so icons stay visible when app mode differs from system mode.
- **Navigation bar gesture area** — `Spacer` filled with nav-bar container color sits below the 68 dp bottom tab bar, ensuring content never bleeds into the gesture handle zone.

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
| Camera grid columns | 1× / 2× / 3× |
| Show camera swipe actions | Enable/disable swipe panels on tiles |
| Photo preference | snapshot / thumbnail in event rows |
| Events grid columns | 1× / 2× / 3× |
| Date format | descriptive / numeric |
| Color mode | System / Light / Dark |
| Accent color | 20 presets + saved custom colors |
| Palette style | 9 MaterialKolor styles via slide-out sheet |
| Contrast | −100 → +100 slider |
| AMOLED black | Pure-black backgrounds in dark mode |
| Use wallpaper color | Dynamic color from wallpaper (API 31+) |
| Card shape | Sharp → Pill, 7 presets via slide-out sheet |
| Border width | 0–8 dp slider, applied to all cards |
| Device Capabilities | Read-only: sample rate, audio outputs, Media3 version, H264/H265 codec support |

---

## Bugs fixed in this branch (vs prior Gemini work + iterations)

| Bug | Fix |
|-----|-----|
| `AbstractMethodError` on RTSP/WebRTC open | Removed `userSettingsRepository()` from `WebRtcEntryPoint`; thread settings through `CamerasUiState` |
| RTSP black screen until rotation | `PlayerView` always in tree; overlay on top |
| Camera grid / snapshot blink on refresh | Coil `placeholder = lastPainter` holds previous frame |
| Fullscreen doesn't hide app nav bar | `LocalFullScreenMode` CompositionLocal |
| Fullscreen doesn't hide system bars | `WindowInsetsControllerCompat.hide(systemBars())` |
| RTSP race condition — player starts before auth URL resolved | `produceState<String?>(null)` initial value; tile renders spinner until non-null |
| Snapshot crossfade / blink effect | Global `ImageLoader` `crossfade(false)` |
| WebRTC retry empty Bearer header | Guard: `if (token.isNotEmpty())` |
| JSON parse crash on non-OK response | `safeApiCall()` gates `.body()` behind `response.isSuccessful` |
| Camera grid scroll interfering with swipe | Replaced `awaitPointerEventScope` with separate `detectHorizontalDragGestures` + `detectTapGestures` blocks |
| Cameras pull-to-refresh not filling box | Added `fillMaxSize()` to inner `LazyVerticalGrid` inside `PullToRefreshBox` |
| Camera auto-refresh writing duplicate cache entries | `diskCacheKey` includes `refreshTimestamp`; skeleton reads stable `snap_<name>` key via `diskWriteOnly=true` pattern |
| VOD scrubber does nothing until clip buffers | `AtomicLong seekRef` + `onPlaybackStateChanged(STATE_READY)` applies pending seek |
| VOD shows wrong hour due to timezone | VOD path uses `TimeZone.UTC`; Frigate Docker stores recordings by UTC hour |
| Dead recompose loop in ClipPlayer | Removed unused `progress` state updated in `while(true)` loop |
| EventDetail navigation pops wrong back stack entry | Removed `popUpTo("event/{id}") { inclusive=true }` with literal template string; now just `nav.navigate("event/$newId")` |
| Timeline scrubber misses initial seek on new URL | Split into two `LaunchedEffect` — one fires on `player` change, one debounced on `seekPositionMs` |
| Status bar icons invisible in app dark mode | `SideEffect` in `FrigateViewerTheme` syncs `isAppearanceLightStatusBars` with effective dark theme |
| Events content overlaps gesture handle bar | Column + Spacer(navigationBars inset) below NavigationBar; Scaffold bottom padding now includes gesture area |

---

## Not yet done

- Camera tap action setting (open stream vs open event list).
- MQTT / notification service polish.
- Push notification deep-link to event detail.
- Multiple server support in the camera grid.
- Persist `score` field in `CachedEvent` (requires Room migration).
