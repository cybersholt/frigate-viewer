# core/data — credential & persistence rules

This module owns the line between "secret" and "settings". Crossing it the wrong way leaks credentials.

## Hard rules

1. **Credentials never touch DataStore plaintext.** They go through `CredentialStore` (Tink AEAD, master key wrapped in Android Keystore). The `Server` data class persisted in DataStore intentionally has no `password` field.
2. **`EncryptedSharedPreferences` is banned.** It's deprecated in `androidx.security:security-crypto:1.1.0+` and corrupts keysets on certain OEM devices. Use `CredentialStore`.
3. **DataStore keys are versioned (`*_v1`, `*_v2`).** Never reuse a key with a new shape. New shape = new key + an explicit migration that reads old then writes new.
4. **Never log a `Server` instance directly** (it includes username and base URL). Log the `id` only.
5. **Never write a secret to a file in `filesDir` without going through `CredentialStore`.** The class manages a single directory (`filesDir/secrets/`) and rotates / deletes blobs atomically.

## Adding a new persisted setting

Non-secret:
1. Add to `Server.kt` (or a new `Settings` data class) with a default.
2. Bump the DataStore key version if you changed an existing shape.
3. Write the migration block in `ServerRepository`.

Secret:
1. Use `CredentialStore.setPassword(serverId, ...)` or add a new typed setter that internally serializes to the AEAD blob.
2. Never store as plaintext "for a quick test". The hook will refuse to commit the file anyway.

## Pinned certificates

Per-server PEM lives at `filesDir/secrets/<serverId>.pem`. Loaded by `TrustConfig.applyPinnedCertificate` when building the per-server OkHttp client. A new pinned cert is set via `CredentialStore.setPinnedCert(serverId, pem)`.

Do not store the PEM in DataStore — it bloats reads and serializes badly.

## Forbidden

- `SharedPreferences` for new code (use DataStore Preferences).
- `Gson` for serialization (use kotlinx.serialization, which is the DI-provided `Json`).
- `runBlocking` in any new public function. Suspend functions all the way.
- Storing the bearer token unencrypted in a Cookie file. The `CookieJar` (when added) must be in-memory only or backed by `CredentialStore`.
