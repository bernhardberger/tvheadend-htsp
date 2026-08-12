#!/usr/bin/env python3
"""Generate the reviewed 39-request HTSP convenience/catalog surface."""

from __future__ import annotations

import argparse
import tempfile
from dataclasses import dataclass, replace as dataclass_replace
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
OUTPUT = (
    SCRIPT_DIR.parents[1]
    / "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/GeneratedHtspExtensions.kt"
)


@dataclass(frozen=True)
class Parameter:
    name: str
    type: str
    default: str | None = None


@dataclass(frozen=True)
class Entry:
    method: str
    request: str
    response: str
    access: str
    minimum_version: int | None
    parameters: tuple[Parameter, ...] = ()


@dataclass(frozen=True)
class SelectorOverload:
    method: str
    parameters: tuple[Parameter, ...]
    request_arguments: tuple[str, ...]
    function_name: str | None = None


def parameter(name: str, type: str, default: str | None = None) -> Parameter:
    return Parameter(name, type, default)


# Reviewed constructor/type authority. Never derive this catalog from source literals.
CATALOG: tuple[Entry, ...] = (
    Entry("getProfiles", "GetProfilesRequest", "GetProfilesResponse", "ACCESS_HTSP_STREAMING", 16),
    Entry("getDiskSpace", "GetDiskSpaceRequest", "GetDiskSpaceResponse", "ACCESS_HTSP_STREAMING", 3),
    Entry("getSysTime", "GetSysTimeRequest", "GetSysTimeResponse", "ACCESS_HTSP_STREAMING", 3),
    Entry("enableAsyncMetadata", "EnableAsyncMetadataRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", None, (
        parameter("epg", "Long?", "null"),
        parameter("lastUpdate", "Long?", "null"),
        parameter("epgMaxTime", "Long?", "null"),
        parameter("language", "String?", "null"),
    )),
    Entry("getChannel", "GetChannelRequest", "GetChannelResponse", "ACCESS_HTSP_STREAMING", 14, (
        parameter("channelId", "Long"),
    )),
    Entry("getEvent", "GetEventRequest", "GetEventResponse", "ACCESS_HTSP_STREAMING", None, (
        parameter("eventId", "Long"),
        parameter("language", "String?", "null"),
    )),
    Entry("getEvents", "GetEventsRequest", "GetEventsResponse", "ACCESS_HTSP_STREAMING", 4, (
        parameter("channelId", "Long?", "null"),
        parameter("eventId", "Long?", "null"),
        parameter("language", "String?", "null"),
        parameter("numFollowing", "Long?", "null"),
        parameter("maxTime", "Long?", "null"),
    )),
    Entry("epgQuery", "EpgQueryRequest", "EpgQueryResponse", "ACCESS_HTSP_STREAMING", 4, (
        parameter("query", "String"),
        parameter("channelId", "Long?", "null"),
        parameter("tagId", "Long?", "null"),
        parameter("contentType", "Long?", "null"),
        parameter("language", "String?", "null"),
        parameter("fullText", "Boolean?", "null"),
        parameter("mergeText", "Boolean?", "null"),
        parameter("full", "Long?", "null"),
        parameter("minDurationSeconds", "Long?", "null"),
        parameter("maxDurationSeconds", "Long?", "null"),
    )),
    Entry("getEpgObject", "GetEpgObjectRequest", "GetEpgObjectResponse", "ACCESS_HTSP_STREAMING", None, (
        parameter("id", "Long"),
        parameter("objectType", "HtspEpgObjectType?", "HtspEpgObjectType.BROADCAST"),
    )),
    Entry("getDvrConfigs", "GetDvrConfigsRequest", "GetDvrConfigsResponse", "ACCESS_HTSP_RECORDER", 16),
    Entry("addDvrEntry", "AddDvrEntryRequest", "AddDvrEntryResponse", "ACCESS_HTSP_RECORDER", 4, (
        parameter("selector", "AddDvrEntrySelector"),
        parameter("configName", "String?", "null"),
        parameter("language", "String?", "null"),
        parameter("title", "String?", "null"),
        parameter("subtitle", "String?", "null"),
        parameter("summary", "String?", "null"),
        parameter("description", "String?", "null"),
        parameter("ageRating", "Long?", "null"),
    )),
    Entry("updateDvrEntry", "UpdateDvrEntryRequest", "UpdateDvrEntryResponse", "ACCESS_HTSP_RECORDER", 5, (
        parameter("entryId", "Long"),
        parameter("channelId", "Long?", "null"),
        parameter("configName", "String?", "null"),
        parameter("title", "String?", "null"),
        parameter("subtitle", "String?", "null"),
        parameter("summary", "String?", "null"),
        parameter("description", "String?", "null"),
        parameter("language", "String?", "null"),
        parameter("comment", "String?", "null"),
        parameter("playCount", "Long?", "null"),
        parameter("playPosition", "Long?", "null"),
        parameter("enabled", "Long?", "null"),
        parameter("start", "Long?", "null"),
        parameter("stop", "Long?", "null"),
        parameter("startExtra", "Long?", "null"),
        parameter("stopExtra", "Long?", "null"),
        parameter("retention", "Long?", "null"),
        parameter("removal", "Long?", "null"),
        parameter("priority", "Long?", "null"),
        parameter("ageRating", "Long?", "null"),
    )),
    Entry("stopDvrEntry", "StopDvrEntryRequest", "StopDvrEntryResponse", "ACCESS_HTSP_RECORDER", None, (
        parameter("entryId", "Long"),
    )),
    Entry("cancelDvrEntry", "CancelDvrEntryRequest", "CancelDvrEntryResponse", "ACCESS_HTSP_RECORDER", 5, (
        parameter("entryId", "Long"),
    )),
    Entry("deleteDvrEntry", "DeleteDvrEntryRequest", "DeleteDvrEntryResponse", "ACCESS_HTSP_RECORDER", 4, (
        parameter("entryId", "Long"),
    )),
    Entry("addAutorecEntry", "AddAutorecEntryRequest", "AddAutorecEntryResponse", "ACCESS_HTSP_RECORDER", 13, (
        parameter("title", "String"),
        parameter("channel", "HtspRecordingRuleChannel?", "null"),
        parameter("minDurationSeconds", "Long?", "null"),
        parameter("maxDurationSeconds", "Long?", "null"),
        parameter("fullText", "Long?", "null"),
        parameter("mergeText", "Long?", "null"),
        parameter("duplicateDetection", "Long?", "null"),
        parameter("maximumRecordingCount", "Long?", "null"),
        parameter("broadcastType", "Long?", "null"),
        parameter("startExtraMinutes", "Long?", "null"),
        parameter("stopExtraMinutes", "Long?", "null"),
        parameter("seriesLinkUri", "String?", "null"),
        parameter("approximateStartMinutesSinceMidnight", "Int?", "null"),
        parameter("startMinutesSinceMidnight", "Int?", "null"),
        parameter("startWindowEndMinutesSinceMidnight", "Int?", "null"),
        parameter("enabled", "Boolean?", "null"),
        parameter("retentionDays", "Long?", "null"),
        parameter("removalDays", "Long?", "null"),
        parameter("priority", "Long?", "null"),
        parameter("name", "String?", "null"),
        parameter("comment", "String?", "null"),
        parameter("directory", "String?", "null"),
        parameter("configName", "String?", "null"),
        parameter("daysOfWeekMask", "Long?", "null"),
    )),
    Entry("updateAutorecEntry", "UpdateAutorecEntryRequest", "UpdateAutorecEntryResponse", "ACCESS_HTSP_RECORDER", 25, (
        parameter("id", "String"),
        parameter("channel", "HtspRecordingRuleChannel?", "null"),
        parameter("minDurationSeconds", "Long?", "null"),
        parameter("maxDurationSeconds", "Long?", "null"),
        parameter("fullText", "Long?", "null"),
        parameter("mergeText", "Long?", "null"),
        parameter("duplicateDetection", "Long?", "null"),
        parameter("maximumRecordingCount", "Long?", "null"),
        parameter("broadcastType", "Long?", "null"),
        parameter("startExtraMinutes", "Long?", "null"),
        parameter("stopExtraMinutes", "Long?", "null"),
        parameter("seriesLinkUri", "String?", "null"),
        parameter("startMinutesSinceMidnight", "Int?", "null"),
        parameter("startWindowEndMinutesSinceMidnight", "Int?", "null"),
        parameter("enabled", "Boolean?", "null"),
        parameter("retentionDays", "Long?", "null"),
        parameter("removalDays", "Long?", "null"),
        parameter("priority", "Long?", "null"),
        parameter("name", "String?", "null"),
        parameter("comment", "String?", "null"),
        parameter("directory", "String?", "null"),
        parameter("title", "String?", "null"),
        parameter("configName", "String?", "null"),
        parameter("daysOfWeekMask", "Long?", "null"),
    )),
    Entry("deleteAutorecEntry", "DeleteAutorecEntryRequest", "DeleteAutorecEntryResponse", "ACCESS_HTSP_RECORDER", 13, (
        parameter("id", "String"),
    )),
    Entry("addTimerecEntry", "AddTimerecEntryRequest", "AddTimerecEntryResponse", "ACCESS_HTSP_RECORDER", 18, (
        parameter("title", "String"),
        parameter("channel", "HtspRecordingRuleChannel?", "null"),
        parameter("startMinutesSinceMidnight", "Long?", "null"),
        parameter("stopMinutesSinceMidnight", "Long?", "null"),
        parameter("enabled", "Boolean?", "null"),
        parameter("retentionDays", "Long?", "null"),
        parameter("removalDays", "Long?", "null"),
        parameter("priority", "Long?", "null"),
        parameter("name", "String?", "null"),
        parameter("comment", "String?", "null"),
        parameter("directory", "String?", "null"),
        parameter("configName", "String?", "null"),
        parameter("daysOfWeekMask", "Long?", "null"),
    )),
    Entry("updateTimerecEntry", "UpdateTimerecEntryRequest", "UpdateTimerecEntryResponse", "ACCESS_HTSP_RECORDER", 25, (
        parameter("id", "String"),
        parameter("channel", "HtspRecordingRuleChannel?", "null"),
        parameter("startMinutesSinceMidnight", "Long?", "null"),
        parameter("stopMinutesSinceMidnight", "Long?", "null"),
        parameter("enabled", "Boolean?", "null"),
        parameter("retentionDays", "Long?", "null"),
        parameter("removalDays", "Long?", "null"),
        parameter("priority", "Long?", "null"),
        parameter("name", "String?", "null"),
        parameter("comment", "String?", "null"),
        parameter("directory", "String?", "null"),
        parameter("title", "String?", "null"),
        parameter("configName", "String?", "null"),
        parameter("daysOfWeekMask", "Long?", "null"),
    )),
    Entry("deleteTimerecEntry", "DeleteTimerecEntryRequest", "DeleteTimerecEntryResponse", "ACCESS_HTSP_RECORDER", 18, (
        parameter("id", "String"),
    )),
    Entry("getDvrCutpoints", "GetDvrCutpointsRequest", "GetDvrCutpointsResponse", "ACCESS_HTSP_RECORDER", 12, (
        parameter("entryId", "Long"),
    )),
    Entry("getTicket", "GetTicketRequest", "GetTicketResponse", "ACCESS_HTSP_STREAMING", 5, (
        parameter("selector", "GetTicketSelector"),
    )),
    Entry("subscribe", "SubscribeRequest", "SubscribeResponse", "ACCESS_HTSP_STREAMING", None, (
        parameter("subscriptionId", "Long"),
        parameter("channel", "SubscribeChannel"),
        parameter("profile", "String?", "null"),
        parameter("weight", "Long?", "null"),
        parameter("ninetyKhz", "Long?", "null"),
        parameter("timeshiftPeriodSeconds", "Long?", "null"),
        parameter("queueDepth", "Long?", "null"),
    )),
    Entry("unsubscribe", "UnsubscribeRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", None, (
        parameter("subscriptionId", "Long"),
    )),
    Entry("subscriptionChangeWeight", "SubscriptionChangeWeightRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 5, (
        parameter("subscriptionId", "Long"),
        parameter("weight", "Long?", "null"),
    )),
    Entry("subscriptionSeek", "SubscriptionSeekRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 9, (
        parameter("subscriptionId", "Long"),
        parameter("position", "SubscriptionSeekPosition"),
        parameter("absolute", "Long?", "null"),
    )),
    Entry("subscriptionSkip", "SubscriptionSkipRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 9, (
        parameter("subscriptionId", "Long"),
        parameter("position", "SubscriptionSeekPosition"),
        parameter("absolute", "Long?", "null"),
    )),
    Entry("subscriptionSpeed", "SubscriptionSpeedRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 9, (
        parameter("subscriptionId", "Long"),
        parameter("speed", "Int"),
    )),
    Entry("subscriptionLive", "SubscriptionLiveRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 9, (
        parameter("subscriptionId", "Long"),
    )),
    Entry("subscriptionFilterStream", "SubscriptionFilterStreamRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 12, (
        parameter("subscriptionId", "Long"),
        parameter("enable", "List<Long>?", "null"),
        parameter("disable", "List<Long>?", "null"),
    )),
    Entry("fileOpen", "FileOpenRequest", "FileOpenResponse", "ACCESS_HTSP_RECORDER", 8, (
        parameter("file", "String"),
    )),
    Entry("fileRead", "FileReadRequest", "FileReadResponse", "ACCESS_HTSP_RECORDER", 8, (
        parameter("id", "Long"),
        parameter("size", "Long"),
        parameter("offset", "Long?", "null"),
    )),
    Entry("fileClose", "FileCloseRequest", "FileCloseResponse", "ACCESS_HTSP_RECORDER", 8, (
        parameter("id", "Long"),
    )),
    Entry("fileStat", "FileStatRequest", "FileStatResponse", "ACCESS_HTSP_RECORDER", 8, (
        parameter("id", "Long"),
    )),
    Entry("fileSeek", "FileSeekRequest", "FileSeekResponse", "ACCESS_HTSP_RECORDER", 8, (
        parameter("id", "Long"),
        parameter("offset", "Long"),
        parameter("whence", "FileSeekWhence?", "null"),
    )),
    Entry("api", "ApiRequest", "ApiResponse", "ACCESS_ANONYMOUS", 24, (
        parameter("path", "String"),
        parameter("args", "HtspApiObject?", "null"),
    )),
    Entry("hello", "HelloRequest", "HelloResponse", "ACCESS_ANONYMOUS", None, (
        parameter("htspVersion", "Long"),
        parameter("clientName", "String"),
    )),
    Entry("authenticate", "AuthenticateRequest", "AuthenticateResponse", "ACCESS_ANONYMOUS", None),
)


