# Frigate Viewer (Android, native Kotlin)

Native Android client for [Frigate NVR](https://frigate.video). Rewritten from scratch in Kotlin + Jetpack Compose + Media3 to fix the live-streaming latency and JSON-parse crash classes that the previous React Native version suffered from.

Unofficial — not affiliated with Frigate.

## Status

v0.1 — scaffold. Buildable. Implements:
- Server list + add/edit (HTTPS, basic auth, Frigate JWT)
- Camera list (loads `/api/config` safely — no crash on non-OK responses)
- Event list (`/api/events`)
- MQTT foreground service stub
- Encrypted credential storage (Tink AEAD + Android Keystore)
- Self-signed cert support via user-imported trust anchor

Not yet implemented: live WebRTC tile, MQTT event subscription, snapshot/thumbnail rendering, event detail view, recordings playback, multi-server fast-switch.

## Build

Requires Android Studio Otter (2026.1) or newer, JDK 17, Android SDK 35.

```bash
cp local.properties.example local.properties   # edit sdk.dir
# First time only — generate the Gradle wrapper jar:
#   Open in Android Studio (which auto-creates gradle/wrapper/gradle-wrapper.jar)
#   OR if you have system Gradle 8.13+:  gradle wrapper
./gradlew :app:assembleDebug
```

Release builds need `keystore.properties` (see `keystore.properties.example`). Both files are gitignored — secrets stay on this machine only.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md).

## Why a rewrite

The prior React Native fork (sp-engineering) crashed at startup on the cameras screen because `response.json()` was called on non-OK HTTP responses. Frigate returns HTML on 4xx/5xx, which the JSON parser would choke on. The Kotlin rewrite funnels every API call through `safeApiCall` which gates body parsing behind `response.isSuccessful`. Same class of bug also caused: silent error swallowing on event refresh, missing settings migration on persisted state shape changes, undefined locale crash on first boot.

## License

See `LICENSE`.
