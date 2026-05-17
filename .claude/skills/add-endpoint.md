---
name: add-endpoint
description: Add a new Frigate REST endpoint end-to-end (Retrofit method → ApiResult → repository → ViewModel).
---

# Add a Frigate endpoint

## Steps
1. **Model** — Add `@Serializable data class` to `core/model/FrigateModels.kt`. Unknown fields get a default. Deep nested unknowns stay as `JsonElement`.
2. **API** — Add suspend function to `core/network/FrigateApi.kt`. Return type must be `Response<T>`.
3. **Repository** — Add wrapper in `core/data/FrigateRepository.kt`:
   ```kotlin
   suspend fun thing(...): ApiResult<Thing> = safeApiCall { api.thing(...) }
   ```
4. **ViewModel** — Add a `viewModelScope.launch` that calls the repo method and pattern-matches all four `ApiResult` branches.
5. **Test** — Add a `MockWebServer` test exercising Success + HttpError + NetworkError + ParseError.

## Forbidden shortcuts
- Returning `T` from the Retrofit interface (no `Response<T>` wrapper) — breaks `safeApiCall`.
- Calling `.body()` outside `safeApiCall`.
- Adding a new `Json {}` instance — reuse the DI-provided one.
- Hardcoding the base URL inside the interface — Retrofit's base URL is the active server's `baseUrl()`.
