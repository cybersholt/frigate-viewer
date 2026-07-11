# QA session — 2026-07-10, real Pixel 8 (not emulator)

GitHub Issues are **disabled** on `cybersholt/frigate-viewer` (confirmed via `gh issue create` failing with
"the 'cybersholt/frigate-viewer' repository has disabled issues") — `docs/agents/issue-tracker.md` is stale on
this point. Logged here instead, per user instruction to write findings to markdown and cross-check existing
backlog first. See `memory/project_state.md` → "Known gaps / not yet done" for the canonical, evolving backlog;
this file is the detailed write-up the entries there point back to.

User was testing on a real Pixel 8 over ADB specifically to verify Wi-Fi detection (previous verification was
emulator-only). Investigated via 4 parallel `Explore` agents; no fixes applied yet, findings only.

---

## 1. [Critical] Server Edit → Test deletes the server's real saved credentials — FIXED, verified

**Fix**: `testDraftConnection()` now always mints a fresh throwaway UUID for the credential-store/`FrigateClient`
cache key instead of reusing `form.id` (which is the real server's id when editing). When editing with the
password field left blank, it clones the already-saved secret under the throwaway id (read-only against the real
id) so the test is still authenticated — matching what Save already does when the field is left blank. The
`finally` cleanup now only deletes the throwaway id, never the real one.

**Verified**: reproduced the exact original repro on the emulator — Edit → Test ("Server reachable", 878ms) →
Cancel → Edit again → Test ("Server reachable", 826ms, not a 401) → confirmed Cameras grid still loads all 4
channels live afterward, proving the real server's session was never touched.

**Not previously known** — new bug, introduced by the 2026-07-10 "server-test-on-draft" session (see
`project_state.md` line ~82), which correctly built the *Add*-server draft-test flow but has a latent bug in
the *Edit* path.

**Repro**:
1. Server > Edit (existing, working server) > **Test** → "Test OK"
2. **Cancel**
3. Edit again > **Test** → `HTTP 401: Connection failed`
4. Enter a valid password (don't save) > **Test** → still 401

**Root cause**: `testDraftConnection()` (`SettingsViewModel.kt:354-394`) builds a "draft" `Server`, tests it, then
in a `finally` block calls `credentialStore.delete(draftServer.id)` + `client.invalidate(draftServer.id)` to
clean up. For a brand-new server this is safe (`draftServer.id` is a fresh UUID). But `ServerForm(id = srv.id, ...)`
(`ServersSettingsScreen.kt:81-95`) seeds the Edit form with the **real, saved server's ID** — so the very first
Test tap's cleanup deletes the real server's password/JWT, session cookie, and pinned cert
(`CredentialStore.delete`, `CredentialStore.kt:131-138`), and invalidates its cached `OkHttpClient`. First Test
still says "OK" because it's reusing the still-valid session that gets deleted *after* reporting success; every
Test after that has no session and 401s. Typing a new password doesn't help because `testDraftConnection` only
calls `api.config()`, never `repo.login(...)` — so cookie-based auth never gets a fresh session without tapping
Save.

**Fix direction**: give Edit's draft test a throwaway ID (like Add already does) instead of reusing `srv.id`, or
skip the `credentialStore.delete`/`client.invalidate` cleanup entirely when editing an existing server.

---

## 2. [High] Cameras auto-refresh loop never stops when you leave the Cameras tab — FIXED, verified on real Pixel 8

**Root cause confirmed via logcat**: idle on Settings with "Auto-refresh snapshots" on and "Refresh interval" set
to 1s, the app fired a full events+review+recordings-unavailable+motion-activity refresh roughly once per
second, indefinitely — traced to `EventsViewModel`'s identical `while(true) { delay(interval); refresh() }` loop
(same pattern as `CamerasViewModel`, not just Cameras as originally hypothesized from code alone).

**Fix**: both `CamerasViewModel` and `EventsViewModel` gained a `screenVisible` flag + `setScreenVisible(Boolean)`,
gating `startOrStopAutoRefresh()`'s loop. `CamerasScreen`/`EventsScreen` each report visibility via a top-level
`DisposableEffect(Unit) { vm.setScreenVisible(true); onDispose { vm.setScreenVisible(false) } }` — since
Navigation-Compose actually removes the screen from composition on tab switch (only the `ViewModelStore` survives
via `saveState`/`restoreState`), this correctly starts/stops the loop exactly when the tab becomes visible/hidden.

**Verified on real Pixel 8** (with "Auto-refresh snapshots" ON, 1s interval — the aggressive config that produced
the original bug report): idle on Settings for 8-10s → 0 OkHttp requests (previously ~30+ per 10s). Round-tripped
Cameras tab (65 requests/6s, correct) → Settings (0/6s) → Events tab (19 requests/6s, correct) → Settings (0/8s).
Auto-refresh still works normally while its own tab is visible; only the background leak is fixed.

**Found and reverted along the way**: an uncommitted, broken, unrelated one-line change in `SettingsControls.kt`
(`Modifier.align(Alignment.CenterVertically)` on a `Text` inside a plain `Column`, not valid there) was blocking
compilation. Not from this session — matches the "concurrent tool leaves broken edits" pattern already flagged in
`project_state.md`'s "Watch out for" section. Reverted via `git checkout` to get a clean compile; flag if this
resurfaces.

**New finding, not fixed — real ANR on the Events screen**: while verifying this fix, hit a reproducible
"Frigate Viewer isn't responding" ANR (`Input dispatching timed out ... Waited 5006ms for MotionEvent`) navigating
into the Events screen, twice independently (once on the old build, once on the newly-installed fixed build —
ruling out this session's changes as the cause). The ANR trace showed heavy *system-wide* CPU contention at the
time (system_server 43%, another app at 32%, kernel swap activity at 22%), which is a plausible confound from the
rapid ADB automation used for this verification pass rather than conclusively an app bug — but logged as a new
backlog item since it reproduced twice. See `docs/issues/backlog-2026-07-10.md#22`.

---

## 3. [High] Local-network Wi-Fi detection still not taking effect — ROOT CAUSE FOUND, working as designed

**Root cause: the user simply hadn't granted the location permission in the running app.** Confirmed via
`adb shell dumpsys package net.triton.frigateviewer.debug | grep ACCESS_FINE_LOCATION` — both
`ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` showed `granted=false`, even though the phone was genuinely
connected to the saved SSID ("cybers", confirmed via `adb shell dumpsys wifi`). Walked through the actual UI on
the real Pixel 8: Settings > Servers > Edit > Local Network correctly showed "Grant location access" (not stuck
or broken), tapping it correctly triggered the real Android system permission dialog, and after picking
"While using the app," the permission grant was confirmed (`granted=true`) and the UI immediately updated to show
"Current Wi-Fi: cybers" matching the already-saved SSID entry — no restart needed. **This confirms the
2026-07-10 permission-dead-end fix genuinely works correctly on real hardware** — it was just never exercised
because the permission was never granted through it before now.

**Verified end-to-end after granting**: via live logcat, both the Cameras grid (`Constructing Grid RTSP URL:
rtsp://192.168.88.26:8954/ch1 (SSID: cybers)`, snapshot fetches against `http://192.168.88.26:5005/api/...`) and
the Events screen (event list + snapshot thumbnails against the same local URL) immediately started using the
local server URL instead of `https://frigate-app.cybersholt.com`. Live camera feeds share the same
`Server.effectiveBaseUrl()`/`FrigateClient` path already confirmed working for both screens, so this is
considered resolved across Cameras, Events, and live feeds as the user requested.

**No code changes were needed for this one** — it was a one-time device/permission-state issue, not a bug.

<details>
<summary>Original investigation notes (superseded by the above)</summary>

**This re-opens a backlog item marked "fixed 2026-07-10"** (`project_state.md` "Backlog" #4 and "Future ideas"
session note "server-test-on-draft + Wi-Fi permission dead-end fix"). That session found and fixed a real bug —
the only UI path to request `ACCESS_FINE_LOCATION` was dead-ended behind `currentSsid != null`, which could never
become true without the permission already granted — and added a `WifiMonitor.refresh()` workaround for stale
`NetworkCallback` data after granting. **Verified on the emulator only.** This user's current Pixel-8 testing
session is exactly the real-device re-verification that was flagged as outstanding, and per their report, it's
still not working: added their home SSID under a server's Local Network settings, connected to that Wi-Fi, but
logcat shows no sign of the app switching to the local URL or gating RTSP for LAN-only use.

**Confirmed this mechanism does exist in code and is wired up** (not dead/unused):
- `Server.kt:52-57` `effectiveBaseUrl(currentSsid)` — swaps in `localNetworkUrl` when the current SSID is in
  `localNetworkSsids`. Called from `CamerasViewModel.kt:166-173`, `EventsViewModel.kt:116,397`,
  `FrigateClient.kt:102`.
- `CamerasScreen.kt:736-742`/`1036-1042` — off-LAN RTSP→WebRTC downgrade when `rtspHost` is set but the current
  SSID isn't in `localNetworkSsids`.

**Needs on real Pixel 8** (not yet done this session — investigation only so far):
1. Confirm `ACCESS_FINE_LOCATION` is actually granted (`adb shell dumpsys package net.triton.frigateviewer.debug
   | grep ACCESS_FINE_LOCATION`) — the exact permission that was the root cause last time.
2. Confirm the Settings > Servers > Local Network section is showing "Current Wi-Fi: <ssid>" and not still stuck
   on "Grant location access" or "Not currently connected to Wi-Fi."
3. Confirm the saved SSID string matches exactly (case, no stray quotes) what `WifiMonitor` reports live on this
   device — `WifiMonitor.kt:20-115` strips quotes and filters `"<unknown ssid>"`, but hasn't been checked against
   real Pixel 8 SSID formatting specifically.
4. If permission is granted and SSID matches but it's still not switching, check whether `WifiMonitor.refresh()`'s
   register/unregister workaround (added for the emulator) actually behaves the same on real hardware — this was
   flagged as unverified on real hardware in the fix's own notes.

</details>

---

## 4. [Medium] Server edit sheet silently discards unsaved changes (incl. Local Network SSIDs) — FIXED, verified

**Fix**: `ServerFormSheet` now compares `current` (the in-progress form) against the original `form` param it was
opened with. Cancel, tap-outside, swipe-down, and back gesture all funnel through `ModalBottomSheet`'s
`onDismissRequest`, which now goes through a new `attemptDismiss` lambda: if `current != form`, show an
`AlertDialog` ("Discard changes?" / "Keep editing" / "Discard") instead of dismissing immediately; only tapping
"Discard" actually closes the sheet and drops the edit. No changes → dismisses immediately as before.

**Verified on emulator**: Servers > Edit "Home" > appended a character to Name > Cancel → "Discard changes?"
dialog appeared → "Keep editing" → returned to the form with the edit still present → Cancel again → "Discard" →
sheet closed, server list still shows the original unmodified name ("Home 🏡", not "Home 🏡X") — confirming the
edit was genuinely discarded, not silently saved or left ambiguous.

<details>
<summary>Original findings (superseded by the fix above)</summary>

**Repro**: Server > edit > Local Network > remove an SSID > add it back > dismiss the sheet via Cancel/tap-outside
/swipe-down (not Save) > reopen > list looks unchanged, no way to tell if the edit was ever applied or discarded.

**Root cause**: `ServerFormSheet`'s SSID add/remove (`ServersSettingsScreen.kt:354-358, 374-382`) only mutates a
local `remember { mutableStateOf(form) }` (`ServersSettingsScreen.kt:216`) — nothing persists until Save
(`ServersSettingsScreen.kt:255` → `onSave` → `SettingsViewModel.saveServer` → `serverRepo.upsert`). Not a data bug
(remove-then-re-add nets out to the same list either way) — the actual problem is no save/discard confirmation,
so a genuine "didn't stick" report is indistinguishable from "I forgot to tap Save."

**Fix direction**: either a confirmation prompt on non-Save dismissal with unsaved changes, or a visible "unsaved
changes" indicator.

</details>

---

## 5. [Medium, by-design-but-confusing] Fullscreen stream-protocol picker overrides the grid permanently

**Repro**: change ch1's protocol from inside fullscreen live view (e.g. to WebRTC) > back to grid > ch1 now also
shows WebRTC there > picking "Snapshot" from the grid doesn't cleanly reset it, only "Use default" does.

**This is working as designed, not a stray bug** — confirmed via `project_state.md`'s "Working" section
("Per-camera feed-mode override... persisted in DataStore... Falls back to the global default when unset") and
inline code comments (`CamerasScreen.kt:935-936, 1241-1245`). The fullscreen protocol pill (`StreamTypeBadge`) and
the grid tile's per-camera override menu are the *same* component writing to the *same* persisted
`cameraStreamOverrides` map, keyed only by camera name (`CamerasViewModel.kt:294-302`,
`UserSettingsRepository.kt:89-98,176-191`) — there's no separate "fullscreen session only" scope.

The "picking Snapshot explicitly didn't fix it" detail is a side effect of #6/#7 below, not this: explicitly
choosing a protocol just writes another override entry rather than clearing one, so if the RTSP tile was already
stuck (see below), re-picking the same protocol doesn't force a fresh reconnect — only clearing the override
(picking "Use default") does.

**Worth a product decision, not just a fix**: should the fullscreen picker stay wired to the persistent
per-camera setting (current, intentional behavior), or become session/local-only so a fullscreen experiment
doesn't silently change the grid's default?

---

## 6. [High] RTSP tile can show "Live view unavailable" while video keeps playing behind it — FIXED, not live-repro'd

**Fix applied** (follow-up session): both documented root causes below addressed directly.
1. `RtspLiveTile.kt` — added a `playerRef` holder (declared before `handleFailure`, which itself is declared
   before the `ExoPlayer` exists since the player's own error listener needs to call it) so `handleFailure()` now
   calls `playerRef?.stop()` before flipping `liveState` to `Reconnecting`/`Error`. Previously a declared
   timeout/error never touched the actual player — it could keep buffering and silently resume rendering frames
   after the UI had already declared it dead.
2. `StreamOverlay.kt` — the `Error` state's background changed from `Color.Black.copy(alpha = 0.55f)` to fully
   opaque `Color.Black`, so even if a stray frame did render, it can't show through the "Live view unavailable"
   card.

**Not reproduced live this session** — the original repro needed a specific unhealthy camera on the real Pixel 8
(logcat captures below); the test server's cameras are all healthy, so the failure path itself couldn't be
forced. Smoke-tested instead: normal RTSP fullscreen playback (ch1, live, unmuted toggle, badge) still works with
no regression after the change. `./gradlew :app:compileDebugKotlin` clean.

<details>
<summary>Original findings (superseded by the fix above)</summary>

Logcat: `pixel 8 ch1 playing then unavai while still playing.logcat`, `ch1 live fail 2.logcat`.

**Root cause, two compounding issues in `RtspLiveTile.kt`**:
1. The 20s connection timeout watchdog (`RtspLiveTile.kt:139-158`) only flips the Compose `liveState` to
   `Error` via `handleFailure(StreamError.TIMEOUT)` (`RtspLiveTile.kt:121-135`) — it never calls
   `pause()`/`stop()`/`release()` on the actual `ExoPlayer`. If the underlying player recovers and starts
   rendering frames after the UI already declared it dead, those frames keep showing.
2. The error overlay is translucent by design (`StreamOverlay.kt:100-101`, `Color.Black.copy(alpha = 0.55f)`),
   and the `PlayerView` is always in the composition tree underneath (`RtspLiveTile.kt:302-304`'s own comment) —
   so a still-buffering feed plays visibly through the dim scrim.

**Fix direction**: have the timeout/error path actually pause or release the player, not just flip UI state; or
make the "unavailable" overlay opaque so a disagreement between player state and UI state isn't visible.

</details>

---

## 7. [Medium] Retry on a failed RTSP tile doesn't reliably restart playback

Same repro/logcat as #6 — tapping Retry re-buffers and fails again the same way.

**Root cause**: `onRetry` (`RtspLiveTile.kt:328-331`) bumps `retryTrigger`, and `remember(url, retryTrigger)`
(`RtspLiveTile.kt:160-211`) does correctly build a new `ExoPlayer` and release the old one
(`RtspLiveTile.kt:278-283`) — that part matches what the "Stream connection state machine (Task B)" session
intended (`project_state.md` line ~294, "Manual Retry always resets... fully tears down the previous... ExoPlayer
before recreating"). The bug is one layer up: the `AndroidView` hosting `PlayerView`
(`RtspLiveTile.kt:305-321`) only assigns `player = exoPlayer` inside `factory`, which Compose only runs once on
first creation — there's no `update` block, so the visible `PlayerView` never gets rebound to the new `ExoPlayer`
instance on retry. The old player is destroyed, a new one starts fetching independently, but the screen is still
looking at whichever player it was first pointed at.

**Fix direction**: add an `update = { it.player = exoPlayer }` block (or equivalent) to the `AndroidView` so retry
actually rebinds the view to the new player instance.

---

## 8. [Low, feature request] Stream stats/info option in the live playback 3-dot menu — FIXED, verified

**Implementation**: added a "Stream info" item to the fullscreen overflow menu (`FullscreenChrome` in
`CamerasScreen.kt`), next to "Picture-in-picture". Opens an `AlertDialog` showing Camera, Protocol (RTSP/WebRTC/
Snapshot), Resolution (HD main / SD sub, when the camera has a sub stream), Status, and Recording
(enabled/disabled). Status is genuinely live — threaded the existing `LiveStreamState` (Idle/Connecting/
Negotiating/Buffering/Playing/Reconnecting/Error, already tracked internally by `StreamContent` for the
badge/overlay) up through a new `onStreamStateChanged` callback → `FocusedTile` → `FullscreenChrome`, rather than
inventing new state.

**Scope note**: deliberately coarse (connection lifecycle: Playing/Buffering/Reconnecting/Error), not
frame-level bitrate/buffer-depth/pixel-resolution telemetry — that would need new plumbing into ExoPlayer's
`Format`/track listeners and WebRTC's `getStats()` API, a bigger lift than this "Low priority, not investigated"
item justified. Flagged in a code comment for a future pass if real bitrate stats are wanted.

**Verified on emulator**: ch1 fullscreen → overflow menu → "Stream info" → dialog shows Camera=ch1,
Protocol=RTSP, Resolution=HD (main stream), Status=Playing, Recording=Enabled — all fields correct against the
actual live state. Close button dismisses cleanly. `./gradlew :app:compileDebugKotlin` clean.

---

## Not filed — needs discussion first, not a bug report

**"The bubble"** — user flagged this as unclear and possibly worth switching to snapshots instead of video, but
wanted to discuss before anything gets filed. Investigated: there is no floating/PiP video overlay of any kind
anywhere in this codebase. The only thing matching "bubble" is `PreviewThumbnailBubble.kt`
(`feature/events/PreviewThumbnailBubble.kt:26-45`) — the small 120dp still-frame thumbnail that follows your
finger while scrubbing the Events/Event-Detail timeline (built in the 2026-07-10 "Timeline preview-frame
thumbnail scrubbing" session, see `project_state.md`). It already shows a locally-extracted still frame, not a
live video stream — so "switch it to snapshots instead of video" may already be exactly what it does; worth
confirming with the user what behavior they actually saw before assuming there's a live-video bubble somewhere
that isn't in the code.
