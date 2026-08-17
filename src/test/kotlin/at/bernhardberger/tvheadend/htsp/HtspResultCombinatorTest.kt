package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtspResultCombinatorTest {
    private val failures: List<HtspFailure>
        get() = listOf(
            HtspResult.ServerError,
            HtspResult.AccessDenied,
            HtspResult.ConnectionLimit,
            HtspResult.Timeout,
            HtspResult.TransportUnavailable,
            HtspResult.NotSupported,
        )

    @Test
    fun getOrNullReturnsOnlyTheSuccessValue() {
        val value = Any()

        assertSame(value, HtspResult.Ok(value).getOrNull())
        failures.forEach { failure -> assertNull(failure.getOrNull()) }
    }

    @Test
    fun getOrElseReturnsSuccessWithoutCallbackAndPassesExactFailureOnce() {
        val value = Any()
        var successCallbackInvoked = false

        assertSame(value, HtspResult.Ok(value).getOrElse {
            successCallbackInvoked = true
            Any()
        })
        assertFalse(successCallbackInvoked)

        failures.forEach { failure ->
            val fallback = Any()
            var callbackCount = 0
            val result = failure.getOrElse { observed ->
                callbackCount += 1
                assertSame(failure, observed)
                fallback
            }

            assertSame(fallback, result)
            assertEquals(1, callbackCount)
        }
    }

    @Test
    fun onOkAndOnFailureInvokeOnlyTheirBranchAndReturnExactReceiver() {
        val value = Any()
        val success: HtspResult<Any> = HtspResult.Ok(value)
        var successCount = 0
        var successFailureCount = 0

        assertSame(success, success.onOk { observed ->
            successCount += 1
            assertSame(value, observed)
        })
        assertSame(success, success.onFailure { successFailureCount += 1 })
        assertEquals(1, successCount)
        assertEquals(0, successFailureCount)

        failures.forEach { failure ->
            val result: HtspResult<Any> = failure
            var failureSuccessCount = 0
            var failureCount = 0

            assertSame(result, result.onOk { failureSuccessCount += 1 })
            assertSame(result, result.onFailure { observed ->
                failureCount += 1
                assertSame(failure, observed)
            })
            assertEquals(0, failureSuccessCount)
            assertEquals(1, failureCount)
        }
    }

    @Test
    fun foldInvokesExactlyOneBranchWithExactValueOrFailure() {
        val value = Any()
        var successCount = 0
        var successFailureCount = 0

        assertEquals("ok", HtspResult.Ok(value).fold(
            onOk = { observed ->
                successCount += 1
                assertSame(value, observed)
                "ok"
            },
            onFailure = {
                successFailureCount += 1
                "failure"
            },
        ))
        assertEquals(1, successCount)
        assertEquals(0, successFailureCount)

        failures.forEach { failure ->
            val result: HtspResult<Any> = failure
            var failureSuccessCount = 0
            var failureCount = 0

            assertEquals("failure", result.fold(
                onOk = {
                    failureSuccessCount += 1
                    "ok"
                },
                onFailure = { observed ->
                    failureCount += 1
                    assertSame(failure, observed)
                    "failure"
                },
            ))
            assertEquals(0, failureSuccessCount)
            assertEquals(1, failureCount)
        }
    }

    @Test
    fun mapTransformsOnlySuccessAndPreservesEveryFailureIdentity() {
        assertEquals(HtspResult.Ok(5), HtspResult.Ok("value").map(String::length))

        failures.forEach { failure ->
            val result: HtspResult<String> = failure
            var transformInvoked = false
            val mapped = result.map { value ->
                transformInvoked = true
                value.length
            }

            assertSame(failure, mapped)
            assertFalse(transformInvoked)
        }
    }

    @Test
    fun mapPropagatesEveryTransformThrowableExactly() {
        val runtimeFailure = IllegalStateException("consumer bug")
        assertExactThrowable(runtimeFailure) {
            HtspResult.Ok("value").map { throw runtimeFailure }
        }

        val otherRuntimeFailure = IllegalArgumentException("other runtime")
        assertExactThrowable(otherRuntimeFailure) {
            HtspResult.Ok("value").map { throw otherRuntimeFailure }
        }

        val cancellation = CancellationException("cancelled")
        assertExactThrowable(cancellation) {
            HtspResult.Ok("value").map { throw cancellation }
        }

        val error = AssertionError("fatal")
        assertExactThrowable(error) {
            HtspResult.Ok("value").map { throw error }
        }

        val nonRuntimeThrowable = Throwable("other")
        assertExactThrowable(nonRuntimeThrowable) {
            HtspResult.Ok("value").map { throw nonRuntimeThrowable }
        }
    }

    @Test
    fun callbacksPropagateTheirExactExceptionsWithoutSanitizing() {
        val getOrElseCancellation = CancellationException("getOrElse")
        assertExactThrowable(getOrElseCancellation) {
            HtspResult.AccessDenied.getOrElse { throw getOrElseCancellation }
        }

        val onOkFailure = IllegalStateException("onOk")
        assertExactThrowable(onOkFailure) {
            HtspResult.Ok("value").onOk { throw onOkFailure }
        }

        val onFailureFailure = Throwable("onFailure")
        assertExactThrowable(onFailureFailure) {
            HtspResult.Timeout.onFailure { throw onFailureFailure }
        }

        val foldOkFailure = CancellationException("fold ok")
        assertExactThrowable(foldOkFailure) {
            HtspResult.Ok("value").fold(
                onOk = { throw foldOkFailure },
                onFailure = { "failure" },
            )
        }

        val foldFailure = AssertionError("fold failure")
        assertExactThrowable(foldFailure) {
            HtspResult.NotSupported.fold(
                onOk = { "ok" },
                onFailure = { throw foldFailure },
            )
        }
    }

    private fun assertExactThrowable(expected: Throwable, block: () -> Unit) {
        val actual = runCatching(block).exceptionOrNull()

        assertTrue(actual != null, "Expected block to throw")
        assertSame(expected, actual)
    }
}