SELECTOR_OVERLOADS: tuple[SelectorOverload, ...] = (
    SelectorOverload("addDvrEntry", (
        parameter("eventId", "Long"),
        *CATALOG[10].parameters[1:],
    ), (
        "selector = AddDvrEntrySelector.Event(eventId)",
        *(f"{value.name} = {value.name}" for value in CATALOG[10].parameters[1:]),
    )),
    SelectorOverload("addDvrEntry", (
        parameter("channelId", "Long"),
        parameter("start", "Long"),
        parameter("stop", "Long"),
        *CATALOG[10].parameters[1:],
    ), (
        "selector = AddDvrEntrySelector.ExplicitChannelTime(channelId, start, stop)",
        *(f"{value.name} = {value.name}" for value in CATALOG[10].parameters[1:]),
    )),
    SelectorOverload("subscribe", (
        CATALOG[23].parameters[0],
        parameter("channelId", "Long"),
        *CATALOG[23].parameters[2:],
    ), (
        "subscriptionId = subscriptionId",
        "channel = SubscribeChannel.Id(channelId)",
        *(f"{value.name} = {value.name}" for value in CATALOG[23].parameters[2:]),
    )),
    SelectorOverload("subscribe", (
        CATALOG[23].parameters[0],
        parameter("channelName", "String"),
        *CATALOG[23].parameters[2:],
    ), (
        "subscriptionId = subscriptionId",
        "channel = SubscribeChannel.Name(channelName)",
        *(f"{value.name} = {value.name}" for value in CATALOG[23].parameters[2:]),
    )),
    SelectorOverload("subscriptionSeek", (
        CATALOG[26].parameters[0],
        parameter("position", "SubscriptionSeekPosition.Time"),
        *CATALOG[26].parameters[2:],
    ), tuple(f"{value.name} = {value.name}" for value in CATALOG[26].parameters)),
    SelectorOverload("subscriptionSeek", (
        CATALOG[26].parameters[0],
        parameter("position", "SubscriptionSeekPosition.Size"),
        *CATALOG[26].parameters[2:],
    ), tuple(f"{value.name} = {value.name}" for value in CATALOG[26].parameters)),
    SelectorOverload("subscriptionSkip", (
        CATALOG[27].parameters[0],
        parameter("position", "SubscriptionSeekPosition.Time"),
        *CATALOG[27].parameters[2:],
    ), tuple(f"{value.name} = {value.name}" for value in CATALOG[27].parameters)),
    SelectorOverload("subscriptionSkip", (
        CATALOG[27].parameters[0],
        parameter("position", "SubscriptionSeekPosition.Size"),
        *CATALOG[27].parameters[2:],
    ), tuple(f"{value.name} = {value.name}" for value in CATALOG[27].parameters)),
    SelectorOverload("getTicket", (
        parameter("selector", "GetTicketSelector.Channel"),
    ), ("selector = selector",)),
    SelectorOverload("getTicket", (
        parameter("selector", "GetTicketSelector.Dvr"),
    ), ("selector = selector",)),
    SelectorOverload("fileClose", (
        parameter("id", "Long"),
        parameter("playPositionSeconds", "Long?"),
        parameter("playCount", "Long?"),
    ), (
        "id = id",
        "playPositionSeconds = playPositionSeconds",
        "playCount = playCount",
    ), "fileCloseWithProgress"),
)


