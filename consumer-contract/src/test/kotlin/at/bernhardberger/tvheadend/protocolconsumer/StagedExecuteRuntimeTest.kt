package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.requests.GetSysTimeRequest
import at.bernhardberger.tvheadend.htsp.requests.GetSysTimeResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspRequest
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StagedExecuteRuntimeTest {
    @Test
    fun convenienceRequestUsesFakeExecuteMember(): Unit = runBlocking {
        val owner = createHtspConnection(Dispatchers.Unconfined)
        val expected = GetSysTimeResponse(
            unixTimeSeconds = 1_723_456_789L,
            legacyTimezoneHoursWestOfGmt = -2,
            gmtOffsetMinutes = 120,
        )
        val connection = object : HtspConnection by owner {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(
                request: HtspRequest<R>,
                timeoutMs: Long,
                expectedGeneration: HtspConnectionGeneration?,
            ): HtspResult<R> {
                assertTrue(request is GetSysTimeRequest)
                assertEquals(5_000L, timeoutMs)
                assertEquals(null, expectedGeneration)
                return HtspResult.Ok(expected) as HtspResult<R>
            }
        }

        try {
            assertEquals(HtspResult.Ok(expected), connection.getSysTime())
        } finally {
            connection.close()
        }
    }
}
