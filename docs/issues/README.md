# Issue tracker (local markdown)

**See `docs/TODO.md` first.** As of 2026-07-14 that file is the main "what's still left" document —
this index and the dated `backlog-*.md` files below are now historical/traceability records of
already-resolved issues, not the place to look for or add open work.

GitHub Issues are disabled on `cybersholt/frigate-viewer` as of 2026-07-10 (see `docs/agents/issue-tracker.md`).
Until re-enabled, issues live here as markdown instead.

Status legend: 🔴 open, 🟡 in progress, ✅ done (kept here briefly for traceability, then folded into
`project_state.md` and removed from this table).

**Resolved 2026-07-10/11 (Wi-Fi/ANR/traffic follow-up)**: #2, #3, #22, #13, #1 — see git history for detail if
needed.

**Resolved 2026-07-11 (App Notes 2 batch)**: #23/#24 (Settings row alignment + palette label) fixed and mostly
verified. #26/#27 turned out to be the *same* bug as backlog #7 (RTSP `AndroidView` never rebinding `PlayerView`
to a new `ExoPlayer`) — one `update` block fix closed all three at once, verified live (camera switch now shows
the right feed, back gesture cleanly exits fullscreen to portrait). #29 (status bar collision + LIVE/RTSP
alignment) fixed and verified. #25 (recording-enabled red blink) fixed and verified on emulator (test server has
`record.enabled: true` on every camera). #28 (Developer Options page + startup crash handler) built and verified
end-to-end on emulator (trigger crash → cold relaunch → crash-report screen → dismiss → normal nav, shown only
once). #6 (RTSP tile shows "unavailable" while video plays behind it) fixed at the code level (player is now
stopped when a failure is declared, error overlay made opaque) but not live-repro'd — the original failure needs
a genuinely unhealthy camera, which the test server doesn't have. #4 (server edit sheet silently discards
unsaved changes) fixed and verified — Cancel/back/tap-outside with unsaved changes now shows a "Discard
changes?" confirmation. #11 (event card "170:44" duration bug) fixed — minutes now roll into hours. #9 (Task C
per-context stream policy audit) came back clean, all five settings genuinely wired, safe to build on (one
unrelated leftover — app-wide cleartext traffic — found and tracked separately under Tech debt). #14
(possibly-dead code sweep) resolved: 4 genuinely dead items deleted (`CameraStreamState.Offline.reason`,
`snapshotCachedAt`, `SnapshotLiveTile`'s unused `showLastImageWhileLoading` param, `WifiMonitor.isEmulator()`,
`ApiResult.getOrNull()`, `FrigateClient.activeServerFlow`/`setActive`), 1 left as documented-intentional
scaffolding (`EventsViewModel.delete`/`toggleRetain`). #4, #6, #7, #9, #11, #14, #23, #24, #25, #26, #27, #28,
#29 removed from the table below.

