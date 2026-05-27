# Frigate Viewer — Claude project memory

## Why
Native Android client for Frigate NVR. Rewritten in Kotlin to fix the JSON-parse-on-non-OK-response crash that broke the prior React Native fork on the cameras screen, and to deliver sub-second live video via Media3 + WebRTC instead of an RN-bridged VLC player. Unofficial, not affiliated with Frigate.

## Map
```
/CLAUDE.md                            ← this file (project north star)
/ARCHITECTURE.md                      ← design + invariants
/docs/
  adr/                                ← architecture decision records (numbered)
  runbooks/                           ← release, signing, on-call
/.claude/skills/                      ← reusable workflow playbooks
/.claude/hooks/                       ← deterministic guardrails (settings.local.json + scripts)
/gradle/libs.versions.toml            ← single source of truth for all dep versions
/app/src/main/java/net/triton/frigateviewer/
  FrigateViewerApp.kt                 ← Hilt application + notification channels
  MainActivity.kt                     ← Compose entry + nav scaffold
  di/                                 ← Hilt modules
  core/
    model/                            ← Frigate domain types (Serializable)
    network/  ← CLAUDE.md             ← JSON safety rules live here
    data/     ← CLAUDE.md             ← credential storage rules live here
  feature/
    cameras/ events/ settings/        ← screen + ViewModel + UI
  notification/ ← CLAUDE.md           ← foreground service rules live here
```

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
./gradlew ktlintCheck                 style (when ktlint plugin added)
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
adb connect 192.168.88.XXX:5555

# Verify
adb devices

# Deploy debug build
./gradlew installDebug

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

