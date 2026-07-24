# Frigate Viewer — Claude project memory

## Why
Native Android client for Frigate NVR. Rewritten in Kotlin to fix the JSON-parse-on-non-OK-response crash that broke the prior React Native fork on the cameras screen, and to deliver sub-second live video via Media3 + WebRTC instead of an RN-bridged VLC player. Unofficial, not affiliated with Frigate.

## Finish the task (non-negotiable, read before anything else)

**If the user asked for it, it gets done this session. Do not defer requested work into
`docs/TODO.md`.**

This was violated repeatedly on 2026-07-21: the same player changes were asked for three times
because each round they were written into the backlog instead of built. Filing a request is not
progress — it converts one request into a repeated one.

1. **`docs/TODO.md` is for work nobody has asked for yet.** Ideas, observations, follow-ups you
   spotted on your own. The moment the user asks for something it stops being backlog and becomes
   the task. Never move a live request into it.
2. **Cost and context warnings are informational. They never justify deferring.** The
   `COST CRITICAL` and `StrategicCompact` hooks say so in their own text. Running long is not a
   reason to stop; if context is genuinely the limit, compact and keep going.
3. **"I'll do it next", "filed for the next pass", "remaining polish" are not outcomes.** If a turn
   ends with requested work undone, that is an unfinished task, no matter how neat the summary is.
4. **Don't invent scope boundaries to justify stopping.** "That belongs to the next feature",
   "that's the same file so I'll batch it", "that's a separate surface" — if the user asked, none of
   those are reasons. Batching is the user's call, not yours.
5. **Small and adjacent means do it now.** A two-line change is never worth a backlog entry. The
   speed menu got deferred as "polish" while carrying a real bug (`"${speed.toInt()}x"` renders
   `0x` for `0.5`) — deferring cost more than fixing.
6. **If you genuinely cannot finish, say so in the reply, in plain words, with the reason** — a
   blocker only the user can resolve, or a decision only they can make. Say it out loud. Do not
   record it in a doc and let the summary imply completion.
7. **Ask only when the answer changes what you build.** Otherwise pick the sensible default, do it,
   and say which default you picked.

Verify on the emulator before reporting (see Device / Deployment), and check upstream Frigate before
guessing (see When in doubt). Those are how a task gets *finished*, not extra credit.

