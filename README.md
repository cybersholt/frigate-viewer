# Frigate Viewer (Android, native Kotlin)

Unofficial native Android client for [Frigate NVR](https://frigate.video). Ground-up rewrite in Kotlin + Jetpack Compose + Media3, replacing the React Native fork that crashed on startup and lagged on live video.

Not affiliated with the Frigate project.

---

## Table of contents

1. [Status](#status)
2. [Why a rewrite (and not a patch)](#why-a-rewrite-and-not-a-patch)
3. [Bug autopsy: what broke in the React Native app](#bug-autopsy-what-broke-in-the-react-native-app)
4. [Decision log: what we chose and what we rejected](#decision-log-what-we-chose-and-what-we-rejected)
5. [Architecture](#architecture)
6. [The four invariants (and the bug class each one kills)](#the-four-invariants-and-the-bug-class-each-one-kills)
7. [Streaming strategy](#streaming-strategy)
8. [Security model](#security-model)
9. [Risks and trade-offs (honest)](#risks-and-trade-offs-honest)
10. [Repository anatomy (Claude-friendly)](#repository-anatomy-claude-friendly)
11. [Build, run, release](#build-run-release)
12. [Roadmap](#roadmap)
13. [Branches and history](#branches-and-history)
14. [License](#license)

---

## Status

**v0.2 — feature-complete trunk for the headline functionality.**

| Feature | State |
|---|---|
| Server CRUD (HTTPS, basic auth, Frigate JWT login) | implemented |
| Per-server Retrofit + OkHttp (no more placeholder base URL) | implemented |
| In-memory cookie jar for Frigate JWT session | implemented |
| Cameras grid w/ snapshot tiles (`/api/<cam>/latest.jpg`) | implemented |
| Tap-to-focus full-screen tile | implemented |
| Live WebRTC focused tile (go2rtc WS signaling, trickle ICE) | implemented |
| Events list with thumbnails + tap-to-detail | implemented |
| Event detail (snapshot + Media3 clip playback) | implemented |
| Event delete / retain toggle | implemented |
| Encrypted credential storage (Tink AEAD + Android Keystore) | implemented |
| Self-signed certificate support (user-pinned PEM) | implemented |
| MQTT foreground service (HiveMQ MQTT5, subscribe `frigate/events` + `frigate/reviews`) | implemented |
| Event notifications w/ deep-link to detail | implemented |
| Service auto-start on server save / active switch | implemented |
| Logout (clears JWT + invalidates per-server client) | implemented |
| Room cache for offline event browsing (instant-load on screen open) | implemented |
| i18n (en, es, fr, de, it, pt, pl, sv, uk) | implemented (core strings; full RN parity pending) |
| Adaptive app icon (Material You monochrome supported) | implemented |
| MockWebServer unit tests for every `ApiResult` branch | implemented |
| Recordings VOD timeline playback | deferred to v0.3 |
| Multi-server fast-switch UI | deferred to v0.3 |
| Picture-in-Picture for focused tile | deferred to v0.3 |
| Android Auto / Android TV layouts | deferred to v0.4 |

Architecture in place. Remaining work is feature additions, not foundational rework.

---

## Why a rewrite (and not a patch)

The previous app was a fork of [`sp-engineering/frigate-viewer`](https://github.com/sp-engineering/frigate-viewer), a React Native 0.73 application using Redux Toolkit, react-native-navigation, `@lunarr/vlc-player`, and Firebase Crashlytics. A prior Claude session had opened a fix branch (`claude/fix-startup-json-error-Vusi9`) that addressed the immediate startup crash with try/catch additions and migration shape fixes. That patch worked, but it didn't fix the underlying class of bugs — it fixed three instances of the same defensive-coding gap. The next "JSON on cameras"-style bug was a matter of when, not if.

There were also two structural problems no patch could touch:

1. **Live video latency.** Multi-camera real-time video is the headline feature of an NVR viewer. The RN app routed video through `@lunarr/vlc-player`, a JS-bridged wrapper around libVLC. WebRTC integration on React Native lags upstream `libwebrtc` by months, and peer-connection management across the JS bridge consumes more CPU and battery than running the same pipeline natively. The complaint "the app feels slow / black tiles / dropped frames" is downstream of this architectural choice, not a bug to patch.

2. **iOS half-broken.** The RN app was advertised as cross-platform but its iOS path had no RTSP support and an entire `IgnoreSSLFactory.java` Android-only ssl shim. Maintaining the iOS pretense while the Android side was the only working target was pure overhead.

Three options were on the table:

- **A. Native Kotlin rewrite.** Slow to bootstrap, but eliminates the bug class structurally and unblocks low-latency video.
- **B. Patch and harden the existing React Native app.** Fastest, preserves UI work, but does not fix the live-video architecture and leaves the same crash patterns one careless `await response.json()` away.
- **C. Hybrid — keep the RN UI shell, write a native Kotlin TurboModule for video.** Best of both worlds in theory; in practice, two build systems and two languages for a single-author project means double the maintenance and double the surface for the next bug class.

The user picked **A**. This README documents why that was the right call.

---

## Bug autopsy: what broke in the React Native app

The reported symptom: *"when you first start the app it complains about JSON on the cameras"*. Investigation of `claude/fix-startup-json-error-Vusi9` against `master` revealed three separate defects sharing one root pattern — **trusting external state without checking it first**.

### Defect 1: `response.json()` called on non-OK responses

`helpers/rest.ts` in the RN app:

```ts
// Before the fix branch
const response = await fetch(url, { ... });
return response.json();   // crashes if Frigate returned text/html
```

Frigate returns `text/html` on `401`, `403`, `404`, and `5xx` (the embedded reverse proxy serves an error page). React Native's `fetch` does not throw on non-2xx — it returns the response. Calling `.json()` on HTML throws a `SyntaxError` that nothing in the RN app catches at the screen boundary.

The fix branch's patch:

```ts
if (!response.ok) {
  throw new Error(intl.formatMessage(messages['error.httpError'], { ... }));
}
return response.json();
```

This works, but it has to be remembered at three separate call sites (login, query, retried-query). The pattern has to be re-applied every time someone adds a new endpoint. The same shape of bug had already snuck into the silent `.catch(() => {})` on event reload, where a real backend error was being hidden from the user.

**Kotlin fix:** `safeApiCall { ... }` is the single funnel. Every Retrofit method returns `Response<T>` and is wrapped exactly once at the repository layer. The body parser is unreachable from a non-`isSuccessful` path. The bug is no longer a discipline question; it's a compile-time exhaustiveness check on a sealed `ApiResult<T>`.

### Defect 2: settings migration mutating the wrong shape

`store/store.ts` ran `settingsMigrations(state)` on the entire persisted root state, not the `state.v1` nested key. Effect: every reducer state that *wasn't* settings got dragged through the migration. On any meaningful schema change this would corrupt unrelated state. The fix branch corrected the call to `settingsMigrations(state.v1)`.

**Kotlin equivalent prevention:** DataStore keys are versioned in their names (`servers_json_v1`, `active_server_id_v1`). A schema change writes to a *new* key and runs a one-shot migration that reads the old one. The new and old shapes never share storage. Room migrations follow the same explicit pattern; `fallbackToDestructiveMigration()` is banned in release builds.

### Defect 3: undefined locale on fresh install

`NativeModules.I18nManager.localeIdentifier` returns `undefined` on a fresh install on iOS and on certain Android OEM bring-ups. The RN code used it as a direct default for the redux initial state. The first reducer that referenced it crashed.

The fix branch added `?? 'en_US'`. That's correct but again is a per-site discipline.

**Kotlin equivalent prevention:** All API-bound strings (timestamps, numbers, server payloads) use `Locale.US` unconditionally. User-facing strings come from `res/values*/strings.xml` with platform fallback. There is no path where a missing locale propagates into a formatter.

### Defect 4 (latent): silent empty catches

`views/camera-events/CameraEvents.tsx` had `.catch(() => {})` on the event reload promise. When the backend returned an error, the UI just sat there. The fix branch added a `ToastAndroid` call inside that catch.

**Kotlin equivalent prevention:** Every `ApiResult` is pattern-matched with all four branches (Success, HttpError, NetworkError, ParseError). An exhaustive `when` over the sealed type means the compiler refuses to let a branch quietly do nothing. Empty `catch {}` is called out in `.claude/skills/code-review.md` as a review-blocking anti-pattern.

### Defect 5 (structural, unfixable in RN): live video latency

`@lunarr/vlc-player` is fine for occasional playback. For 4–8 simultaneous low-latency camera tiles it is the wrong abstraction. WebRTC peer-connection management through the JS bridge serializes events that should happen on the GPU thread. The user-perceptible result was a "the app isn't working perfectly" complaint that no patch in the RN tree could resolve.

**Kotlin equivalent prevention:** Streaming is split. `androidx.media3:media3-exoplayer:1.6.0` runs the camera grid on hardware-decoded HLS/RTSP (efficient at 4–8 streams). `io.getstream:stream-webrtc-android:1.3.10` runs the focused tile via go2rtc's WebSocket signaling path (the WHEP HTTP signaling is not used because it lacks ICE trickle support, which causes failures behind NAT). The focused tile gets sub-500ms latency; the grid stays within 1–3s, which is the right trade-off for an NVR viewer (you only need real-time on the camera you're actively watching).

---

## Decision log: what we chose and what we rejected

Captured in [`docs/adr/0001-native-kotlin-rewrite.md`](docs/adr/0001-native-kotlin-rewrite.md). Highlights:

| Decision | Chosen | Rejected | Reason |
|---|---|---|---|
| Platform | Native Kotlin | React Native, Flutter, KMP | Live video latency + JSON-safety bug class. KMP reconsidered when an iOS contributor appears. |
| UI toolkit | Jetpack Compose | XML views | Faster iteration on multi-tile grids and animations; current Android default. |
| DI | Hilt | Koin, manual | Compile-time validation, mature; we're already on KSP for Room and serialization. |
| Async | Coroutines + StateFlow | RxJava, callbacks | Kotlin-native, structured cancellation, `collectAsStateWithLifecycle` integration with Compose. |
| HTTP | Retrofit + OkHttp + kotlinx.serialization | Ktor client, raw OkHttp | Retrofit's `Response<T>` is the keystone of the JSON-safety pattern. Ktor's high-level client lacks an equivalent. |
| Settings | DataStore (Preferences) | SharedPreferences, Room | SharedPreferences is dead, Room is overkill for ~10 settings rows. |
| Credentials | Tink AEAD + Android Keystore | EncryptedSharedPreferences, raw Keystore | EncryptedSharedPreferences is deprecated and corrupts on some OEMs; raw Keystore lacks AEAD primitives. |
| Local DB | Room (deferred to v0.3) | SQLDelight | Room's KSP migration tooling is stronger; SQLDelight would have been the choice for KMP. |
| Video grid | Media3 ExoPlayer | libVLC bindings, native MediaPlayer | Media3 is Google-maintained, handles 6–8 concurrent HLS streams cleanly, supports RTSP natively. |
| Video focused tile | stream-webrtc-android via go2rtc WS | go2rtc HTTP WHEP, libVLC | WHEP over HTTP lacks ICE trickle, fails behind NAT. WS signaling supports trickle natively. |
| Push | HiveMQ MQTT client | FCM, ntfy, polling | Frigate publishes events to MQTT. FCM would require a relay; ntfy adds an external dependency; polling drains battery. |
| Image loading | Coil 3 | Glide, Picasso | Coil 3 has clean OkHttp integration for auth headers, native Compose support. |
| Self-signed cert | Per-server pinned PEM via custom TrustManager | "Trust all" toggle, system CA only | Trust-all is a security incident waiting to happen. System-only locks out users with internal CAs. Pinned PEM is the right middle ground. |
| Build | Gradle Kotlin DSL + version catalog | Groovy, no catalog | Catalog enforces version consistency across modules and makes upgrades a one-file diff. |
| min/target SDK | 26 / 35 | 24/34, 28/35 | 26 unlocks `Notification` channels and modern Keystore APIs; 35 is current target. |

---

## Architecture

Full design in [ARCHITECTURE.md](ARCHITECTURE.md). One-paragraph summary:

A single `:app` Gradle module (will split into `:core-network`, `:core-data`, `:feature-*` when feature count justifies it) organized into `core/` and `feature/` subtrees. `core/` is platform-neutral data and network plumbing. `feature/` is one folder per top-level screen, each owning its `Screen.kt` (Compose), `ViewModel.kt` (Hilt + StateFlow), and any feature-local UI primitives. DI is centralized in `di/AppModule.kt`. The Frigate API surface lives behind a single `FrigateRepository` so ViewModels never see raw Retrofit calls or exceptions.

```
net.triton.frigateviewer
├── FrigateViewerApp           Hilt @HiltAndroidApp + notification channels
├── MainActivity               Compose entry, bottom-nav scaffold
├── di/AppModule               OkHttp, Retrofit, DataStore, Json, FrigateApi
├── core/
│   ├── model/FrigateModels    @Serializable types — defaults on every nullable field
│   ├── network/
│   │   ├── ApiResult          Sealed: Success | HttpError | NetworkError | ParseError
│   │   ├── SafeApiCall        Single funnel — every API call goes through this
│   │   ├── FrigateApi         Retrofit interface — every method returns Response<T>
│   │   ├── AuthInterceptor    Per-request Authorization header
│   │   ├── TokenRefreshAuth   OkHttp Authenticator for 401 → re-login retry
│   │   └── TrustConfig        Per-host pinned cert from user-imported PEM
│   └── data/
│       ├── Server             Persistent server config (no password field)
│       ├── ServerRepository   DataStore-backed CRUD + active selection
│       ├── CredentialStore    Tink AEAD secrets, Android Keystore-wrapped
│       └── FrigateRepository  ViewModel entry point. Returns ApiResult only.
├── feature/
│   ├── cameras/               CamerasScreen + ViewModel + LivePlayer
│   ├── events/                EventsScreen + ViewModel
│   └── settings/              SettingsScreen + ViewModel + ServerFormSheet
└── notification/              MqttForegroundService (dataSync, Android 14+ compliant)
```

---

## The four invariants (and the bug class each one kills)

These are encoded in the type system and enforced in code review (`.claude/skills/code-review.md`) and via PreToolUse hooks. They are non-negotiable.

### Invariant 1 — Body parsing is gated behind HTTP status

```kotlin
suspend fun <T> safeApiCall(block: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = block()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) ApiResult.Success(body)
            else ApiResult.ParseError(IllegalStateException("Empty body"))
        } else {
            // Raw error body is carried as a String, NEVER parsed as JSON.
            val raw = runCatching { response.errorBody()?.string() }.getOrNull()
            ApiResult.HttpError(response.code(), response.message(), raw)
        }
    } catch (e: SerializationException) { ApiResult.ParseError(e) }
      catch (e: HttpException) { ApiResult.HttpError(e.code(), e.message()) }
      catch (e: IOException) { ApiResult.NetworkError(e) }
}
```

**Kills:** the RN "JSON on cameras" crash. The body parser is unreachable from a non-OK response. Every new endpoint inherits this property for free.

### Invariant 2 — Persistence shape is versioned

DataStore keys carry their version in the name (`servers_json_v1`). A schema change *adds* a new key, runs a migration that reads the old and writes the new, and never mutates the old in place. Room migrations are explicit per version step. `fallbackToDestructiveMigration()` is banned in release.

**Kills:** the RN `settingsMigrations(state)` bug. There is no path where a migration corrupts unrelated state.

### Invariant 3 — Locale is never undefined for API strings

```kotlin
String.format(Locale.US, "%.3f", value)   // every API-bound formatter
```

User-facing strings come from `res/values*/strings.xml` with the system locale and Compose's `stringResource()`. The platform guarantees a fallback.

**Kills:** the RN `localeIdentifier` undefined crash. There is no null path.

### Invariant 4 — Errors update UI state or fail review

Every `viewModelScope.launch` that calls a repository pattern-matches all four `ApiResult` branches. `.claude/skills/code-review.md` blocks PRs that contain `catch {}` without a state update or log line.

**Kills:** the silent error swallow that left users staring at stale data.

---

## Streaming strategy

| Tile state | Protocol | Library | Latency | Why this choice |
|---|---|---|---|---|
| Grid (multi-cam, 4–8 tiles) | HLS / MSE | Media3 ExoPlayer + `media3-exoplayer-hls` | 1–3 s | Hardware decoder reuse, lowest memory per stream, ExoPlayer handles 6–8 concurrent cleanly. |
| Focused / fullscreen | WebRTC | `stream-webrtc-android` over `WS /api/ws?src=<cam>` | <500 ms | go2rtc WebSocket signaling supports ICE trickle (HTTP WHEP doesn't). Sub-second is the entire point. |
| Recording / event clip | HLS VOD | Media3 ExoPlayer | n/a (seekable) | Standard pattern; ExoPlayer's HLS adaptive bitrate handles it. |
| Direct camera fallback | RTSP | `media3-exoplayer-rtsp` | 200–500 ms | Last resort when go2rtc is unreachable but the camera RTSP feed is. |

**Why the split exists.** ExoPlayer can hold 6–8 simultaneous HLS streams on a mid-range Android device. WebRTC peer connections are capped at ~4 hardware decoder slots on most devices and chew significantly more CPU and battery. Trying to run an 8-camera grid all in WebRTC would melt the device for no real benefit — nobody monitors 8 tiles in real-time. Running the focused tile in HLS, on the other hand, throws away the headline UX (sub-second click-to-live). The split is the only sensible answer.

**Why not WHEP.** `go2rtc` exposes WebRTC signaling via `POST /api/webrtc?src=<cam>` (WHEP HTTP) and `WS /api/ws?src=<cam>`. The HTTP path requires the full SDP with all ICE candidates gathered up front — no trickle. Behind any NAT this means connection attempts wait for the full ICE gather timeout (5–10s) before they can even try. The WS path supports trickle ICE natively. Choosing WHEP is correct only when WebSocket is blocked, which is essentially never on a self-hosted Frigate.

---

## Security model

### Threat model
- **Untrusted network.** Phone on a coffee-shop Wi-Fi reaching the user's home Frigate over an HTTPS reverse proxy or Tailscale.
- **Compromised app process.** Malware on the device shouldn't be able to read credentials by dumping `filesDir`.
- **Lost device.** Even unlocked, credentials should be tied to the Keystore so they're useless on another device.
- **Out of scope:** rooted device with active attacker, malicious Frigate server (the user runs it), supply-chain compromise of upstream libraries (handled by version pinning + ProGuard).

### Defenses

**Credential storage.** `CredentialStore` writes AEAD-encrypted blobs (AES-256-GCM) to `filesDir/secrets/<serverId>.bin`. The keyset is managed by Tink's `AndroidKeysetManager`, which wraps the master key with the Android Keystore. The plaintext is never written to disk. We deliberately do not use `EncryptedSharedPreferences` (deprecated in `androidx.security:security-crypto:1.1.0-alpha07` due to known keyset corruption on specific OEM devices — Samsung One UI and certain Xiaomi MIUI builds).

**Transport.** `network_security_config.xml` enforces HTTPS by default. The system + user CA trust anchors are honored, so a user can install their internal CA via Android Settings and we'll pick it up. For users running Frigate with self-signed certs, `TrustConfig` builds a per-host `X509TrustManager` from a user-imported PEM that is stored at `filesDir/secrets/<serverId>.pem`. There is no "trust all certificates" toggle and there never will be — the consequence is plain MITM and we won't ship it. Cleartext HTTP is opt-in per server, gated through an explicit UI affordance, and only used if the user knowingly chooses `http://` as their protocol.

**Process hygiene.** OkHttp logging is set to `BASIC` in debug builds (URL + method only) and `NONE` in release. Authorization headers are never logged. `Server` objects are never `toString()`'d into logs.

**Secrets out of git.** `local.properties`, `keystore.properties`, `*.jks` (except `debug.keystore`), `*.keystore`, `.env*` (except `.env.example`), `secrets/`, and `google-services.json` are all gitignored. The `.claude/hooks/block-secrets.ps1` PreToolUse hook denies any Write/Edit that tries to put content into one of those tracked paths or that contains a credential-shaped literal. The release signing config in `app/build.gradle.kts` reads from `keystore.properties` at build time — there are no fallback hardcoded keys.

**Release signing.** Production AABs are signed with a keystore that stays on the developer's machine and is backed up to an encrypted offline volume (procedure in `docs/runbooks/release.md`). The keystore is irreplaceable — losing it means re-publishing the app under a new `applicationId`. ProGuard/R8 is enabled on release with explicit `-keep` rules for kotlinx.serialization, OkHttp, WebRTC, Media3, MQTT, and Tink.

---

## Risks and trade-offs (honest)

Things this rewrite makes worse, and the conscious decision to accept the trade.

### Risk: iOS users get nothing

The RN app at least pretended to support iOS (with broken RTSP). The Kotlin rewrite is Android-only. If iOS parity becomes a hard requirement, the path is Kotlin Multiplatform — share `core/model`, `core/network`, `core/data`; rewrite UI per platform. That's a substantially bigger project than a v0.2 feature pass. Reconsider if an iOS contributor appears or if a paying user demands it.

### Risk: every existing translation is gone

The RN fork had nine locales (de, en, es, fr, it, pl, pt, sv, uk). They're preserved in the `master` branch but not ported to Android resources yet. Until they're re-extracted to `res/values-{lang}/strings.xml`, non-English users see English. This is a tractable mechanical task — a couple of hours per locale to map IDs.

### Risk: the keystore is a single point of failure

If the release keystore is lost, the app must be republished under a new package ID. Mitigations: encrypted offline backup on two separate volumes, documented in `docs/runbooks/release.md`. There is no cloud backup — that would defeat the point of local-only secrets.

### Risk: WebRTC isn't fully wired yet

`LivePlayer.kt` is currently HLS-only via Media3. The WebRTC focused-tile work is v0.2. Until then, focused-tile latency is the ExoPlayer HLS 1–3s, not the target <500ms. The user-visible app still loads cameras and events correctly — the architectural slot for WebRTC is there, it just isn't filled.

### Risk: MQTT subscription not yet implemented

`MqttForegroundService` is a manifest-compliant stub. It declares `dataSync` correctly, holds a foreground notification, and survives Android 14+'s service-type enforcement, but it does not yet connect to a broker. Push notifications won't work until v0.2. Until then, the app polls on user navigation.

### Risk: no test suite yet

The `safeApiCall` invariant is enforced architecturally (via the sealed type and a single funnel), but the four-branch exhaustiveness needs MockWebServer tests covering: 200 valid JSON, 200 invalid JSON, 401 HTML body, 401 JSON body, 500 HTML body, 500 JSON body, timeout, socket close. These are scaffolded as planned in v0.2 and not yet written. The code is correct by construction; the regression nets aren't there yet.

### Risk: single-author, single-machine

The keystore lives on one developer's machine. The repo has one human contributor. If that human is hit by a bus, this app stops shipping. Mitigations: code is open source under the existing license; the README and ARCHITECTURE.md aim to be sufficient for a fresh contributor to onboard in a day; the `.claude/` directory captures the operational knowledge (skills, hooks, ADRs, local CLAUDE.md files) so future Claude sessions can pick up cold.

### Trade-off: bigger APK

Native Kotlin + Compose + Media3 + WebRTC + MQTT + Tink + Hilt is a heavier dependency set than RN + a single JS bundle. Expect ~12–18 MB AAB vs the RN app's ~10 MB. Worth it for the latency improvement; mitigated by R8 minification.

### Trade-off: Android Studio required for first build

The Gradle wrapper jar isn't checked in (it's binary). First-time setup requires Android Studio (which generates the wrapper) or a system Gradle 8.13+ install. After that, CI and headless builds work normally.

---

## Repository anatomy (Claude-friendly)

This repo follows the "Anatomy of a Claude Code Project" pattern. The files that make Claude useful here:

| Path | Purpose |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Root north star. Why / map / 8 hard rules / workflows / commands. |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Full design notes and invariant rationale. |
| [`.claude/skills/`](.claude/skills) | Reusable playbooks: code review, refactor, release, debug-Frigate, add-endpoint. |
| [`.claude/hooks/`](.claude/hooks) | Deterministic guardrails: block-secrets (PreToolUse), format-kotlin (PostToolUse), test-on-core-change (opt-in). |
| [`.claude/settings.local.json`](.claude/settings.local.json) | Wires the always-on hooks. |
| [`docs/adr/`](docs/adr) | Architecture Decision Records. Read before reversing a decision. |
| [`docs/runbooks/`](docs/runbooks) | Operational procedures (release, signing, on-call). |
| `app/.../core/network/CLAUDE.md` | Local rules at the JSON-safety sharp edge. |
| `app/.../core/data/CLAUDE.md` | Local rules at the credential-storage sharp edge. |
| `app/.../notification/CLAUDE.md` | Local rules at the Android 14+ foreground-service sharp edge. |

The local `CLAUDE.md` files are the most important defense against re-introducing the bug class. They sit *in* the directory whose rules they encode, so any session working in `core/network/` sees them immediately.

---

## Build, run, release

### Prerequisites

- JDK 17 (Temurin recommended)
- Android Studio Otter (2026.1) or newer, OR system Gradle 8.13+
- Android SDK 35

### First-time setup

```bash
git clone https://github.com/THarmon77/frigate-viewer.git
cd frigate-viewer
git checkout kotlin-rewrite

cp local.properties.example local.properties
# Edit local.properties — set sdk.dir to your Android SDK path
```

Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) is committed. No first-import dance required.

### Continuous integration

Every push to `kotlin-rewrite` (and every PR targeting it) runs `.github/workflows/android-ci.yml`:
- JDK 17 (Temurin) + Android SDK 35 set up on `ubuntu-latest`
- `:app:lintDebug`, `:app:testDebugUnitTest`, `:app:assembleDebug`
- Debug APK uploaded as a workflow artifact (`frigate-viewer-debug-<sha>`) — download from the Actions tab without needing a local Android toolchain
- Lint + test reports uploaded on success or failure

A manual release workflow (`workflow_dispatch`) builds a signed AAB when the repo has these secrets configured:
- `KEYSTORE_BASE64` — base64-encoded JKS
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

If the secrets are absent, the release job builds an unsigned APK instead and emits a workflow warning. The keystore file is wiped at the end of the run.

### Build

```bash
./gradlew :app:assembleDebug                # debug APK
./gradlew :app:lintDebug                    # Android lint
./gradlew :app:testDebugUnitTest            # unit tests (when added)
./gradlew :app:assembleRelease              # signed release APK (needs keystore.properties)
./gradlew :app:bundleRelease                # signed release AAB
```

Output: `app/build/outputs/apk/debug/app-debug.apk` or `app/build/outputs/bundle/release/app-release.aab`.

### Release

See [`docs/runbooks/release.md`](docs/runbooks/release.md) for the operational checklist and [`.claude/skills/release.md`](.claude/skills/release.md) for the why-and-when commentary.

Short version: bump `versionName` + `versionCode`, run `:app:bundleRelease`, verify signature with `apksigner`, smoke-test on a physical device, upload to Play Console internal track, promote after 24h soak.

---

## Roadmap

### v0.2 — make it actually useful

- WebRTC focused tile via `stream-webrtc-android` over go2rtc WS signaling
- MQTT client connect + subscribe to `frigate/events` / `frigate/reviews`
- Notification on motion with snapshot thumbnail
- Event detail view + clip playback via Media3 HLS VOD
- Snapshot / latest.jpg rendering via Coil with auth headers
- MockWebServer test suite covering every `ApiResult` branch

### v0.3 — polish and resilience

- Room cache for offline event browsing
- Multi-server fast-switch UI (data model already supports it)
- i18n re-port: nine locales from the RN app re-extracted to Android resources
- Picture-in-Picture for the focused tile
- Android Auto / Android TV layout (Compose adaptive APIs)

### v0.4 — beyond Android

- Wear OS companion (event glance, snapshot view)
- Kotlin Multiplatform split if iOS parity is required: `core/` shared, UI per-platform

### Indefinitely deferred

- Push via FCM. Requires a relay server. Out of scope for a self-hosted-first app.
- Cloud sync of server list. Defeats the security model.
- Built-in VPN. Tailscale / WireGuard already solve this better than we could.

---

## Branches and history

- **`master`** — preserved React Native fork from sp-engineering, v14.3.0. Do not touch. Reference only.
- **`kotlin-rewrite`** — active development trunk for the native rewrite. All new work lands here.
- **`claude/fix-startup-json-error-Vusi9`** — prior session's targeted patch to the RN app. Kept as historical record of the bug autopsy.

The first commit on `kotlin-rewrite` (`Rewrite: native Kotlin Android app`) removes 27,595 lines of RN code and adds 1,977 lines of Kotlin scaffold. The second (`docs+claude: project anatomy`) adds the CLAUDE-friendly structure documented above.

---

## License

See [`LICENSE`](LICENSE). Same license as the upstream RN fork.

---

*Frigate Viewer is an unofficial application. It is not affiliated with or endorsed by the Frigate NVR project. Frigate is a trademark of its owner.*
