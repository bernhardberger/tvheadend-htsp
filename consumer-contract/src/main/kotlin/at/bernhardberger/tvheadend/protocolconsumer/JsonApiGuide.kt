package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspFailure
import at.bernhardberger.tvheadend.htsp.connection.HtspLiveConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.jsonapi.ApiResponse
import at.bernhardberger.tvheadend.htsp.jsonapi.HtspApiList
import at.bernhardberger.tvheadend.htsp.jsonapi.HtspApiObject
import at.bernhardberger.tvheadend.htsp.jsonapi.HtspApiString
import at.bernhardberger.tvheadend.htsp.jsonapi.HtspJsonApi
import at.bernhardberger.tvheadend.htsp.jsonapi.api

// region server-info
@OptIn(HtspJsonApi::class)
suspend fun serverInfoName(connection: HtspConnection, live: HtspLiveConnection): String? =
    when (val result = connection.api("serverinfo", expectedGeneration = live.generation)) {
        is HtspResult.Ok -> when (val response = result.value) {
            is ApiResponse.Payload ->
                ((response.value as? HtspApiObject)?.get("name") as? HtspApiString)?.value
            ApiResponse.NoPayload -> null
        }
        is HtspFailure -> null
    }
// endregion

// region path-list
@OptIn(HtspJsonApi::class)
suspend fun apiPaths(connection: HtspConnection, live: HtspLiveConnection): List<String>? {
    val result = connection.api("pathlist", expectedGeneration = live.generation)
    val payload = (result as? HtspResult.Ok)?.value as? ApiResponse.Payload ?: return null
    val list = payload.value as? HtspApiList ?: return null
    return List(list.size) { index -> (list[index] as? HtspApiString)?.value ?: return null }
}
// endregion
