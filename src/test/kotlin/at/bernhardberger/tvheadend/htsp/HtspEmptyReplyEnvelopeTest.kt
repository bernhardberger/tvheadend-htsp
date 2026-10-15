package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.requests.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class HtspEmptyReplyEnvelopeTest {
    private val requests = listOf(
        SubscriptionSkipRequest(7L, SubscriptionSeekPosition.Time(0L)) to HtspEmptyResponse,
        FileCloseRequest(7L) to FileCloseResponse,
    )

    @Test
    fun emptyAcknowledgementsAcceptValidatedSuccessEnvelope() {
        for ((request, response) in requests) {
            for (fields in listOf(emptyMap(), mapOf("seq" to 1L), mapOf("seq" to 1L, "noaccess" to 0L))) {
                assertEquals(HtspResult.Ok(response), classifyHtspReply(HtspWireReply(fields), request, 44L))
            }
        }
    }

    @Test
    fun emptyAcknowledgementsStillRejectUnexpectedPayloads() {
        for ((request, _) in requests) {
            for (key in listOf("unexpected", "success", "connlimit", "time", "id")) {
                val fields = mapOf("seq" to 1L, "noaccess" to 0L, key to 0L)
                assertEquals(HtspResult.MalformedReply, classifyHtspReply(HtspWireReply(fields), request, 44L), key)
            }
        }
    }

    @Test
    fun envelopeValidationStillPrecedesEmptyPayloadDecoding() {
        for ((request, _) in requests) {
            for (invalid in listOf(null, "0", false, -1L, 2L)) {
                assertEquals(
                    HtspResult.MalformedReply,
                    classifyHtspReply(HtspWireReply(mapOf("seq" to 1L, "noaccess" to invalid)), request, 44L),
                )
            }
            assertEquals(
                HtspResult.AccessDenied,
                classifyHtspReply(HtspWireReply(mapOf("seq" to 1L, "noaccess" to 1L)), request, 44L),
            )
            assertEquals(
                HtspResult.ServerError("server failure"),
                classifyHtspReply(
                    HtspWireReply(mapOf("seq" to 1L, "noaccess" to 0L, "error" to "server failure")), request, 44L,
                ),
            )
        }
    }
}
