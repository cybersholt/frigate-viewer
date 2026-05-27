package net.triton.frigateviewer.core.network

/**
 * Sealed result wrapper for every API call.
 *
 * Direct mitigation for the prior React Native bug: `response.json()` was called on
 * non-OK HTTP responses, which crashed JSON.parse when Frigate returned HTML error pages.
 *
 * Every Retrofit call must funnel through [safeApiCall], which guarantees:
 *  - non-2xx responses become [ApiResult.HttpError] without ever touching the body parser
 *  - thrown exceptions during parsing become [ApiResult.ParseError]
 *  - network IO exceptions become [ApiResult.NetworkError]
 *
 * UI never calls `.getOrThrow()`. It pattern-matches the sealed type.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(
        val data: T,
    ) : ApiResult<T>

    data class HttpError(
        val code: Int,
        val message: String,
        val rawBody: String? = null,
    ) : ApiResult<Nothing>

    data class NetworkError(
        val cause: Throwable,
    ) : ApiResult<Nothing>

    data class ParseError(
        val cause: Throwable,
    ) : ApiResult<Nothing>

    fun <R> map(transform: (T) -> R): ApiResult<R> =
        when (this) {
            is Success -> Success(transform(data))
            is HttpError -> this
            is NetworkError -> this
            is ParseError -> this
        }

    fun getOrNull(): T? = (this as? Success)?.data
}
