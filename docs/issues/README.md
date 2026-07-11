# Issue tracker (local markdown)

GitHub Issues are disabled on `cybersholt/frigate-viewer` as of 2026-07-10 (see `docs/agents/issue-tracker.md`).
Until re-enabled, issues live here as markdown instead. `memory/project_state.md` → "Known gaps / not yet done"
is the canonical backlog pointer for future sessions; this index is the working list.

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

## Bugs

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| 5 | Fullscreen protocol picker shares state with the grid (by-design, needs a decision) | Medium | 🔴 | `qa/2026-07-10-pixel8-qa-notes.md#5` |

## Verification / audit

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| 10 | Real-device verification checklist (preview-frame scrub, PiP auto-home-press, live-401 recovery, fullscreen-back narrow path) | Medium | 🔴 | `issues/backlog-2026-07-10.md#10` |

## Tech debt

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| 12 | `TrustConfig.applyAllowUntrusted()` violates the repo's own "no trust-all-certs" rule | High | 🔴 | `issues/backlog-2026-07-10.md#12` |
| — | Cleartext traffic still enabled app-wide (`usesCleartextTraffic="true"`) — Task B leftover, found during #9 audit | Medium | 🔴 | `issues/backlog-2026-07-10.md#9` |

## Features / enhancements

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| 15 | Export/import settings | Medium | 🔴 | `issues/backlog-2026-07-10.md#15` |
| 16 | Recent-events side panel next to live view | Low | 🔴 | `issues/backlog-2026-07-10.md#16` |
| 17 | Timeline-strip side panel next to live view | Low | 🔴 | `issues/backlog-2026-07-10.md#17` |
| 19 | Notifications and Downloads settings are placeholders — needs product scoping | Medium | 🔴 | `issues/backlog-2026-07-10.md#19` |
| 20 | Recording indicator dot should reflect active-event state (superseded by fixed #25) | Low | 🔴 | `issues/backlog-2026-07-10.md#20` |

## Chores

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| 18 | Settings visual redesign — extend to remaining sub-pages | Low | 🔴 | `issues/backlog-2026-07-10.md#18` |
| 21 | Full testTag sweep | Low | 🔴 | `issues/backlog-2026-07-10.md#21` |

## Not filed — needs discussion, not a tracked issue

- **"The bubble"** — see `qa/2026-07-10-pixel8-qa-notes.md` bottom section. Confirmed no floating/live-video
  overlay exists anywhere in the codebase; the only match is the timeline-scrub still-frame thumbnail. Needs a
  conversation with the user before this becomes an issue.

## Conventions

- New issues get appended to `backlog-<date>.md` (or a fresh dated file for a new investigation session, like
  `qa/2026-07-10-pixel8-qa-notes.md`), numbered continuing from the highest existing number across all files in
  this directory.
- Update this table's Status column as work happens. Once an issue is done and folded into `project_state.md`'s
  session notes, remove its row here rather than marking it ✅ indefinitely — this index should only reflect
  what's still open or actively in progress.
