# core/network — JSON safety rules

This module exists because the prior React Native app crashed at startup by calling `response.json()` on non-OK HTTP responses. Frigate returns `text/html` on errors. JSON.parse on HTML → unhandled crash on the cameras screen.

## Hard rules

1. **Every Retrofit method returns `Response<T>`.** Not `T`, not `Call<T>`. The wrapper is what lets `safeApiCall` branch on status before touching the body.
2. **Every call to a Retrofit method is wrapped in `safeApiCall { ... }`.** No exceptions. If you find a bare `api.foo()` outside `safeApiCall`, you've reintroduced the original bug class.
3. **Never call `.body()` directly.** It is called exactly once, inside `SafeApiCall.kt`, after `response.isSuccessful` is confirmed.
4. **Never parse `errorBody()` as JSON.** It is opaque bytes. If you need to surface it to the user, treat it as text in `ApiResult.HttpError.rawBody`.
5. **`HttpException` and `IOException` are both caught in `safeApiCall`.** Do not add try/catch at the ViewModel layer for these. Pattern-match the `ApiResult` instead.
6. **`SerializationException` becomes `ApiResult.ParseError`.** Never let it escape to the UI as a crash.

## Adding a field that Frigate may or may not send

Add a default. Always.

```kotlin
@Serializable
data class Thing(
    val id: String,
    val maybe: String? = null,         // missing → null
    val maybeList: List<String> = emptyList(),
    val unknownBlob: JsonElement? = null,  // deep nested + version-variant
)
```

The DI-provided `Json` is configured with `ignoreUnknownKeys = true`, so new fields in newer Frigate versions don't crash the parser. But missing fields without a default still crash. Use defaults.

## Adding auth-required endpoints

Auth header is injected by `AuthInterceptor`. Do not add `@Header("Authorization")` to the Retrofit method.

If the endpoint can return 401 on expired JWT, the `TokenRefreshAuthenticator` will refresh once and retry. If refresh fails, the call returns `ApiResult.HttpError(401, ...)` and the UI prompts re-login.

## What lives where

| File | Responsibility |
|---|---|
| `ApiResult.kt` | Sealed return type. Edit only to add a new error category. |
| `SafeApiCall.kt` | The single funnel. If you change this, run every network test. |
| `FrigateApi.kt` | Retrofit interface. Add endpoints here. |
| `AuthInterceptor.kt` | Per-request `Authorization`. Reads from `CredentialStore`. |
| `TrustConfig.kt` | Per-host pinned cert from user-imported PEM. **No "trust all" path.** |

## Forbidden

- `OkHttpClient.Builder().hostnameVerifier { _, _ -> true }` — never.
- `X509TrustManager.checkServerTrusted` returning unconditionally — never.
- `runBlocking { ... }` outside an OkHttp interceptor (where it's an adapter unavoidably).
- Logging full headers (BASIC level only, never HEADERS or BODY).
