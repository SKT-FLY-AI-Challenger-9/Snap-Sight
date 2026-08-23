package com.example.snap_sight.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRuntimeTest {

    @Test
    fun `optional auth header is added only for a nonblank token`() {
        val request = Request.Builder().url("https://example.test/api").build()

        assertEquals(null, request.withSnapSightToken("").header("X-Snap-Sight-Token"))
        assertEquals(
            "test-token",
            request.withSnapSightToken("test-token").header("X-Snap-Sight-Token"),
        )
    }

    @Test
    fun `backoff grows exponentially respects server minimum and caps`() {
        val backoff = ExponentialBackoff(initialMs = 1_000L, maxMs = 8_000L)

        assertEquals(1_000L, backoff.next())
        assertEquals(3_000L, backoff.next(serverMinimumMs = 3_000L))
        assertEquals(4_000L, backoff.next())
        assertEquals(8_000L, backoff.next())
        assertEquals(30_000L, backoff.next(serverMinimumMs = 30_000L))
    }

    @Test
    fun `replacing same session cancels old token without touching another session`() {
        val registry = SessionRequestRegistry()
        val old = registry.replace("s_one")
        val other = registry.replace("s_two")
        val current = registry.replace("s_one")

        assertTrue(old.isCancelled)
        assertFalse(other.isCancelled)
        assertTrue(registry.isCurrent("s_one", current))
        assertFalse(registry.finish("s_one", old))
        assertTrue(registry.finish("s_one", current))
    }

    @Test
    fun `explicit cancellation suppresses completion`() {
        val registry = SessionRequestRegistry()
        val handle = registry.replace("s_cancel")

        registry.cancel("s_cancel")

        assertTrue(handle.isCancelled)
        assertFalse(registry.isCurrent("s_cancel", handle))
        assertFalse(registry.finish("s_cancel", handle))
    }

    @Test
    fun `terminal identity rejects a response from another revision`() {
        assertEquals(
            "capture revision 불일치(expected=8, actual=7)",
            terminalIdentityError(
                actualRevision = 7L,
                finalFrameId = "candidate_01",
                expectedRevision = 8L,
            ),
        )
        assertEquals(
            null,
            terminalIdentityError(
                actualRevision = 8L,
                finalFrameId = "candidate_01",
                expectedRevision = 8L,
            ),
        )
    }

    @Test
    fun `polling retries only rate limits and server failures`() {
        listOf(429, 500, 503, 599).forEach { assertTrue(isRetryablePollHttpCode(it)) }
        listOf(400, 401, 403, 404, 408, 413, 415, 422).forEach {
            assertFalse(isRetryablePollHttpCode(it))
        }
    }

    @Test
    fun `retry after delta seconds is bounded and invalid values fall back`() {
        assertEquals(3_000L, retryAfterMillis("3"))
        assertEquals(0L, retryAfterMillis("Sun, 23 Aug 2026 00:00:00 GMT"))
        assertEquals(0L, retryAfterMillis("-1"))
        assertEquals(86_400_000L, retryAfterMillis("999999"))
    }

    @Test
    fun `backend URL normalization permits local HTTP only in debug policy`() {
        assertEquals(
            "http://192.168.0.10:8000",
            BackendConfig.normalize("192.168.0.10:8000/", allowCleartext = true),
        )
        assertEquals(
            "https://api.example.test",
            BackendConfig.normalize("api.example.test/", allowCleartext = false),
        )
        assertEquals(
            null,
            BackendConfig.normalize("http://api.example.test", allowCleartext = false),
        )
    }
}