def render_extension(
    entry: Entry,
    parameters: tuple[Parameter, ...],
    request_arguments: tuple[str, ...],
    function_name: str | None = None,
) -> list[str]:
    lines = (["@HtspJsonApi"] if entry.method == "api" else []) + [
        f"public suspend fun HtspConnection.{function_name or entry.method}("
    ]
    for value in parameters:
        default = "" if value.default is None else f" = {value.default}"
        lines.append(f"    {value.name}: {value.type}{default},")
    lines.extend((
        "    timeoutMs: Long = 5_000L,",
        "    expectedGeneration: HtspConnectionGeneration? = null,",
        f"): HtspResult<{entry.response}> =",
        "    call(",
    ))
    if request_arguments:
        lines.append(f"        request = {entry.request}(")
        lines.extend(f"            {argument}," for argument in request_arguments)
        lines.append("        ),")
    else:
        lines.append(f"        request = {entry.request}(),")
    lines.extend((
        "        timeoutMs = timeoutMs,",
        "        expectedGeneration = expectedGeneration,",
        "    )",
        "",
    ))
    return lines


def render() -> str:
    lines = [
        "// Generated by docs/htsp-protocol/generate_typed_requests.py. DO NOT EDIT.",
        "// The reviewed catalog in that generator is the request/response constructor authority.",
        "package at.bernhardberger.tvheadend.htsp",
        "",
        "internal data class `TypedHtspRequestCatalogEntry-internal`(",
        "    val method: String,",
        "    val requestType: String,",
        "    val responseType: String,",
        "    val access: HtspAccess,",
        "    val minimumProtocolVersion: Int?,",
        ")",
        "",
        "internal typealias TypedHtspRequestCatalogEntry =",
        "    `TypedHtspRequestCatalogEntry-internal`",
        "",
        "@get:JvmSynthetic",
        "internal val typedHtspRequestCatalog: List<TypedHtspRequestCatalogEntry> = listOf(",
    ]
    for entry in CATALOG:
        version = "null" if entry.minimum_version is None else str(entry.minimum_version)
        lines.append(
            f'    TypedHtspRequestCatalogEntry("{entry.method}", "{entry.request}", '
            f'"{entry.response}", HtspAccess.{entry.access}, {version}),'
        )
    lines.extend((")", ""))
    for entry in CATALOG:
        request_arguments = tuple(f"{value.name} = {value.name}" for value in entry.parameters)
        lines.extend(render_extension(entry, entry.parameters, request_arguments))
        for overload in SELECTOR_OVERLOADS:
            if overload.method == entry.method:
                lines.extend(
                    render_extension(
                        entry,
                        overload.parameters,
                        overload.request_arguments,
                        overload.function_name,
                    )
                )
    return "\n".join(lines)


