---
name: code-review
description: Frigate Viewer code review checklist. Run before opening any PR into kotlin-rewrite.
---

# Code review checklist

## Network layer
- [ ] Every Retrofit call goes through `safeApiCall { api.foo() }` — never `.execute()` or raw `.body()` on a `Response`.
- [ ] Retrofit interface returns `Response<T>` for every endpoint (so the safe wrapper can branch on status).
- [ ] No `errorBody()?.string()` is being parsed as JSON. It is opaque, may be HTML.
- [ ] OkHttp interceptors do not log full Authorization headers (logging level BASIC only in debug).

## Persistence
- [ ] DataStore keys are versioned (`*_v1`, `*_v2`). No key reuse with a new shape.
- [ ] Room migrations are explicit per version step. No `fallbackToDestructiveMigration()`.
- [ ] Credentials never touch DataStore. They go through `CredentialStore` only.

## Coroutines
- [ ] `viewModelScope.launch` blocks update a single `StateFlow` (no scattered `mutableState`).
- [ ] No `runBlocking` in production code paths (allowed only inside OkHttp `Interceptor.intercept` for adapter glue).
- [ ] Suspend functions are cancellation-safe — they propagate `CancellationException` rather than swallowing it.

## Compose
- [ ] State is collected with `collectAsStateWithLifecycle()`, not `collectAsState()`.
- [ ] `remember(key)` keys are stable.
- [ ] `LazyColumn` / `LazyVerticalGrid` items have stable `key` argument.
- [ ] No business logic in `@Composable` functions. ViewModels own state transitions.

## Security
- [ ] No new "trust all certs" code paths. Self-signed = pinned PEM in `CredentialStore` only.
- [ ] No secrets in BuildConfig fields. No secrets in resources. No secrets in commits.
- [ ] New permissions are documented in the PR with justification.

## UX
- [ ] Every screen handles `loading` / `success` / `error` / `empty` states. No bare spinners.
- [ ] Error messages explain what to do, not just what went wrong.
- [ ] No string literals in `@Composable` UI — use `stringResource(R.string.X)`.

## Tests
- [ ] Every new `ApiResult` branch (Success, HttpError, NetworkError, ParseError) is covered by a MockWebServer test.
- [ ] ViewModel tests use `Turbine` for flow assertions.
- [ ] No `Thread.sleep` in tests — use coroutine test schedulers.

## Build hygiene
- [ ] New dependencies are added to `gradle/libs.versions.toml`. No inline version strings.
- [ ] No new modules without updating `settings.gradle.kts`.
- [ ] ProGuard rules updated when adding a library that uses reflection/serialization.
