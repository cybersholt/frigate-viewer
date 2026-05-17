# ADR-0001: Rewrite the Android client in native Kotlin

- **Status:** Accepted
- **Date:** 2026-05-17
- **Deciders:** THarmon77

## Context
The forked React Native app (`sp-engineering/frigate-viewer`, v14.3.0) crashed at startup on the cameras screen and had multiple defensive-coding gaps:

- `response.json()` called unconditionally on Retrofit-style fetches. Frigate returns `text/html` on 4xx/5xx, which crashed the JSON parser.
- `settingsMigrations(state)` was applied to the full persisted state shape instead of `state.v1`, corrupting saved settings.
- `NativeModules.I18nManager.localeIdentifier` was undefined on fresh installs, propagating null into Intl formatters.
- Live video latency on the RN-bridged VLC player was poor; WebRTC integration through React Native lags upstream `libwebrtc`.

The product is a low-latency multi-camera NVR viewer with push notifications. The framework needs to deliver native video, native background services, and predictable JSON handling.

## Decision
Rewrite the Android client from scratch in native Kotlin with Jetpack Compose, Hilt, Coroutines, Retrofit + kotlinx.serialization, Media3 ExoPlayer (HLS/RTSP), `stream-webrtc-android` (WebRTC focused tile), HiveMQ MQTT (push), Tink AEAD + Android Keystore (credentials), DataStore (settings). Target SDK 35, min SDK 26.

A sealed `ApiResult<T>` + single `safeApiCall` funnel make the prior JSON crash class structurally impossible.

The React Native `master` branch is preserved untouched for reference; active development moves to `kotlin-rewrite`.

## Consequences

**Easier**
- Sub-second live video on the focused tile (WebRTC via go2rtc WS).
- HTTP error handling becomes a compile-time sealed-type exhaustiveness check.
- Credential storage uses platform-native Keystore-wrapped AEAD; no JS-bridge surface.
- Foreground services for MQTT push are native and meet Android 14+ `dataSync` requirements.

**Harder**
- No iOS share. iOS users lose the (already RTSP-broken) sp-engineering client.
- Two codebases if iOS parity is wanted later. Kotlin Multiplatform is a future path if needed.
- All the i18n strings from the RN app must be re-extracted to `res/values-*/strings.xml`.

## Alternatives considered
- **Harden the existing React Native app.** Rejected: would fix the JSON crash but not the live-video latency root cause (RN bridge + VLC player).
- **Hybrid (keep RN, write a native Kotlin video TurboModule).** Rejected: preserves UI work but adds a multi-build-system maintenance burden for a single-author project. Reconsider if an iOS contributor appears.
- **Flutter.** Rejected: video plugin ecosystem is improving but WebRTC integration still lags the native libwebrtc cadence; we'd hit the same RN-style bridge bottleneck on the focused tile.

## References
- `ARCHITECTURE.md`
- Research memo: `~/.claude/agent-memory/researcher/frigate-android-kotlin.md`
- Prior RN crash diff: `git show origin/claude/fix-startup-json-error-Vusi9 -- helpers/rest.ts`
