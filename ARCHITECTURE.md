# Architecture

## Stack

- Kotlin 2.1, AGP 8.10, JDK 17
- Jetpack Compose (Material 3), Compose BOM 2026.05
- Hilt DI
- Coroutines + StateFlow
- Retrofit + OkHttp + kotlinx.serialization
- DataStore (Preferences) for non-secret settings
- Tink AEAD + Android Keystore for credentials
- Media3 ExoPlayer (HLS/RTSP) for camera grid
- stream-webrtc-android for low-latency focused tile (v0.2)
- HiveMQ MQTT client for push (v0.2)
- Coil 3 for image loading
- min SDK 26, target SDK 35

## Module layout (single `:app` module for v0.1, will split when it grows)

```
net.triton.frigateviewer
├── FrigateViewerApp          Hilt application + notification channels
├── MainActivity              Compose entry, bottom-nav scaffold
├── di/AppModule              OkHttp, Retrofit, DataStore, Json, FrigateApi
├── core/
│   ├── model/                Frigate domain types (Serializable)
│   ├── network/
│   │   ├── ApiResult         Sealed Success | HttpError | NetworkError | ParseError
│   │   ├── SafeApiCall       Single funnel — every API call goes through this
│   │   ├── FrigateApi        Retrofit interface (Response<T> everywhere)
│   │   ├── AuthInterceptor   Per-request Authorization header
│   │   ├── TokenRefreshAuth  OkHttp Authenticator for 401 → re-login retry
│   │   └── TrustConfig       Per-host pinned cert from user-imported PEM
│   └── data/
│       ├── Server            Persistent server configxcdf
│       ├── ServerRepository  DataStore-backed CRUD + active selectionxcdf
│       ├── CredentialStore   Tink AEAD secrets, Android Keystore-wrappedxcdf
│       └── FrigateRepository Single entry point for ViewModelsxcdf
├── feature/xcdf
│   ├── cameras/              CamerasScreen + ViewModel + LivePlayerxcdf
│   ├── events/               EventsScreen + ViewModelxcdf
│   └── settings/             SettingsScreen + ViewModel + ServerFormSheetxcdf
└── notification/             MqttForegroundService
```

## Critical invariants

These are the defenses against the bug classes that broke the prior React Native version.

### 1. Never parse a non-OK response body as JSON

The RN bug: `await response.json()` was called regardless of HTTP status. Frigate returns `text/html` on errors. JSON.parse on HTML → unhandled crash on the cameras screen at startup.

Kotlin fix: `safeApiCall` is the only path a Retrofit `Response<T>` reaches. It checks `response.isSuccessful` before touching `.body()`. Errors become structured `ApiResult.HttpError` carrying the raw body untouched.

```kotlin
when (val r = repo.config()) {
    is ApiResult.Success     -> render(r.data)
    is ApiResult.HttpError   -> showHttp(r.code, r.message)
    is ApiResult.NetworkError -> showNetwork(r.cause)
    is ApiResult.ParseError  -> showParse()    // never crashes the app
}
```

### 2. Settings migrations are explicit and versioned

The RN bug: `settingsMigrations(state)` mutated the full persisted state shape instead of just `state.v1`, corrupting saved settings.

Kotlin practice:
- DataStore keys are versioned (`servers_json_v1`, never reuse).
- New schema = new key + an explicit migration block that reads old, writes new, never overwrites both at once.
- Room migrations declared per version step. Never `fallbackToDestructiveMigration()` in release.

### 3. Locale always has a fallback

The RN bug: `NativeModules.I18nManager.localeIdentifier` was undefined on a fresh install, propagating null into Intl formatters.

Kotlin practice: `Locale.US` for all API-bound strings (timestamps, numbers); user-facing strings go through resources with system locale + sane defaults.

### 4. Errors never get silently swallowed

The RN bug: `.catch(() => {})` empty bodies on event reload hid backend failures.

Kotlin practice: every `ApiResult` branch ends in either a UI state update or an explicit log + retry hook. No empty try/catch.

## Streaming strategy

| Tile state | Protocol | Library | Latency |
|---|---|---|---|
| Grid (multi-cam) | HLS / MSE | Media3 ExoPlayer + HLS | 1–3 s |
| Focused / fullscreen | WebRTC | stream-webrtc-android via go2rtc `/api/ws?src=X` | <500 ms |
| Recording playback | HLS VOD | Media3 ExoPlayer | n/a |
| Last-resort | RTSP | media3-exoplayer-rtsp | 200–500 ms |

Reason for the split: ExoPlayer handles 6–8 simultaneous HLS streams cleanly on mid-range devices; WebRTC peer connections cap at ~4 hardware decoder slots and chew CPU + battery. Use the right tool per tile state.

## Security

- All credentials encrypted by `CredentialStore` (Tink AEAD, key wrapped in Android Keystore). Never in DataStore plaintext.
- `EncryptedSharedPreferences` deliberately avoided — deprecated in security-crypto 1.1.0+ and corrupts on some OEMs.
- Network: `network_security_config.xml` enforces HTTPS-only by default. Cleartext is opt-in per-server via runtime OkHttp scheme. Self-signed certs supported only via user-installed CA (Android Settings) or a per-server pinned PEM imported into `CredentialStore`. There is no "trust all certs" toggle.
- `local.properties`, `keystore.properties`, `*.jks`, `.env`, `secrets/` are all gitignored.
- ProGuard/R8 enabled on release builds; serializer rules in `proguard-rules.pro`.

## Push notifications

Frigate publishes events on MQTT (topic `frigate/events` or `frigate/reviews`). `MqttForegroundService` (manifest type `dataSync`, required for Android 14+) subscribes via HiveMQ MQTT client, deduplicates by event ID, and posts to a high-importance channel. Snapshot thumbnails fetched via Coil with the active server's auth headers.

## Testing

- Unit: JUnit5 + MockK + Turbine for Flow assertions.
- Network: OkHttp MockWebServer — every `safeApiCall` branch (200, 401 HTML body, 500 HTML body, malformed JSON 200, socket timeout) gets a dedicated test.
- UI: Compose test rule + Hilt test modules with fake repositories.
