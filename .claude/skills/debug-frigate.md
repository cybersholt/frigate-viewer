---
name: debug-frigate
description: Diagnose Frigate connection / streaming / auth failures. Start here when a user reports breakage.
---

# Debug Frigate issues

## Triage tree

### Symptom: "Cameras screen shows error / nothing"
1. Check the `ApiResult` branch surfaced in the UI:
   - `HttpError 401/403` → auth misconfig. Confirm auth mode matches the Frigate server (`auth` block in `config.yml`). Frigate 0.14+ defaults to JWT on port 8971.
   - `HttpError 404` → wrong base path. The form's "base path" field appends to host before `/api`.
   - `HttpError 500` → server-side. Pull `docker logs frigate` for a stack.
   - `NetworkError` → unreachable host, expired DNS, wrong port, or self-signed cert without a pinned PEM.
   - `ParseError` → Frigate version older than expected, or a reverse proxy is rewriting the body. Inspect raw response with `curl -v`.
2. **Never bypass `safeApiCall`** to "see the raw JSON". If you need the raw body, log it inside the `HttpError` branch via `rawBody`.

### Symptom: "Live tile is black / spinning forever"
1. WebRTC path: confirm `ws://host:port/api/ws?src=<cam>` is reachable from the device (not just LAN). Test in Chrome on the same device.
2. ICE trickle: `go2rtc` HTTP WHEP path does NOT support trickle. Confirm the app is using the WS path.
3. Hardware decoder limit: device-specific cap (4–8 concurrent). Drop grid count or switch grid tiles to HLS.
4. HLS fallback: `https://host:8971/vod/<cam>/index.m3u8` returning 404 means recording is off for that camera.

### Symptom: "App crashes at startup"
This was the original RN bug. In Kotlin it should be impossible — but if it happens:
1. Reproduce with a debug build. `adb logcat -d AndroidRuntime:E *:S`.
2. If the stack mentions `SerializationException`, it's a Frigate response shape change. Add a `@Serializable` field with a default or wrap as `JsonElement`.
3. If the stack mentions `IllegalStateException: Empty body`, the server returned 200 with no body. Check reverse proxy buffering.

### Symptom: "Login works but next request gets 401"
1. JWT cookie not being persisted. Check OkHttp `CookieJar` is installed.
2. Token expired. `TokenRefreshAuthenticator` should retry once. If it doesn't, check `TokenRefreshAuthenticator.authenticate` returns a non-null retry request.

## Tools
- `adb logcat -s OkHttp,FrigateViewer`  — request + app logs
- `curl -k -v -H "Authorization: Bearer $TOKEN" https://host:8971/api/config`  — bypass app to confirm server state
- Frigate admin: `https://host:8971/system` for live stats
- go2rtc admin: `http://host:1984` for stream introspection (LAN only by default)

## Output format
When reporting a Frigate bug, include:
- Frigate version (`/api/version`)
- Auth mode + port
- The `ApiResult` branch hit
- Last 50 lines of `adb logcat`
- Whether the problem reproduces on LAN, WAN, or both