def validate_file_close_progress_overload(
    overloads: tuple[SelectorOverload, ...],
) -> None:
    matching = tuple(
        overload for overload in overloads
        if overload.function_name == "fileCloseWithProgress"
    )
    if matching != (
        SelectorOverload(
            method="fileClose",
            parameters=(
                parameter("id", "Long"),
                parameter("playPositionSeconds", "Long?"),
                parameter("playCount", "Long?"),
            ),
            request_arguments=(
                "id = id",
                "playPositionSeconds = playPositionSeconds",
                "playCount = playCount",
            ),
            function_name="fileCloseWithProgress",
        ),
    ):
        raise ValueError("fileCloseWithProgress must preserve its exact reviewed overload mapping")


def validate_catalog() -> None:
    expected = (
        "getProfiles", "getDiskSpace", "getSysTime", "enableAsyncMetadata", "getChannel", "getEvent",
        "getEvents", "epgQuery", "getEpgObject", "getDvrConfigs", "addDvrEntry", "updateDvrEntry", "stopDvrEntry",
        "cancelDvrEntry", "deleteDvrEntry", "addAutorecEntry", "updateAutorecEntry",
        "deleteAutorecEntry", "addTimerecEntry", "updateTimerecEntry", "deleteTimerecEntry",
        "getDvrCutpoints", "getTicket", "subscribe", "unsubscribe",
        "subscriptionChangeWeight", "subscriptionSeek", "subscriptionSkip", "subscriptionSpeed",
        "subscriptionLive", "subscriptionFilterStream", "fileOpen", "fileRead", "fileClose",
        "fileStat", "fileSeek", "api", "hello", "authenticate",
    )
    methods = tuple(entry.method for entry in CATALOG)
    if methods != expected or len(set(methods)) != 39:
        raise ValueError("typed request catalog must contain exactly the reviewed 39 methods")
    epg_query = CATALOG[7]
    if epg_query != Entry(
        "epgQuery", "EpgQueryRequest", "EpgQueryResponse", "ACCESS_HTSP_STREAMING", 4, (
            parameter("query", "String"),
            parameter("channelId", "Long?", "null"),
            parameter("tagId", "Long?", "null"),
            parameter("contentType", "Long?", "null"),
            parameter("language", "String?", "null"),
            parameter("fullText", "Boolean?", "null"),
            parameter("mergeText", "Boolean?", "null"),
            parameter("full", "Long?", "null"),
            parameter("minDurationSeconds", "Long?", "null"),
            parameter("maxDurationSeconds", "Long?", "null"),
        ),
    ):
        raise ValueError("epgQuery catalog entry must preserve the reviewed constructor contract")
    get_epg_object = CATALOG[8]
    if get_epg_object != Entry(
        "getEpgObject", "GetEpgObjectRequest", "GetEpgObjectResponse", "ACCESS_HTSP_STREAMING", None, (
            parameter("id", "Long"),
            parameter("objectType", "HtspEpgObjectType?", "HtspEpgObjectType.BROADCAST"),
        ),
    ):
        raise ValueError("getEpgObject catalog entry must preserve the reviewed constructor contract")
    get_ticket = CATALOG[22]
    if get_ticket != Entry(
        "getTicket", "GetTicketRequest", "GetTicketResponse", "ACCESS_HTSP_STREAMING", 5, (
            parameter("selector", "GetTicketSelector"),
        ),
    ):
        raise ValueError("getTicket catalog entry must preserve the reviewed constructor contract")
    subscription_skip = CATALOG[27]
    if subscription_skip != Entry(
        "subscriptionSkip", "SubscriptionSkipRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 9, (
            parameter("subscriptionId", "Long"),
            parameter("position", "SubscriptionSeekPosition"),
            parameter("absolute", "Long?", "null"),
        ),
    ):
        raise ValueError("subscriptionSkip catalog entry must preserve the reviewed constructor contract")
    file_operations = CATALOG[31:36]
    if file_operations != (
        Entry("fileOpen", "FileOpenRequest", "FileOpenResponse", "ACCESS_HTSP_RECORDER", 8, (
            parameter("file", "String"),
        )),
        Entry("fileRead", "FileReadRequest", "FileReadResponse", "ACCESS_HTSP_RECORDER", 8, (
            parameter("id", "Long"),
            parameter("size", "Long"),
            parameter("offset", "Long?", "null"),
        )),
        Entry("fileClose", "FileCloseRequest", "FileCloseResponse", "ACCESS_HTSP_RECORDER", 8, (
            parameter("id", "Long"),
        )),
        Entry("fileStat", "FileStatRequest", "FileStatResponse", "ACCESS_HTSP_RECORDER", 8, (
            parameter("id", "Long"),
        )),
        Entry("fileSeek", "FileSeekRequest", "FileSeekResponse", "ACCESS_HTSP_RECORDER", 8, (
            parameter("id", "Long"),
            parameter("offset", "Long"),
            parameter("whence", "FileSeekWhence?", "null"),
        )),
    ):
        raise ValueError("file operation catalog entries must preserve the reviewed constructor contract")
    api_bridge = CATALOG[36]
    if api_bridge != Entry("api", "ApiRequest", "ApiResponse", "ACCESS_ANONYMOUS", 24, (
        parameter("path", "String"),
        parameter("args", "HtspApiObject?", "null"),
    )):
        raise ValueError("api catalog entry must preserve the reviewed constructor contract")
    handshake = CATALOG[37:39]
    if handshake != (
        Entry("hello", "HelloRequest", "HelloResponse", "ACCESS_ANONYMOUS", None, (
            parameter("htspVersion", "Long"),
            parameter("clientName", "String"),
        )),
        Entry("authenticate", "AuthenticateRequest", "AuthenticateResponse", "ACCESS_ANONYMOUS", None),
    ):
        raise ValueError("handshake catalog entries must preserve the reviewed constructor contract")
    if tuple((overload.method, overload.function_name) for overload in SELECTOR_OVERLOADS) != (
        ("addDvrEntry", None), ("addDvrEntry", None), ("subscribe", None), ("subscribe", None),
        ("subscriptionSeek", None), ("subscriptionSeek", None),
        ("subscriptionSkip", None), ("subscriptionSkip", None),
        ("getTicket", None), ("getTicket", None), ("fileClose", "fileCloseWithProgress"),
    ):
        raise ValueError("typed request overload catalog must contain exactly eleven reviewed cases")
    validate_file_close_progress_overload(SELECTOR_OVERLOADS)