## Map
```
/CLAUDE.md                            ← this file (project north star)
/ARCHITECTURE.md                      ← design + invariants
/memory/project_state.md              ← what's working, what's next (update every session)
/docs/
  TODO.md                             ← main "what's left" backlog — check this first
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
      ZoomPanState.kt                 ← pinch/pan math, shared by the RTSP + WebRTC tiles. Pan is clamped so the
                                         picture always covers the viewport; pinch anchors to the gesture centroid.
                                         Pure functions (no Compose runtime) → unit-tested in ZoomPanStateTest.kt
      ZoomIndicator.kt                ← Wyze-style zoom minimap: frame outline + visible-region fill, idle-fades
      CameraStreamState.kt            ← Skeleton/LoadingWithCache/Live/Offline sealed state shared by all tiles
      CameraEditSheet.kt              ← reorder/hide bottom sheet
    review/
      ReviewScreen.kt / ReviewViewModel.kt   ← review feed grid + filters; VM also loads the rail's motion waveform
      ReviewSeverityTimeline.kt         ← the right-hand rail: paints, grab-the-handle drag gesture, zoom buttons
      ReviewRailRenderer.kt             ← rail Canvas drawing (ruler + activity strip + bracketed handle).
                                           All geometry dp/sp-derived — never hardcode Paint.textSize in px
      ReviewTimelineLayout.kt           ← pure tick/waveform math (no Compose) → ReviewTimelineLayoutTest.kt
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
- **OkHttp 5.x / Retrofit 3.x break LAN connectivity on this app (found + reverted 2026-07-14).** Android Studio's
  library-update flow bumped OkHttp 4.12.0→5.4.0 and Retrofit 2.11.0→3.0.0. Both compiled clean, but
  `FrigateClient`'s OkHttp client then failed every request to the local Frigate server with a consistent 15s
  `SocketTimeoutException`, even though `adb shell nc` reached the same host:port instantly — the regression was
  isolated to the OkHttp/Retrofit stack itself, not device networking. Root cause in OkHttp 5/Retrofit 3 not
  further diagnosed; just reverted (see `gradle/libs.versions.toml`, pinned versions as of this commit). Don't
  bump OkHttp past 4.12.0 or Retrofit past 2.11.0 without verifying LAN (not just internet-routed HTTPS)
  connectivity on-device first.

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

## Testing
**Unit tests are JUnit 5 (Jupiter), not JUnit 4.** `app/build.gradle.kts` declares
`junit-jupiter` + `junit-jupiter-engine` and sets `tasks.withType<Test> { useJUnitPlatform() }`.
There is no `junit-vintage` engine on the classpath, so a test written against JUnit 4 **compiles
cleanly and is then silently never run** — the only symptom is
`No tests found for given includes: [...]` from a `--tests` filter, or a green build that executed
nothing. This cost a full debug cycle on 2026-07-21.

- Import `org.junit.jupiter.api.Test` and `org.junit.jupiter.api.Assertions.*` — never `org.junit.*`.
- **Argument order differs from JUnit 4:** the message is the *last* parameter, not the first.
  `assertTrue(condition, "message")`, `assertEquals(expected, actual, delta, "message")`.
- Setup/teardown are `@BeforeEach`/`@AfterEach`, not `@Before`/`@After`.
- Run a single class with the fully qualified name:
  `./gradlew :app:testDebugUnitTest --tests "net.triton.frigateviewer.feature.cameras.ZoomPanStateTest"`
- Confirm tests actually ran — a passing task is not proof. Check the count in
  `app/build/test-results/testDebugUnitTest/TEST-<fqcn>.xml` (`tests=` / `failures=`).
- Test naming follows `SafeApiCallTest.kt`: backtick-quoted sentences describing the behaviour.

Pull pure logic out of composables so it can be tested on the JVM at all — `ZoomPanState.kt` is the
pattern: the clamp/zoom math lives in top-level functions with no Android or Compose-runtime
dependency, and `ZoomPanStateTest.kt` covers it without an emulator.

## Commands
```
./gradlew :app:assembleDebug          build debug
./gradlew :app:assembleRelease        build signed release (needs keystore.properties)
./gradlew :app:testDebugUnitTest      unit tests (JUnit 5 — see Testing above)
./gradlew :app:lintDebug              Android lint
./gradlew ktlintCheck                 style check (ktlint CLI; rules in .editorconfig)
./gradlew ktlintFormat                auto-fix style violations
```

## Committing

**Don't commit or push unless the user asked in their most recent message.** Approval is per-batch
and expires once that commit lands — a previous "commit and push" doesn't cover the next batch.
Uncommitted work is a fine place to end a turn: report the diff and stop.

### Commits are GPG-signed — wait for the passphrase

Once you *have* been asked: `git commit` triggers a pinentry dialog on the user's machine. The first
attempt often returns `gpg: signing failed: Timeout` simply because nobody had typed the passphrase
yet. **That is not an error to work around.** Do not disable signing, do not pass `--no-gpg-sign`,
do not restart `gpg-agent`. Retry the same commit, tell the user to enter their passphrase, and give
them time. If it times out repeatedly they're probably away from the machine — say so and stop
retrying rather than burning minutes per attempt.

## End of session: clean up what you started

Before ending a session, shut down anything still running that this session started or woke up:

    adb -s emulator-5554 shell settings put system accelerometer_rotation 1   # restore state first
    adb -s emulator-5554 emu kill
    ./gradlew --stop                       # Gradle daemons (they idle for 3h otherwise)

**`adb emu kill` is not enough — verify.** It detaches the device (so `adb devices` looks clean)
while leaving the `emulator` host process alive, which is exactly the "stuck on but not visible"
state reported 2026-07-21: no window, no adb device, still holding GBs of RAM, only findable in Task
Manager. Always confirm, and force-kill what survives:

    Get-Process | Where-Object { $_.ProcessName -match "qemu|emulator" }
    Get-Process -Name emulator -ErrorAction SilentlyContinue | Stop-Process -Force

Restore any device state the session changed before killing it — sessions that test landscape set
`user_rotation` / `accelerometer_rotation`, and leaving auto-rotate off makes the emulator look
broken next time.

## Branches
- `master` — preserved RN fork (do not touch)
- `kotlin-rewrite` — active development trunk for the native rewrite

## When in doubt
**Check upstream Frigate before guessing, or just ask.** The source is
`https://github.com/blakeblackshear/frigate` — the web UI under `web/src/` is the reference this app
is modelled on, and raw files fetch fine (e.g.
`raw.githubusercontent.com/blakeblackshear/frigate/dev/web/src/components/player/VideoControls.tsx`).
Reading how they actually do it beats inventing a version that then has to be redone: the player
control bar was rebuilt twice because it was styled from imagination instead of from upstream's
`w-auto ... rounded-lg bg-background/60 px-4 py-2`.

Read `ARCHITECTURE.md` for the why. Read the nearest local `CLAUDE.md` for the gotchas. Read the relevant ADR in `docs/adr/` for prior decisions before reversing one.

## Documentation discipline
**Keep docs current as code changes** — don't batch at the end. After any session that ships a feature:
1. Update this `CLAUDE.md` if device info, commands, or key rules changed.
2. Update `memory/project_state.md` with what's now working and what's next.
3. If a new architectural decision was made, add an ADR in `docs/adr/`.
Do this before the context gets too long to remember what changed.

