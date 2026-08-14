#!/usr/bin/env python3
"""Reviewed, stdlib-only authority for the generated Kotlin HTSP surface.

The objects in this module are deliberately semantic catalog data.  They are
not parsed from Kotlin and they do not contain a whole-source Kotlin fallback.
G3 can iterate ``SERVER_WIRE_FIELDS`` without understanding the renderer.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class KotlinProperty:
    name: str
    kotlin_type: str
    default: str | None = None
    stored: bool = True
    visibility: str | None = "public"


@dataclass(frozen=True)
class SnapshotFeature:
    property: str
    kotlin_type: str
    expression: str
    visibility: str = "public"


@dataclass(frozen=True)
class ValidationFeature:
    feature: str
    fields: tuple[tuple[str, str], ...] = ()
    detail: str = ""


@dataclass(frozen=True)
class EqualityFeature:
    feature: str
    source_parameter: str
    backing_property: str
    accessor: str
    accessor_kdoc: str
    to_string_label: str


@dataclass(frozen=True)
class KotlinDeclaration:
    name: str
    kind: str
    properties: tuple[KotlinProperty, ...] = ()
    kdoc: str | None = None
    supertype: str | None = None
    snapshots: tuple[SnapshotFeature, ...] = ()
    validations: tuple[ValidationFeature, ...] = ()
    equality: EqualityFeature | None = None
    visibility: str = "public"


@dataclass(frozen=True)
class WireField:
    owner: str
    kotlin_property: str
    wire_names: tuple[str, ...]
    wire_type: str
    presence: str
    decoder: str
    # An explicit field-level protocol gate from the checked-in v44 evidence.
    # Entry-wide availability remains on ServerMessageEntry; it is not copied
    # onto every field.
    minimum_version: int | None = None
    nested_shape: str | None = None
    nested_wire_names: tuple[str, ...] = ()
    lenient_malformed: bool = False
    direction: str = "server-to-client"


@dataclass(frozen=True)
class NestedShape:
    name: str
    kotlin_type: str
    decoder: str
    fields: tuple[WireField, ...]


@dataclass(frozen=True)
class ServerMessageEntry:
    method: str
    message_type: str
    decoder: str
    minimum_version: int | None
    fields: tuple[WireField, ...]


def P(name: str, kotlin_type: str, default: str | None = None, *, stored: bool = True) -> KotlinProperty:
    return KotlinProperty(name, kotlin_type, default, stored, "public" if stored else None)


def S(property: str, kotlin_type: str, expression: str) -> SnapshotFeature:
    return SnapshotFeature(property, kotlin_type, expression)


def V(feature: str, *fields: tuple[str, str], detail: str = "") -> ValidationFeature:
    return ValidationFeature(feature, tuple(fields), detail)


def W(
    owner: str,
    kotlin_property: str,
    wire_type: str,
    presence: str,
    decoder: str,
    *wire_names: str,
    minimum_version: int | None = None,
    nested_shape: str | None = None,
    nested_wire_names: tuple[str, ...] = (),
    lenient_malformed: bool = False,
) -> WireField:
    return WireField(
        owner=owner,
        kotlin_property=kotlin_property,
        wire_names=tuple(wire_names),
        wire_type=wire_type,
        presence=presence,
        decoder=decoder,
        minimum_version=minimum_version,
        nested_shape=nested_shape,
        nested_wire_names=nested_wire_names,
        lenient_malformed=lenient_malformed,
    )


U32 = "u32"
S64 = "s64"
S32 = "s32"
STR = "str"
BIN = "bin"
FLAG = "bool"
LIST = "list"
MAP = "map"
REQ = "required"
OPT = "optional"


CHANNEL_NUMBER_KEYS = ("channelNumber", "number", "lcn", "channelNum", "channelno")
CHANNEL_TAG_KEYS = ("tagIds", "tags", "channelTags")
TAG_ID_KEYS = ("tagId", "id")
TAG_NAME_KEYS = ("tagName", "name")
TAG_INDEX_KEYS = ("tagIndex", "index")
DVR_ID_KEYS = ("id", "dvrId")
DVR_CHANNEL_KEYS = ("channelId", "channel")
DVR_ERROR_KEYS = ("error", "statusError")
DVR_PLAY_POSITION_KEYS = ("playposition", "playPosition")
DVR_PLAY_COUNT_KEYS = ("playcount", "playCount")
DVR_FILE_PATH_KEYS = ("filename", "path")
EVENT_ID_KEYS = ("eventId", "id")
EVENT_CHANNEL_KEYS = ("channelId", "channel")
EVENT_START_KEYS = ("start", "startTime")
EVENT_STOP_KEYS = ("stop", "stopTime")
EVENT_TITLE_KEYS = ("title", "eventTitle", "name")
EVENT_CONTENT_KEYS = ("contentType", "content")
SUBSCRIPTION_ID_KEYS = ("subscriptionId", "id")
STATUS_KEYS = ("state", "status")
SUBSCRIPTION_ERROR_KEYS = ("subscriptionError", "error")

SERVER_ALIAS_CONSTANTS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("CHANNEL_NUMBER_KEYS", CHANNEL_NUMBER_KEYS),
    ("CHANNEL_TAG_KEYS", CHANNEL_TAG_KEYS),
    ("TAG_ID_KEYS", TAG_ID_KEYS),
    ("TAG_NAME_KEYS", TAG_NAME_KEYS),
    ("TAG_INDEX_KEYS", TAG_INDEX_KEYS),
    ("DVR_ID_KEYS", DVR_ID_KEYS),
    ("DVR_CHANNEL_KEYS", DVR_CHANNEL_KEYS),
    ("DVR_ERROR_KEYS", DVR_ERROR_KEYS),
    ("DVR_PLAY_POSITION_KEYS", DVR_PLAY_POSITION_KEYS),
    ("DVR_PLAY_COUNT_KEYS", DVR_PLAY_COUNT_KEYS),
    ("DVR_FILE_PATH_KEYS", DVR_FILE_PATH_KEYS),
    ("EVENT_ID_KEYS", EVENT_ID_KEYS),
    ("EVENT_CHANNEL_KEYS", EVENT_CHANNEL_KEYS),
    ("EVENT_START_KEYS", EVENT_START_KEYS),
    ("EVENT_STOP_KEYS", EVENT_STOP_KEYS),
    ("EVENT_TITLE_KEYS", EVENT_TITLE_KEYS),
    ("EVENT_CONTENT_KEYS", EVENT_CONTENT_KEYS),
    ("SUBSCRIPTION_ID_KEYS", SUBSCRIPTION_ID_KEYS),
    ("STATUS_KEYS", STATUS_KEYS),
    ("SUBSCRIPTION_ERROR_KEYS", SUBSCRIPTION_ERROR_KEYS),
)


CHANNEL_PROPERTIES = (
    P("channelId", "Long"), P("channelName", "String?", "null"),
    P("channelUuid", "String?", "null"), P("channelNumber", "Long?", "null"),
    P("channelNumberMinor", "Long?", "null"), P("channelIcon", "String?", "null"),
    P("currentEventId", "Long?", "null"), P("nextEventId", "Long?", "null"),
    P("services", "List<HtspChannelService>?", "null", stored=False),
    P("tagIds", "List<Long>?", "null", stored=False),
)
TAG_PROPERTIES = (
    P("tagId", "Long"), P("tagName", "String?", "null"), P("tagUuid", "String?", "null"),
    P("tagIndex", "Long?", "null"), P("tagIcon", "String?", "null"),
    P("tagTitledIcon", "Long?", "null"), P("channelIds", "List<Long>?", "null", stored=False),
)
DVR_PROPERTIES = (
    P("entryId", "Long"), P("entryUuid", "String?", "null"), P("enabled", "Long?", "null"),
    P("channelId", "Long?", "null"), P("channelName", "String?", "null"),
    P("eventId", "Long?", "null"), P("autorecEntryUuid", "String?", "null"),
    P("timerecEntryUuid", "String?", "null"), P("start", "Long?", "null"),
    P("stop", "Long?", "null"), P("startExtraMinutes", "Long?", "null"),
    P("stopExtraMinutes", "Long?", "null"), P("retentionDays", "Long?", "null"),
    P("removalDays", "Long?", "null"), P("priority", "Long?", "null"),
    P("contentType", "Long?", "null"), P("ageRating", "Long?", "null"),
    P("ratingLabel", "String?", "null"), P("ratingIcon", "String?", "null"),
    P("ratingAuthority", "String?", "null"), P("ratingCountry", "String?", "null"),
    P("playCount", "Long?", "null"), P("playPositionSeconds", "Long?", "null"),
    P("seasonNumber", "Long?", "null"), P("episodeNumber", "Long?", "null"),
    P("episodeCount", "Long?", "null"), P("partNumber", "Long?", "null"),
    P("partCount", "Long?", "null"), P("title", "String?", "null"),
    P("description", "String?", "null"), P("summary", "String?", "null"),
    P("subtitle", "String?", "null"), P("owner", "String?", "null"),
    P("creator", "String?", "null"), P("comment", "String?", "null"),
    P("image", "String?", "null"), P("fanartImage", "String?", "null"),
    P("copyrightYear", "Long?", "null"), P("files", "List<HtspDvrRecordingFile>?", "null", stored=False),
    P("path", "String?", "null"), P("dvrConfigUuid", "String?", "null"),
    P("duplicate", "Long?", "null"), P("state", "String?", "null"),
    P("error", "String?", "null"), P("subscriptionError", "String?", "null"),
    P("streamErrors", "Long?", "null"), P("dataErrors", "Long?", "null"),
    P("dataSizeBytes", "Long?", "null"),
)
DVR_U32 = tuple((name, name) for name in (
    "enabled", "channelId", "eventId", "retentionDays", "removalDays", "priority",
    "contentType", "ageRating", "playCount", "playPositionSeconds", "seasonNumber",
    "episodeNumber", "episodeCount", "partNumber", "partCount", "copyrightYear",
    "duplicate", "streamErrors", "dataErrors",
))
AUTOREC_ADD_PROPERTIES = (
    P("id", "String"), P("enabled", "Boolean"), P("maxDurationSeconds", "Long"),
    P("minDurationSeconds", "Long"), P("retentionDays", "Long"), P("removalDays", "Long"),
    P("daysOfWeekMask", "Long"), P("approximateStartMinutesSinceMidnight", "Int"),
    P("startMinutesSinceMidnight", "Int"), P("startWindowEndMinutesSinceMidnight", "Int"),
    P("priority", "Long"), P("startExtraMinutes", "Long"), P("stopExtraMinutes", "Long"),
    P("duplicateDetection", "Long"), P("maximumRecordingCount", "Long"),
    P("broadcastType", "Long"), P("comment", "String"), P("title", "String?", "null"),
    P("fullText", "Boolean?", "null"), P("mergeText", "Boolean?", "null"), P("name", "String"),
    P("directory", "String?", "null"), P("owner", "String"), P("creator", "String"),
    P("channelId", "Long?", "null"), P("seriesLinkUri", "String?", "null"),
    P("configId", "String?", "null"),
)
AUTOREC_UPDATE_PROPERTIES = tuple(
    P(p.name, p.kotlin_type if p.name == "id" else p.kotlin_type.removesuffix("?") + "?", None if p.name == "id" else "null")
    for p in AUTOREC_ADD_PROPERTIES
)
AUTOREC_U32 = tuple((name, name) for name in (
    "maxDurationSeconds", "minDurationSeconds", "retentionDays", "removalDays",
    "daysOfWeekMask", "priority", "duplicateDetection", "maximumRecordingCount",
    "broadcastType", "channelId",
))
TIMEREC_ADD_PROPERTIES = (
    P("id", "String"), P("enabled", "Boolean"), P("name", "String"), P("title", "String"),
    P("channelId", "Int"), P("startMinutesSinceMidnight", "Int"),
    P("stopMinutesSinceMidnight", "Int"), P("daysOfWeekMask", "Long?", "null"),
    P("priority", "Long?", "null"), P("retentionDays", "Long?", "null"),
    P("directory", "String?", "null"), P("owner", "String?", "null"),
    P("creator", "String?", "null"), P("configId", "String?", "null"),
    P("comment", "String?", "null"),
)
TIMEREC_UPDATE_PROPERTIES = tuple(
    P(p.name, p.kotlin_type if p.name == "id" else p.kotlin_type.removesuffix("?") + "?", None if p.name == "id" else "null")
    for p in TIMEREC_ADD_PROPERTIES
)
EVENT_UPDATE_PROPERTIES = (
    P("eventId", "Long"), P("channelId", "Long?", "null"), P("start", "Long?", "null"),
    P("stop", "Long?", "null"), P("title", "String?", "null"), P("subtitle", "String?", "null"),
    P("summary", "String?", "null"), P("description", "String?", "null"), P("genre", "String?", "null"),
    P("categories", "List<String>?", "null", stored=False), P("keywords", "List<String>?", "null", stored=False),
    P("seriesLinkUri", "String?", "null"), P("episodeUri", "String?", "null"),
    P("contentType", "Long?", "null"), P("ageRating", "Long?", "null"),
    P("ratingLabel", "String?", "null"), P("ratingIcon", "String?", "null"),
    P("ratingAuthority", "String?", "null"), P("ratingCountry", "String?", "null"),
    P("starRating", "Long?", "null"), P("copyrightYear", "Long?", "null"),
    P("firstAired", "Long?", "null"), P("isNew", "Long?", "null"),
    P("seasonNumber", "Long?", "null"), P("seasonCount", "Long?", "null"),
    P("episodeNumber", "Long?", "null"), P("episodeCount", "Long?", "null"),
    P("partNumber", "Long?", "null"), P("partCount", "Long?", "null"),
    P("episodeOnscreen", "String?", "null"), P("episodeId", "Long?", "null"),
    P("seriesLinkId", "Long?", "null"), P("image", "String?", "null"),
    P("dvrId", "Long?", "null"), P("nextEventId", "Long?", "null"),
)
EVENT_U32 = tuple((name, name) for name in (
    "channelId", "contentType", "ageRating", "starRating", "copyrightYear", "isNew",
    "seasonNumber", "seasonCount", "episodeNumber", "episodeCount", "partNumber",
    "partCount", "episodeId", "seriesLinkId", "dvrId", "nextEventId",
))

SERVER_DECLARATIONS: tuple[KotlinDeclaration, ...] = (
    KotlinDeclaration("HtspChannelAddMessage", "class", CHANNEL_PROPERTIES, supertype="HtspServerMessage",
        snapshots=(S("services", "List<HtspChannelService>?", "services?.immutableServerSnapshot()"), S("tagIds", "List<Long>?", "tagIds?.immutableServerSnapshot()")),
        validations=(V("u32", ("channelId", "channelId"), ("channelNumber", "channelNumber"), ("channelNumberMinor", "channelNumberMinor"), ("currentEventId", "currentEventId"), ("nextEventId", "nextEventId")), V("u32-list", ("tagIds", "this.tagIds")), V("channel-services"))),
    KotlinDeclaration("HtspChannelUpdateMessage", "class", (
        CHANNEL_PROPERTIES[0], CHANNEL_PROPERTIES[2], CHANNEL_PROPERTIES[3], CHANNEL_PROPERTIES[4],
        CHANNEL_PROPERTIES[1], *CHANNEL_PROPERTIES[5:]), supertype="HtspServerMessage",
        snapshots=(S("services", "List<HtspChannelService>?", "services?.immutableServerSnapshot()"), S("tagIds", "List<Long>?", "tagIds?.immutableServerSnapshot()")),
        validations=(V("u32", ("channelId", "channelId"), ("channelNumber", "channelNumber"), ("channelNumberMinor", "channelNumberMinor"), ("currentEventId", "currentEventId"), ("nextEventId", "nextEventId")), V("u32-list", ("tagIds", "this.tagIds")), V("channel-services"))),
    KotlinDeclaration("HtspChannelDeleteMessage", "data class", (P("channelId", "Long"),), supertype="HtspServerMessage", validations=(V("u32", ("channelId", "channelId")),)),
    KotlinDeclaration("HtspTagAddMessage", "class", TAG_PROPERTIES, supertype="HtspServerMessage", snapshots=(S("channelIds", "List<Long>?", "channelIds?.immutableServerSnapshot()"),), validations=(V("u32", ("tagId", "tagId"), ("tagIndex", "tagIndex"), ("tagTitledIcon", "tagTitledIcon")), V("u32-list", ("channelIds", "this.channelIds")))),
    KotlinDeclaration("HtspTagUpdateMessage", "class", (TAG_PROPERTIES[0], TAG_PROPERTIES[2], TAG_PROPERTIES[3], TAG_PROPERTIES[1], *TAG_PROPERTIES[4:]), supertype="HtspServerMessage", snapshots=(S("channelIds", "List<Long>?", "channelIds?.immutableServerSnapshot()"),), validations=(V("u32", ("tagId", "tagId"), ("tagIndex", "tagIndex"), ("tagTitledIcon", "tagTitledIcon")), V("u32-list", ("channelIds", "this.channelIds")))),
    KotlinDeclaration("HtspTagDeleteMessage", "data class", (P("tagId", "Long"),), supertype="HtspServerMessage", validations=(V("u32", ("tagId", "tagId")),)),
    KotlinDeclaration("HtspDvrRecordingFile", "data class", (P("fileId", "Long?"), P("path", "String?"), P("start", "Long?"), P("stop", "Long?"), P("sizeBytes", "Long?")), kdoc="Known bounded fields from one otherwise upstream-dynamic DVR recording-file map.", validations=(V("u32", ("fileId", "fileId")),)),
    KotlinDeclaration("HtspDvrEntryAddMessage", "class", DVR_PROPERTIES, supertype="HtspServerMessage", snapshots=(S("files", "List<HtspDvrRecordingFile>?", "files?.immutableServerSnapshot()"),), validations=(V("u32", ("entryId", "entryId"), *DVR_U32),)),
    KotlinDeclaration("HtspDvrEntryUpdateMessage", "class", DVR_PROPERTIES, supertype="HtspServerMessage", snapshots=(S("files", "List<HtspDvrRecordingFile>?", "files?.immutableServerSnapshot()"),), validations=(V("u32", ("entryId", "entryId"), *DVR_U32),)),
    KotlinDeclaration("HtspDvrEntryDeleteMessage", "data class", (P("entryId", "Long"),), supertype="HtspServerMessage", validations=(V("u32", ("entryId", "entryId")),)),
    KotlinDeclaration("HtspAutorecEntryAddMessage", "data class", AUTOREC_ADD_PROPERTIES, kdoc="A complete automatic DVR rule announced during asynchronous metadata sync.", supertype="HtspServerMessage", validations=(V("u32", *AUTOREC_U32),)),
    KotlinDeclaration("HtspAutorecEntryUpdateMessage", "data class", AUTOREC_UPDATE_PROPERTIES, kdoc="A partial automatic DVR rule update. Null properties were absent on the wire.", supertype="HtspServerMessage", validations=(V("u32", *AUTOREC_U32),)),
    KotlinDeclaration("HtspAutorecEntryDeleteMessage", "data class", (P("id", "String"),), supertype="HtspServerMessage"),
    KotlinDeclaration("HtspTimerecEntryAddMessage", "data class", TIMEREC_ADD_PROPERTIES, kdoc="A complete time-based DVR rule announced during asynchronous metadata sync.", supertype="HtspServerMessage", validations=(V("timerec"),)),
    KotlinDeclaration("HtspTimerecEntryUpdateMessage", "data class", TIMEREC_UPDATE_PROPERTIES, kdoc="A partial time-based DVR rule update. Null properties were absent on the wire.", supertype="HtspServerMessage", validations=(V("timerec"),)),
    KotlinDeclaration("HtspTimerecEntryDeleteMessage", "data class", (P("id", "String"),), supertype="HtspServerMessage"),
    KotlinDeclaration("HtspEventAddMessage", "class", (P("event", "HtspEvent", stored=False), P("genre", "String?", "null"), P("episodeId", "Long?", "null"), P("seriesLinkId", "Long?", "null")), supertype="HtspServerMessage", snapshots=(S("event", "HtspEvent", "event.copy(categories = event.categories?.immutableServerSnapshot(), keywords = event.keywords?.immutableServerSnapshot())"),), validations=(V("event-add"),)),
    KotlinDeclaration("HtspEventUpdateMessage", "class", EVENT_UPDATE_PROPERTIES, supertype="HtspServerMessage", snapshots=(S("categories", "List<String>?", "categories?.immutableServerSnapshot()"), S("keywords", "List<String>?", "keywords?.immutableServerSnapshot()")), validations=(V("u32", ("eventId", "eventId"), *EVENT_U32),)),
    KotlinDeclaration("HtspEventDeleteMessage", "data class", (P("eventId", "Long"),), supertype="HtspServerMessage", validations=(V("u32", ("eventId", "eventId")),)),
    KotlinDeclaration("HtspInitialSyncCompletedMessage", "data object", supertype="HtspServerMessage"),
    KotlinDeclaration("HtspMuxPacketMessage", "data class", (P("subscriptionId", "Long"), P("frameType", "Long"), P("streamIndex", "Long"), P("decodingTimestamp", "Long?"), P("presentationTimestamp", "Long?"), P("duration", "Long"), P("payload", "HtspBinary")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId"), ("frameType", "frameType"), ("streamIndex", "streamIndex"), ("duration", "duration")),)),
    KotlinDeclaration("HtspQueueStatusMessage", "data class", (P("subscriptionId", "Long"), P("packetCount", "Long"), P("byteCount", "Long"), P("delay", "Long?"), P("bFrameDropCount", "Long"), P("pFrameDropCount", "Long"), P("iFrameDropCount", "Long")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId"), ("packetCount", "packetCount"), ("byteCount", "byteCount"), ("bFrameDropCount", "bFrameDropCount"), ("pFrameDropCount", "pFrameDropCount"), ("iFrameDropCount", "iFrameDropCount")),)),
    KotlinDeclaration("HtspSubscriptionStream", "data class", (P("streamIndex", "Long"), P("streamType", "String"), P("language", "String?"), P("compositionId", "Long?"), P("ancillaryId", "Long?"), P("width", "Long?"), P("height", "Long?"), P("frameDuration", "Long?"), P("aspectNumerator", "Long?"), P("aspectDenominator", "Long?"), P("audioType", "Long?"), P("audioVersion", "Long?"), P("channelCount", "Long?"), P("sampleRate", "Long?"), P("rdsUecp", "Long?"), P("codecMetadata", "HtspBinary?", "null")), validations=(V("u32", ("streamIndex", "streamIndex")), V("u32-group", *(("stream field", n) for n in ("compositionId", "ancillaryId", "width", "height", "frameDuration", "aspectNumerator", "aspectDenominator", "audioType", "audioVersion", "channelCount", "sampleRate", "rdsUecp"))))),
    KotlinDeclaration("HtspSubscriptionSourceInfo", "data class", tuple(P(n, "String?") for n in ("adapterUuid", "muxUuid", "networkUuid", "adapter", "mux", "network", "networkType", "provider", "service", "satellitePosition"))),
    KotlinDeclaration("HtspSubscriptionStartMessage", "class", (P("subscriptionId", "Long"), P("streams", "List<HtspSubscriptionStream>?", "null", stored=False), P("sourceInfo", "HtspSubscriptionSourceInfo?", "null"), P("codecMetadata", "HtspBinary?", "null"), P("status", "String?", "null"), P("subscriptionError", "String?", "null")), supertype="HtspServerMessage", snapshots=(S("streams", "List<HtspSubscriptionStream>?", "streams?.immutableServerSnapshot()"),), validations=(V("u32", ("subscriptionId", "subscriptionId")),)),
    KotlinDeclaration("HtspSubscriptionStopMessage", "data class", (P("subscriptionId", "Long"), P("status", "String?"), P("subscriptionError", "String?")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId")),)),
    KotlinDeclaration("HtspSubscriptionGraceMessage", "data class", (P("subscriptionId", "Long"), P("graceTimeoutSeconds", "Long")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId"), ("graceTimeoutSeconds", "graceTimeoutSeconds")),)),
    KotlinDeclaration("HtspSubscriptionStatusMessage", "data class", (P("subscriptionId", "Long"), P("status", "String?"), P("subscriptionError", "String?")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId")),)),
    KotlinDeclaration("HtspSignalStatusMessage", "data class", (P("subscriptionId", "Long"), P("frontendStatus", "String?"), P("relativeSnr", "Long?"), P("absoluteSnr", "Long?"), P("relativeSignal", "Long?"), P("absoluteSignal", "Long?"), P("bitErrorRate", "Long?"), P("uncorrectedBlockCount", "Long?")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId")), V("u32-group", *(("signal field", n) for n in ("relativeSnr", "relativeSignal", "bitErrorRate", "uncorrectedBlockCount"))))),
    KotlinDeclaration("HtspDescrambleInfoMessage", "data class", (P("subscriptionId", "Long"), P("pid", "Long"), P("conditionalAccessId", "Long"), P("providerId", "Long"), P("ecmTime", "Long"), P("hopCount", "Long"), P("cardSystem", "String?", "null"), P("reader", "String?", "null"), P("source", "String?", "null"), P("protocol", "String?", "null")), kdoc="Complete bounded descrambling observations emitted by pinned HTSP v24+ source.", supertype="HtspServerMessage", validations=(V("u32-group", *(("descramble field", n) for n in ("subscriptionId", "pid", "conditionalAccessId", "providerId", "ecmTime", "hopCount"))),)),
    KotlinDeclaration("HtspSubscriptionSpeedMessage", "data class", (P("subscriptionId", "Long"), P("speed", "Int")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId")),)),
    KotlinDeclaration("HtspTimeshiftStatusMessage", "data class", (P("subscriptionId", "Long"), P("full", "Long"), P("shift", "Long"), P("start", "Long?"), P("end", "Long?"), P("speed", "Int?", "null")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId"), ("full", "full")),)),
    KotlinDeclaration("HtspSubscriptionSkipMessage", "data class", (P("subscriptionId", "Long"), P("absolute", "Long?"), P("error", "Long?"), P("time", "Long?"), P("sizeBytes", "Long?")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId"), ("absolute", "absolute"), ("error", "error")),)),
)


def WF(owner: str, property: str, wire_type: str, presence: str, decoder: str, names: tuple[str, ...], **kwargs: object) -> WireField:
    return W(owner, property, wire_type, presence, decoder, *names, **kwargs)


SERVICE_FIELDS = (
    W("service", "name", STR, REQ, "string", "name"), W("service", "type", STR, REQ, "string", "type"),
    W("service", "content", U32, REQ, "u32", "content"), W("service", "conditionalAccessId", U32, OPT, "u32", "caid"),
    W("service", "conditionalAccessName", STR, OPT, "string", "caname"),
    W("service", "providerName", STR, OPT, "string", "providername", minimum_version=38),
)
DVR_FILE_FIELDS = (
    W("dvr-file", "fileId", U32, OPT, "u32", "id"), WF("dvr-file", "path", STR, OPT, "string", DVR_FILE_PATH_KEYS),
    W("dvr-file", "start", S64, OPT, "s64", "start"), W("dvr-file", "stop", S64, OPT, "s64", "stop"),
    W("dvr-file", "sizeBytes", S64, OPT, "s64", "size"),
)
EVENT_FIELDS = (
    WF("event", "eventId", U32, REQ, "u32", EVENT_ID_KEYS), WF("event", "channelId", U32, OPT, "u32", EVENT_CHANNEL_KEYS),
    WF("event", "start", S64, REQ, "s64", EVENT_START_KEYS), WF("event", "stop", S64, REQ, "s64", EVENT_STOP_KEYS),
    WF("event", "title", STR, OPT, "string", EVENT_TITLE_KEYS), W("event", "subtitle", STR, OPT, "string", "subtitle"),
    W("event", "summary", STR, OPT, "string", "summary"), W("event", "description", STR, OPT, "string", "description"),
    W("event", "categories", LIST, OPT, "event-categories", "category"), W("event", "keywords", LIST, OPT, "string-list", "keyword"),
    W("event", "seriesLinkUri", STR, OPT, "string", "serieslinkUri"), W("event", "episodeUri", STR, OPT, "string", "episodeUri"),
    WF("event", "contentType", U32, OPT, "u32", EVENT_CONTENT_KEYS), W("event", "ageRating", U32, OPT, "u32", "ageRating"),
    W("event", "ratingLabel", STR, OPT, "string", "ratingLabel"), W("event", "ratingIcon", STR, OPT, "string", "ratingIcon"),
    W("event", "ratingAuthority", STR, OPT, "string", "ratingAuthority", minimum_version=41),
    W("event", "ratingCountry", STR, OPT, "string", "ratingCountry", minimum_version=41),
    W("event", "starRating", U32, OPT, "u32", "starRating"), W("event", "copyrightYear", U32, OPT, "u32", "copyrightYear"),
    W("event", "firstAired", S64, OPT, "s64", "firstAired"), W("event", "isNew", U32, OPT, "u32", "isNew"),
    W("event", "seasonNumber", U32, OPT, "event-u32", "seasonNumber", "season", nested_wire_names=("seasonNumber", "season")),
    W("event", "seasonCount", U32, OPT, "event-u32", "seasonCount", nested_wire_names=("seasonCount", "count")),
    W("event", "episodeNumber", U32, OPT, "event-u32", "episodeNumber", nested_wire_names=("episodeNumber", "number")),
    W("event", "episodeCount", U32, OPT, "event-u32", "episodeCount", nested_wire_names=("episodeCount", "count")),
    W("event", "partNumber", U32, OPT, "event-u32", "partNumber", "part", nested_wire_names=("partNumber", "part")),
    W("event", "partCount", U32, OPT, "event-u32", "partCount", nested_wire_names=("partCount",)),
    W("event", "episodeOnscreen", STR, OPT, "string", "episodeOnscreen"), W("event", "image", STR, OPT, "string", "image"),
    W("event", "dvrId", U32, OPT, "u32", "dvrId"), W("event", "nextEventId", U32, OPT, "u32", "nextEventId"),
)
STREAM_FIELDS = (
    W("stream", "streamIndex", U32, REQ, "u32", "index"), W("stream", "streamType", STR, REQ, "string", "type"),
    W("stream", "language", STR, OPT, "string", "language"),
    W("stream", "compositionId", U32, OPT, "u32", "composition_id", minimum_version=5),
    W("stream", "ancillaryId", U32, OPT, "u32", "ancillary_id", minimum_version=5), W("stream", "width", U32, OPT, "u32", "width"),
    W("stream", "height", U32, OPT, "u32", "height"), W("stream", "frameDuration", U32, OPT, "u32", "duration"),
    W("stream", "aspectNumerator", U32, OPT, "u32", "aspect_num", minimum_version=5),
    W("stream", "aspectDenominator", U32, OPT, "u32", "aspect_den", minimum_version=5),
    W("stream", "audioType", U32, OPT, "u32", "audio_type", minimum_version=11), W("stream", "audioVersion", U32, OPT, "u32", "audio_version"),
    W("stream", "channelCount", U32, OPT, "u32", "channels", minimum_version=5),
    W("stream", "sampleRate", U32, OPT, "u32", "rate", minimum_version=5),
    W("stream", "rdsUecp", U32, OPT, "u32", "rds_uecp"),
    W("stream", "codecMetadata", BIN, OPT, "binary-value", "meta", minimum_version=17),
)
SOURCE_FIELDS = tuple(W("source-info", p, STR, OPT, "string", w, minimum_version=20 if p == "satellitePosition" else None) for p, w in (
    ("adapterUuid", "adapter_uuid"), ("muxUuid", "mux_uuid"), ("networkUuid", "network_uuid"),
    ("adapter", "adapter"), ("mux", "mux"), ("network", "network"), ("networkType", "network_type"),
    ("provider", "provider"), ("service", "service"), ("satellitePosition", "satpos"),
))
SERVER_NESTED_SHAPES = (
    NestedShape("service", "HtspChannelService", "decodeServerChannelService", SERVICE_FIELDS),
    NestedShape("dvr-file", "HtspDvrRecordingFile", "decodeDvrRecordingFile", DVR_FILE_FIELDS),
    NestedShape("event", "HtspEvent", "decodeServerEvent", EVENT_FIELDS),
    NestedShape("stream", "HtspSubscriptionStream", "decodeSubscriptionStream", STREAM_FIELDS),
    NestedShape("source-info", "HtspSubscriptionSourceInfo", "decodeSubscriptionSourceInfo", SOURCE_FIELDS),
)


def channel_fields(owner: str) -> tuple[WireField, ...]:
    return (
        W(owner, "channelId", U32, REQ, "u32", "channelId"),
        W(owner, "channelUuid", STR, OPT, "string", "channelIdStr", minimum_version=41),
        WF(owner, "channelNumber", U32, OPT, "u32", CHANNEL_NUMBER_KEYS),
        W(owner, "channelNumberMinor", U32, OPT, "u32", "channelNumberMinor", minimum_version=13),
        W(owner, "channelName", STR, OPT, "string", "channelName"), W(owner, "channelIcon", STR, OPT, "string", "channelIcon"),
        W(owner, "currentEventId", U32, OPT, "u32", "eventId"), W(owner, "nextEventId", U32, OPT, "u32", "nextEventId"),
        W(owner, "services", LIST, OPT, "object-list", "services", minimum_version=5, nested_shape="service"),
        WF(owner, "tagIds", LIST, OPT, "u32-list", CHANNEL_TAG_KEYS),
    )


def tag_fields(owner: str) -> tuple[WireField, ...]:
    return (
        WF(owner, "tagId", U32, REQ, "u32", TAG_ID_KEYS),
        W(owner, "tagUuid", STR, OPT, "string", "tagIdStr", minimum_version=41),
        WF(owner, "tagIndex", U32, OPT, "u32", TAG_INDEX_KEYS, minimum_version=18), WF(owner, "tagName", STR, OPT, "string", TAG_NAME_KEYS),
        W(owner, "tagIcon", STR, OPT, "string", "tagIcon"), W(owner, "tagTitledIcon", U32, OPT, "u32", "tagTitledIcon"),
        W(owner, "channelIds", LIST, OPT, "u32-list", "members"),
    )


def dvr_fields(owner: str) -> tuple[WireField, ...]:
    specs = (
        ("entryId", U32, REQ, "u32", DVR_ID_KEYS, None), ("entryUuid", STR, OPT, "string", ("idStr",), 41),
        ("enabled", U32, OPT, "u32", ("enabled",), None), ("channelId", U32, OPT, "u32", DVR_CHANNEL_KEYS, None),
        ("channelName", STR, OPT, "string", ("channelName",), None), ("eventId", U32, OPT, "u32", ("eventId",), 13),
        ("autorecEntryUuid", STR, OPT, "string", ("autorecId",), 13), ("timerecEntryUuid", STR, OPT, "string", ("timerecId",), None),
        ("start", S64, OPT, "s64", ("start",), None), ("stop", S64, OPT, "s64", ("stop",), None),
        ("startExtraMinutes", S64, OPT, "s64", ("startExtra",), 13), ("stopExtraMinutes", S64, OPT, "s64", ("stopExtra",), 13),
        ("retentionDays", U32, OPT, "u32", ("retention",), 13), ("removalDays", U32, OPT, "u32", ("removal",), None),
        ("priority", U32, OPT, "u32", ("priority",), 13), ("contentType", U32, OPT, "u32", ("contentType",), 13),
        ("ageRating", U32, OPT, "u32", ("ageRating",), None), ("ratingLabel", STR, OPT, "string", ("ratingLabel",), None),
        ("ratingIcon", STR, OPT, "string", ("ratingIcon",), None), ("ratingAuthority", STR, OPT, "string", ("ratingAuthority",), 41),
        ("ratingCountry", STR, OPT, "string", ("ratingCountry",), 41), ("playCount", U32, OPT, "u32", DVR_PLAY_COUNT_KEYS, None),
        ("playPositionSeconds", U32, OPT, "u32", DVR_PLAY_POSITION_KEYS, None), ("seasonNumber", U32, OPT, "u32", ("seasonNumber",), None),
        ("episodeNumber", U32, OPT, "u32", ("episodeNumber",), None), ("episodeCount", U32, OPT, "u32", ("episodeCount",), None),
        ("partNumber", U32, OPT, "u32", ("partNumber",), None), ("partCount", U32, OPT, "u32", ("partCount",), None),
        ("title", STR, OPT, "string", ("title",), None), ("description", STR, OPT, "string", ("description",), None),
        ("summary", STR, OPT, "string", ("summary",), None), ("subtitle", STR, OPT, "string", ("subtitle",), None),
        ("owner", STR, OPT, "string", ("owner",), None), ("creator", STR, OPT, "string", ("creator",), None),
        ("comment", STR, OPT, "string", ("comment",), None), ("image", STR, OPT, "string", ("image",), None),
        ("fanartImage", STR, OPT, "string", ("fanartImage",), None), ("copyrightYear", U32, OPT, "u32", ("copyrightYear",), None),
        ("files", LIST, OPT, "object-list", ("files",), None), ("path", STR, OPT, "string", ("path",), None),
        ("dvrConfigUuid", STR, OPT, "string", ("configId",), None), ("duplicate", U32, OPT, "u32", ("duplicate",), None),
        ("state", STR, OPT, "string", STATUS_KEYS, None), ("error", STR, OPT, "string", DVR_ERROR_KEYS, None),
        ("subscriptionError", STR, OPT, "string", ("subscriptionError",), 20), ("streamErrors", U32, OPT, "u32", ("streamErrors",), None),
        ("dataErrors", U32, OPT, "u32", ("dataErrors",), None), ("dataSizeBytes", S64, OPT, "s64", ("dataSize",), None),
    )
    return tuple(
        WF(
            owner,
            p,
            t,
            presence,
            decoder,
            names,
            minimum_version=minimum_version,
            nested_shape="dvr-file" if p == "files" else None,
        )
        for p, t, presence, decoder, names, minimum_version in specs
    )


def autorec_fields(owner: str, update: bool) -> tuple[WireField, ...]:
    required = not update
    specs = (
        ("id", STR, "string", "id", True), ("enabled", FLAG, "flag", "enabled", required),
        ("maxDurationSeconds", U32, "u32", "maxDuration", required), ("minDurationSeconds", U32, "u32", "minDuration", required),
        ("retentionDays", U32, "u32", "retention", required), ("removalDays", U32, "u32", "removal", required),
        ("daysOfWeekMask", U32, "u32", "daysOfWeek", required), ("approximateStartMinutesSinceMidnight", S32, "s32", "approxTime", required),
        ("startMinutesSinceMidnight", S32, "s32", "start", required), ("startWindowEndMinutesSinceMidnight", S32, "s32", "startWindow", required),
        ("priority", U32, "u32", "priority", required), ("startExtraMinutes", S64, "s64", "startExtra", required),
        ("stopExtraMinutes", S64, "s64", "stopExtra", required), ("duplicateDetection", U32, "u32", "dupDetect", required),
        ("maximumRecordingCount", U32, "u32", "maxCount", required), ("broadcastType", U32, "u32", "broadcastType", required),
        ("comment", STR, "string", "comment", required), ("title", STR, "string", "title", False),
        ("fullText", FLAG, "flag", "fulltext", False), ("mergeText", FLAG, "flag", "mergetext", False),
        ("name", STR, "string", "name", required), ("directory", STR, "string", "directory", False),
        ("owner", STR, "string", "owner", required), ("creator", STR, "string", "creator", required),
        ("channelId", U32, "u32", "channel", False), ("seriesLinkUri", STR, "string", "serieslinkUri", False),
        ("configId", STR, "string", "configId", False),
    )
    return tuple(W(owner, p, t, REQ if req else OPT, decoder, wire) for p, t, decoder, wire, req in specs)


def timerec_fields(owner: str, update: bool) -> tuple[WireField, ...]:
    required = not update
    specs = (
        ("id", STR, "string", "id", True), ("enabled", FLAG, "flag", "enabled", required),
        ("name", STR, "string", "name", required), ("title", STR, "string", "title", required),
        ("channelId", S32, "bounded-int", "channel", required),
        ("startMinutesSinceMidnight", S32, "minutes", "start", required),
        ("stopMinutesSinceMidnight", S32, "minutes", "stop", required),
        ("daysOfWeekMask", U32, "u32", "daysOfWeek", False), ("priority", U32, "u32", "priority", False),
        ("retentionDays", U32, "u32", "retention", False), ("directory", STR, "string", "directory", False),
        ("owner", STR, "string", "owner", False), ("creator", STR, "string", "creator", False),
        ("configId", STR, "string", "configId", False), ("comment", STR, "string", "comment", False),
    )
    return tuple(W(owner, p, t, REQ if req else OPT, decoder, wire, lenient_malformed=(update or not req) and p != "id") for p, t, decoder, wire, req in specs)


def event_update_fields(owner: str) -> tuple[WireField, ...]:
    replacements = {
        "eventId": EVENT_ID_KEYS, "channelId": EVENT_CHANNEL_KEYS, "start": EVENT_START_KEYS,
        "stop": EVENT_STOP_KEYS, "title": EVENT_TITLE_KEYS, "contentType": EVENT_CONTENT_KEYS,
    }
    fields = []
    for field in EVENT_FIELDS:
        names = replacements.get(field.kotlin_property, field.wire_names)
        presence = REQ if field.kotlin_property == "eventId" else OPT
        fields.append(WF(
            owner,
            field.kotlin_property,
            field.wire_type,
            presence,
            field.decoder,
            names,
            minimum_version=field.minimum_version,
            nested_wire_names=field.nested_wire_names,
        ))
    by_property = {field.kotlin_property: field for field in fields}
    by_property.update({
        "genre": W(owner, "genre", STR, OPT, "event-genre", "genre", "category"),
        "episodeId": W(owner, "episodeId", U32, OPT, "u32", "episodeId"),
        "seriesLinkId": WF(owner, "seriesLinkId", U32, OPT, "u32", ("serieslinkId", "seriesLinkId")),
    })
    return tuple(by_property[prop.name] for prop in EVENT_UPDATE_PROPERTIES)


SERVER_MESSAGE_CATALOG: tuple[ServerMessageEntry, ...] = (
    ServerMessageEntry("channelAdd", "HtspChannelAddMessage", "decodeChannelAdd", None, channel_fields("channelAdd")),
    ServerMessageEntry("channelUpdate", "HtspChannelUpdateMessage", "decodeChannelUpdate", None, channel_fields("channelUpdate")),
    ServerMessageEntry("channelDelete", "HtspChannelDeleteMessage", "decodeChannelDelete", None, (W("channelDelete", "channelId", U32, REQ, "u32", "channelId"),)),
    ServerMessageEntry("tagAdd", "HtspTagAddMessage", "decodeTagAdd", None, tag_fields("tagAdd")),
    ServerMessageEntry("tagUpdate", "HtspTagUpdateMessage", "decodeTagUpdate", None, tag_fields("tagUpdate")),
    ServerMessageEntry("tagDelete", "HtspTagDeleteMessage", "decodeTagDelete", None, (WF("tagDelete", "tagId", U32, REQ, "u32", TAG_ID_KEYS),)),
    ServerMessageEntry("dvrEntryAdd", "HtspDvrEntryAddMessage", "decodeDvrEntryAdd", 4, dvr_fields("dvrEntryAdd")),
    ServerMessageEntry("dvrEntryUpdate", "HtspDvrEntryUpdateMessage", "decodeDvrEntryUpdate", 4, dvr_fields("dvrEntryUpdate")),
    ServerMessageEntry("dvrEntryDelete", "HtspDvrEntryDeleteMessage", "decodeDvrEntryDelete", 4, (WF("dvrEntryDelete", "entryId", U32, REQ, "u32", DVR_ID_KEYS),)),
    ServerMessageEntry("autorecEntryAdd", "HtspAutorecEntryAddMessage", "decodeAutorecEntryAdd", 13, autorec_fields("autorecEntryAdd", False)),
    ServerMessageEntry("autorecEntryUpdate", "HtspAutorecEntryUpdateMessage", "decodeAutorecEntryUpdate", 13, autorec_fields("autorecEntryUpdate", True)),
    ServerMessageEntry("autorecEntryDelete", "HtspAutorecEntryDeleteMessage", "decodeAutorecEntryDelete", 13, (W("autorecEntryDelete", "id", STR, REQ, "string", "id"),)),
    ServerMessageEntry("timerecEntryAdd", "HtspTimerecEntryAddMessage", "decodeTimerecEntryAdd", 18, timerec_fields("timerecEntryAdd", False)),
    ServerMessageEntry("timerecEntryUpdate", "HtspTimerecEntryUpdateMessage", "decodeTimerecEntryUpdate", 18, timerec_fields("timerecEntryUpdate", True)),
    ServerMessageEntry("timerecEntryDelete", "HtspTimerecEntryDeleteMessage", "decodeTimerecEntryDelete", 18, (W("timerecEntryDelete", "id", STR, REQ, "string", "id"),)),
    ServerMessageEntry("eventAdd", "HtspEventAddMessage", "decodeEventAdd", 6, (W("eventAdd", "event", MAP, REQ, "root-shape", nested_shape="event"), W("eventAdd", "genre", STR, OPT, "event-genre", "genre", "category"), W("eventAdd", "episodeId", U32, OPT, "u32", "episodeId"), WF("eventAdd", "seriesLinkId", U32, OPT, "u32", ("serieslinkId", "seriesLinkId")))),
    ServerMessageEntry("eventUpdate", "HtspEventUpdateMessage", "decodeEventUpdate", 6, event_update_fields("eventUpdate")),
    ServerMessageEntry("eventDelete", "HtspEventDeleteMessage", "decodeEventDelete", 6, (WF("eventDelete", "eventId", U32, REQ, "u32", EVENT_ID_KEYS),)),
    ServerMessageEntry("initialSyncCompleted", "HtspInitialSyncCompletedMessage", "decodeInitialSyncCompleted", 2, ()),
    ServerMessageEntry("muxpkt", "HtspMuxPacketMessage", "decodeMuxPacket", None, (
        W("muxpkt", "subscriptionId", U32, REQ, "u32", "subscriptionId"), W("muxpkt", "frameType", U32, REQ, "u32", "frametype"),
        W("muxpkt", "streamIndex", U32, REQ, "u32", "stream"), W("muxpkt", "decodingTimestamp", S64, OPT, "s64", "dts"),
        W("muxpkt", "presentationTimestamp", S64, OPT, "s64", "pts"), W("muxpkt", "duration", U32, REQ, "u32", "duration"),
        W("muxpkt", "payload", BIN, REQ, "binary-value", "payload"),
    )),
    ServerMessageEntry("queueStatus", "HtspQueueStatusMessage", "decodeQueueStatus", None, tuple(W("queueStatus", p, t, presence, decoder, wire) for p, t, presence, decoder, wire in (
        ("subscriptionId", U32, REQ, "u32", "subscriptionId"), ("packetCount", U32, REQ, "u32", "packets"),
        ("byteCount", U32, REQ, "u32", "bytes"), ("delay", S64, OPT, "s64", "delay"),
        ("bFrameDropCount", U32, REQ, "u32", "Bdrops"), ("pFrameDropCount", U32, REQ, "u32", "Pdrops"),
        ("iFrameDropCount", U32, REQ, "u32", "Idrops"),
    ))),
    ServerMessageEntry("subscriptionStart", "HtspSubscriptionStartMessage", "decodeSubscriptionStart", None, (
        WF("subscriptionStart", "subscriptionId", U32, REQ, "u32", SUBSCRIPTION_ID_KEYS), W("subscriptionStart", "streams", LIST, OPT, "object-list", "streams", nested_shape="stream"),
        W("subscriptionStart", "sourceInfo", MAP, OPT, "object", "sourceinfo", nested_shape="source-info"),
        W("subscriptionStart", "codecMetadata", BIN, OPT, "binary-value", "meta", minimum_version=17),
        WF("subscriptionStart", "status", STR, OPT, "string", STATUS_KEYS), WF("subscriptionStart", "subscriptionError", STR, OPT, "string", SUBSCRIPTION_ERROR_KEYS),
    )),
    ServerMessageEntry("subscriptionStop", "HtspSubscriptionStopMessage", "decodeSubscriptionStop", None, (WF("subscriptionStop", "subscriptionId", U32, REQ, "u32", SUBSCRIPTION_ID_KEYS), WF("subscriptionStop", "status", STR, OPT, "string", STATUS_KEYS), WF("subscriptionStop", "subscriptionError", STR, OPT, "string", SUBSCRIPTION_ERROR_KEYS, minimum_version=20))),
    ServerMessageEntry("subscriptionGrace", "HtspSubscriptionGraceMessage", "decodeSubscriptionGrace", 13, (W("subscriptionGrace", "subscriptionId", U32, REQ, "u32", "subscriptionId"), W("subscriptionGrace", "graceTimeoutSeconds", U32, REQ, "u32", "graceTimeout"))),
    ServerMessageEntry("subscriptionStatus", "HtspSubscriptionStatusMessage", "decodeSubscriptionStatus", None, (WF("subscriptionStatus", "subscriptionId", U32, REQ, "u32", SUBSCRIPTION_ID_KEYS), WF("subscriptionStatus", "status", STR, OPT, "string", STATUS_KEYS), WF("subscriptionStatus", "subscriptionError", STR, OPT, "string", SUBSCRIPTION_ERROR_KEYS, minimum_version=20))),
    ServerMessageEntry("signalStatus", "HtspSignalStatusMessage", "decodeSignalStatus", None, tuple(W("signalStatus", p, t, OPT if p != "subscriptionId" else REQ, decoder, wire, minimum_version=minimum_version) for p, t, decoder, wire, minimum_version in (
        ("subscriptionId", U32, "u32", "subscriptionId", None), ("frontendStatus", STR, "string", "feStatus", None),
        ("relativeSnr", U32, "u32", "feSNR", None), ("absoluteSnr", S64, "s64", "feAbsoluteSNR", 44),
        ("relativeSignal", U32, "u32", "feSignal", None), ("absoluteSignal", S64, "s64", "feAbsoluteSignal", 44),
        ("bitErrorRate", U32, "u32", "feBER", None), ("uncorrectedBlockCount", U32, "u32", "feUNC", None),
    ))),
    ServerMessageEntry("descrambleInfo", "HtspDescrambleInfoMessage", "decodeDescrambleInfo", 24, tuple(W("descrambleInfo", p, t, presence, decoder, wire, minimum_version=24 if p == "subscriptionId" else None) for p, t, presence, decoder, wire in (
        ("subscriptionId", U32, REQ, "u32", "subscriptionId"), ("pid", U32, REQ, "u32", "pid"),
        ("conditionalAccessId", U32, REQ, "u32", "caid"), ("providerId", U32, REQ, "u32", "provid"),
        ("ecmTime", U32, REQ, "u32", "ecmtime"), ("hopCount", U32, REQ, "u32", "hops"),
        ("cardSystem", STR, OPT, "string", "cardsystem"), ("reader", STR, OPT, "string", "reader"),
        ("source", STR, OPT, "string", "from"), ("protocol", STR, OPT, "string", "protocol"),
    ))),
    ServerMessageEntry("subscriptionSpeed", "HtspSubscriptionSpeedMessage", "decodeSubscriptionSpeed", 9, (W("subscriptionSpeed", "subscriptionId", U32, REQ, "u32", "subscriptionId"), W("subscriptionSpeed", "speed", S32, REQ, "s32", "speed"))),
    ServerMessageEntry("timeshiftStatus", "HtspTimeshiftStatusMessage", "decodeTimeshiftStatus", 9, tuple(W("timeshiftStatus", p, t, presence, decoder, wire) for p, t, presence, decoder, wire in (
        ("subscriptionId", U32, REQ, "u32", "subscriptionId"), ("full", U32, REQ, "u32", "full"),
        ("shift", S64, REQ, "s64", "shift"), ("start", S64, OPT, "s64", "start"),
        ("end", S64, OPT, "s64", "end"), ("speed", S32, OPT, "s32", "speed"),
    ))),
    ServerMessageEntry("subscriptionSkip", "HtspSubscriptionSkipMessage", "decodeSubscriptionSkip", 9, tuple(W("subscriptionSkip", p, t, presence, decoder, wire) for p, t, presence, decoder, wire in (
        ("subscriptionId", U32, REQ, "u32", "subscriptionId"), ("absolute", U32, OPT, "u32", "absolute"),
        ("error", U32, OPT, "u32", "error"), ("time", S64, OPT, "s64", "time"), ("sizeBytes", S64, OPT, "s64", "size"),
    ))),
)


# Direct G3 traversal API: message and nested-shape fields, in reviewed decode order.
SERVER_WIRE_FIELDS: tuple[WireField, ...] = tuple(
    field for entry in SERVER_MESSAGE_CATALOG for field in entry.fields
) + tuple(field for shape in SERVER_NESTED_SHAPES for field in shape.fields)

# No G1 assertion is made about agreement with htsp_spec.json.  The structured
# transcription was checked against the base Kotlin behavior; exhaustive spec
# consistency enforcement and any required waivers remain pending G3.
SERVER_SPEC_WAIVERS: tuple[tuple[str, str], ...] = ()
SERVER_SPEC_CONSISTENCY_STATUS = "pending-g3"

# G1 uses no per-entry verbatim Kotlin escape.  Irregular event/timerec/nested
# behavior is represented by named decoder and validation features above.
SERVER_VERBATIM_ESCAPES: tuple[tuple[str, str], ...] = ()
