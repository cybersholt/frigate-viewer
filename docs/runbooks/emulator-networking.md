# Emulator Networking & Local Testing

When testing Frigate Viewer in the Android Emulator against a local Frigate instance (e.g. `http://192.168.x.x`), be aware of these caveats.

## 1. Cleartext (HTTP) Traffic

Android 9+ blocks cleartext (`http://`) traffic by default. We allow it app-wide via `android:usesCleartextTraffic="true"` + `network_security_config.xml`'s `base-config cleartextTrafficPermitted="true"`, because the "Local Server URL" setting supports plain-HTTP local Frigate instances and Android's network security config has no mechanism to allow cleartext per-server at runtime — it's enforced at the OS/socket layer before OkHttp ever sees the request. This is a real, intentional security tradeoff (not a bug): any host the app talks to over `http://` is unencrypted. Self-signed HTTPS certs still go through the normal per-server "Allow untrusted certs" + imported PEM flow, not cleartext.

## 2. Emulator Wi-Fi SSID Detection

The Android Emulator has no real Wi-Fi radio, so `WifiMonitor` correctly reports `ssid = null` there — this is not a bug, just a platform limitation. As a result, the "Local Server URL" switch (which only activates when the detected SSID matches a server's configured `localNetworkSsids`) can never trigger automatically in the emulator.

There's no in-app override for this (tried once, removed — not worth the complexity for a debug-only path). Test the Local Server URL switch on a real device on the actual Wi-Fi network instead.

## 3. RTSP Reachability

RTSP is gated off when off the configured local network (`rtspOffLan` in `CamerasScreen.kt`) and falls back to WebRTC automatically — this applies identically on the emulator and real devices, with no emulator-specific bypass. If your `rtspHost` is a real LAN IP (e.g. `192.168.x.x`), the emulator's virtual NAT genuinely cannot route to it regardless of SSID matching, so RTSP will still fail there — WebRTC is the only mode that can work for LAN-only RTSP hosts inside the emulator. Test RTSP on a real device on the actual Wi-Fi network instead.

## 4. WebRTC ICE candidates behind a home router

If WebRTC connects fine externally but hangs/fails when testing from the same LAN as the server, check `go2rtc`'s WebRTC candidate config (in Frigate's `config.yml`, nested under `go2rtc:`, not top-level — a bare top-level `webrtc:` key fails Frigate's schema validation). If `go2rtc` only advertises the public WAN IP as a candidate, same-LAN clients need NAT hairpin (loopback through the router's public IP) to connect, which most consumer routers don't support. Fix: add the LAN IP as an explicit candidate alongside the public one:
```yaml
go2rtc:
  webrtc:
    candidates:
      - 192.168.x.x:8555   # LAN IP — port must match the container-internal go2rtc WebRTC port even if the host port mapping differs
      - your-public-host.example.com:8555
```
This is a server-side fix, not something this app can work around.

## History

A previous attempt at these fixes (before 2026-07-10) injected fabricated "synthetic ICE candidates" using the server hostname as a candidate IP address, and bypassed the RTSP off-LAN gate specifically on emulators. Both were removed — the synthetic candidates were syntactically invalid (ICE candidates require IP literals, not hostnames) and broke every WebRTC connection attempt; the RTSP bypass forced connection attempts to a host the emulator can't physically reach, defeating the working WebRTC fallback.
