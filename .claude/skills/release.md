---
name: release
description: Cut a release of Frigate Viewer. Keystore stays local.
---

# Release procedure

## Preconditions
- `keystore.properties` exists at repo root (gitignored).
- Release keystore JKS exists at the path it references.
- All unit tests pass on `kotlin-rewrite`.
- No open `TODO(security)` comments in `git grep`.

## Versioning
- Semver. `versionName` in `app/build.gradle.kts`.
- Bump `versionCode` by exactly 1 per Play Store upload.

## Steps
1. `git checkout kotlin-rewrite && git pull`
2. Bump `versionName` + `versionCode` in `app/build.gradle.kts`. Commit: `chore: bump version to vX.Y.Z`.
3. `./gradlew :app:lintRelease :app:testReleaseUnitTest`
4. `./gradlew :app:bundleRelease` (or `:app:assembleRelease` for sideload APK)
5. Verify signature: `apksigner verify --verbose app/build/outputs/bundle/release/app-release.aab`
6. Tag: `git tag -a vX.Y.Z -m "Frigate Viewer vX.Y.Z"` && `git push origin vX.Y.Z`.
7. Upload AAB to Play Console internal testing track. Promote after one device smoke test.

## Hard rules
- Never check `release.jks` into git.
- Never paste keystore passwords into Slack, email, or commit messages.
- If the keystore is lost: app must be re-published under a new package ID. Back it up to an offline encrypted volume.

## Post-release
- Update `docs/runbooks/release.md` if anything in the procedure changed.
- Open a new ADR if the change altered an architectural decision.
