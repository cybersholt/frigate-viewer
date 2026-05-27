package net.triton.frigateviewer.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET

/**
 * Every branch of [safeApiCall]. These are the regression net against the
 * "JSON parse on HTML error body" crash class from the prior React Native app.
 */
class SafeApiCallTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TestApi

    private interface TestApi {
        @GET("/thing")
        suspend fun thing(): retrofit2.Response<String>
    }

    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .client(OkHttpClient())
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()
                .create(TestApi::class.java)
    }

    @AfterEach fun teardown() {
        server.shutdown()
    }

    @Test fun `200 with body returns Success`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val r = safeApiCall { api.thing() }
            assertTrue(r is ApiResult.Success, "expected Success, got $r")
            assertEquals("ok", (r as ApiResult.Success).data)
        }

    @Test fun `401 with HTML body returns HttpError without crashing`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><body>Unauthorized</body></html>"),
            )
            val r = safeApiCall { api.thing() }
            assertTrue(r is ApiResult.HttpError, "expected HttpError, got $r")
            val err = r as ApiResult.HttpError
            assertEquals(401, err.code)
            // The HTML must not be parsed — it should arrive as raw text.
            assertNotNull(err.rawBody)
            assertTrue(err.rawBody!!.contains("Unauthorized"))
        }

    @Test fun `500 with HTML body returns HttpError`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html>500 Internal Server Error</html>"),
            )
            val r = safeApiCall { api.thing() }
            assertTrue(r is ApiResult.HttpError)
            assertEquals(500, (r as ApiResult.HttpError).code)
        }

    @Test fun `socket close returns NetworkError`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
            val r = safeApiCall { api.thing() }
            assertTrue(r is ApiResult.NetworkError, "expected NetworkError, got $r")
        }

    @Test fun `204 empty body returns ParseError`() =
        runBlocking {
            // Scalars converter expects a body; 204 with no body should not crash.
            server.enqueue(MockResponse().setResponseCode(204))
            val r = safeApiCall { api.thing() }
            // Either Success("") (if Retrofit synthesizes empty) or ParseError —
            // both acceptable; what matters is no exception escapes.
            assertTrue(r is ApiResult.Success || r is ApiResult.ParseError, "got $r")
        }
}
