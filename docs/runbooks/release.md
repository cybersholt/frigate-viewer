# Runbook: Cut a release

Operational checklist. Skill version with commentary lives at `.claude/skills/release.md`.

## 1. Preflight
- [ ] Working tree clean: `git status` is empty.
- [ ] On `kotlin-rewrite`, up to date with `origin`.
- [ ] `keystore.properties` exists locally. `release.jks` referenced path resolves.
- [ ] No `TODO(security)` or `// FIXME(crash)` in `git grep -n`.

## 2. Version bump
- [ ] Edit `app/build.gradle.kts`:
  - `versionName = "X.Y.Z"`
  - `versionCode = N + 1`
- [ ] `git commit -am "chore: bump version to vX.Y.Z"`

## 3. Build
```
./gradlew clean
./gradlew :app:lintRelease
./gradlew :app:testReleaseUnitTest
./gradlew :app:bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

## 4. Verify signature
```
apksigner verify --verbose --print-certs app/build/outputs/bundle/release/app-release.aab
```
- [ ] Confirms `v2/v3 signature`.
- [ ] SHA-256 cert fingerprint matches the one on file in Play Console.

## 5. Smoke test
- [ ] Install on at least one physical device.
- [ ] Add a server, load cameras, view an event, kill the app, relaunch — no crash.

## 6. Upload
- [ ] Play Console → Internal testing → Upload AAB.
- [ ] Wait for review-clean status.
- [ ] Promote to production after 24h of internal soak.

## 7. Tag + push
```
git tag -a vX.Y.Z -m "Frigate Viewer vX.Y.Z"
git push origin vX.Y.Z
```

## 8. Update changelog
- [ ] Append to `docs/CHANGELOG.md` (create on first release).

## Rollback
- Halt: Pause rollout in Play Console.
- Revert: Upload previous AAB with higher `versionCode` (Play won't accept the same code).
- Hotfix: branch from the tag, fix, bump patch, repeat from step 2.

## Lost keystore
The keystore is irreplaceable. If lost:
- The app must be re-published under a new `applicationId`.
- Users must reinstall.
- Back up the keystore + `keystore.properties` to at least two encrypted offline volumes.