## Device / Deployment

**There is an emulator. Use it. Never report a UI change as "unverified" without trying it first.**

`emulator-5554` (Android Studio AVD name: **Pixel8-Virt**) is normally already running, is 1080x2400
like the real Pixel 8 so tap coordinates transfer directly, and is **already configured with the
live Frigate server** — real cameras, real events, real motion data. Verifying a screen there costs
about four commands.

    adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
    adb -s emulator-5554 shell am start -n "net.triton.frigateviewer.debug/net.triton.frigateviewer.MainActivity"
    adb -s emulator-5554 shell input tap <x> <y>
    adb -s emulator-5554 exec-out screencap -p > screenshots/<name>.png

Do not confuse this with the rule about the physical phone. **"Don't push builds to the phone"
applies to the phone only** — the user collects those APKs over SMB. It says nothing about the
emulator, which is the intended place to check work. Conflating the two is what caused a whole
session of shipping UI changes marked "not device-verified" when they could simply have been
verified (2026-07-21).

Always pass `-s <serial>`: the emulator and the physical Pixel 8 are often attached at once, so a
bare `adb` command is ambiguous and will fail or hit the wrong target.

### Physical device (Pixel 8, wireless ADB) — for real-hardware checks only

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
- Pinned via `org.gradle.java.home`, but **in `C:/Users/Sean/.gradle/gradle.properties`
  (`GRADLE_USER_HOME`), NOT the repo's own `gradle.properties`.** The repo file is committed and
  shared with GitHub Actions CI (`ubuntu-latest`) and any other contributor — a Windows-only path
  there breaks their builds outright (this happened for real, commit `e2bae21` removed it from the
  repo file for exactly that reason). Gradle merges `GRADLE_USER_HOME/gradle.properties` over a
  project's own `gradle.properties` (higher precedence), so the machine-local file is the correct
  place for this pin — it never reaches CI or anyone else's checkout.
- Required because Cursor IDE's embedded JRE (`.antigravity-ide`) lacks `jlink.exe`, which AGP 9.2.1 needs for the `androidJdkImage` transform. Without the pin, Gradle also resolves whatever default JDK it finds instead of the one an already-running daemon used — real symptom seen once: duplicate Gradle + Kotlin-compile daemons piling up under mismatched JDKs, several GB of RAM, until Gradle's 3-hour idle timeout finally reaped them.
- **Do NOT move this pin into the repo's `gradle.properties`** — that breaks CI. If Cursor builds start failing with a `jlink` error, check `C:/Users/Sean/.gradle/gradle.properties` still has the pin first.
- Do NOT let `gradle/gradle-daemon-jvm.properties` exist/reappear **in the repo**. Gradle 9's daemon-toolchain feature reads it
  and OVERRIDES both the user-level and project-level `org.gradle.java.home`, auto-selecting whatever JDK 21 it finds first — on this machine that's
  the Antigravity IDE's bundled JRE, which lacks `jlink.exe` and fails `:app:compileDebugJavaWithJavac` with
  `JdkImageTransform ... jlink executable ... does not exist`. If `./gradlew updateDaemonJvm` or an IDE
  regenerates this file, delete it.

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
9. **Timelines scrub by dragging the handle — never by tapping the track.** The handle must be a **real, sized composable** with its own `pointerInput` (48 dp tall, with a visible translucent band so the target is discoverable), *not* a hit-test inside the timeline's own gesture handler. This matches Frigate's `use-draggable-element`, where the handle element itself receives the drag. Sharing one gesture between "pan the window" and "is this press near the scrubber?" is what made fine adjustment impossible — a finger a few px off the line silently panned instead, worst in landscape where the rail is half as tall. Drag the handle → scrub only. Drag the track → pan only. At the ends the handle forwards leftover movement to the pan callback so a long drag can continue past the window.
10. **Never set `android.graphics.Paint.textSize` to a raw number for Canvas drawing.** Those are device pixels: `15f` is ~5.7 dp on a 420 dpi phone. Derive from `Density` (`DrawScope` is one) via `sp.toPx()`, so it also honours the system font-size setting.

## Agent skills

### Issue tracker

GitHub Issues are **disabled** on `cybersholt/frigate-viewer` (the `origin` remote) as of 2026-07-10 — `gh issue create`/`list` fail outright. Until re-enabled, `docs/TODO.md` is the working backlog; `docs/issues/` holds historical/resolved write-ups. See `docs/agents/issue-tracker.md` for the GitHub-native conventions to resume once issues come back.

### Triage labels

Default label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`) — no repo-specific overrides. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — one `CONTEXT.md` (once created) + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

