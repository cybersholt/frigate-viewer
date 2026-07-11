# Issue tracker (local markdown)

GitHub Issues are disabled on `cybersholt/frigate-viewer` as of 2026-07-10 (see `docs/agents/issue-tracker.md`).
Until re-enabled, issues live here as markdown instead. `memory/project_state.md` → "Known gaps / not yet done"
is the canonical backlog pointer for future sessions; this index is the working list.

Status legend: 🔴 open, 🟡 in progress, ✅ done (kept here briefly for traceability, then folded into
`project_state.md` and removed from this table).

**Resolved this session (2026-07-10/11, follow-up)**: #2 (auto-refresh traffic loop) fixed in code and verified
on the real Pixel 8. #3 (Wi-Fi local-network detection) turned out not to be a code bug at all — the location
permission had simply never been granted in the running app; granting it fixed it immediately with no code
changes, confirmed working across Cameras, Events, and live feeds. #22 (Events-screen ANR, found during that
verification) turned out to be a real, 2-day-old latent bug — a `runBlocking` in `SessionCookieJar` reachable from
the main thread via `FrigateClient.hasSessionCookie()` — root-caused via a live `adb bugreport` stack trace, fixed
with a `withContext(Dispatchers.IO)`, and verified with 3 clean cold-start reproductions. This also closed #13
(the matching tech-debt entry). Full writeup: `issues/backlog-2026-07-10.md#22`. #3, #13, #22 removed from the
table below since there's nothing left to track.

## Bugs

| # | Title | Priority | Status | File |
|---|-------|----------|--------|------|
| 1 | Server Edit → Test deletes the server's real saved credentials | Critical | 🔴 | `qa/2026-07-10-pixel8-qa-notes.md#1` |
| 2 | Cameras/Events auto-refresh loop never stops when you leave the tab | High | ✅ fixed & verified (Pixel 8) | `qa/2026-07-10-pixel8-qa-notes.md#2` |
| 4 | Server edit sheet silently discards unsaved changes | Medium | 🔴 | `qa/2026-07-10-pixel8-qa-notes.md#4` |
| 5 | Fullscreen protocol picker shares state with the grid (by-design, needs a decision) | Medium | 🔴 | `qa/2026-07-10-pixel8-qa-notes.md#5` |
| 6 | RTSP tile can show "Live view unavailable" while video plays behind it | High | 🔴 | `qa/2026-07-10-pixel8-qa-notes.md#6` |
| 7 | Retry on a failed RTSP tile doesn't reliably restart playback | Medium | 🔴 | `qa/2026-07-10-pixel8-qa-notes.md#7` |
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
| 20 | Recording indicator dot should reflect active-event state | Low | 🔴 | `issues/backlog-2026-07-10.md#20` |

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
