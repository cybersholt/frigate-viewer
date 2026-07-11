package net.triton.frigateviewer.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Wraps a Retrofit suspend call.
 *
 *  - 2xx: returns parsed body as Success
 *  - 2xx with null body: ParseError (server contract violation)
 *  - Non-2xx: HttpError carrying raw errorBody string — NEVER parsed as JSON.
 *    Frigate frequently returns text/html on errors; calling .json() on that would crash.
 *  - IOException: NetworkError
 *  - SerializationException: ParseError (logged, never thrown to UI)
 *  - HttpException (defensive, in case of mis-wired callers): mapped to HttpError
 */
suspend fun <T> safeApiCall(block: suspend () -> Response<T>): ApiResult<T> =
    try {
        val response = block()
        if (response.isSuccessful) {
            // Retrofit parses the response body lazily when .body() is called.
            // For large JSONs (e.g. 3000 events), this can block the caller's thread for seconds.
            val body = withContext(Dispatchers.IO) { response.body() }
            if (body != null) {
                ApiResult.Success(body)
            } else {
                ApiResult.ParseError(IllegalStateException("Empty body on ${response.raw().request.url}"))
            }
        } else {
            val raw = withContext(Dispatchers.IO) {
                runCatching { response.errorBody()?.string() }.getOrNull()
            }
            ApiResult.HttpError(code = response.code(), message = response.message(), rawBody = raw)
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: SerializationException) {
        ApiResult.ParseError(e)
    } catch (e: HttpException) {
        ApiResult.HttpError(code = e.code(), message = e.message())
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    } catch (e: Throwable) {
        ApiResult.NetworkError(e)
    }
