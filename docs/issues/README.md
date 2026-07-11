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
alignment) fixed and verified. #25 (recording-enabled red blink) fixed and compiles clean, **not yet verified
against a real `record.enabled: true` camera** — flagged for a follow-up check. #7, #23, #24, #25, #26, #27, #29
removed from the table below. #28 (Developer Options + crash handler) is unstarted — see Features below.

## Bugs

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| 4 | Server edit sheet silently discards unsaved changes | Medium | 🔴 | `qa/2026-07-10-pixel8-qa-notes.md#4` |
| 5 | Fullscreen protocol picker shares state with the grid (by-design, needs a decision) | Medium | 🔴 | `qa/2026-07-10-pixel8-qa-notes.md#5` |
| 6 | RTSP tile can show "Live view unavailable" while video plays behind it | High | 🔴 | `qa/2026-07-10-pixel8-qa-notes.md#6` |
| 11 | Event card shows a nonsensical duration ("170:44") | Low/Medium | 🔴 | `issues/backlog-2026-07-10.md#11` |

## Verification / audit

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| 9 | Task C (per-context stream policy) code is unverified — audit before building on it | High | 🔴 | `issues/backlog-2026-07-10.md#9` |
| 10 | Real-device verification checklist (preview-frame scrub, PiP auto-home-press, live-401 recovery, fullscreen-back narrow path) | Medium | 🔴 | `issues/backlog-2026-07-10.md#10` |

## Tech debt

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| 12 | `TrustConfig.applyAllowUntrusted()` violates the repo's own "no trust-all-certs" rule | High | 🔴 | `issues/backlog-2026-07-10.md#12` |
| 14 | Possibly-dead / half-wired code from Task B and elsewhere | Low | 🔴 | `issues/backlog-2026-07-10.md#14` |

## Features / enhancements

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| 8 | Add stream stats/info to the live playback 3-dot menu | Low | 🔴 | `qa/2026-07-10-pixel8-qa-notes.md#8` |
| 15 | Export/import settings | Medium | 🔴 | `issues/backlog-2026-07-10.md#15` |
| 16 | Recent-events side panel next to live view | Low | 🔴 | `issues/backlog-2026-07-10.md#16` |
| 17 | Timeline-strip side panel next to live view | Low | 🔴 | `issues/backlog-2026-07-10.md#17` |
| 19 | Notifications and Downloads settings are placeholders — needs product scoping | Medium | 🔴 | `issues/backlog-2026-07-10.md#19` |
| 20 | Recording indicator dot should reflect active-event state (superseded by fixed #25) | Low | 🔴 | `issues/backlog-2026-07-10.md#20` |
| 28 | Add Developer Options page (Trigger test crash) + startup crash handler | Medium | 🔴 | `qa/2026-07-11-app-notes-2.md#28` |

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