def self_test() -> None:
    validate_catalog()
    file_close_progress = SELECTOR_OVERLOADS[-1]
    invalid_file_close_progress_catalogs = (
        SELECTOR_OVERLOADS[:-1] + (
            dataclass_replace(
                file_close_progress,
                parameters=(
                    parameter("id", "Long"),
                    parameter("playCount", "Long?"),
                    parameter("playPositionSeconds", "Long?"),
                ),
            ),
        ),
        SELECTOR_OVERLOADS[:-1] + (
            dataclass_replace(
                file_close_progress,
                request_arguments=(
                    "id = id",
                    "playCount = playCount",
                    "playPositionSeconds = playPositionSeconds",
                ),
            ),
        ),
        SELECTOR_OVERLOADS[:-1] + (
            dataclass_replace(
                file_close_progress,
                request_arguments=("id = id", "playPositionSeconds = playPositionSeconds"),
            ),
        ),
        SELECTOR_OVERLOADS[:-1] + (
            dataclass_replace(
                file_close_progress,
                request_arguments=(
                    "id = id",
                    "playPositionSeconds = playPositionSeconds",
                    "playPositionSeconds = playPositionSeconds",
                    "playCount = playCount",
                ),
            ),
        ),
        SELECTOR_OVERLOADS[:-1] + (
            dataclass_replace(
                file_close_progress,
                request_arguments=(
                    "id = id",
                    "playPosition = playPositionSeconds",
                    "playCount = playCount",
                ),
            ),
        ),
        SELECTOR_OVERLOADS[:-1] + (dataclass_replace(file_close_progress, method="fileRead"),),
        SELECTOR_OVERLOADS[:-1] + (
            dataclass_replace(file_close_progress, function_name="fileCloseProgress"),
        ),
    )
    for invalid_overloads in invalid_file_close_progress_catalogs:
        try:
            validate_file_close_progress_overload(invalid_overloads)
        except ValueError:
            pass
        else:
            raise AssertionError("fileCloseWithProgress mapping mutation was accepted")
    first = render()
    second = render()
    if first != second:
        raise AssertionError("generator output is not deterministic")
    extension_lines = [line for line in first.splitlines() if line.startswith("public suspend fun")]
    if len(extension_lines) != 50:
        raise AssertionError("generated extensions must contain 39 canonical and 11 reviewed overloads")
    if "    request:" in first:
        raise AssertionError("generated extensions must not accept request objects")
    if first.count("timeoutMs: Long = 5_000L") != 50:
        raise AssertionError("every generated extension must expose the default request timeout")
    if first.count("expectedGeneration: HtspConnectionGeneration? = null") != 50:
        raise AssertionError("every generated extension must expose the optional generation fence")
    required_signatures = (
        "public suspend fun HtspConnection.getEvents(\n    channelId: Long? = null,",
        "public suspend fun HtspConnection.epgQuery(\n    query: String,\n    channelId: Long? = null,",
        "public suspend fun HtspConnection.getEpgObject(\n    id: Long,\n    objectType: HtspEpgObjectType? = HtspEpgObjectType.BROADCAST,",
        "public suspend fun HtspConnection.getTicket(\n    selector: GetTicketSelector,",
        "public suspend fun HtspConnection.getTicket(\n    selector: GetTicketSelector.Channel,",
        "public suspend fun HtspConnection.getTicket(\n    selector: GetTicketSelector.Dvr,",
        "public suspend fun HtspConnection.fileStat(\n    id: Long,",
        "public suspend fun HtspConnection.fileOpen(\n    file: String,",
        "public suspend fun HtspConnection.fileRead(\n    id: Long,\n    size: Long,\n    offset: Long? = null,",
        "public suspend fun HtspConnection.fileClose(\n    id: Long,",
        "public suspend fun HtspConnection.fileCloseWithProgress(\n    id: Long,\n    playPositionSeconds: Long?,\n    playCount: Long?,",
        "public suspend fun HtspConnection.fileSeek(\n    id: Long,\n    offset: Long,\n    whence: FileSeekWhence? = null,",
        "@HtspJsonApi\npublic suspend fun HtspConnection.api(\n    path: String,\n    args: HtspApiObject? = null,",
        "    fullText: Boolean? = null,\n    mergeText: Boolean? = null,\n    full: Long? = null,\n    minDurationSeconds: Long? = null,\n    maxDurationSeconds: Long? = null,",
        "public suspend fun HtspConnection.addDvrEntry(\n    selector: AddDvrEntrySelector,",
        "    selector: AddDvrEntrySelector,\n    configName: String? = null,\n    language: String? = null,\n    title: String? = null,",
        "public suspend fun HtspConnection.addDvrEntry(\n    eventId: Long,",
        "public suspend fun HtspConnection.addDvrEntry(\n    channelId: Long,\n    start: Long,\n    stop: Long,",
        "public suspend fun HtspConnection.subscribe(\n    subscriptionId: Long,\n    channel: SubscribeChannel,",
        "    channel: SubscribeChannel,\n    profile: String? = null,\n    weight: Long? = null,\n    ninetyKhz: Long? = null,\n    timeshiftPeriodSeconds: Long? = null,\n    queueDepth: Long? = null,",
        "public suspend fun HtspConnection.subscribe(\n    subscriptionId: Long,\n    channelId: Long,",
        "public suspend fun HtspConnection.subscribe(\n    subscriptionId: Long,\n    channelName: String,",
        "public suspend fun HtspConnection.subscriptionSeek(\n    subscriptionId: Long,\n    position: SubscriptionSeekPosition,",
        "public suspend fun HtspConnection.subscriptionSeek(\n    subscriptionId: Long,\n    position: SubscriptionSeekPosition.Time,",
        "public suspend fun HtspConnection.subscriptionSeek(\n    subscriptionId: Long,\n    position: SubscriptionSeekPosition.Size,",
        "public suspend fun HtspConnection.subscriptionSkip(\n    subscriptionId: Long,\n    position: SubscriptionSeekPosition,",
        "public suspend fun HtspConnection.subscriptionSkip(\n    subscriptionId: Long,\n    position: SubscriptionSeekPosition.Time,",
        "public suspend fun HtspConnection.subscriptionSkip(\n    subscriptionId: Long,\n    position: SubscriptionSeekPosition.Size,",
        "public suspend fun HtspConnection.updateDvrEntry(\n    entryId: Long,\n    channelId: Long? = null,\n    configName: String? = null,\n    title: String? = null,",
        "    comment: String? = null,\n    playCount: Long? = null,\n    playPosition: Long? = null,\n    enabled: Long? = null,\n    start: Long? = null,",
        "public suspend fun HtspConnection.addAutorecEntry(\n    title: String,\n    channel: HtspRecordingRuleChannel? = null,",
        "public suspend fun HtspConnection.updateAutorecEntry(\n    id: String,\n    channel: HtspRecordingRuleChannel? = null,",
        "public suspend fun HtspConnection.deleteAutorecEntry(\n    id: String,",
        "public suspend fun HtspConnection.addTimerecEntry(\n    title: String,\n    channel: HtspRecordingRuleChannel? = null,",
        "public suspend fun HtspConnection.updateTimerecEntry(\n    id: String,\n    channel: HtspRecordingRuleChannel? = null,",
        "public suspend fun HtspConnection.deleteTimerecEntry(\n    id: String,",
        "public suspend fun HtspConnection.hello(\n    htspVersion: Long,\n    clientName: String,",
        "public suspend fun HtspConnection.authenticate(\n    timeoutMs: Long = 5_000L,",
    )
    missing = [signature for signature in required_signatures if signature not in first]
    if missing:
        raise AssertionError(f"generated extension signatures are incomplete: {missing}")
    if first.count("    call(") != 50:
        raise AssertionError("every generated extension must contain exactly one call delegation")
    if first.count("        timeoutMs = timeoutMs,") != 50:
        raise AssertionError("every generated extension must forward timeout exactly once")
    if first.count("        expectedGeneration = expectedGeneration,") != 50:
        raise AssertionError("every generated extension must forward generation exactly once")
    if first.count("request = SubscriptionSkipRequest(") != 3:
        raise AssertionError("subscriptionSkip must emit one canonical and two selector overload request constructions")
    if first.count("request = HelloRequest(") != 1 or first.count("request = AuthenticateRequest(),") != 1:
        raise AssertionError("handshake extensions must each construct exactly one finite request")
    if first.count("request = ApiRequest(") != 1 or first.count("@HtspJsonApi") != 1:
        raise AssertionError("api extension must construct one finite request and require its opt-in")
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "generated.kt"
        path.write_text(first, encoding="utf-8")
        if path.read_text(encoding="utf-8") != first:
            raise AssertionError("temporary output round-trip drifted")


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    validate_catalog()
    if args.self_test:
        self_test()
        return 0
    generated = render()
    if args.write:
        OUTPUT.write_text(generated, encoding="utf-8")
        return 0
    if not OUTPUT.is_file() or OUTPUT.read_text(encoding="utf-8") != generated:
        raise SystemExit(f"generated typed HTSP request source is stale: {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
