# Frigate Viewer — Claude project memory

## Why
Native Android client for Frigate NVR. Rewritten in Kotlin to fix the JSON-parse-on-non-OK-response crash that broke the prior React Native fork on the cameras screen, and to deliver sub-second live video via Media3 + WebRTC instead of an RN-bridged VLC player. Unofficial, not affiliated with Frigate.

## Map
```
/CLAUDE.md                            ← this file (project north star)
/ARCHITECTURE.md                      ← design + invariants
/memory/project_state.md              ← what's working, what's next (update every session)
/docs/
  adr/                                ← architecture decision records (numbered)
  runbooks/                           ← release, signing, on-call
/.claude/skills/                      ← reusable workflow playbooks
/.claude/hooks/                       ← deterministic guardrails (settings.local.json + scripts)
/gradle/libs.versions.toml            ← single source of truth for all dep versions
/app/src/main/AndroidManifest.xml     ← MainActivity intent filters (launcher + frigateviewer:// deep link)
/app/src/main/java/net/triton/frigateviewer/
  FrigateViewerApp.kt                 ← Hilt application + notification channels
  MainActivity.kt                     ← Compose entry, nav scaffold, frigateviewer:// deep-link routing
  di/AppModule.kt                     ← Hilt modules
  core/
    model/FrigateModels.kt            ← Frigate domain types (Serializable): FrigateConfig, CameraConfig, FrigateEvent
    network/  ← CLAUDE.md             ← JSON safety rules live here
      FrigateApi.kt                   ← Retrofit interface (all endpoints)
      FrigateClient.kt                ← per-server OkHttp/Retrofit factory + PerServerAuthInterceptor + SessionCookieJar
                                         (cookies cached in-memory, persisted encrypted via CredentialStore)
      AuthInterceptor.kt              ← AuthMode enum + TokenRefreshAuthenticator (per-server, wired into
                                         FrigateClient's OkHttpClient.Builder as of 2026-07-10)
      SafeApiCall.kt                  ← the isSuccessful-gated funnel; ApiResult.kt is the sealed return type
      WifiMonitor.kt                  ← current SSID, used for local-network base-URL + RTSP LAN gating
      TrustConfig.kt                  ← per-host pinned cert / allow-untrusted
    data/     ← CLAUDE.md             ← credential storage rules live here
      Server.kt                       ← server config model: host, rtspPort, rtspHost (LAN override), localNetworkSsids
      ServerRepository.kt / CredentialStore.kt / FrigateRepository.kt / UserSettingsRepository.kt (DataStore prefs)
    db/EventCache.kt                  ← Room offline cache for events
    image/FrigateImage.kt             ← Coil AsyncImage wrapper (per-tile independent load/cache); ImageLoader.kt = DI-provided ImageLoader
  feature/
    cameras/
      CamerasScreen.kt                ← grid (LazyVerticalGrid, per-tile state) + FocusedTile + StreamContent (mode routing) + StreamTypeBadge (per-camera override menu)
      CamerasViewModel.kt             ← camera list/order/hidden, liveStreamOption, cameraStreamOverrides, currentSsid
      RtspLiveTile.kt / WebRtcLiveTile.kt / SnapshotLiveTile.kt ← the three live-mode players (each owns fullscreen + BackHandler)
      CameraStreamState.kt            ← Skeleton/LoadingWithCache/Live/Offline sealed state shared by all tiles
      CameraEditSheet.kt              ← reorder/hide bottom sheet
    events/
      EventsScreen.kt / EventsViewModel.kt   ← grid + filters (camera/label/zone), ordered by the same cameraOrder as Cameras
      EventDetail.kt                  ← event detail: VOD/Clip tabs (ClipPlayer, HLS via DefaultMediaSourceFactory), timeline
      HorizontalTimeline.kt / TimelinePanel.kt
    settings/ SettingsScreen.kt / SettingsViewModel.kt ← servers, appearance, live-stream default, etc.
  notification/ ← CLAUDE.md           ← foreground service rules live here
```

