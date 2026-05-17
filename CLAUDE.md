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