**Resolved 2026-07-11 (audio-focus fix, #5, #10):** WebRTC audio-ducking bug fixed (explicit `AudioAttributes.
USAGE_MEDIA` instead of WebRTC's default `USAGE_VOICE_COMMUNICATION`), user-confirmed on the real Pixel 8. **#5**
resolved per product decision — fullscreen protocol picks are now session-only, never persist to the grid's
per-camera override; found + fixed a related stuck-landscape bug along the way (`isFullScreen` ownership hoisted
from the swappable Rtsp/WebRtc tiles up to `StreamContent`). **#10** mostly resolved — 3 of 4 checklist items
verified live (preview-frame scrub, PiP auto-trigger on Home-press, fullscreen back-handling narrow path); the
4th (`TokenRefreshAuthenticator` live-401 recovery) is left open as a documented, structurally-blocked gap — the
test server's JWT auth is stateless (confirmed via safe curl checks), so forcing a real 401 requires either
waiting out a real expiry or risky on-device credential tampering, neither attempted. #5 and #10 removed from
the tables below; the 401-recovery detail lives in `project_state.md`'s 2026-07-11 session notes if picked back
up later.

**Resolved 2026-07-11 (#12, tech debt):** Deleted `TrustConfig.applyAllowUntrusted()` and `Server.allowUntrusted`
entirely (the real trust-all-certs hole). Built the previously-missing pinned-cert import UI in
`ServersSettingsScreen.kt` ("Pinned certificate" — Import/Replace/Remove), wired to the `CredentialStore
.setPinnedCert`/`FrigateClient.applyPinnedCertificate` plumbing that already existed but had zero callers.
Verified live on the Pixel 8: reject-invalid-file, import-valid-cert, and remove all work correctly. The
cleartext-traffic item (below) was reviewed in the same discussion and intentionally left as-is — it's a
deliberate, already-documented trade-off (`network_security_config.xml`'s own comment explains it enables the
real per-server "Use SSL" HTTP option for local Frigate instances), not a forgotten leftover; downgraded from
"needs a fix" to "accepted, no action needed" rather than removed from the table, since it's still worth a human
glance if requirements change. #12 removed from the table below.

**Resolved 2026-07-11 (#20):** Audited first — turned out **not** superseded by #25 (that's a static config flag,
this is a live "event happening now" signal) and not actually data-blocked either (`FrigateEvent.endTime` was
already nullable; just needed a poll instead of a one-shot fetch). Built per the user's spec: `LiveIndicatorDot`
gained a solid-blue state for "an event is open right now," scoped to the fullscreen-focused camera only (15s
poll, started/stopped via `CamerasViewModel.setFocusedCamera`). Verified live on the emulator (poll starts/stops
correctly, correct interval, no crashes); Pixel 8 testing deferred per user request. A `TODO(#20 follow-up)`
comment marks where a Developer Options polling-rate control should hook in once one exists. #20 removed from
the table below.

**Resolved 2026-07-11 (#16, #17):** Built together — one new side panel (`LiveEventsPanel.kt`), shown next to
the live view only in fullscreen landscape (matches the reference wishlist screenshots, `todo_005/006.png`),
with a Material3 segmented-button toggle between List mode (#16 — a scrollable recent-events list with
thumbnail/label/time/duration, plus a label filter) and Timeline mode (#17 — a compact vertical activity
timeline, reusing `feature/events/TimelineRenderer.kt`'s internal `drawActivityTimeline` so it never drifts from
the Events screen's own timeline). Per explicit user direction, doesn't recreate the mockup pixel-for-pixel —
built with this app's own Material3 components instead. Verified live on the emulator: real events with
thumbnails/bounding boxes in List mode, real activity waveform + scrubber in Timeline mode, label filter, and
"View all events" navigating to the Events tab filtered to the camera (reusing the same `onNavigateToEvents`
callback the grid tiles' existing swipe-left panel already uses). #16 and #17 removed from the tables below.

**Resolved 2026-07-11 (#15, scoped down):** Scoped to app preferences only, per explicit product decision —
servers and credentials are never included (separate system, separate trust boundary). New "Backup & Restore"
settings page (`BackupSettingsScreen.kt`) exports appearance/camera-grid/streaming/events preferences to a
user-chosen JSON file (SAF `CreateDocument`) and imports them back (SAF `OpenDocument`), via
`UserSettingsRepository.exportPreferencesJson()`/`importPreferencesJson()` (a versioned `EXPORTABLE_KEYS`
manifest, deliberately excluding `KEY_LAST_KNOWN_CAMERA_NAMES`). Verified full round-trip live on the emulator:
exported, hand-edited `camera_grid_columns_v1` from 1→3 in the exported file, imported it back, and confirmed
the Cameras View settings page now shows "Grid columns: 3x" — proving the import genuinely writes values, not
just shows a success toast. #15 removed from the table below.

**Resolved 2026-07-11 (#18):** Extended the Appearance/Streaming/Events card-per-row + section-header redesign to
the four remaining real sub-pages — About, Advanced, Device Capabilities, Servers — using the same shared
`SettingsSectionHeader`/`SettingsCard`/`SwitchSetting`/`ActionSetting` components. `SystemStatsView.kt`'s stat
cards and section labels were also restyled to match. Notifications/Downloads intentionally skipped (still
placeholders, see #19); Servers' `ServerFormSheet` modal intentionally left alone (different UI surface, not a
page-level list). Verified live on the emulator across all four pages, no regressions. #18 removed from the
table below.

## Tech debt

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| — | Cleartext traffic enabled app-wide (`usesCleartextTraffic="true"`) — accepted, documented trade-off (enables per-server plain-HTTP for local Frigate instances; not revertable without dropping that feature) | — | ✅ accepted | `issues/backlog-2026-07-10.md#9` |

**Dropped 2026-07-11 (#21):** Full testTag sweep. The original motivation was letting AI-driven verification find
elements by tag instead of screenshot-and-tap-coordinate guessing, not "for an eventual instrumented-test suite."
That need never actually materialized — every session's live-device/emulator verification, including this one,
has worked fine with plain screenshots + computed tap coordinates. User's call: not needed, don't keep it on the
list. #21 removed from the table below (see `issues/backlog-2026-07-10.md#21` for the full note if reconsidered).

**Resolved 2026-07-11 (#19):** Notifications gets the full set — master toggle, per-camera filter, label/zone
filters (both free-text, no finite known list), quiet hours (with a hand-rolled `TimePicker`-in-`AlertDialog`,
since Material3 has no ready-made `TimePickerDialog`). All wired live into `MqttForegroundService`'s
notification-posting path. Downloads turned out to need nothing new — clip export already existed
(`EventDetail.kt`'s "Export clip" via `DownloadManager`, just never documented) — so its settings page is now a
short explanatory card instead of a placeholder. Verified live on the emulator: toggle persistence, quiet-hours
time picker, camera multi-select sheet (populated with real known cameras), and label tag-add all round-tripped
correctly. #19 removed from the table below.

## Features / enhancements

_None open._

## Conventions

- New issues get appended to `backlog-<date>.md` (or a fresh dated file for a new investigation session, like
  `qa/2026-07-10-pixel8-qa-notes.md`), numbered continuing from the highest existing number across all files in
  this directory.
- Update this table's Status column as work happens. Once an issue is done and folded into `project_state.md`'s
  session notes, remove its row here rather than marking it ✅ indefinitely — this index should only reflect
  what's still open or actively in progress.
