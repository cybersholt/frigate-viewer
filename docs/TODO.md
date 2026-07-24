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

- ~~**Review rail rework needs a live-device check.**~~ Checked on `emulator-5554` 2026-07-21:
  labels legible, minor ticks dense, activity strip populated with real motion data, mid-rail tap
  ignored, handle drag moved the scrubber and scrolled the grid in sync. Detail kept below because
  the "why" is still useful. Rebuilt 2026-07-21 against the PWA reference
  (`192.168.88.26:5005/review`): dense minor ticks with labelled major ticks (15 min over 1 min at
  the 2 h default), a bracketed scrub handle (two rules + a bold red time capsule), and an
  **activity strip** down the right edge carrying the amber motion waveform with severity-coloured
  review pills on top. Rail widened 64→76 dp to fit.
  - **Root cause of "hard to read" was density, not layout:** the rail set
    `android.graphics.Paint.textSize = 15f` in *raw pixels*, which on a 420 dpi phone is ~5.7 dp of
    text — under half the 26f the Event Detail timeline uses. All geometry is now dp/sp-derived
    off `DrawScope`'s `Density`. Watch for this in any other `Paint` built for a Canvas.
  - Renderer moved out of `feature/events/TimelineRenderer.kt` into
    `feature/review/ReviewRailRenderer.kt` so it can use the review package's layout math without
    events depending back on review. `drawSeverityEventTimeline` no longer exists.
  - Ruler + waveform math is pure and unit-tested (`ReviewTimelineLayoutTest`, 11 tests) — but
    **nothing has been seen on a device.** Confirm the strip actually populates: it needs
    `api/review/activity/motion`, and on failure it silently falls back to review-item density
    (logged at `warn` by `ReviewViewModel.loadMotionActivity`, so check logcat before assuming
    the waveform is real motion data).
- **Snapshot-only cameras don't fit the event-playback model.** A snapshot-only camera's live
  tile works fine, but event review assumes a clip/VOD exists. Look at how Frigate's own web UI
  handles this before building anything — may turn out to be a "what does done even mean"
  question, and may tie into the explicit live/snapshot toggle idea below.
- **Motion indicator dot is inconsistent across screens.** The camera grid tile's status dot
  cycles green (idle) → red (recording), but never turns blue for "motion happening now" — even
  though blue already means motion/active-event elsewhere in the app (the fullscreen
  focused-tile `LiveIndicatorDot`). The two need to share one source of state.
- ~~**Event playback player chrome is off-spec.**~~ Fixed 2026-07-21: the control bar is now a
  content-hugging rounded pill matching upstream's `w-auto rounded-lg bg-background/60 px-4 py-2`,
  the full-width dark gradient is gone, and `navigationBarsPadding()` is fullscreen-only so the bar
  sits on the frame edge instead of floating above it. Verified on ch1/ch2/wyze_1 in both
  orientations. Re-open only if a specific camera still looks wrong.
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

- **Timelines are grab-the-handle, not tap-anywhere — everywhere.** Standing interaction rule,
  stated 2026-07-21: *"instead of accidentally clicking anywhere on the timeline, you actually have
  to grab the little red line and DRAG that. it should be like this whereever a timeline is
  displayed."* **DONE in all three surfaces and verified on the emulator:**
  `ReviewSeverityTimeline.kt`, `HorizontalTimeline.kt` (Event Detail), `TimelinePanel.kt` (Explore
  sidebar). Pattern is `awaitEachGesture` + `awaitFirstDown` with the press ignored unless it lands
  within a 24 dp grab radius of the scrub line. Two traps if you touch these again: the scrub time
  must go through `rememberUpdatedState`, or the hit-test runs against a stale handle position and
  the handle becomes ungrabbable after one drag; and on `HorizontalTimeline` a non-grabbing press
  still *pans* the window — that was pre-existing, useful behaviour, only the tap-to-jump was
  removed. Verified: tap mid-timeline leaves the scrubber alone, drag on the handle moves it.