## Known gaps (found during 2026-07-03 bugfix pass, not yet resolved)
- ~~`TokenRefreshAuthenticator` never attached~~ — **fixed 2026-07-10.** Rebound to a specific `Server` (matching
  `PerServerAuthInterceptor`'s pattern instead of the old activeServer()-lookup design) and wired via
  `.authenticator(...)` in `FrigateClient.buildClient()`. A 401 now attempts one silent `POST api/login` refresh
  + retry before surfacing as `ApiResult.HttpError(401, ...)`. Verified via smoke test (cameras/events/event-detail
  all still load); the live "recovers from a real 401" path itself is not separately verified.
- Frigate recordings have no "substream" VOD — `<camera>_sub` is a go2rtc *live*-restream name only. Don't reuse
  it for `/vod/.../index.m3u8` or `/api/events/.../clip.mp4` paths (confirmed via live 404 in production logs).

## Rules (non-negotiable)
1. **Never parse a non-OK HTTP response as JSON.** Every Retrofit call funnels through `safeApiCall()` which gates `.body()` behind `response.isSuccessful`. See `core/network/CLAUDE.md`.
2. **Never store credentials in DataStore plaintext.** Use `CredentialStore` (Tink AEAD + Android Keystore). EncryptedSharedPreferences is banned (deprecated + OEM keyset corruption). See `core/data/CLAUDE.md`.
3. **No `fallbackToDestructiveMigration()`** in Room. No in-place mutation of persisted DataStore shapes. New schema = new versioned key + explicit migration.
4. **No `.catch {}` empty bodies.** Every `ApiResult` branch must update UI state or log + retry.
5. **No "trust all certs" toggle.** Self-signed support is per-server pinned PEM only, imported into `CredentialStore`.
6. **Secrets stay local.** `local.properties`, `keystore.properties`, `*.jks`, `.env`, `secrets/`, `google-services.json` are gitignored and stay on this machine.
7. **Use `Locale.US` for API-bound strings.** User-facing strings via `res/values/strings.xml`.
8. **Pin dependency versions in `libs.versions.toml`.** Never inline `"1.2.3"` in a `build.gradle.kts`.

## Workflows
- **Add a new Frigate endpoint** → `.claude/skills/add-endpoint.md`
- **Code review checklist** → `.claude/skills/code-review.md`
- **Refactor playbook** → `.claude/skills/refactor.md`
- **Debug a live-stream issue** → `.claude/skills/debug-frigate.md`
- **Cut a release** → `.claude/skills/release.md` + `docs/runbooks/release.md`

## Commands
```
./gradlew :app:assembleDebug          build debug
./gradlew :app:assembleRelease        build signed release (needs keystore.properties)
./gradlew :app:testDebugUnitTest      unit tests
./gradlew :app:lintDebug              Android lint
./gradlew ktlintCheck                 style check (ktlint CLI; rules in .editorconfig)
./gradlew ktlintFormat                auto-fix style violations
```

## Branches
- `master` — preserved RN fork (do not touch)
- `kotlin-rewrite` — active development trunk for the native rewrite

## When in doubt
Read `ARCHITECTURE.md` for the why. Read the nearest local `CLAUDE.md` for the gotchas. Read the relevant ADR in `docs/adr/` for prior decisions before reversing one.

## Documentation discipline
**Keep docs current as code changes** — don't batch at the end. After any session that ships a feature:
1. Update this `CLAUDE.md` if device info, commands, or key rules changed.
2. Update `memory/project_state.md` with what's now working and what's next.
3. If a new architectural decision was made, add an ADR in `docs/adr/`.
Do this before the context gets too long to remember what changed.

## Device / Deployment

Pixel 8 connected via wireless ADB.

# Connect if session dropped
adb connect 192.168.88.32:5555

# ZeroTier wireless ADB (use only when user explicitly asks to deploy via ZeroTier)
adb connect 192.168.192.200:5555

# Verify
adb devices

# Deploy debug build
./gradlew installDebug

## Build Environment

JDK: Android Studio bundled JBR at `C:/Program Files/Android/Android Studio/jbr`
- Pinned in `gradle.properties` via `org.gradle.java.home`
- Required because Cursor IDE's embedded JRE (`.antigravity-ide`) lacks `jlink.exe`, which AGP 9.2.1 needs for the `androidJdkImage` transform
- Do NOT remove `org.gradle.java.home` from `gradle.properties` or builds will break in Cursor
- Do NOT let `gradle/gradle-daemon-jvm.properties` exist/reappear. Gradle 9's daemon-toolchain feature reads it
  and OVERRIDES `org.gradle.java.home`, auto-selecting whatever JDK 21 it finds first — on this machine that's
  the Antigravity IDE's bundled JRE, which lacks `jlink.exe` and fails `:app:compileDebugJavaWithJavac` with
  `JdkImageTransform ... jlink executable ... does not exist`. If `./gradlew updateDaemonJvm` or an IDE
  regenerates this file, delete it — the pinned `org.gradle.java.home` is the only source of truth here.

ANDROID_HOME: `C:/Users/Sean/AppData/Local/Android/Sdk`

# Screenshot a screen (run from project root; screenshots/ dir exists in repo)
adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
adb exec-out screencap -p > screenshots/filename.png

# Nav tab tap coordinates (Pixel 8)
# Cameras: adb shell input tap 173 2274
# Events:  adb shell input tap 540 2274
# Settings: adb shell input tap 907 2274

# Logcat filtered to app
adb logcat -s OkHttp,FrigateViewer,AndroidRuntime

Device: Pixel 8, Android CinnamonBun (API 36)
Package: net.triton.frigateviewer.debug
Activity: net.triton.frigateviewer.MainActivity
Launch: adb shell am start -n "net.triton.frigateviewer.debug/net.triton.frigateviewer.MainActivity"

## Development Rules
1. No destructive migrations in Room
2. No `.catch {}` empty bodies
3. Use `safeApiCall()` for all network calls
4. Store credentials in `CredentialStore`
5. Secrets stay local, never commit them
6. Pin dependency versions in `libs.versions.toml`

## UI / UX Rules
1. All screens must have a light and dark mode toggle
2. No hardcoded colors, use theme attributes
3. All buttons must have a ripple effect
4. All lists must support pull to refresh
5. All forms must support keyboard navigation
6. All screens must support landscape and portrait mode
7. All screens must support 1x1 aspect ratio, 2x2 aspect ratio, 3x3 aspect ratio
8. All password fields must include a visibility toggle (eye icon) to reveal/hide the value — never a bare `PasswordVisualTransformation()` with no way to check what was typed

## Agent skills

### Issue tracker

GitHub (`cybersholt/frigate-viewer`, the `origin` remote), via the `gh` CLI; external PRs are not treated as a triage surface. See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`) — no repo-specific overrides. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — one `CONTEXT.md` (once created) + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

