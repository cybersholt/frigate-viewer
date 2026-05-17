package net.triton.frigateviewer.core.network

import kotlinx.coroutines.CancellationException
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
suspend fun <T> safeApiCall(block: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = block()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) ApiResult.Success(body)
            else ApiResult.ParseError(IllegalStateException("Empty body on ${response.raw().request.url}"))
        } else {
            val raw = runCatching { response.errorBody()?.string() }.getOrNull()
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
}