- **Event player: gradient + landscape — DONE 2026-07-21, verified on emulator.**
  - The "weird gradient" was `Brush.verticalGradient(Transparent → Black 0.75f)` on a
    `fillMaxWidth()` control container, smearing a dark band across the lower third of every frame.
    Replaced with a content-hugging rounded bar. **Checked against upstream first** rather than
    guessed: Frigate's `web/src/components/player/VideoControls.tsx` container is
    `"z-50 flex w-auto select-none items-center justify-between gap-4 rounded-lg bg-background/60
    px-4 py-2 text-primary"` — `w-auto`, `rounded-lg`, a *semi-transparent solid*, no gradient.
  - `navigationBarsPadding()` was applied unconditionally, lifting the bar ~34 dp off the frame
    even when the player is inline mid-screen. Now fullscreen-only.
  - Landscape was structurally broken, not just styled wrong: the player was
    `fillMaxWidth().aspectRatio(16f/9f)`, which on a 2400x1080 screen asks for a 1350 px-tall video
    in 1080 px — the video ate the whole height and the timeline was crushed to a sliver at the
    bottom (and the leftover Column background showed as the white strip on the left edge).
    Landscape now uses a `Row`: video letterboxed on black with `weight(1f)`, timeline a 160 dp
    vertical rail on the right, matching `pwa_landscape_event_player_and_timeline.png`. The two
    panes are declared as `playerPane` / `viewPane` lambdas so portrait and landscape share bodies.
  - Still open on this screen: the scrub thumbnail, the app-bar time capsule, and grab-to-drag —
    see the next item.

- **Event player scrub preview + app-bar clock — DONE 2026-07-21, verified on emulator.**
  - The floating `PreviewThumbnailBubble` over the timeline is gone. The preview frame now replaces
    the *main player image* while dragging (`playerPane` in `EventDetail.kt`), which is what the PWA
    does and what was asked for — the bubble covered the timeline it was meant to help you read.
    `PreviewThumbnailBubble.kt` is still used by Explore (`EventsScreen.kt:224`) — keep it. That
    surface is a grid with no player to put the frame in, so the bubble is the only preview it can
    show.
  - The app-bar date pill becomes a live red clock while the handle is dragged
    (`previewYFraction != null` is the "is scrubbing" signal), matching `pwa_timeline_scrubbing.png`.
    Verified: pill read `10:17:32 AM` mid-drag and reverted to `Jul 21` on release.
  - Landscape keeps the timeline rail **including in fullscreen** (the layout branches on
    `isLandscape` alone, not `isLandscape && !fullScreen`), matching the reference screenshot of
    fullscreen PWA playback where the rail is still present.

- **Pinch-zoom on the event / VOD player.** Deferred deliberately on 2026-07-21 — the user asked
  for it "after this stuff is done", i.e. after the live-tile zoom fix. The hard part is already
  built and shared: `feature/cameras/ZoomPanState.kt` (clamped pan, centroid-anchored pinch) and
  `ZoomIndicator.kt` (the minimap) have no live-stream dependency, so wiring them into
  `EventDetail.kt`'s `ClipPlayer` is mostly a `graphicsLayer` + `onSizeChanged` + gesture hookup,
  plus feeding the aspect ratio from ExoPlayer's `onVideoSizeChanged` the same way `RtspLiveTile`
  now does. Also worth considering for `SnapshotLiveTile`, which still has no gestures at all.
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

- **Cross-camera latency in the debug overlay.** Asked for and then withdrawn the same day
  (2026-07-21). The motivation was a symptom seen in the *Frigate web app*, not this one: an
  MSE-played stream whose latency climbed steadily until playback stopped. It reproduces in Brave
  but not Firefox, so the user's read is a browser buffering/caching issue rather than anything
  server-side or app-side. Don't rebuild this as a native feature off that evidence — if the
  same drift ever shows up in *our* MSE/WebRTC path, that's a real bug to chase on its own terms.

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
