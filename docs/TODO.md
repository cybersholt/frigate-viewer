# TODO — what's left (main working document)

Last updated: 2026-07-14. **This is the single place to check "what's left"** — it supersedes
`docs/issues/README.md`'s per-issue table as the primary index. That file (plus
`backlog-2026-07-10.md`/`backlog-2026-07-13.md`) still holds historical, mostly-resolved
issue write-ups for traceability; `memory/project_state.md` is the full session-by-session log
(root causes, verification detail, why a decision was made). Add new items here, not there.

---

## Recently resolved

- **`org.gradle.java.home` pin moved out of the repo's `gradle.properties`, into a machine-local
  file.** Commit `e2bae21` (*"chore: remove hardcoded java home path from gradle properties"*,
  2026-07-14, not from a Claude session) removed the Windows-only JBR path from the repo's
  `gradle.properties` — correctly, since that file is committed and shared with GitHub Actions
  CI (`ubuntu-latest`, see `.github/workflows/android-ci.yml`) and any other contributor; a
  Windows path there would break every one of them. But removing it also broke the reason the
  pin existed locally (Cursor's embedded JRE lacks `jlink.exe`, needed by AGP 9.2.1), and caused
  a real, measured problem: without any pin, Gradle resolved a different default JDK (Eclipse
  Adoptium 17) than the one already running a lingering daemon (Android Studio's JBR), so the two
  couldn't share a daemon — Gradle spun up a second one under the new JDK, plus its own Kotlin
  compile daemon, and Gradle's default 3-hour idle timeout let all of it sit in memory (~1.6–2GB
  each, `-Xmx4096m` ceiling). Confirmed via `Get-CimInstance Win32_Process -Filter "Name='java.exe'"`.
  **Fix**: the JBR pin now lives in `C:/Users/Sean/.gradle/gradle.properties` (`GRADLE_USER_HOME`,
  outside any repo — Gradle merges it over the project's own `gradle.properties`, with higher
  precedence), so it applies only on this machine and never reaches CI or another contributor's
  checkout. The repo's `gradle.properties` stays exactly as `e2bae21` left it. If RAM creep from
  stray `java.exe` daemons shows up again: check `~/.gradle/gradle.properties` still has the pin,
  and check whether `gradle/gradle-daemon-jvm.properties` has reappeared in the repo (Gradle 9's
  daemon-toolchain feature reads that file and silently overrides both pins if it exists — delete
  it, don't try to reconcile it).

---

## Bugs (open)

- **Review severity rail still needs a live-device check.** Reworked in `dced08d` to match
  Frigate's real `EventReviewTimeline` (dot markers, correct theme colors, 2h default zoom with
  minor ruler ticks), but a mid-session screenshot showed only two gridline labels near the
  bottom of the rail instead of one at every interval — the draw math checks out by hand-trace
  against Explore's identical, working `drawActivityTimeline`, so code review alone didn't find
  the cause. Confirm on a real device/emulator before trusting it renders densely throughout.
- **Snapshot-only cameras don't fit the event-playback model.** A snapshot-only camera's live
  tile works fine, but event review assumes a clip/VOD exists. Look at how Frigate's own web UI
  handles this before building anything — may turn out to be a "what does done even mean"
  question, and may tie into the explicit live/snapshot toggle idea below.
- **Motion indicator dot is inconsistent across screens.** The camera grid tile's status dot
  cycles green (idle) → red (recording), but never turns blue for "motion happening now" — even
  though blue already means motion/active-event elsewhere in the app (the fullscreen
  focused-tile `LiveIndicatorDot`). The two need to share one source of state.
- **Event playback player chrome is off-spec.** Controls are misaligned (reproduced on the
  bird-feeder camera) and the skin reads as a generic/legacy player, not this app's Material 3
  language. Cosmetic, but it's the second-most-used screen after the grid.
- **HA notification → event detail deep link doesn't work end-to-end.** The receiving half
  already exists: `AndroidManifest.xml` declares the `frigateviewer://` intent filter and
  `MainActivity.resolveDeepLink()` parses `frigateviewer://event?id=<id>&camera=<cam>` (plus an
  extras fallback for HA's `intent://` wrapping). What's missing is the Home Assistant–side
  companion-app `clickAction` config (HA is the real sender, not this app's own MQTT service) —
  e.g. `clickAction: "frigateviewer://event?id={{ trigger.payload_json.after.id }}&camera={{ trigger.payload_json.after.camera }}"`
  — plus a live end-to-end test. Separately, the app's *own* `MqttForegroundService` notification
  can't deep-link even in principle: it builds a plain `Intent(MainActivity)` with an `event_id`
  extra, while `resolveDeepLink()` expects a `frigateviewer://` URI or `camera`/`id` extras.
  Decide first whether that path is even wanted (given HA is the real sender) before fixing the
  key mismatch.
- **Channel-switch tap-to-switch feels laggy — re-measure before touching it.** Reported before
  `WebRtcCore` (the shared-singleton fix) landed; switching now only recreates the
  `PeerConnection`, not the whole WebRTC stack + EGL context + audio module, so much of the
  perceived lag may already be gone. If it's still slow after a real re-test, the fix is UI
  feedback — drive the loading state from the tap itself, not from the first stream event — not
  more speed.

## Features in progress / next up

- **System Metrics screen** (Settings → Advanced, renamed from "Advanced"; content untouched so
  far): replace the percentage bar charts with line graphs (reuse `StreamStats.kt`'s
  `Sparkline` — area fill + dashed average line, don't reinvent it), expand Detectors to graph
  CPU and memory over time instead of just current value, and condense "Auto refresh" +
  "Refresh now" (currently eat a disproportionate amount of the screen). Poll rate is already
  user-configurable (Developer Options → Stats poll rate) and drives this screen's refresh loop.
- **Live camera 3-dot menu: fold in stream stats.** The fullscreen overflow menu duplicates much
  of what the stats overlay already shows — bring in stream type, bandwidth, latency, frame
  counts, drop rate, plus a condensed bandwidth chart sized for a menu. `StreamStats.kt` already
  owns all of it (`StreamStats`, `StreamStatsHistory`, `Sparkline`), so this is a presentation
  problem, not a data one — but the sampler currently only runs while the overlay is mounted, so
  a menu-only consumer needs it hoisted or given its own subscription.
- **Motion scrub mode** (Review surface, `screenshots/review_3.png`): a multi-camera recording
  grid, scrubbed against the motion-activity waveform. Playback speeds are 4x/8x/12x/16x
  (confirmed against the Frigate PWA — a different set from the single-event player's
  0.5/1/2/4/8/16, don't copy that menu). The biggest remaining lift on the Review surface — it's
  a recordings-playback surface, not a list.
- **Mark-as-reviewed UI.** `POST /api/reviews/viewed` (mark) and `/api/review/{id}/viewed`
  (unmark) both exist server-side; no UI yet. The feed deliberately omits the `reviewed` query
  param entirely today — see the gotcha below before wiring this up.
- **Explore's label-grouped object view** (`screenshots/explore.png`): "Bird · 307 Tracked
  Objects" rows with a per-label overflow arrow, plus a semantic-search tab (see the
  natural-language search idea below — same underlying Frigate capability).

### Gotcha: `reviewed` is a filter, not an include-flag

Verified live against the test server: omitting `reviewed` returns all 130 segments (38 alerts +
92 detections); `?reviewed=1` returns only the 2 already-reviewed ones; `?reviewed=0` returns the
other 128. The OpenAPI description ("Include items that have been reviewed") reads like an
include-flag and is actively misleading — passing `reviewed=1` once produced a feed showing 2
alerts / 0 detections instead of the real counts. Omit it. (`/api/review/summary`'s
`last24Hours` does expose `reviewed_alert`/`reviewed_detection` counts if a "N unreviewed" badge
is ever wanted, but the feed's own counts are derived from the loaded list today.)

## Known telemetry gaps (RTSP) — not bugs, limits of the transport

- **Latency is unavailable on RTSP.** ExoPlayer's RTSP stack exposes no round-trip time; a real
  number means reading RTCP receiver reports directly.
- **Bandwidth may read "not reported by this transport" on RTSP.** media3's RTP data channels
  may never feed `DefaultBandwidthMeter`; a real number needs a custom RTP data-channel wrapper
  that counts bytes. WebRTC tiles already report real bandwidth either way.
- Stream stats cover live tiles only, not event/recording playback.

## Tech debt

- **Room schema export is off.** Build warns *"Schema export directory was not provided to the
  annotation processor so Room cannot export the schema."* Not cosmetic — exported schema JSON
  is what lets Room **validate** migrations, directly relevant to this repo's "no
  `fallbackToDestructiveMigration`" rule. Apply the `androidx.room` Gradle plugin, set
  `room { schemaDirectory("$projectDir/schemas") }`, commit the generated JSON. Do this *before*
  writing the next Room migration.
- **Cleartext traffic enabled app-wide** (`usesCleartextTraffic="true"`) — accepted, documented
  trade-off, not a bug. It's what lets the per-server plain-HTTP "Local Server URL" option work
  at all (Android enforces cleartext blocking at the OS/socket layer, before OkHttp reads any
  per-server setting — there's no dynamic per-server mechanism). Worth a glance only if
  requirements change.

## Ideas — not scoped, not committed

Mostly from reviewing the Lumen iOS Frigate client for layout ideas (`screenshots/lumen_0001–
0009.webp`, `screenshots/todo_005/006.png`). Lumen is a paid app with subscription scaffolding
this app doesn't want — reviewed for UI/feature ideas only, nothing adopted wholesale.

**Worth a real design conversation** (real Frigate capabilities not yet surfaced, not just UI):
1. **Natural-language event search** — "person at the front door", "car in the driveway at
   night". Maps to Frigate's semantic-search/embeddings API in recent versions. Would be a new
   top-level destination. Check `temp/frigate.yml` (the pulled API spec) for the actual endpoint
   and which Frigate version gates it before scoping.

**Smaller:**
- **Camera detail page** as a middle step between grid tile and fullscreen: per-camera
  FPS/detection/skipped stats, Recordings + Controls entries, zone chips. Today it's grid straight
  to fullscreen with nothing between.
- **Summary stat header on Cameras**: "12 Detections · 5 Persons · 4 Vehicles", "All quiet" —
  computable from data already fetched; does a lot of the emotional work of making the screen
  feel alive.
- **Richer camera cards**: "Last motion 15m ago", detected-object icon row, event-count badge.
- **Explicit live/snapshot segmented toggle** rather than inferring the mode — ties into the
  snapshot-only-camera bug above.
- **Grid density modes**, including a stacked/swipe single-camera mode.

**Bigger, speculative:**
- Home/lock-screen widgets via Jetpack Glance.
- Geofencing-based notification rules — needs its own design pass before it's scopable.

**Already shipped, listed here only so they aren't re-proposed:**
- ~~Review vs. Events split~~ — shipped 2026-07-13 (Review feed + Explore rename).
- ~~Confidence % on event cards~~ — already shipped, verified in code: `EventsScreen.kt`'s
  `SegmentedEventPill` renders `(topScore ?: score)` as a rounded percentage today, and
  `CachedEvent.topScore` is already a persisted Room column. Nothing left to build here.

## Explicitly declined / not doing (so it doesn't get re-litigated)

- **Full testTag sweep.** The motivation (AI-driven verification finding elements by tag instead
  of screenshot+tap-coordinate guessing) never materialized across many sessions of exactly that
  kind of live-device/emulator verification. User's call: dropped.
- **Downloads as a real settings page.** Clip export already worked via `DownloadManager`; the
  page is now a short explanatory card, not new preferences, per explicit user instruction.
- **Split-view / literal multi-camera compositing** in fullscreen — substituted a quick
  camera-switcher in the overflow menu instead.

## Housekeeping already done (kept here briefly so it isn't re-checked)

- `.artifacts/` is gitignored (`c7b2707`).
- ktlint is wired for real — CLI via `JavaExec`, not the `org.jlleitschuh.gradle.ktlint` plugin
  (that plugin discovers sources through `org.jetbrains.kotlin.android`, which AGP 9's built-in
  Kotlin never applies, so it silently linted only `*.kts`). Don't "simplify" it back.

## Where things live

- **This file** — the current, prioritized backlog. Update it as work happens; don't let new
  items scatter back into session notes only.
- `memory/project_state.md` — full session-by-session log (root causes, what was verified and
  how). Read the "Current status" pointer at the top, not the whole file, to get oriented fast.
- `docs/issues/README.md`, `backlog-2026-07-10.md`, `backlog-2026-07-13.md` — historical,
  mostly-resolved issue write-ups kept for traceability. Don't add new items there.
- `docs/qa/` — dated real-device QA investigation notes.
- `docs/adr/` — architecture decision records.
