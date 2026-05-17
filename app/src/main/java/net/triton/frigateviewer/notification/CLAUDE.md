# notification — foreground service rules

This module runs a long-lived foreground service that subscribes to Frigate's MQTT and posts notifications. Android 14+ enforces strict service-type rules. Get this wrong and the app gets killed silently or banned from the Play Store.

## Hard rules

1. **Service type is `dataSync`.** Declared in `AndroidManifest.xml` AND passed to `startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)` on API 34+. Both must match.
2. **`FOREGROUND_SERVICE_DATA_SYNC` permission is declared.** Without it, Android 14+ throws `SecurityException` at start.
3. **Always post the persistent notification before any other work.** `startForeground` must be called within 5s of `onStartCommand` or Android kills the service.
4. **The persistent notification uses `CHANNEL_SERVICE` (LOW importance).** Event notifications use `CHANNEL_EVENTS` (HIGH). Never reuse channels — users tune them independently.
5. **Event de-dupe by Frigate event ID.** MQTT may redeliver. Keep a bounded LRU of seen IDs (e.g., 256 entries) in memory; survives process lifetime, not death — which is fine.
6. **Network calls inside this service still funnel through `safeApiCall`.** Same JSON safety rules as the rest of the app.

## MQTT subscription

When wired (v0.2):
- Use HiveMQ MQTT client (`hivemq-mqtt-client`) — modern, supports MQTT 5.
- Subscribe to `frigate/events` (Frigate <0.14) OR `frigate/reviews` (Frigate 0.14+). Detect by checking `/api/version` once at service start.
- TLS to the MQTT broker is required for non-LAN connections. Reject `tcp://` if the server's base URL is `https://`.

## Forbidden

- Service type `none` or omitted on Android 14+ — silent kill.
- Holding a `WakeLock` longer than the network call duration.
- Long-running work in `onCreate`. `onStartCommand` is where you bind to MQTT.
- Calling `Notification.Builder` without the `NotificationCompat` wrapper.
- Crashlytics / analytics SDKs inside this module — they delay foreground start.
