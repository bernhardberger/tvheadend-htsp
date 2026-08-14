#!/usr/bin/env python3
"""Reviewed, stdlib-only authority for the generated Kotlin HTSP surface.

The objects in this module are deliberately semantic catalog data.  They are
not parsed from Kotlin and they do not contain a whole-source Kotlin fallback.
G3 can iterate ``SERVER_WIRE_FIELDS`` without understanding the renderer.
"""

from __future__ import annotations

from dataclasses import dataclass, replace


@dataclass(frozen=True)
class GeneratedOutput:
    key: str
    package: str
    relative_path: str
    jvm_name: str | None = None
    annotations: tuple[str, ...] = ()


GENERATED_OUTPUTS: tuple[GeneratedOutput, ...] = (
    GeneratedOutput(
        "request-models",
        "at.bernhardberger.tvheadend.htsp.requests",
        "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/requests/GeneratedHtspRequests.kt",
    ),
    GeneratedOutput(
        "request-extensions",
        "at.bernhardberger.tvheadend.htsp.requests",
        "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/requests/GeneratedHtspExtensions.kt",
    ),
    GeneratedOutput(
        "server-models",
        "at.bernhardberger.tvheadend.htsp.messages",
        "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/messages/GeneratedHtspServerMessages.kt",
    ),
    GeneratedOutput(
        "server-dispatch",
        "at.bernhardberger.tvheadend.htsp.messages",
        "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/messages/GeneratedHtspServerMessageDispatch.kt",
    ),
    GeneratedOutput(
        "jsonapi-models",
        "at.bernhardberger.tvheadend.htsp.jsonapi",
        "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/jsonapi/GeneratedHtspJsonApiModels.kt",
    ),
    GeneratedOutput(
        "jsonapi-extensions",
        "at.bernhardberger.tvheadend.htsp.jsonapi",
        "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/jsonapi/GeneratedHtspJsonApiExtensions.kt",
        "GeneratedHtspExtensionsKt",
        ("@HtspJsonApi",),
    ),
)

GENERATED_OUTPUT_BY_KEY = {output.key: output for output in GENERATED_OUTPUTS}


@dataclass(frozen=True)
class KotlinProperty:
    name: str
    kotlin_type: str
    default: str | None = None
    stored: bool = True
    visibility: str | None = "public"
    modifier: str = ""


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
    annotations: tuple[str, ...] = ()
    feature: str = "standard"
    nested: tuple["KotlinDeclaration", ...] = ()
    enum_values: tuple[str, ...] = ()
    output: str = "request-models"


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
    source_expression: str | None = None


@dataclass(frozen=True)
class ConditionalPresenceRule:
    owner: str
    wire_name: str
    kind: str
    minimum_version: int


@dataclass(frozen=True)
class CoupledPresenceGroup:
    owner: str
    name: str
    kind: str
    wire_names: tuple[str, ...]


@dataclass(frozen=True)
class NestedShape:
    name: str
    kotlin_type: str
    decoder: str
    fields: tuple[WireField, ...]
    spec_domain: str = "shape"
    spec_owner: str | None = None
    spec_reference_target: str | None = None


@dataclass(frozen=True)
class ServerMessageEntry:
    method: str
    message_type: str
    decoder: str
    minimum_version: int | None
    fields: tuple[WireField, ...]


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
    canonical_extension_projection: tuple[str, ...] | None = None
    model_output: str = "request-models"
    extension_output: str = "request-extensions"


@dataclass(frozen=True)
class RequestModelSpec:
    method: str
    constructor_parameters: tuple[Parameter, ...]
    kind: str = "data class"
    kdoc: str | None = None
    annotations: tuple[str, ...] = ()
    minimum_expression: str | None = None
    interfaces: tuple[str, ...] = ()
    validations: tuple[str, ...] = ()
    unstored_parameters: tuple[str, ...] = ()
    snapshots: tuple[str, ...] = ()
    body_feature: str = "standard"
    encoder_feature: str = "fields"
    decoder_feature: str = "fields"


@dataclass(frozen=True)
class SelectorOverload:
    method: str
    parameters: tuple[Parameter, ...] = ()
    request_arguments: tuple[str, ...] = ()
    function_name: str | None = None
    parameter_projection: tuple[str, ...] = ()
    strip_projection_defaults: bool = False


def parameter(name: str, type: str, default: str | None = None) -> Parameter:
    return Parameter(name, type, default)


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


def RW(
    owner: str,
    kotlin_property: str,
    wire_type: str,
    presence: str,
    encoder: str,
    wire_name: str,
    *,
    minimum_version: int | None = None,
    nested_shape: str | None = None,
    source_expression: str | None = None,
) -> WireField:
    return WireField(
        owner=owner,
        kotlin_property=kotlin_property,
        wire_names=(wire_name,),
        wire_type=wire_type,
        presence=presence,
        decoder=encoder,
        minimum_version=minimum_version,
        nested_shape=nested_shape,
        direction="client-to-server",
        source_expression=source_expression,
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


def _spec_waivers(reason: str, *identities: str) -> tuple[tuple[str, str], ...]:
    """Attach one reviewed reason to each exact catalog occurrence."""
    return tuple((identity, reason) for identity in identities)


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
        snapshots=(S("services", "List<HtspChannelService>?", "services?.immutableSnapshot()"), S("tagIds", "List<Long>?", "tagIds?.immutableSnapshot()")),
        validations=(V("u32", ("channelId", "channelId"), ("channelNumber", "channelNumber"), ("channelNumberMinor", "channelNumberMinor"), ("currentEventId", "currentEventId"), ("nextEventId", "nextEventId")), V("u32-list", ("tagIds", "this.tagIds")), V("channel-services"))),
    KotlinDeclaration("HtspChannelUpdateMessage", "class", (
        CHANNEL_PROPERTIES[0], CHANNEL_PROPERTIES[2], CHANNEL_PROPERTIES[3], CHANNEL_PROPERTIES[4],
        CHANNEL_PROPERTIES[1], *CHANNEL_PROPERTIES[5:]), supertype="HtspServerMessage",
        snapshots=(S("services", "List<HtspChannelService>?", "services?.immutableSnapshot()"), S("tagIds", "List<Long>?", "tagIds?.immutableSnapshot()")),
        validations=(V("u32", ("channelId", "channelId"), ("channelNumber", "channelNumber"), ("channelNumberMinor", "channelNumberMinor"), ("currentEventId", "currentEventId"), ("nextEventId", "nextEventId")), V("u32-list", ("tagIds", "this.tagIds")), V("channel-services"))),
    KotlinDeclaration("HtspChannelDeleteMessage", "data class", (P("channelId", "Long"),), supertype="HtspServerMessage", validations=(V("u32", ("channelId", "channelId")),)),
    KotlinDeclaration("HtspTagAddMessage", "class", TAG_PROPERTIES, supertype="HtspServerMessage", snapshots=(S("channelIds", "List<Long>?", "channelIds?.immutableSnapshot()"),), validations=(V("u32", ("tagId", "tagId"), ("tagIndex", "tagIndex"), ("tagTitledIcon", "tagTitledIcon")), V("u32-list", ("channelIds", "this.channelIds")))),
    KotlinDeclaration("HtspTagUpdateMessage", "class", (TAG_PROPERTIES[0], TAG_PROPERTIES[2], TAG_PROPERTIES[3], TAG_PROPERTIES[1], *TAG_PROPERTIES[4:]), supertype="HtspServerMessage", snapshots=(S("channelIds", "List<Long>?", "channelIds?.immutableSnapshot()"),), validations=(V("u32", ("tagId", "tagId"), ("tagIndex", "tagIndex"), ("tagTitledIcon", "tagTitledIcon")), V("u32-list", ("channelIds", "this.channelIds")))),
    KotlinDeclaration("HtspTagDeleteMessage", "data class", (P("tagId", "Long"),), supertype="HtspServerMessage", validations=(V("u32", ("tagId", "tagId")),)),
    KotlinDeclaration("HtspDvrRecordingFile", "data class", (P("fileId", "Long?"), P("path", "String?"), P("start", "Long?"), P("stop", "Long?"), P("sizeBytes", "Long?")), kdoc="Known bounded fields from one otherwise upstream-dynamic DVR recording-file map.", validations=(V("u32", ("fileId", "fileId")),)),
    KotlinDeclaration("HtspDvrEntryAddMessage", "class", DVR_PROPERTIES, supertype="HtspServerMessage", snapshots=(S("files", "List<HtspDvrRecordingFile>?", "files?.immutableSnapshot()"),), validations=(V("u32", ("entryId", "entryId"), *DVR_U32),)),
    KotlinDeclaration("HtspDvrEntryUpdateMessage", "class", DVR_PROPERTIES, supertype="HtspServerMessage", snapshots=(S("files", "List<HtspDvrRecordingFile>?", "files?.immutableSnapshot()"),), validations=(V("u32", ("entryId", "entryId"), *DVR_U32),)),
    KotlinDeclaration("HtspDvrEntryDeleteMessage", "data class", (P("entryId", "Long"),), supertype="HtspServerMessage", validations=(V("u32", ("entryId", "entryId")),)),
    KotlinDeclaration("HtspAutorecEntryAddMessage", "data class", AUTOREC_ADD_PROPERTIES, kdoc="A complete automatic DVR rule announced during asynchronous metadata sync.", supertype="HtspServerMessage", validations=(V("u32", *AUTOREC_U32),)),
    KotlinDeclaration("HtspAutorecEntryUpdateMessage", "data class", AUTOREC_UPDATE_PROPERTIES, kdoc="A partial automatic DVR rule update. Null properties were absent on the wire.", supertype="HtspServerMessage", validations=(V("u32", *AUTOREC_U32),)),
    KotlinDeclaration("HtspAutorecEntryDeleteMessage", "data class", (P("id", "String"),), supertype="HtspServerMessage"),
    KotlinDeclaration("HtspTimerecEntryAddMessage", "data class", TIMEREC_ADD_PROPERTIES, kdoc="A complete time-based DVR rule announced during asynchronous metadata sync.", supertype="HtspServerMessage", validations=(V("timerec"),)),
    KotlinDeclaration("HtspTimerecEntryUpdateMessage", "data class", TIMEREC_UPDATE_PROPERTIES, kdoc="A partial time-based DVR rule update. Null properties were absent on the wire.", supertype="HtspServerMessage", validations=(V("timerec"),)),
    KotlinDeclaration("HtspTimerecEntryDeleteMessage", "data class", (P("id", "String"),), supertype="HtspServerMessage"),
    KotlinDeclaration("HtspEventAddMessage", "class", (P("event", "HtspEvent", stored=False), P("genre", "String?", "null"), P("episodeId", "Long?", "null"), P("seriesLinkId", "Long?", "null")), supertype="HtspServerMessage", snapshots=(S("event", "HtspEvent", "event.copy(categories = event.categories?.immutableSnapshot(), keywords = event.keywords?.immutableSnapshot())"),), validations=(V("event-add"),)),
    KotlinDeclaration("HtspEventUpdateMessage", "class", EVENT_UPDATE_PROPERTIES, supertype="HtspServerMessage", snapshots=(S("categories", "List<String>?", "categories?.immutableSnapshot()"), S("keywords", "List<String>?", "keywords?.immutableSnapshot()")), validations=(V("u32", ("eventId", "eventId"), *EVENT_U32),)),
    KotlinDeclaration("HtspEventDeleteMessage", "data class", (P("eventId", "Long"),), supertype="HtspServerMessage", validations=(V("u32", ("eventId", "eventId")),)),
    KotlinDeclaration("HtspInitialSyncCompletedMessage", "data object", supertype="HtspServerMessage"),
    KotlinDeclaration("HtspMuxPacketMessage", "data class", (P("subscriptionId", "Long"), P("frameType", "Long"), P("streamIndex", "Long"), P("decodingTimestamp", "Long?"), P("presentationTimestamp", "Long?"), P("duration", "Long"), P("payload", "HtspBinary")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId"), ("frameType", "frameType"), ("streamIndex", "streamIndex"), ("duration", "duration")),)),
    KotlinDeclaration("HtspQueueStatusMessage", "data class", (P("subscriptionId", "Long"), P("packetCount", "Long"), P("byteCount", "Long"), P("delay", "Long?"), P("bFrameDropCount", "Long"), P("pFrameDropCount", "Long"), P("iFrameDropCount", "Long")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId"), ("packetCount", "packetCount"), ("byteCount", "byteCount"), ("bFrameDropCount", "bFrameDropCount"), ("pFrameDropCount", "pFrameDropCount"), ("iFrameDropCount", "iFrameDropCount")),)),
    KotlinDeclaration("HtspSubscriptionStream", "data class", (P("streamIndex", "Long"), P("streamType", "String"), P("language", "String?"), P("compositionId", "Long?"), P("ancillaryId", "Long?"), P("width", "Long?"), P("height", "Long?"), P("frameDuration", "Long?"), P("aspectNumerator", "Long?"), P("aspectDenominator", "Long?"), P("audioType", "Long?"), P("audioVersion", "Long?"), P("channelCount", "Long?"), P("sampleRate", "Long?"), P("rdsUecp", "Long?"), P("codecMetadata", "HtspBinary?", "null")), validations=(V("u32", ("streamIndex", "streamIndex")), V("u32-group", *(("stream field", n) for n in ("compositionId", "ancillaryId", "width", "height", "frameDuration", "aspectNumerator", "aspectDenominator", "audioType", "audioVersion", "channelCount", "sampleRate", "rdsUecp"))))),
    KotlinDeclaration("HtspSubscriptionSourceInfo", "data class", tuple(P(n, "String?") for n in ("adapterUuid", "muxUuid", "networkUuid", "adapter", "mux", "network", "networkType", "provider", "service", "satellitePosition"))),
    KotlinDeclaration("HtspSubscriptionStartMessage", "class", (P("subscriptionId", "Long"), P("streams", "List<HtspSubscriptionStream>?", "null", stored=False), P("sourceInfo", "HtspSubscriptionSourceInfo?", "null"), P("codecMetadata", "HtspBinary?", "null"), P("status", "String?", "null"), P("subscriptionError", "String?", "null")), supertype="HtspServerMessage", snapshots=(S("streams", "List<HtspSubscriptionStream>?", "streams?.immutableSnapshot()"),), validations=(V("u32", ("subscriptionId", "subscriptionId")),)),
    KotlinDeclaration("HtspSubscriptionStopMessage", "data class", (P("subscriptionId", "Long"), P("status", "String?"), P("subscriptionError", "String?")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId")),)),
    KotlinDeclaration("HtspSubscriptionGraceMessage", "data class", (P("subscriptionId", "Long"), P("graceTimeoutSeconds", "Long")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId"), ("graceTimeoutSeconds", "graceTimeoutSeconds")),)),
    KotlinDeclaration("HtspSubscriptionStatusMessage", "data class", (P("subscriptionId", "Long"), P("status", "String?"), P("subscriptionError", "String?")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId")),)),
    KotlinDeclaration("HtspSignalStatusMessage", "data class", (P("subscriptionId", "Long"), P("frontendStatus", "String?"), P("relativeSnr", "Long?"), P("absoluteSnr", "Long?"), P("relativeSignal", "Long?"), P("absoluteSignal", "Long?"), P("bitErrorRate", "Long?"), P("uncorrectedBlockCount", "Long?")), supertype="HtspServerMessage", validations=(V("u32", ("subscriptionId", "subscriptionId")), V("u32-group", *(("signal field", n) for n in ("relativeSnr", "relativeSignal", "bitErrorRate", "uncorrectedBlockCount"))))),
    KotlinDeclaration("HtspDescrambleInfoMessage", "data class", (P("subscriptionId", "Long"), P("pid", "Long"), P("conditionalAccessId", "Long"), P("providerId", "Long"), P("ecmTime", "Long"), P("hopCount", "Long"), P("cardSystem", "String?", "null"), P("reader", "String?", "null"), P("source", "String?", "null"), P("protocol", "String?", "null")), kdoc="Descrambling observations for one subscription, including PID, access and provider identifiers, ECM timing, hop count, and optional source labels.", supertype="HtspServerMessage", validations=(V("u32-group", *(("descramble field", n) for n in ("subscriptionId", "pid", "conditionalAccessId", "providerId", "ecmTime", "hopCount"))),)),
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
    NestedShape("service", "HtspChannelService", "decodeServerChannelService", SERVICE_FIELDS, spec_owner="service"),
    NestedShape("dvr-file", "HtspDvrRecordingFile", "decodeDvrRecordingFile", DVR_FILE_FIELDS, spec_owner="recordingFile"),
    NestedShape(
        "event",
        "HtspEvent",
        "decodeServerEvent",
        EVENT_FIELDS,
        spec_owner="event",
        spec_reference_target="message:eventAdd",
    ),
    NestedShape("stream", "HtspSubscriptionStream", "decodeSubscriptionStream", STREAM_FIELDS, spec_owner="stream"),
    NestedShape("source-info", "HtspSubscriptionSourceInfo", "decodeSubscriptionSourceInfo", SOURCE_FIELDS, spec_owner="sourceInfo"),
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
    ServerMessageEntry("eventAdd", "HtspEventAddMessage", "decodeEventAdd", 6, (W("eventAdd", "event", MAP, REQ, "root-shape", "<root>", nested_shape="event"), W("eventAdd", "genre", STR, OPT, "event-genre", "genre", "category"), W("eventAdd", "episodeId", U32, OPT, "u32", "episodeId"), WF("eventAdd", "seriesLinkId", U32, OPT, "u32", ("serieslinkId", "seriesLinkId")))),
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

SERVER_DISPATCH_DECLARATIONS: tuple[KotlinDeclaration, ...] = (
    KotlinDeclaration("HtspServerMessageDecodeResult", "sealed interface", kdoc="Finite outcome of decoding one candidate asynchronous HTSP message."),
    KotlinDeclaration("HtspServerMessageDecoded", "data class", kdoc="A recognized asynchronous message decoded into its typed model."),
    KotlinDeclaration("HtspServerMessageUnknownMethod", "data object", kdoc="A reply envelope or message whose method is absent, malformed, or unrecognized."),
    KotlinDeclaration("HtspServerMessageMalformedKnownMessage", "data object", kdoc="A recognized asynchronous method whose fields do not satisfy its typed decoder."),
)
SERVER_DISPATCH_DECODER_KDOC = "Decodes one raw field map into the finite typed asynchronous-message result family."


# Direct G3 traversal API: message and nested-shape fields, in reviewed decode order.
SERVER_WIRE_FIELDS: tuple[WireField, ...] = tuple(
    field for entry in SERVER_MESSAGE_CATALOG for field in entry.fields
) + tuple(field for shape in SERVER_NESTED_SHAPES for field in shape.fields)

# Exact catalog occurrences that intentionally preserve accepted wire vocabulary
# not represented identically by the pinned-v44 evidence.  G3 requires every
# entry to be consumed by one mismatch and rejects stale or canonical waivers.
SERVER_SPEC_WAIVERS: tuple[tuple[str, str], ...] = (
    *_spec_waivers(
        "shipped decoder accepts this exact compatibility wire name; pinned-v44 owner inventory omits it",
        "message.channelAdd.channelNumber.wire.number",
        "message.channelAdd.channelNumber.wire.lcn",
        "message.channelAdd.channelNumber.wire.channelNum",
        "message.channelAdd.channelNumber.wire.channelno",
        "message.channelAdd.tagIds.wire.tagIds",
        "message.channelAdd.tagIds.wire.channelTags",
        "message.channelUpdate.channelNumber.wire.number",
        "message.channelUpdate.channelNumber.wire.lcn",
        "message.channelUpdate.channelNumber.wire.channelNum",
        "message.channelUpdate.channelNumber.wire.channelno",
        "message.channelUpdate.tagIds.wire.tagIds",
        "message.channelUpdate.tagIds.wire.channelTags",
        "message.tagAdd.tagId.wire.id",
        "message.tagAdd.tagIndex.wire.index",
        "message.tagAdd.tagName.wire.name",
        "message.tagUpdate.tagId.wire.id",
        "message.tagUpdate.tagIndex.wire.index",
        "message.tagUpdate.tagName.wire.name",
        "message.tagDelete.tagId.wire.id",
        "message.dvrEntryAdd.entryId.wire.dvrId",
        "message.dvrEntryAdd.channelId.wire.channelId",
        "message.dvrEntryAdd.playCount.wire.playCount",
        "message.dvrEntryAdd.playPositionSeconds.wire.playPosition",
        "message.dvrEntryAdd.seasonNumber.wire.seasonNumber",
        "message.dvrEntryAdd.episodeNumber.wire.episodeNumber",
        "message.dvrEntryAdd.episodeCount.wire.episodeCount",
        "message.dvrEntryAdd.partNumber.wire.partNumber",
        "message.dvrEntryAdd.partCount.wire.partCount",
        "message.dvrEntryAdd.state.wire.status",
        "message.dvrEntryAdd.error.wire.statusError",
        "message.dvrEntryUpdate.entryId.wire.dvrId",
        "message.dvrEntryUpdate.channelId.wire.channelId",
        "message.dvrEntryUpdate.playCount.wire.playCount",
        "message.dvrEntryUpdate.playPositionSeconds.wire.playPosition",
        "message.dvrEntryUpdate.seasonNumber.wire.seasonNumber",
        "message.dvrEntryUpdate.episodeNumber.wire.episodeNumber",
        "message.dvrEntryUpdate.episodeCount.wire.episodeCount",
        "message.dvrEntryUpdate.partNumber.wire.partNumber",
        "message.dvrEntryUpdate.partCount.wire.partCount",
        "message.dvrEntryUpdate.state.wire.status",
        "message.dvrEntryUpdate.error.wire.statusError",
        "message.dvrEntryDelete.entryId.wire.dvrId",
        "message.eventAdd.genre.wire.genre",
        "message.eventAdd.episodeId.wire.episodeId",
        "message.eventAdd.seriesLinkId.wire.serieslinkId",
        "message.eventAdd.seriesLinkId.wire.seriesLinkId",
        "message.eventUpdate.eventId.wire.id",
        "message.eventUpdate.channelId.wire.channel",
        "message.eventUpdate.start.wire.startTime",
        "message.eventUpdate.stop.wire.stopTime",
        "message.eventUpdate.title.wire.eventTitle",
        "message.eventUpdate.title.wire.name",
        "message.eventUpdate.genre.wire.genre",
        "message.eventUpdate.contentType.wire.content",
        "message.eventUpdate.seasonNumber.wire.season",
        "message.eventUpdate.seasonNumber.nested-wire.season",
        "message.eventUpdate.seasonCount.nested-wire.count",
        "message.eventUpdate.episodeNumber.nested-wire.number",
        "message.eventUpdate.episodeCount.nested-wire.count",
        "message.eventUpdate.partNumber.wire.part",
        "message.eventUpdate.partNumber.nested-wire.part",
        "message.eventUpdate.episodeId.wire.episodeId",
        "message.eventUpdate.seriesLinkId.wire.serieslinkId",
        "message.eventUpdate.seriesLinkId.wire.seriesLinkId",
        "message.eventDelete.eventId.wire.id",
        "message.subscriptionStart.subscriptionId.wire.id",
        "message.subscriptionStart.status.wire.state",
        "message.subscriptionStart.status.wire.status",
        "message.subscriptionStart.subscriptionError.wire.subscriptionError",
        "message.subscriptionStart.subscriptionError.wire.error",
        "message.subscriptionStop.subscriptionId.wire.id",
        "message.subscriptionStop.status.wire.state",
        "message.subscriptionStop.subscriptionError.wire.error",
        "message.subscriptionStatus.subscriptionId.wire.id",
        "message.subscriptionStatus.status.wire.state",
        "message.subscriptionStatus.subscriptionError.wire.error",
        "message-nested.dvr-file.fileId.wire.id",
        "message-nested.dvr-file.path.wire.filename",
        "message-nested.dvr-file.path.wire.path",
        "message-nested.dvr-file.start.wire.start",
        "message-nested.dvr-file.stop.wire.stop",
        "message-nested.dvr-file.sizeBytes.wire.size",
        "message-nested.event.eventId.wire.id",
        "message-nested.event.channelId.wire.channel",
        "message-nested.event.start.wire.startTime",
        "message-nested.event.stop.wire.stopTime",
        "message-nested.event.title.wire.eventTitle",
        "message-nested.event.title.wire.name",
        "message-nested.event.contentType.wire.content",
        "message-nested.event.seasonNumber.wire.season",
        "message-nested.event.seasonNumber.nested-wire.season",
        "message-nested.event.seasonCount.nested-wire.count",
        "message-nested.event.episodeNumber.nested-wire.number",
        "message-nested.event.episodeCount.nested-wire.count",
        "message-nested.event.partNumber.wire.part",
        "message-nested.event.partNumber.nested-wire.part",
    ),
    *_spec_waivers(
        "shipped decoder preserves Boolean flag handling; pinned-v44 emitter evidence records u32",
        "message.autorecEntryAdd.enabled.wire.enabled",
        "message.autorecEntryAdd.fullText.wire.fulltext",
        "message.autorecEntryAdd.mergeText.wire.mergetext",
        "message.autorecEntryUpdate.enabled.wire.enabled",
        "message.autorecEntryUpdate.fullText.wire.fulltext",
        "message.autorecEntryUpdate.mergeText.wire.mergetext",
        "message.timerecEntryAdd.enabled.wire.enabled",
        "message.timerecEntryUpdate.enabled.wire.enabled",
    ),
    *_spec_waivers(
        "shipped decoder preserves signed channel handling; pinned-v44 emitter evidence records u32",
        "message.timerecEntryAdd.channelId.wire.channel",
        "message.timerecEntryUpdate.channelId.wire.channel",
    ),
    *_spec_waivers(
        "shipped decoder accepts a scalar compatibility alias; pinned-v44 canonical field is a list",
        "message.eventAdd.genre.wire.category",
        "message.eventUpdate.genre.wire.category",
    ),
    *_spec_waivers(
        "shipped catalog distinguishes the accepted map container; pinned-v44 evidence records generic msg",
        "message.subscriptionStart.sourceInfo.wire.sourceinfo",
    ),
)
SERVER_SPEC_CONSISTENCY_STATUS = "verified-v44"

# G1 uses no per-entry verbatim Kotlin escape.  Irregular event/timerec/nested
# behavior is represented by named decoder and validation features above.
SERVER_VERBATIM_ESCAPES: tuple[tuple[str, str], ...] = ()


# Reviewed request constructor and public-extension authority.  These objects
# are shared by both request-model and request-extension renderers.
def _parameters(*values: tuple[str, str] | tuple[str, str, str]) -> tuple[Parameter, ...]:
    return tuple(parameter(*value) for value in values)


_AUTOREC_COMMON = _parameters(
    ("channel", "HtspRecordingRuleChannel?", "null"),
    ("minDurationSeconds", "Long?", "null"), ("maxDurationSeconds", "Long?", "null"),
    ("fullText", "Long?", "null"), ("mergeText", "Long?", "null"),
    ("duplicateDetection", "Long?", "null"), ("maximumRecordingCount", "Long?", "null"),
    ("broadcastType", "Long?", "null"), ("startExtraMinutes", "Long?", "null"),
    ("stopExtraMinutes", "Long?", "null"), ("seriesLinkUri", "String?", "null"),
)
_RULE_TAIL = _parameters(
    ("enabled", "Boolean?", "null"), ("retentionDays", "Long?", "null"),
    ("removalDays", "Long?", "null"), ("priority", "Long?", "null"),
    ("name", "String?", "null"), ("comment", "String?", "null"),
    ("directory", "String?", "null"), ("configName", "String?", "null"),
    ("daysOfWeekMask", "Long?", "null"),
)
_TIMEREC_COMMON = _parameters(
    ("channel", "HtspRecordingRuleChannel?", "null"),
    ("startMinutesSinceMidnight", "Long?", "null"),
    ("stopMinutesSinceMidnight", "Long?", "null"),
)


CATALOG: tuple[Entry, ...] = (
    Entry("getProfiles", "GetProfilesRequest", "GetProfilesResponse", "ACCESS_HTSP_STREAMING", 16),
    Entry("getDiskSpace", "GetDiskSpaceRequest", "GetDiskSpaceResponse", "ACCESS_HTSP_STREAMING", 3),
    Entry("getSysTime", "GetSysTimeRequest", "GetSysTimeResponse", "ACCESS_HTSP_STREAMING", 3),
    Entry("enableAsyncMetadata", "EnableAsyncMetadataRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", None, _parameters(
        ("epg", "Long?", "null"), ("lastUpdate", "Long?", "null"),
        ("epgMaxTime", "Long?", "null"), ("language", "String?", "null"),
    )),
    Entry("getChannel", "GetChannelRequest", "GetChannelResponse", "ACCESS_HTSP_STREAMING", 14, _parameters(("channelId", "Long"))),
    Entry("getEvent", "GetEventRequest", "GetEventResponse", "ACCESS_HTSP_STREAMING", None, _parameters(
        ("eventId", "Long"), ("language", "String?", "null"),
    )),
    Entry("getEvents", "GetEventsRequest", "GetEventsResponse", "ACCESS_HTSP_STREAMING", 4, _parameters(
        ("channelId", "Long?", "null"), ("eventId", "Long?", "null"),
        ("language", "String?", "null"), ("numFollowing", "Long?", "null"),
        ("maxTime", "Long?", "null"),
    )),
    Entry("epgQuery", "EpgQueryRequest", "EpgQueryResponse", "ACCESS_HTSP_STREAMING", 4, _parameters(
        ("query", "String"), ("channelId", "Long?", "null"), ("tagId", "Long?", "null"),
        ("contentType", "Long?", "null"), ("language", "String?", "null"),
        ("fullText", "Boolean?", "null"), ("mergeText", "Boolean?", "null"),
        ("full", "Long?", "null"), ("minDurationSeconds", "Long?", "null"),
        ("maxDurationSeconds", "Long?", "null"),
    )),
    Entry("getEpgObject", "GetEpgObjectRequest", "GetEpgObjectResponse", "ACCESS_HTSP_STREAMING", None, _parameters(
        ("id", "Long"), ("objectType", "HtspEpgObjectType?", "HtspEpgObjectType.BROADCAST"),
    )),
    Entry("getDvrConfigs", "GetDvrConfigsRequest", "GetDvrConfigsResponse", "ACCESS_HTSP_RECORDER", 16),
    Entry("addDvrEntry", "AddDvrEntryRequest", "AddDvrEntryResponse", "ACCESS_HTSP_RECORDER", 4, _parameters(
        ("selector", "AddDvrEntrySelector"), ("configName", "String?", "null"),
        ("language", "String?", "null"), ("title", "String?", "null"),
        ("subtitle", "String?", "null"), ("summary", "String?", "null"),
        ("description", "String?", "null"), ("ageRating", "Long?", "null"),
    )),
    Entry("updateDvrEntry", "UpdateDvrEntryRequest", "UpdateDvrEntryResponse", "ACCESS_HTSP_RECORDER", 5, _parameters(
        ("entryId", "Long"), ("channelId", "Long?", "null"), ("configName", "String?", "null"),
        ("title", "String?", "null"), ("subtitle", "String?", "null"),
        ("summary", "String?", "null"), ("description", "String?", "null"),
        ("language", "String?", "null"), ("comment", "String?", "null"),
        ("playCount", "Long?", "null"), ("playPosition", "Long?", "null"),
        ("enabled", "Long?", "null"), ("start", "Long?", "null"),
        ("stop", "Long?", "null"), ("startExtra", "Long?", "null"),
        ("stopExtra", "Long?", "null"), ("retention", "Long?", "null"),
        ("removal", "Long?", "null"), ("priority", "Long?", "null"),
        ("ageRating", "Long?", "null"),
    )),
    Entry("stopDvrEntry", "StopDvrEntryRequest", "StopDvrEntryResponse", "ACCESS_HTSP_RECORDER", None, _parameters(("entryId", "Long"))),
    Entry("cancelDvrEntry", "CancelDvrEntryRequest", "CancelDvrEntryResponse", "ACCESS_HTSP_RECORDER", 5, _parameters(("entryId", "Long"))),
    Entry("deleteDvrEntry", "DeleteDvrEntryRequest", "DeleteDvrEntryResponse", "ACCESS_HTSP_RECORDER", 4, _parameters(("entryId", "Long"))),
    Entry("addAutorecEntry", "AddAutorecEntryRequest", "AddAutorecEntryResponse", "ACCESS_HTSP_RECORDER", 13, (
        parameter("title", "String"), *_AUTOREC_COMMON,
        parameter("approximateStartMinutesSinceMidnight", "Int?", "null"),
        parameter("startMinutesSinceMidnight", "Int?", "null"),
        parameter("startWindowEndMinutesSinceMidnight", "Int?", "null"), *_RULE_TAIL,
    )),
    Entry("updateAutorecEntry", "UpdateAutorecEntryRequest", "UpdateAutorecEntryResponse", "ACCESS_HTSP_RECORDER", 25, (
        parameter("id", "String"), *_AUTOREC_COMMON,
        parameter("startMinutesSinceMidnight", "Int?", "null"),
        parameter("startWindowEndMinutesSinceMidnight", "Int?", "null"),
        *_RULE_TAIL[:-2], parameter("title", "String?", "null"), *_RULE_TAIL[-2:],
    )),
    Entry("deleteAutorecEntry", "DeleteAutorecEntryRequest", "DeleteAutorecEntryResponse", "ACCESS_HTSP_RECORDER", 13, _parameters(("id", "String"))),
    Entry("addTimerecEntry", "AddTimerecEntryRequest", "AddTimerecEntryResponse", "ACCESS_HTSP_RECORDER", 18, (
        parameter("title", "String"), *_TIMEREC_COMMON, *_RULE_TAIL,
    )),
    Entry("updateTimerecEntry", "UpdateTimerecEntryRequest", "UpdateTimerecEntryResponse", "ACCESS_HTSP_RECORDER", 25, (
        parameter("id", "String"), *_TIMEREC_COMMON,
        *_RULE_TAIL[:-2], parameter("title", "String?", "null"), *_RULE_TAIL[-2:],
    )),
    Entry("deleteTimerecEntry", "DeleteTimerecEntryRequest", "DeleteTimerecEntryResponse", "ACCESS_HTSP_RECORDER", 18, _parameters(("id", "String"))),
    Entry("getDvrCutpoints", "GetDvrCutpointsRequest", "GetDvrCutpointsResponse", "ACCESS_HTSP_RECORDER", 12, _parameters(("entryId", "Long"))),
    Entry("getTicket", "GetTicketRequest", "GetTicketResponse", "ACCESS_HTSP_STREAMING", 5, _parameters(("selector", "GetTicketSelector"))),
    Entry("subscribe", "SubscribeRequest", "SubscribeResponse", "ACCESS_HTSP_STREAMING", None, _parameters(
        ("subscriptionId", "Long"), ("channel", "SubscribeChannel"),
        ("profile", "String?", "null"), ("weight", "Long?", "null"),
        ("ninetyKhz", "Long?", "null"), ("timeshiftPeriodSeconds", "Long?", "null"),
        ("queueDepth", "Long?", "null"),
    )),
    Entry("unsubscribe", "UnsubscribeRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", None, _parameters(("subscriptionId", "Long"))),
    Entry("subscriptionChangeWeight", "SubscriptionChangeWeightRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 5, _parameters(
        ("subscriptionId", "Long"), ("weight", "Long?", "null"),
    )),
    Entry("subscriptionSeek", "SubscriptionSeekRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 9, _parameters(
        ("subscriptionId", "Long"), ("position", "SubscriptionSeekPosition"),
        ("absolute", "Long?", "null"),
    )),
    Entry("subscriptionSkip", "SubscriptionSkipRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 9, _parameters(
        ("subscriptionId", "Long"), ("position", "SubscriptionSeekPosition"),
        ("absolute", "Long?", "null"),
    )),
    Entry("subscriptionSpeed", "SubscriptionSpeedRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 9, _parameters(
        ("subscriptionId", "Long"), ("speed", "Int"),
    )),
    Entry("subscriptionLive", "SubscriptionLiveRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 9, _parameters(("subscriptionId", "Long"))),
    Entry("subscriptionFilterStream", "SubscriptionFilterStreamRequest", "HtspEmptyResponse", "ACCESS_HTSP_STREAMING", 12, _parameters(
        ("subscriptionId", "Long"), ("enable", "List<Long>?", "null"),
        ("disable", "List<Long>?", "null"),
    )),
    Entry("fileOpen", "FileOpenRequest", "FileOpenResponse", "ACCESS_HTSP_RECORDER", 8, _parameters(("file", "String"))),
    Entry("fileRead", "FileReadRequest", "FileReadResponse", "ACCESS_HTSP_RECORDER", 8, _parameters(
        ("id", "Long"), ("size", "Long"), ("offset", "Long?", "null"),
    )),
    Entry("fileClose", "FileCloseRequest", "FileCloseResponse", "ACCESS_HTSP_RECORDER", 8, _parameters(
        ("id", "Long"), ("playPositionSeconds", "Long?", "null"),
        ("playCount", "Long?", "null"),
    ), ("id",)),
    Entry("fileStat", "FileStatRequest", "FileStatResponse", "ACCESS_HTSP_RECORDER", 8, _parameters(("id", "Long"))),
    Entry("fileSeek", "FileSeekRequest", "FileSeekResponse", "ACCESS_HTSP_RECORDER", 8, _parameters(
        ("id", "Long"), ("offset", "Long"), ("whence", "FileSeekWhence?", "null"),
    )),
    Entry("api", "ApiRequest", "ApiResponse", "ACCESS_ANONYMOUS", 24, _parameters(
        ("path", "String"), ("args", "HtspApiObject?", "null"),
    ), model_output="jsonapi-models", extension_output="jsonapi-extensions"),
    Entry("hello", "HelloRequest", "HelloResponse", "ACCESS_ANONYMOUS", None, _parameters(
        ("htspVersion", "Long"), ("clientName", "String"),
    )),
    Entry("authenticate", "AuthenticateRequest", "AuthenticateResponse", "ACCESS_ANONYMOUS", None),
)


SELECTOR_OVERLOADS: tuple[SelectorOverload, ...] = (
    SelectorOverload("addDvrEntry", (parameter("eventId", "Long"), *CATALOG[10].parameters[1:]),
        ("selector = AddDvrEntrySelector.Event(eventId)", *(f"{value.name} = {value.name}" for value in CATALOG[10].parameters[1:]))),
    SelectorOverload("addDvrEntry", (parameter("channelId", "Long"), parameter("start", "Long"), parameter("stop", "Long"), *CATALOG[10].parameters[1:]),
        ("selector = AddDvrEntrySelector.ExplicitChannelTime(channelId, start, stop)", *(f"{value.name} = {value.name}" for value in CATALOG[10].parameters[1:]))),
    SelectorOverload("subscribe", (CATALOG[23].parameters[0], parameter("channelId", "Long"), *CATALOG[23].parameters[2:]),
        ("subscriptionId = subscriptionId", "channel = SubscribeChannel.Id(channelId)", *(f"{value.name} = {value.name}" for value in CATALOG[23].parameters[2:]))),
    SelectorOverload("subscribe", (CATALOG[23].parameters[0], parameter("channelName", "String"), *CATALOG[23].parameters[2:]),
        ("subscriptionId = subscriptionId", "channel = SubscribeChannel.Name(channelName)", *(f"{value.name} = {value.name}" for value in CATALOG[23].parameters[2:]))),
    SelectorOverload("subscriptionSeek", (CATALOG[26].parameters[0], parameter("position", "SubscriptionSeekPosition.Time"), *CATALOG[26].parameters[2:]), tuple(f"{value.name} = {value.name}" for value in CATALOG[26].parameters)),
    SelectorOverload("subscriptionSeek", (CATALOG[26].parameters[0], parameter("position", "SubscriptionSeekPosition.Size"), *CATALOG[26].parameters[2:]), tuple(f"{value.name} = {value.name}" for value in CATALOG[26].parameters)),
    SelectorOverload("subscriptionSkip", (CATALOG[27].parameters[0], parameter("position", "SubscriptionSeekPosition.Time"), *CATALOG[27].parameters[2:]), tuple(f"{value.name} = {value.name}" for value in CATALOG[27].parameters)),
    SelectorOverload("subscriptionSkip", (CATALOG[27].parameters[0], parameter("position", "SubscriptionSeekPosition.Size"), *CATALOG[27].parameters[2:]), tuple(f"{value.name} = {value.name}" for value in CATALOG[27].parameters)),
    SelectorOverload("getTicket", (parameter("selector", "GetTicketSelector.Channel"),), ("selector = selector",)),
    SelectorOverload("getTicket", (parameter("selector", "GetTicketSelector.Dvr"),), ("selector = selector",)),
    SelectorOverload(
        "fileClose",
        function_name="fileCloseWithProgress",
        parameter_projection=("id", "playPositionSeconds", "playCount"),
        strip_projection_defaults=True,
    ),
)


# Every generated public declaration has one explicit reviewed prose record.
# The owner plus exact declaration name or callable signature is its stable key;
# renderers only look up these records and never invent documentation.
GeneratedKDocRecord = tuple[str, str, str]


GENERATED_FUNCTION_KDOC_RECORDS: tuple[GeneratedKDocRecord, ...] = (
    ("jsonapi-extensions", "HtspConnection.api(String,HtspApiObject?,Long,HtspConnectionGeneration?)", "Calls one JSON API path with an optional object argument through typed connection execution; failures remain [HtspResult] values."),
    ("server-dispatch", "decodeHtspServerMessage(Map<String,Any?>)", "Classifies one raw field map as a decoded server message, an unknown method, or malformed known input without throwing decoder failures."),
    ("request-extensions", "HtspConnection.getProfiles(Long,HtspConnectionGeneration?)", "Fetches the server's stream-profile metadata through typed connection execution and returns its transport or reply failure as [HtspResult]."),
    ("request-extensions", "HtspConnection.getDiskSpace(Long,HtspConnectionGeneration?)", "Reads free, used, and total recording-storage counters through the typed request boundary."),
    ("request-extensions", "HtspConnection.getSysTime(Long,HtspConnectionGeneration?)", "Reads the server clock and timezone observations through typed connection execution."),
    ("request-extensions", "HtspConnection.enableAsyncMetadata(Long?,Long?,Long?,String?,Long,HtspConnectionGeneration?)", "Requests asynchronous metadata delivery with the selected EPG window and language options and decodes the typed acknowledgement."),
    ("request-extensions", "HtspConnection.getChannel(Long,Long,HtspConnectionGeneration?)", "Fetches one channel by unsigned identifier and decodes the reply through the typed connection boundary."),
    ("request-extensions", "HtspConnection.getEvent(Long,String?,Long,HtspConnectionGeneration?)", "Fetches one EPG event by identifier, optionally localized to [language], through typed execution."),
    ("request-extensions", "HtspConnection.getEvents(Long?,Long?,String?,Long?,Long?,Long,HtspConnectionGeneration?)", "Fetches an event window selected by channel, event, language, following count, or maximum time through typed execution."),
    ("request-extensions", "HtspConnection.epgQuery(String,Long?,Long?,Long?,String?,Boolean?,Boolean?,Long?,Long?,Long?,Long,HtspConnectionGeneration?)", "Searches EPG text with the supplied channel, tag, content, language, detail, and duration filters through typed execution."),
    ("request-extensions", "HtspConnection.getEpgObject(Long,HtspEpgObjectType?,Long,HtspConnectionGeneration?)", "Fetches the selected detailed EPG object and decodes its finite broadcast shape through typed execution."),
    ("request-extensions", "HtspConnection.getDvrConfigs(Long,HtspConnectionGeneration?)", "Fetches visible DVR configurations through the typed recorder request boundary."),
    ("request-extensions", "HtspConnection.addDvrEntry(AddDvrEntrySelector,String?,String?,String?,String?,String?,String?,Long?,Long,HtspConnectionGeneration?)", "Requests DVR scheduling from the explicit selector and optional metadata, then decodes the typed mutation reply."),
    ("request-extensions", "HtspConnection.addDvrEntry(Long,String?,String?,String?,String?,String?,String?,Long?,Long,HtspConnectionGeneration?)", "Adapts [eventId] to [AddDvrEntrySelector.Event] before sending the same typed DVR-add request."),
    ("request-extensions", "HtspConnection.addDvrEntry(Long,Long,Long,String?,String?,String?,String?,String?,String?,Long?,Long,HtspConnectionGeneration?)", "Adapts channel, start, and stop to [AddDvrEntrySelector.ExplicitChannelTime] before sending a typed DVR-add request."),
    ("request-extensions", "HtspConnection.updateDvrEntry(Long,Long?,String?,String?,String?,String?,String?,String?,String?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,Long,HtspConnectionGeneration?)", "Requests a DVR-entry change carrying the supplied partial metadata, timing, progress, and policy fields."),
    ("request-extensions", "HtspConnection.stopDvrEntry(Long,Long,HtspConnectionGeneration?)", "Requests that the selected DVR entry stop and decodes the server's typed mutation reply."),
    ("request-extensions", "HtspConnection.cancelDvrEntry(Long,Long,HtspConnectionGeneration?)", "Requests cancellation of the selected DVR entry and decodes the server's typed mutation reply."),
    ("request-extensions", "HtspConnection.deleteDvrEntry(Long,Long,HtspConnectionGeneration?)", "Requests deletion of the selected DVR entry and returns the decoded typed mutation reply."),
    ("request-extensions", "HtspConnection.addAutorecEntry(String,HtspRecordingRuleChannel?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,String?,Int?,Int?,Int?,Boolean?,Long?,Long?,Long?,String?,String?,String?,String?,Long?,Long,HtspConnectionGeneration?)", "Requests an automatic recording rule with the supplied title, matching, schedule, retention, and ownership fields."),
    ("request-extensions", "HtspConnection.updateAutorecEntry(String,HtspRecordingRuleChannel?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,Long?,String?,Int?,Int?,Boolean?,Long?,Long?,Long?,String?,String?,String?,String?,String?,Long?,Long,HtspConnectionGeneration?)", "Requests a change to the identified automatic recording rule using only the supplied selector and policy fields."),
    ("request-extensions", "HtspConnection.deleteAutorecEntry(String,Long,HtspConnectionGeneration?)", "Requests deletion of the automatic recording rule identified by [id] through the typed recorder boundary."),
    ("request-extensions", "HtspConnection.addTimerecEntry(String,HtspRecordingRuleChannel?,Long?,Long?,Boolean?,Long?,Long?,Long?,String?,String?,String?,String?,Long?,Long,HtspConnectionGeneration?)", "Requests a time-based recording rule with the supplied channel, daily interval, day mask, and policy fields."),
    ("request-extensions", "HtspConnection.updateTimerecEntry(String,HtspRecordingRuleChannel?,Long?,Long?,Boolean?,Long?,Long?,Long?,String?,String?,String?,String?,String?,Long?,Long,HtspConnectionGeneration?)", "Requests a change to the identified time-based recording rule with the supplied interval and policy fields."),
    ("request-extensions", "HtspConnection.deleteTimerecEntry(String,Long,HtspConnectionGeneration?)", "Requests deletion of the time-based recording rule identified by [id] through typed connection execution."),
    ("request-extensions", "HtspConnection.getDvrCutpoints(Long,Long,HtspConnectionGeneration?)", "Fetches the ordered cutpoint coordinates and action codes for one DVR entry through typed execution."),
    ("request-extensions", "HtspConnection.getTicket(GetTicketSelector,Long,HtspConnectionGeneration?)", "Requests a temporary access path and ticket for exactly one channel or DVR selector through typed execution."),
    ("request-extensions", "HtspConnection.getTicket(GetTicketSelector.Channel,Long,HtspConnectionGeneration?)", "Forwards a channel ticket selector without widening it to another source kind, preserving the typed result boundary."),
    ("request-extensions", "HtspConnection.getTicket(GetTicketSelector.Dvr,Long,HtspConnectionGeneration?)", "Forwards a DVR ticket selector without widening it to another source kind, preserving the typed result boundary."),
    ("request-extensions", "HtspConnection.subscribe(Long,SubscribeChannel,String?,Long?,Long?,Long?,Long?,Long,HtspConnectionGeneration?)", "Requests a subscription for exactly one channel selector with profile, weight, timestamp, timeshift, and queue options."),
    ("request-extensions", "HtspConnection.subscribe(Long,Long,String?,Long?,Long?,Long?,Long?,Long,HtspConnectionGeneration?)", "Wraps [channelId] as [SubscribeChannel.Id] before sending the typed subscription request."),
    ("request-extensions", "HtspConnection.subscribe(Long,String,String?,Long?,Long?,Long?,Long?,Long,HtspConnectionGeneration?)", "Wraps [channelName] as [SubscribeChannel.Name] before sending the typed subscription request."),
    ("request-extensions", "HtspConnection.unsubscribe(Long,Long,HtspConnectionGeneration?)", "Requests termination of the selected subscription and decodes the typed acknowledgement."),
    ("request-extensions", "HtspConnection.subscriptionChangeWeight(Long,Long?,Long,HtspConnectionGeneration?)", "Requests a scheduling-weight change for one subscription and decodes its typed acknowledgement; the reply does not establish that the weight was applied."),
    ("request-extensions", "HtspConnection.subscriptionSeek(Long,SubscriptionSeekPosition,Long?,Long,HtspConnectionGeneration?)", "Requests a subscription seek by exactly one signed time or byte coordinate and decodes the typed acknowledgement."),
    ("request-extensions", "HtspConnection.subscriptionSeek(Long,SubscriptionSeekPosition.Time,Long?,Long,HtspConnectionGeneration?)", "Keeps a time seek selector distinct while forwarding it through the typed subscription-seek boundary."),
    ("request-extensions", "HtspConnection.subscriptionSeek(Long,SubscriptionSeekPosition.Size,Long?,Long,HtspConnectionGeneration?)", "Keeps a byte-size seek selector distinct while forwarding it through the typed subscription-seek boundary."),
    ("request-extensions", "HtspConnection.subscriptionSkip(Long,SubscriptionSeekPosition,Long?,Long,HtspConnectionGeneration?)", "Requests a subscription skip using exactly one signed time or byte coordinate and decodes the typed acknowledgement."),
    ("request-extensions", "HtspConnection.subscriptionSkip(Long,SubscriptionSeekPosition.Time,Long?,Long,HtspConnectionGeneration?)", "Adapts the concrete time coordinate to the shared skip request without changing typed failure handling."),
    ("request-extensions", "HtspConnection.subscriptionSkip(Long,SubscriptionSeekPosition.Size,Long?,Long,HtspConnectionGeneration?)", "Adapts the concrete byte coordinate to the shared skip request without changing typed failure handling."),
    ("request-extensions", "HtspConnection.subscriptionSpeed(Long,Int,Long,HtspConnectionGeneration?)", "Requests the signed playback speed for one subscription and decodes the typed acknowledgement."),
    ("request-extensions", "HtspConnection.subscriptionLive(Long,Long,HtspConnectionGeneration?)", "Requests live mode for one subscription and decodes the typed acknowledgement; asynchronous subscription status remains authoritative for the resulting position."),
    ("request-extensions", "HtspConnection.subscriptionFilterStream(Long,List<Long>?,List<Long>?,Long,HtspConnectionGeneration?)", "Requests the supplied immutable enable and disable stream-index filters and decodes the typed acknowledgement."),
    ("request-extensions", "HtspConnection.fileOpen(String,Long,HtspConnectionGeneration?)", "Requests opening the exact supplied protocol file selector and decodes the returned handle; no path normalization is added."),
    ("request-extensions", "HtspConnection.fileRead(Long,Long,Long?,Long,HtspConnectionGeneration?)", "Reads a bounded byte range from an open protocol file handle through typed execution."),
    ("request-extensions", "HtspConnection.fileClose(Long,Long,HtspConnectionGeneration?)", "Requests closure of an open protocol file handle without recording-progress fields and decodes the acknowledgement."),
    ("request-extensions", "HtspConnection.fileCloseWithProgress(Long,Long?,Long?,Long,HtspConnectionGeneration?)", "Projects all close parameters so requested recording position and play count can be sent with typed failure handling."),
    ("request-extensions", "HtspConnection.fileStat(Long,Long,HtspConnectionGeneration?)", "Reads size and modification metadata for an open protocol file handle through typed execution."),
    ("request-extensions", "HtspConnection.fileSeek(Long,Long,FileSeekWhence?,Long,HtspConnectionGeneration?)", "Requests a signed seek from the optional origin and decodes the server-reported absolute file offset."),
    ("request-extensions", "HtspConnection.hello(Long,String,Long,HtspConnectionGeneration?)", "Negotiates the requested HTSP version and client name through the typed handshake request boundary."),
    ("request-extensions", "HtspConnection.authenticate(Long,HtspConnectionGeneration?)", "Requests the current connection's authentication and access observations through typed execution; credentials stay in the envelope."),
)


GENERATED_TYPE_KDOC_RECORDS: tuple[GeneratedKDocRecord, ...] = (
    ("jsonapi-models", "ApiRequest", "Carries an exact JSON API [path] and optional finite [args] object without rewriting either value."),
    ("jsonapi-models", "ApiResponse", "Finite successful JSON API reply: either a typed container payload or an explicit absence of payload."),
    ("jsonapi-models", "ApiResponse.Payload", "Contains the recursively typed map or list returned by a successful JSON API call."),
    ("jsonapi-models", "ApiResponse.NoPayload", "Marks a successful JSON API callback that supplied no response payload."),

    ("server-models", "HtspChannelAddMessage", "Carries a channel-add message whose only required identity field is [channelId]; nullable channel metadata was absent when null."),
    ("server-models", "HtspChannelUpdateMessage", "Carries a channel update identified by [channelId]; nullable metadata, services, tags, and event references were absent when null."),
    ("server-models", "HtspChannelDeleteMessage", "Carries the complete unsigned [channelId] reported by a channel-delete message."),
    ("server-models", "HtspTagAddMessage", "Carries channel-tag add metadata including identity, display order, names, icons, and current channel membership when present."),
    ("server-models", "HtspTagUpdateMessage", "Carries a tag update identified by [tagId]; null properties and channel membership were absent from the message."),
    ("server-models", "HtspTagDeleteMessage", "Carries the complete unsigned [tagId] reported by a channel-tag delete message."),
    ("server-models", "HtspDvrRecordingFile", "One bounded recording-file entry with optional file identity, path, time range, and byte size."),
    ("server-models", "HtspDvrEntryAddMessage", "Carries a DVR-entry add message whose only required identity field is [entryId]; nullable schedule, metadata, state, progress, error, and file fields were absent when null."),
    ("server-models", "HtspDvrEntryUpdateMessage", "Carries one DVR-entry update; nullable schedule, metadata, progress, state, error, and file properties were absent when null."),
    ("server-models", "HtspDvrEntryDeleteMessage", "Carries the complete unsigned [entryId] reported by a DVR-entry delete message."),
    ("server-models", "HtspAutorecEntryAddMessage", "Carries the bounded automatic-recording-rule fields reported by an add message, including match, schedule, ownership, and retention data."),
    ("server-models", "HtspAutorecEntryUpdateMessage", "Carries an automatic-recording-rule update; every nullable matching, scheduling, ownership, or retention property was absent when null."),
    ("server-models", "HtspAutorecEntryDeleteMessage", "Carries the string [id] reported by an automatic-recording-rule delete message."),
    ("server-models", "HtspTimerecEntryAddMessage", "Carries the bounded time-based recording-rule fields reported by an add message, including channel, interval, policy, and ownership data."),
    ("server-models", "HtspTimerecEntryUpdateMessage", "Carries a time-based recording-rule update; nullable interval, channel, day, policy, and ownership properties were absent when null."),
    ("server-models", "HtspTimerecEntryDeleteMessage", "Carries the string [id] reported by a time-based recording-rule delete message."),
    ("server-models", "HtspEventAddMessage", "Carries the bounded [event] fields reported by an event-add message plus accepted genre, episode, and series-link identifiers."),
    ("server-models", "HtspEventUpdateMessage", "Carries an update identified by [eventId]; nullable timing, text, rating, episode, image, and DVR fields were absent when null."),
    ("server-models", "HtspEventDeleteMessage", "Carries the complete unsigned [eventId] reported by an EPG-event delete message."),
    ("server-models", "HtspInitialSyncCompletedMessage", "Fieldless server marker reporting completion of the initial asynchronous metadata snapshot."),
    ("server-models", "HtspMuxPacketMessage", "One subscription packet with stream and frame identifiers, optional decode and presentation timestamps, duration, and copied payload bytes."),
    ("server-models", "HtspQueueStatusMessage", "Queue counters for one subscription: queued packets and bytes, optional delay, and dropped B-, P-, and I-frame counts."),
    ("server-models", "HtspSubscriptionStream", "One stream descriptor with index and codec type plus optional language, video, audio, radio-data, and codec metadata fields."),
    ("server-models", "HtspSubscriptionSourceInfo", "Optional tuner source identity and display metadata for a subscription, including adapter, mux, network, provider, service, and satellite position."),
    ("server-models", "HtspSubscriptionStartMessage", "Reports subscription-start metadata: stream list, source information, codec metadata, and optional status or subscription error."),
    ("server-models", "HtspSubscriptionStopMessage", "Reports a subscription-stop message with the server's optional terminal status and subscription error."),
    ("server-models", "HtspSubscriptionGraceMessage", "Reports the grace interval, in seconds, allowed for the identified subscription."),
    ("server-models", "HtspSubscriptionStatusMessage", "Reports the current optional status and subscription error for one subscription."),
    ("server-models", "HtspSignalStatusMessage", "Signal observations for one subscription: frontend status, relative and absolute SNR and signal, bit errors, and uncorrected blocks."),
    ("server-models", "HtspDescrambleInfoMessage", "Descrambling observations for one subscription, including PID, access and provider identifiers, ECM timing, hop count, and optional source labels."),
    ("server-models", "HtspSubscriptionSpeedMessage", "Carries the signed playback [speed] reported by the server for one subscription."),
    ("server-models", "HtspTimeshiftStatusMessage", "Timeshift state for one subscription: fullness, current shift, optional start and end bounds, and optional speed."),
    ("server-models", "HtspSubscriptionSkipMessage", "Reports the server result of a subscription skip with optional absolute flag, error code, time coordinate, and byte coordinate."),

    ("server-dispatch", "HtspServerMessageDecodeResult", "Closed result family for decoding one candidate asynchronous HTSP message."),
    ("server-dispatch", "HtspServerMessageDecoded", "Contains the recognized and fully decoded asynchronous [message]."),
    ("server-dispatch", "HtspServerMessageUnknownMethod", "Marks an RPC envelope or message whose method is absent, malformed, or outside the finite dispatch catalog."),
    ("server-dispatch", "HtspServerMessageMalformedKnownMessage", "Marks a recognized asynchronous method whose fields failed its typed decoder."),

    ("request-models", "HtspProfile", "One stream profile with its stable UUID, display [name], and server [comment]."),
    ("request-models", "HtspDvrConfig", "One DVR configuration with its stable UUID, display [name], and server [comment]."),
    ("request-models", "HtspChannelService", "One channel service with name, type, content code, optional conditional-access data, and optional provider name."),
    ("request-models", "HtspChannel", "A complete channel reply with identity, numbering, display data, current and next event IDs, services, and tag IDs."),
    ("request-models", "HtspEvent", "A bounded EPG event with channel and time coordinates, localized text, categories, ratings, episode data, imagery, and DVR references."),
    ("request-models", "HtspDvrCutpoint", "One DVR cutpoint from [start] through [end] with its unsigned action [type]."),
    ("request-models", "HtspEmptyResponse", "Explicit successful acknowledgement for an RPC with no method-specific reply fields."),
    ("request-models", "HelloResponse", "Handshake observations: negotiated version, optional server labels, copied challenge, web root, language, capabilities, and API version."),
    ("request-models", "AuthenticateResponse", "Authentication access observations and limits; each nullable property was absent or malformed when null."),
    ("request-models", "GetProfilesResponse", "Contains the optional ordered stream-profile list returned by `getProfiles`."),
    ("request-models", "GetDiskSpaceResponse", "Contains free and total recording bytes plus the optional used-byte counter."),
    ("request-models", "GetSysTimeResponse", "Contains Unix time, the legacy hours-west timezone value, and an optional GMT offset in minutes."),
    ("request-models", "GetChannelResponse", "Contains the complete channel selected by `getChannel`."),
    ("request-models", "GetEventResponse", "Contains the complete EPG event selected by `getEvent`."),
    ("request-models", "GetEventsResponse", "Contains the ordered finite event list selected by `getEvents`."),
    ("request-models", "EpgQueryResponse", "Closed `epgQuery` reply family containing either event identifiers or complete event values."),
    ("request-models", "EpgQueryResponse.EventIds", "Contains only the event identifiers returned by a summary EPG query."),
    ("request-models", "EpgQueryResponse.Events", "Contains complete typed events returned by a detailed EPG query."),
    ("request-models", "HtspEpgObjectType", "Finite object selector encoded by `getEpgObject`; [BROADCAST] selects a broadcast record."),
    ("request-models", "HtspEpgEpisodeNumber", "Optional episode numbering with episode, season, and part values, totals, and display text."),
    ("request-models", "HtspEpgBroadcastObject", "Bounded detailed broadcast record with timing, channel and event identity, flags, ratings, localized text, numbering, genres, and links; opaque credentials are omitted."),
    ("request-models", "GetEpgObjectResponse", "Contains the detailed broadcast selected by `getEpgObject`."),
    ("request-models", "GetDvrConfigsResponse", "Contains the optional ordered DVR-configuration list visible to the caller."),
    ("request-models", "HtspDvrMutationRequest", "Closed request marker implemented by add, update, stop, cancel, and delete DVR entry requests."),
    ("request-models", "HtspDvrMutationResponse", "Shared DVR mutation fields: optional success code, untrusted server error text, and an optional entry identifier where applicable."),
    ("request-models", "AddDvrEntryResponse", "DVR-add reply with optional success code, returned [entryId], and untrusted server [error]."),
    ("request-models", "UpdateDvrEntryResponse", "DVR-update result carrying the optional success code and untrusted server error text."),
    ("request-models", "StopDvrEntryResponse", "DVR-stop result carrying the optional success code and untrusted server error text."),
    ("request-models", "CancelDvrEntryResponse", "DVR-cancel result carrying the optional success code and untrusted server error text."),
    ("request-models", "DeleteDvrEntryResponse", "DVR-delete result carrying the optional success code and untrusted server error text."),
    ("request-models", "HtspRecordingRuleChannel", "Closed channel selector for automatic and time-based recording rule requests."),
    ("request-models", "HtspRecordingRuleChannel.Id", "Selects one recording-rule channel by its complete unsigned [channelId]."),
    ("request-models", "HtspRecordingRuleChannel.Any", "Encodes the signed `-1` any-channel sentinel; update requests may also clear a channel by omitting it."),
    ("request-models", "AddAutorecEntryResponse", "Carries the server identifier returned by an automatic-recording-rule add reply."),
    ("request-models", "UpdateAutorecEntryResponse", "Carries the strict success discriminator returned for an automatic-recording-rule update request."),
    ("request-models", "DeleteAutorecEntryResponse", "Carries the strict success discriminator returned for an automatic-recording-rule delete request."),
    ("request-models", "AddTimerecEntryResponse", "Carries the server identifier returned by a time-based recording-rule add reply."),
    ("request-models", "UpdateTimerecEntryResponse", "Carries the strict success discriminator returned for a time-based recording-rule update request."),
    ("request-models", "DeleteTimerecEntryResponse", "Carries the strict success discriminator returned for a time-based recording-rule delete request."),
    ("request-models", "GetDvrCutpointsResponse", "Contains the optional ordered cutpoint list for the selected DVR entry."),
    ("request-models", "GetTicketSelector", "Closed exactly-one selector for a channel ticket or a DVR ticket."),
    ("request-models", "GetTicketSelector.Channel", "Selects a ticket source by complete unsigned channel identifier."),
    ("request-models", "GetTicketSelector.Dvr", "Selects a ticket source by complete unsigned DVR identifier."),
    ("request-models", "GetTicketResponse", "Credential-bearing ticket reply containing the access [path] and [ticket]; string rendering redacts both."),
    ("request-models", "FileOpenResponse", "Opened file handle [id] with source-coupled optional size and modification-time metadata."),
    ("request-models", "FileReadResponse", "Contains one defensively copied bounded payload; an empty payload is a valid successful read."),
    ("request-models", "FileCloseResponse", "Explicit successful empty acknowledgement returned for a protocol file-close request."),
    ("request-models", "FileStatResponse", "Optional size and modification time for an open file handle; the pair is absent together when unavailable."),
    ("request-models", "FileSeekResponse", "Contains the successful absolute non-negative file offset after a seek."),
    ("request-models", "FileSeekWhence", "Finite file-seek origin vocabulary: start, current position, or end; a null request value omits the field."),
    ("request-models", "SubscribeResponse", "Negotiated subscription values: optional 90 kHz mode, normalized-timestamp flag, scheduling weight, and timeshift period in seconds."),

    ("request-models", "GetProfilesRequest", "Requests the stream-profile list and carries no method-specific parameters."),
    ("request-models", "GetDiskSpaceRequest", "Requests recording-storage counters and carries no method-specific parameters."),
    ("request-models", "GetSysTimeRequest", "Requests the server clock and timezone observations without method-specific parameters."),
    ("request-models", "EnableAsyncMetadataRequest", "Selects asynchronous metadata options: EPG inclusion, update frontier, EPG maximum time, and language; null omits each field."),
    ("request-models", "GetChannelRequest", "Selects one channel by complete unsigned [channelId]."),
    ("request-models", "GetEventRequest", "Selects one event by complete unsigned [eventId] and optional response [language]."),
    ("request-models", "GetEventsRequest", "Selects an event set by optional channel or event ID, language, following count, and maximum Unix time."),
    ("request-models", "EpgQueryRequest", "Carries required search [query] plus optional channel, tag, content, language, text-mode, detail, and duration filters."),
    ("request-models", "GetEpgObjectRequest", "Selects a detailed EPG object by unsigned [id] and optional finite [objectType]."),
    ("request-models", "GetDvrConfigsRequest", "Requests visible DVR configurations and carries no method-specific parameters."),
    ("request-models", "AddDvrEntrySelector", "Closed DVR scheduling selector: an existing event or an explicit channel and time range."),
    ("request-models", "AddDvrEntrySelector.Event", "Selects an existing event by complete unsigned [eventId] for DVR scheduling."),
    ("request-models", "AddDvrEntrySelector.ExplicitChannelTime", "Selects a complete unsigned [channelId] and signed [start] and [stop] coordinates for DVR scheduling."),
    ("request-models", "AddDvrEntryRequest", "Requests DVR scheduling from exactly one [selector] with optional configuration, language, programme text, and age rating."),
    ("request-models", "UpdateDvrEntryRequest", "Identifies one DVR entry and carries optional channel, configuration, programme text, progress, enablement, timing, retention, priority, and age rating changes."),
    ("request-models", "StopDvrEntryRequest", "Selects one DVR entry by complete unsigned [entryId] for stopping."),
    ("request-models", "CancelDvrEntryRequest", "Selects one DVR entry by complete unsigned [entryId] for cancellation."),
    ("request-models", "DeleteDvrEntryRequest", "Selects one DVR entry by complete unsigned [entryId] for deletion."),
    ("request-models", "AddAutorecEntryRequest", "Defines an automatic recording rule with required title and optional channel, text, duration, time-window, count, schedule, retention, ownership, and configuration fields."),
    ("request-models", "UpdateAutorecEntryRequest", "Identifies an automatic recording rule and carries optional channel, matching, duration, schedule, count, retention, ownership, title, and configuration changes."),
    ("request-models", "DeleteAutorecEntryRequest", "Selects one automatic recording rule by string [id] for deletion."),
    ("request-models", "AddTimerecEntryRequest", "Defines a time-based recording rule with title and optional channel, daily interval, enablement, days, priority, retention, ownership, and configuration fields."),
    ("request-models", "UpdateTimerecEntryRequest", "Identifies a time-based recording rule and carries optional channel, daily interval, enablement, days, policy, ownership, title, and configuration changes."),
    ("request-models", "DeleteTimerecEntryRequest", "Selects one time-based recording rule by string [id] for deletion."),
    ("request-models", "GetDvrCutpointsRequest", "Selects one DVR entry by complete unsigned [entryId] for cutpoint retrieval."),
    ("request-models", "GetTicketRequest", "Carries exactly one channel-or-DVR [selector] for temporary ticket retrieval."),
    ("request-models", "SubscribeChannel", "Closed exactly-one subscription channel selector by unsigned ID or exact name."),
    ("request-models", "SubscribeChannel.Id", "Selects a subscription channel by complete unsigned [channelId]."),
    ("request-models", "SubscribeChannel.Name", "Selects a subscription channel by exact [channelName]."),
    ("request-models", "SubscribeRequest", "Requests [subscriptionId] for exactly one [channel] with optional profile, weight, 90 kHz timestamps, timeshift period, and queue depth."),
    ("request-models", "UnsubscribeRequest", "Selects one subscription by complete unsigned [subscriptionId] for termination."),
    ("request-models", "SubscriptionChangeWeightRequest", "Selects one subscription and optionally supplies its new unsigned scheduling [weight]."),
    ("request-models", "SubscriptionSeekPosition", "Closed signed subscription coordinate: media time or byte size."),
    ("request-models", "SubscriptionSeekPosition.Time", "Carries a signed media [time] coordinate for seek or skip."),
    ("request-models", "SubscriptionSeekPosition.Size", "Carries a signed byte [size] coordinate for seek or skip."),
    ("request-models", "SubscriptionSeekRequest", "Selects a subscription, one signed [position], and an optional unsigned [absolute] flag for seeking."),
    ("request-models", "SubscriptionSkipRequest", "Selects a subscription, one signed [position], and an optional unsigned [absolute] flag for skipping."),
    ("request-models", "SubscriptionSpeedRequest", "Selects a subscription and carries the requested signed playback [speed]."),
    ("request-models", "SubscriptionLiveRequest", "Selects one subscription by complete unsigned [subscriptionId] and requests live mode."),
    ("request-models", "SubscriptionFilterStreamRequest", "Selects one subscription and immutable optional stream-index lists to enable and disable."),
    ("request-models", "FileOpenRequest", "Carries the exact protocol [file] selector without path normalization; diagnostics redact it."),
    ("request-models", "FileReadRequest", "Selects an open file [id], bounded byte [size], and optional signed [offset] for one read."),
    ("request-models", "FileCloseRequest", "Selects an open file [id] and optional recording position and play-count values; null omits each progress field."),
    ("request-models", "FileStatRequest", "Selects an open protocol file handle by complete unsigned [id] for metadata retrieval."),
    ("request-models", "FileSeekRequest", "Selects an open file [id], signed [offset], and optional finite [whence] origin."),
    ("request-models", "HelloRequest", "Carries the requested unsigned HTSP version and exact client name for the `hello` exchange."),
    ("request-models", "AuthenticateRequest", "Bare authentication request; credentials belong to the connection envelope rather than constructor properties."),
)


def generated_record_key(record: GeneratedKDocRecord, kind: str) -> str:
    owner, signature, _prose = record
    package = GENERATED_OUTPUT_BY_KEY[owner].package
    separator = "." if kind == "type" else "."
    return f"{kind}:{package}{separator}{signature}"


def _record_map(records: tuple[GeneratedKDocRecord, ...], kind: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for record in records:
        key = generated_record_key(record, kind)
        if key in result:
            raise ValueError(f"duplicate generated {kind} KDoc record: {key}")
        result[key] = record[2]
    return result


GENERATED_TYPE_KDOCS = _record_map(GENERATED_TYPE_KDOC_RECORDS, "type")
GENERATED_FUNCTION_KDOCS = _record_map(GENERATED_FUNCTION_KDOC_RECORDS, "fun")


def generated_type_kdoc(owner: str, declaration: str) -> str:
    key = f"type:{GENERATED_OUTPUT_BY_KEY[owner].package}.{declaration}"
    try:
        return GENERATED_TYPE_KDOCS[key]
    except KeyError as error:
        raise ValueError(f"missing generated type KDoc record: {key}") from error


def extension_callable_signature(
    entry: Entry,
    parameters: tuple[Parameter, ...],
    function_name: str | None = None,
) -> str:
    types = ",".join(value.type for value in (
        *parameters,
        parameter("timeoutMs", "Long"),
        parameter("expectedGeneration", "HtspConnectionGeneration?"),
    ))
    return f"HtspConnection.{function_name or entry.method}({types})"


def generated_function_kdoc(
    entry: Entry,
    parameters: tuple[Parameter, ...],
    function_name: str | None = None,
) -> str:
    key = f"fun:{GENERATED_OUTPUT_BY_KEY[entry.extension_output].package}.{extension_callable_signature(entry, parameters, function_name)}"
    try:
        return GENERATED_FUNCTION_KDOCS[key]
    except KeyError as error:
        raise ValueError(f"missing generated function KDoc record: {key}") from error


def selector_overload_kdoc(overload: SelectorOverload) -> str:
    entry = next(value for value in CATALOG if value.method == overload.method)
    if overload.parameter_projection:
        parameters = tuple(
            next(value for value in entry.parameters if value.name == name)
            for name in overload.parameter_projection
        )
    else:
        parameters = overload.parameters
    return generated_function_kdoc(entry, parameters, overload.function_name)

def _props(*values: tuple[str, str] | tuple[str, str, str]) -> tuple[KotlinProperty, ...]:
    return tuple(P(*value) for value in values)


REQUEST_PUBLIC_DECLARATIONS: tuple[KotlinDeclaration, ...] = (
    KotlinDeclaration("HtspProfile", "data class", _props(
        ("profileUuid", "String"), ("name", "String"), ("comment", "String"),
    ), "One stream-profile map returned by `getProfiles`."),
    KotlinDeclaration("HtspDvrConfig", "data class", _props(
        ("dvrConfigUuid", "String"), ("name", "String"), ("comment", "String"),
    ), "One DVR-configuration map returned by `getDvrConfigs`."),
    KotlinDeclaration("HtspChannelService", "data class", _props(
        ("name", "String"), ("type", "String"), ("content", "Long"),
        ("conditionalAccessId", "Long?"), ("conditionalAccessName", "String?"),
        ("providerName", "String?"),
    ), "One bounded service map returned inside a channel reply."),
    KotlinDeclaration("HtspChannel", "data class", _props(
        ("channelId", "Long"), ("channelUuid", "String?"), ("channelNumber", "Long"),
        ("channelNumberMinor", "Long?"), ("channelName", "String"),
        ("channelIcon", "String?"), ("currentEventId", "Long"), ("nextEventId", "Long"),
        ("services", "List<HtspChannelService>"), ("tagIds", "List<Long>"),
    ), "Complete typed current-source channel reply."),
    KotlinDeclaration("HtspEvent", "data class", _props(
        ("eventId", "Long"), ("channelId", "Long?"), ("start", "Long"), ("stop", "Long"),
        ("title", "String?"), ("subtitle", "String?"), ("summary", "String?"),
        ("description", "String?"), ("categories", "List<String>?"),
        ("keywords", "List<String>?"), ("seriesLinkUri", "String?"),
        ("episodeUri", "String?"), ("contentType", "Long?"), ("ageRating", "Long?"),
        ("ratingLabel", "String?"), ("ratingIcon", "String?"),
        ("ratingAuthority", "String?"), ("ratingCountry", "String?"),
        ("starRating", "Long?"), ("copyrightYear", "Long?"), ("firstAired", "Long?"),
        ("isNew", "Long?"), ("seasonNumber", "Long?"), ("seasonCount", "Long?"),
        ("episodeNumber", "Long?"), ("episodeCount", "Long?"),
        ("partNumber", "Long?"), ("partCount", "Long?"),
        ("episodeOnscreen", "String?"), ("image", "String?"),
        ("dvrId", "Long?"), ("nextEventId", "Long?"),
    ), "Complete typed current-source event reply, excluding deliberately opaque credits."),
    KotlinDeclaration("HtspDvrCutpoint", "data class", _props(
        ("start", "Long"), ("end", "Long"), ("type", "Long"),
    ), "One DVR cutpoint with the exact unsigned wire action code."),
    KotlinDeclaration("HtspEmptyResponse", "data object", kdoc="Explicit successful empty RPC acknowledgement."),
    KotlinDeclaration("HelloResponse", "class", _props(
        ("htspVersion", "Long"), ("serverName", "String?"), ("serverVersion", "String?"),
        ("challenge", "HtspBinary"), ("webRoot", "String?"), ("language", "String?"),
        ("serverCapabilities", "List<String>?"), ("apiVersion", "Long?"),
    ), "Successful `hello` observations. The challenge bytes remain redacted by [HtspBinary].", feature="hello-response"),
    KotlinDeclaration("AuthenticateResponse", "data class", _props(
        ("noAccess", "Boolean?"), ("admin", "Boolean?"), ("streaming", "Boolean?"),
        ("dvr", "Boolean?"), ("failedDvr", "Boolean?"), ("anonymous", "Boolean?"),
        ("limitAll", "Long?"), ("limitDvr", "Long?"), ("limitStreaming", "Long?"),
        ("uiLevel", "Long?"), ("uiLanguage", "String?"),
    ), "Successful `authenticate` access observations; absent or malformed optional fields are null."),
    KotlinDeclaration("GetProfilesResponse", "data class", _props(("profiles", "List<HtspProfile>?"))),
    KotlinDeclaration("GetDiskSpaceResponse", "data class", _props(
        ("freeBytes", "Long"), ("usedBytes", "Long?"), ("totalBytes", "Long"),
    )),
    KotlinDeclaration("GetSysTimeResponse", "data class", _props(
        ("unixTimeSeconds", "Long"), ("legacyTimezoneHoursWestOfGmt", "Int"),
        ("gmtOffsetMinutes", "Int?"),
    )),
    KotlinDeclaration("GetChannelResponse", "data class", _props(("channel", "HtspChannel"))),
    KotlinDeclaration("GetEventResponse", "data class", _props(("event", "HtspEvent"))),
    KotlinDeclaration("GetEventsResponse", "data class", _props(("events", "List<HtspEvent>"))),
    KotlinDeclaration("EpgQueryResponse", "sealed interface", kdoc="Selected finite reply alternative for `epgQuery`.", feature="epg-query-response"),
    KotlinDeclaration("HtspEpgObjectType", "enum class", kdoc="Finite object selector encoded by `getEpgObject`; [BROADCAST] selects a broadcast record.", enum_values=("BROADCAST",)),
    KotlinDeclaration("HtspEpgEpisodeNumber", "data class", _props(
        ("episodeNumber", "Long?"), ("episodeCount", "Long?"), ("seasonNumber", "Long?"),
        ("seasonCount", "Long?"), ("partNumber", "Long?"), ("partCount", "Long?"),
        ("text", "String?"),
    ), "Optional episode-number object serialized inside a detailed EPG broadcast."),
    KotlinDeclaration("HtspEpgBroadcastObject", "data class", _props(
        ("id", "Long"), ("updatedUnixSeconds", "Long"), ("startUnixSeconds", "Long"),
        ("stopUnixSeconds", "Long"), ("grabber", "String?"), ("channelUuid", "String?"),
        ("eventId", "Long?"), ("externalEventId", "String?"), ("widescreen", "Boolean"),
        ("highDefinition", "Boolean"), ("blackAndWhite", "Boolean"),
        ("deafSigned", "Boolean"), ("subtitled", "Boolean"),
        ("audioDescribed", "Boolean"), ("isNew", "Boolean"), ("isRepeat", "Boolean"),
        ("lines", "Long?"), ("aspectRatio", "Long?"), ("starRating", "Long?"),
        ("ageRating", "Long?"), ("ratingLabel", "String?"), ("image", "String?"),
        ("titles", "Map<String, String>?"), ("subtitles", "Map<String, String>?"),
        ("summaries", "Map<String, String>?"), ("descriptions", "Map<String, String>?"),
        ("episodeNumber", "HtspEpgEpisodeNumber?"), ("genres", "List<Long>?"),
        ("copyrightYear", "Long?"), ("firstAiredUnixSeconds", "Long?"),
        ("categories", "List<String>?"), ("keywords", "List<String>?"),
        ("seriesLinkUri", "String?"), ("episodeLinkUri", "String?"),
    ), """Complete bounded broadcast object returned by `getEpgObject`.

The unconstrained wire `cred` object is deliberately omitted from this finite public model."""),
    KotlinDeclaration("GetEpgObjectResponse", "data class", _props(("broadcast", "HtspEpgBroadcastObject"))),
    KotlinDeclaration("GetDvrConfigsResponse", "data class", _props(("configurations", "List<HtspDvrConfig>?"))),
    KotlinDeclaration("HtspDvrMutationRequest", "sealed interface", kdoc="Closed wire request family for the five DVR mutation methods."),
    KotlinDeclaration("HtspDvrMutationResponse", "sealed interface", kdoc="Wire-shaped DVR mutation reply. [error] is untrusted server text.", feature="dvr-mutation-response"),
    KotlinDeclaration("AddDvrEntryResponse", "data class", (
        KotlinProperty("success", "Long?", modifier="override"),
        KotlinProperty("entryId", "Long?", modifier="override"),
        KotlinProperty("error", "String?", modifier="override"),
    ), supertype="HtspDvrMutationResponse"),
    *(KotlinDeclaration(name, "data class", (
        KotlinProperty("success", "Long?", modifier="override"),
        KotlinProperty("error", "String?", modifier="override"),
    ), supertype="HtspDvrMutationResponse") for name in (
        "UpdateDvrEntryResponse", "StopDvrEntryResponse", "CancelDvrEntryResponse", "DeleteDvrEntryResponse",
    )),
    KotlinDeclaration("HtspRecordingRuleChannel", "sealed interface", kdoc="Channel mutation selected for one autorec or timerec rule request.", feature="recording-rule-channel"),
    KotlinDeclaration("AddAutorecEntryResponse", "data class", _props(("id", "String"))),
    KotlinDeclaration("UpdateAutorecEntryResponse", "data object"),
    KotlinDeclaration("DeleteAutorecEntryResponse", "data object"),
    KotlinDeclaration("AddTimerecEntryResponse", "data class", _props(("id", "String"))),
    KotlinDeclaration("UpdateTimerecEntryResponse", "data object"),
    KotlinDeclaration("DeleteTimerecEntryResponse", "data object"),
    KotlinDeclaration("GetDvrCutpointsResponse", "data class", _props(("cutpoints", "List<HtspDvrCutpoint>?"))),
    KotlinDeclaration("GetTicketSelector", "sealed interface", kdoc="""Exactly-one channel-or-DVR selection is the stricter SDK API contract.

The pinned upstream `getTicket` handler also accepts both selectors and gives
`channelId` precedence. This sealed API makes both-present and neither-present
selection unrepresentable.""", feature="get-ticket-selector"),
    KotlinDeclaration("GetTicketResponse", "class", _props(("path", "String"), ("ticket", "String")),
        "Purpose-specific credential-bearing successful `getTicket` reply.", feature="get-ticket-response"),
    KotlinDeclaration("FileOpenResponse", "data class", _props(
        ("id", "Long"), ("sizeBytes", "Long?"), ("modifiedAtUnixSeconds", "Long?"),
    ), "Finite successful `fileOpen` reply with source-coupled optional metadata."),
    KotlinDeclaration("FileReadResponse", "data class", _props(("data", "HtspBinary")),
        "One bounded binary payload returned by `fileRead`, including a valid empty payload."),
    KotlinDeclaration("FileCloseResponse", "data object", kdoc="Explicit successful empty `fileClose` acknowledgement."),
    KotlinDeclaration("FileStatResponse", "data class", _props(
        ("sizeBytes", "Long?"), ("modifiedAtUnixSeconds", "Long?"),
    ), "Finite successful `fileStat` reply; both values are absent when pinned `fstat` fails."),
    KotlinDeclaration("FileSeekResponse", "data class", _props(("offset", "Long")),
        "Successful absolute non-negative file offset returned by `fileSeek`."),
    KotlinDeclaration("FileSeekWhence", "enum class", kdoc="Complete valid `fileSeek` origin vocabulary; null request selection omits `whence`.", enum_values=("SET", "CURRENT", "END")),
    KotlinDeclaration("SubscribeResponse", "data class", _props(
        ("ninetyKhz", "Long?"), ("normalizedTimestamps", "Long?"),
        ("weight", "Long?"), ("timeshiftPeriodSeconds", "Long?"),
    )),
    KotlinDeclaration("ApiResponse", "sealed interface", kdoc="Finite successful reply topology for the provisional HTSP JSON API bridge.", annotations=("@HtspJsonApi",), feature="api-response", output="jsonapi-models"),
)

_REQUEST_MODEL_OVERRIDES: dict[str, dict[str, object]] = {
    "enableAsyncMetadata": dict(
        minimum_expression="6.takeIf {\n        epg != null || lastUpdate != null || epgMaxTime != null || language != null\n    }",
        validations=('epg?.let { requireU32("epg", it) }',),
        decoder_feature="lenient-empty",
    ),
    "getChannel": dict(validations=('requireU32("channelId", channelId)',), decoder_feature="channel"),
    "getEvent": dict(
        minimum_expression="6.takeIf { language != null }",
        validations=('requireU32("eventId", eventId)',),
        decoder_feature="event",
    ),
    "getEvents": dict(
        minimum_expression="if (\n        channelId != null || eventId != null || language != null || numFollowing != null || maxTime != null\n    ) 6 else 4",
        validations=(
            'channelId?.let { requireU32("channelId", it) }',
            'eventId?.let { requireU32("eventId", it) }',
            'numFollowing?.let { requireU32("numFollowing", it) }',
        ),
    ),
    "epgQuery": dict(
        minimum_expression="""maxVersion(
        4,
        6.takeIf { language != null },
        13.takeIf { minDurationSeconds != null || maxDurationSeconds != null },
    )""",
        validations=tuple(f'{name}?.let {{ requireU32("{wire}", it) }}' for name, wire in (
            ("channelId", "channelId"), ("tagId", "tagId"), ("contentType", "contentType"),
            ("full", "full"), ("minDurationSeconds", "minduration"),
            ("maxDurationSeconds", "maxduration"),
        )), decoder_feature="epg-query",
    ),
    "getEpgObject": dict(validations=('requireU32("id", id)',), decoder_feature="epg-object"),
    "addDvrEntry": dict(
        minimum_expression="""maxVersion(
        4,
        5.takeIf { selector is AddDvrEntrySelector.ExplicitChannelTime },
        6.takeIf { title != null },
        20.takeIf { subtitle != null },
        5.takeIf { description != null },
        36.takeIf { ageRating != null },
    )""",
        interfaces=("HtspDvrMutationRequest",),
        validations=('ageRating?.let { requireU32("ageRating", it) }',),
        encoder_feature="add-dvr-selector",
        decoder_feature="add-dvr-mutation",
    ),
    "updateDvrEntry": dict(
        minimum_expression="""maxVersion(
        5,
        22.takeIf { channelId != null },
        21.takeIf { subtitle != null },
        6.takeIf { description != null },
        42.takeIf { comment != null },
        27.takeIf { playCount != null || playPosition != null },
        23.takeIf { enabled != null },
        6.takeIf { startExtra != null || stopExtra != null },
        13.takeIf { retention != null || priority != null },
        36.takeIf { ageRating != null },
    )""",
        interfaces=("HtspDvrMutationRequest",),
        validations=tuple(
            f'{name}{"" if required else "?"}.let {{ requireU32("{wire}", it) }}'
            if not required else f'requireU32("{wire}", {name})'
            for name, wire, required in (
                ("entryId", "id", True), ("channelId", "channelId", False),
                ("playCount", "playCount", False), ("playPosition", "playPosition", False),
                ("retention", "retention", False), ("removal", "removal", False),
                ("priority", "priority", False), ("ageRating", "ageRating", False),
            )
        ),
        decoder_feature="dvr-mutation",
    ),
    **{method: dict(
        interfaces=("HtspDvrMutationRequest",),
        validations=('requireU32("id", entryId)',),
        decoder_feature="dvr-mutation",
    ) for method in ("stopDvrEntry", "cancelDvrEntry", "deleteDvrEntry")},
    "addAutorecEntry": dict(
        minimum_expression="""maxVersion(
        13,
        25.takeIf { channel is HtspRecordingRuleChannel.Any },
        18.takeIf {
            name != null || startMinutesSinceMidnight != null || startWindowEndMinutesSinceMidnight != null
        },
        19.takeIf { enabled != null || directory != null },
        20.takeIf { fullText != null || duplicateDetection != null },
        39.takeIf { broadcastType != null },
        42.takeIf { comment != null },
    )""",
        validations=("validateAutorecU32Fields()",), encoder_feature="recording-rule",
        decoder_feature="recording-rule-add",
    ),
    "updateAutorecEntry": dict(
        minimum_expression="""maxVersion(
        25,
        39.takeIf { broadcastType != null },
        42.takeIf { comment != null },
    )""",
        validations=("validateAutorecU32Fields()",), encoder_feature="recording-rule",
        decoder_feature="recording-rule-ack",
    ),
    "deleteAutorecEntry": dict(decoder_feature="recording-rule-ack"),
    "addTimerecEntry": dict(
        minimum_expression="""maxVersion(
        18,
        25.takeIf { channel is HtspRecordingRuleChannel.Any },
        19.takeIf { enabled != null || directory != null },
        42.takeIf { comment != null },
    )""",
        validations=("validateTimerecU32Fields()",), encoder_feature="recording-rule",
        decoder_feature="recording-rule-add",
    ),
    "updateTimerecEntry": dict(
        minimum_expression="maxVersion(25, 42.takeIf { comment != null })",
        validations=("validateTimerecU32Fields()",), encoder_feature="recording-rule",
        decoder_feature="recording-rule-ack",
    ),
    "deleteTimerecEntry": dict(decoder_feature="recording-rule-ack"),
    "getDvrCutpoints": dict(validations=('requireU32("id", entryId)',), decoder_feature="cutpoints"),
    "getTicket": dict(encoder_feature="get-ticket-selector", decoder_feature="get-ticket"),
    "subscribe": dict(
        minimum_expression="""maxVersion(
        null,
        16.takeIf { profile != null },
        7.takeIf { ninetyKhz != null || queueDepth != null },
        9.takeIf { timeshiftPeriodSeconds != null },
    )""",
        validations=(
            'requireU32("subscriptionId", subscriptionId)',
            'weight?.let { requireU32("weight", it) }',
            'ninetyKhz?.let { requireU32("90khz", it) }',
            'timeshiftPeriodSeconds?.let { requireU32("timeshiftPeriod", it) }',
            'queueDepth?.let { requireU32("queueDepth", it) }',
        ), encoder_feature="subscribe-selector",
    ),
    "unsubscribe": dict(validations=('requireU32("subscriptionId", subscriptionId)',), decoder_feature="lenient-empty"),
    "subscriptionChangeWeight": dict(validations=(
        'requireU32("subscriptionId", subscriptionId)',
        'weight?.let { requireU32("weight", it) }',
    ), decoder_feature="lenient-empty"),
    "subscriptionSeek": dict(validations=(
        'requireU32("subscriptionId", subscriptionId)',
        'absolute?.let { requireU32("absolute", it) }',
    ), encoder_feature="seek-selector", decoder_feature="lenient-empty"),
    "subscriptionSkip": dict(validations=(
        'requireU32("subscriptionId", subscriptionId)',
        'absolute?.let { requireU32("absolute", it) }',
    ), encoder_feature="seek-selector", decoder_feature="strict-empty"),
    "subscriptionSpeed": dict(validations=('requireU32("subscriptionId", subscriptionId)',), decoder_feature="lenient-empty"),
    "subscriptionLive": dict(validations=('requireU32("subscriptionId", subscriptionId)',), decoder_feature="lenient-empty"),
    "subscriptionFilterStream": dict(
        kind="class", unstored_parameters=("enable", "disable"), snapshots=("enable", "disable"),
        validations=(
            'requireU32("subscriptionId", subscriptionId)',
            'this.enable?.forEach { requireU32("enable", it) }',
            'this.disable?.forEach { requireU32("disable", it) }',
        ), decoder_feature="lenient-empty",
    ),
    "fileOpen": dict(
        kdoc="Raw protocol file open; [file] is sent exactly as supplied, without path normalization.",
        body_feature="file-open-redaction",
        decoder_feature="file-open",
    ),
    "fileRead": dict(
        kdoc="""Bounded raw protocol file read.

[size] is restricted to 0 through 16 MiB so one successful binary reply stays within the
existing JVM bounded-file contract and below the unchanged 32 MiB codec message ceiling.""",
        validations=(
            'requireU32("id", id)',
            'require(size in 0L..MAX_FILE_READ_SIZE_BYTES) {\n            "size must be between zero and 16 MiB"\n        }',
        ), decoder_feature="file-read",
    ),
    "fileClose": dict(
        kdoc="Selects an open file [id] and optional recording position and play-count values; null omits each progress field.",
        minimum_expression="if (playPositionSeconds != null || playCount != null) 27 else 8",
        validations=(
            'requireU32("id", id)',
            'playPositionSeconds?.let { requireU32("playPositionSeconds", it) }',
            'playCount?.let { requireU32("playCount", it) }',
        ), decoder_feature="strict-file-close",
    ),
    "fileStat": dict(validations=('requireU32("id", id)',), decoder_feature="file-stat"),
    "fileSeek": dict(
        kdoc="Signed seek request; omitted [whence] preserves the pinned `SEEK_SET` default.",
        validations=('requireU32("id", id)',), encoder_feature="file-whence",
        decoder_feature="file-seek",
    ),
    "api": dict(
        kdoc="Exact endpoint invocation transported through the provisional HTSP JSON API bridge.",
        annotations=("@HtspJsonApi",), decoder_feature="api",
    ),
    "hello": dict(
        kdoc="Exact current-source `hello` request. Empty client names remain representable.",
        validations=('requireU32("htspVersion", htspVersion)',), decoder_feature="hello",
    ),
    "authenticate": dict(
        kind="class",
        kdoc="Bare public authentication request. Credentials are connection-envelope data, not parameters.",
        decoder_feature="authenticate",
    ),
}


def _request_model(entry: Entry) -> RequestModelSpec:
    values: dict[str, object] = {
        "constructor_parameters": entry.parameters,
        "kind": "class" if not entry.parameters else "data class",
    }
    values.update(_REQUEST_MODEL_OVERRIDES.get(entry.method, {}))
    values["kdoc"] = generated_type_kdoc(entry.model_output, entry.request)
    return RequestModelSpec(method=entry.method, **values)  # type: ignore[arg-type]


REQUEST_MODEL_CATALOG: tuple[RequestModelSpec, ...] = tuple(_request_model(entry) for entry in CATALOG)


REQUEST_AUXILIARY_DECLARATIONS: tuple[tuple[str, KotlinDeclaration], ...] = (
    ("addDvrEntry", KotlinDeclaration("AddDvrEntrySelector", "sealed interface",
        kdoc="Valid `addDvrEntry` selector forms from pinned source and accepted event scheduling.",
        feature="add-dvr-selector")),
    ("subscribe", KotlinDeclaration("SubscribeChannel", "sealed interface",
        kdoc="Exactly one channel selector accepted by the pinned `subscribe` handler.",
        feature="subscribe-channel")),
    ("subscriptionSeek", KotlinDeclaration("SubscriptionSeekPosition", "sealed interface",
        kdoc="Exactly one signed seek coordinate accepted by `subscriptionSeek` and `subscriptionSkip`.",
        feature="subscription-seek-position")),
)

REQUEST_NESTED_DECLARATIONS: dict[str, tuple[KotlinDeclaration, ...]] = {
    "EpgQueryResponse": (
        KotlinDeclaration("EventIds", "data class", kdoc="An `epgQuery` reply containing only event identifiers."),
        KotlinDeclaration("Events", "data class", kdoc="An `epgQuery` reply containing typed event values."),
    ),
    "HtspRecordingRuleChannel": (
        KotlinDeclaration("Id", "value class", kdoc="Select one recording-rule channel by its complete unsigned HTSP channel ID."),
        KotlinDeclaration("Any", "data object", kdoc="Encodes the signed `-1` any-channel sentinel; update requests may also clear a channel by omitting it."),
    ),
    "GetTicketSelector": (
        KotlinDeclaration("Channel", "value class", kdoc="Select one ticket source by its complete unsigned HTSP channel ID."),
        KotlinDeclaration("Dvr", "value class", kdoc="Select one ticket source by its complete unsigned HTSP DVR ID."),
    ),
    "AddDvrEntrySelector": (
        KotlinDeclaration("Event", "data class", kdoc="Select an existing event when constructing an `addDvrEntry` request."),
        KotlinDeclaration("ExplicitChannelTime", "data class", kdoc="Select a channel and explicit start and stop coordinates for `addDvrEntry`."),
    ),
    "SubscribeChannel": (
        KotlinDeclaration("Id", "data class", kdoc="Select a subscription channel by its complete unsigned HTSP channel ID."),
        KotlinDeclaration("Name", "data class", kdoc="Select a subscription channel by its exact name string."),
    ),
    "SubscriptionSeekPosition": (
        KotlinDeclaration("Time", "data class", kdoc="Carry the signed time coordinate for a subscription seek or skip."),
        KotlinDeclaration("Size", "data class", kdoc="Carry the signed byte-size coordinate for a subscription seek or skip."),
    ),
    "ApiResponse": (
        KotlinDeclaration("Payload", "data class", kdoc="A successful JSON API response containing a map or list payload."),
        KotlinDeclaration("NoPayload", "data object", kdoc="A successful JSON API callback that supplied no response payload."),
    ),
}

def _document_declaration(
    owner: str,
    declaration: KotlinDeclaration,
    parent: str = "",
) -> KotlinDeclaration:
    qualified = ".".join(filter(None, (parent, declaration.name)))
    return replace(
        declaration,
        kdoc=generated_type_kdoc(owner, qualified),
        nested=tuple(
            _document_declaration(owner, nested, qualified)
            for nested in declaration.nested
        ),
    )


SERVER_DECLARATIONS = tuple(
    _document_declaration("server-models", declaration)
    for declaration in SERVER_DECLARATIONS
)
SERVER_DISPATCH_DECLARATIONS = tuple(
    _document_declaration("server-dispatch", declaration)
    for declaration in SERVER_DISPATCH_DECLARATIONS
)
SERVER_DISPATCH_DECODER_KDOC = GENERATED_FUNCTION_KDOCS[
    "fun:at.bernhardberger.tvheadend.htsp.messages.decodeHtspServerMessage(Map<String,Any?>)"
]
REQUEST_PUBLIC_DECLARATIONS = tuple(
    _document_declaration(
        declaration.output,
        replace(declaration, nested=REQUEST_NESTED_DECLARATIONS.get(declaration.name, ())),
    )
    for declaration in REQUEST_PUBLIC_DECLARATIONS
)
REQUEST_AUXILIARY_DECLARATIONS = tuple(
    (
        method,
        _document_declaration(
            "request-models",
            replace(declaration, nested=REQUEST_NESTED_DECLARATIONS[declaration.name]),
        ),
    )
    for method, declaration in REQUEST_AUXILIARY_DECLARATIONS
)
REQUEST_NESTED_DECLARATIONS = {
    declaration.name: declaration.nested
    for declaration in (
        *REQUEST_PUBLIC_DECLARATIONS,
        *(value for _method, value in REQUEST_AUXILIARY_DECLARATIONS),
    )
    if declaration.nested
}


ALT = "alternative"


def _client_fields(
    method: str,
    *rows: tuple[str, str, str, str, str] | tuple[str, str, str, str, str, int | None, str | None, str | None],
) -> tuple[WireField, ...]:
    result = []
    for row in rows:
        prop, wire, wire_type, presence, encoder, *tail = row
        minimum = tail[0] if tail else None
        source = tail[1] if len(tail) > 1 else None
        nested = tail[2] if len(tail) > 2 else None
        result.append(RW(method, prop, wire_type, presence, encoder, wire,
            minimum_version=minimum, source_expression=source, nested_shape=nested))
    return tuple(result)


_RULE_WIRE_FIELDS = (
    ("channel", "channelId", S64, OPT, "recording-rule-channel"),
    ("minDurationSeconds", "minduration", U32, OPT, "direct"),
    ("maxDurationSeconds", "maxduration", U32, OPT, "direct"),
    ("fullText", "fulltext", U32, OPT, "direct", 20, None, None),
    ("mergeText", "mergetext", U32, OPT, "direct"),
    ("duplicateDetection", "dupDetect", U32, OPT, "direct", 20, None, None),
    ("maximumRecordingCount", "maxCount", U32, OPT, "direct"),
    ("broadcastType", "broadcastType", U32, OPT, "direct", 39, None, None),
    ("startExtraMinutes", "startExtra", S64, OPT, "direct"),
    ("stopExtraMinutes", "stopExtra", S64, OPT, "direct"),
    ("seriesLinkUri", "serieslinkUri", STR, OPT, "direct"),
)
_RULE_COMMON_TAIL_FIELDS = (
    ("enabled", "enabled", U32, OPT, "flag", 19, None, None),
    ("retentionDays", "retention", U32, OPT, "direct"),
    ("removalDays", "removal", U32, OPT, "direct"),
    ("priority", "priority", U32, OPT, "direct"),
    ("name", "name", STR, OPT, "direct", 18, None, None),
    ("comment", "comment", STR, OPT, "direct", 42, None, None),
    ("directory", "directory", STR, OPT, "direct", 19, None, None),
    ("configName", "configName", STR, OPT, "direct"),
    ("daysOfWeekMask", "daysOfWeek", U32, OPT, "direct"),
)


REQUEST_FIELDS_BY_METHOD: dict[str, tuple[WireField, ...]] = {
    method: () for method in ("getProfiles", "getDiskSpace", "getSysTime", "getDvrConfigs", "authenticate")
}
REQUEST_FIELDS_BY_METHOD.update({
    "enableAsyncMetadata": _client_fields("enableAsyncMetadata",
        ("epg", "epg", U32, OPT, "direct", 6, None, None),
        ("lastUpdate", "lastUpdate", S64, OPT, "direct", 6, None, None),
        ("epgMaxTime", "epgMaxTime", S64, OPT, "direct", 6, None, None),
        ("language", "language", STR, OPT, "direct", 6, None, None)),
    "getChannel": _client_fields("getChannel", ("channelId", "channelId", U32, REQ, "direct")),
    "getEvent": _client_fields("getEvent",
        ("eventId", "eventId", U32, REQ, "direct"),
        ("language", "language", STR, OPT, "direct", 6, None, None)),
    "getEvents": _client_fields("getEvents",
        ("channelId", "channelId", U32, OPT, "direct", 6, None, None),
        ("eventId", "eventId", U32, OPT, "direct", 6, None, None),
        ("language", "language", STR, OPT, "direct", 6, None, None),
        ("numFollowing", "numFollowing", U32, OPT, "direct", 6, None, None),
        ("maxTime", "maxTime", S64, OPT, "direct", 6, None, None)),
    "epgQuery": _client_fields("epgQuery",
        ("query", "query", STR, REQ, "direct"), ("channelId", "channelId", U32, OPT, "direct"),
        ("tagId", "tagId", U32, OPT, "direct"), ("contentType", "contentType", U32, OPT, "direct"),
        ("language", "language", STR, OPT, "direct", 6, None, None),
        ("fullText", "fulltext", FLAG, OPT, "direct"), ("mergeText", "mergetext", FLAG, OPT, "direct"),
        ("full", "full", U32, OPT, "direct"),
        ("minDurationSeconds", "minduration", U32, OPT, "direct", 13, None, None),
        ("maxDurationSeconds", "maxduration", U32, OPT, "direct", 13, None, None)),
    "getEpgObject": _client_fields("getEpgObject",
        ("id", "id", U32, REQ, "direct"), ("objectType", "type", U32, OPT, "epg-object-type")),
    "addDvrEntry": _client_fields("addDvrEntry",
        ("selector", "channelId", U32, ALT, "add-dvr-channel", 5, "request.selector.channelId", None),
        ("selector", "eventId", U32, ALT, "add-dvr-event", None, "request.selector.eventId", None),
        ("configName", "configName", STR, OPT, "direct"), ("language", "language", STR, OPT, "direct"),
        ("selector", "start", S64, ALT, "add-dvr-time", 5, "request.selector.start", None),
        ("selector", "stop", S64, ALT, "add-dvr-time", 5, "request.selector.stop", None),
        ("title", "title", STR, OPT, "direct", 6, None, None),
        ("subtitle", "subtitle", STR, OPT, "direct", 20, None, None),
        ("summary", "summary", STR, OPT, "direct"),
        ("description", "description", STR, OPT, "direct", 5, None, None),
        ("ageRating", "ageRating", U32, OPT, "direct", 36, None, None)),
    "updateDvrEntry": _client_fields("updateDvrEntry",
        ("channelId", "channelId", U32, OPT, "direct", 22, None, None),
        ("configName", "configName", STR, OPT, "direct"), ("title", "title", STR, OPT, "direct"),
        ("subtitle", "subtitle", STR, OPT, "direct", 21, None, None),
        ("summary", "summary", STR, OPT, "direct"),
        ("description", "description", STR, OPT, "direct", 6, None, None),
        ("language", "language", STR, OPT, "direct"),
        ("comment", "comment", STR, OPT, "direct", 42, None, None),
        ("playCount", "playcount", U32, OPT, "direct", 27, None, None),
        ("playPosition", "playposition", U32, OPT, "direct", 27, None, None),
        ("enabled", "enabled", S64, OPT, "direct", 23, None, None),
        ("start", "start", S64, OPT, "direct"), ("stop", "stop", S64, OPT, "direct"),
        ("startExtra", "startExtra", S64, OPT, "direct", 6, None, None),
        ("stopExtra", "stopExtra", S64, OPT, "direct", 6, None, None),
        ("retention", "retention", U32, OPT, "direct", 13, None, None),
        ("removal", "removal", U32, OPT, "direct"),
        ("priority", "priority", U32, OPT, "direct", 13, None, None),
        ("ageRating", "ageRating", U32, OPT, "direct", 36, None, None),
        ("entryId", "id", U32, REQ, "direct")),
    **{method: _client_fields(method, ("entryId", "id", U32, REQ, "direct"))
       for method in ("stopDvrEntry", "cancelDvrEntry", "deleteDvrEntry")},
    "addAutorecEntry": _client_fields("addAutorecEntry",
        ("title", "title", STR, REQ, "direct"), *_RULE_WIRE_FIELDS,
        ("approximateStartMinutesSinceMidnight", "approxTime", S32, OPT, "direct"),
        ("startMinutesSinceMidnight", "start", S32, OPT, "direct", 18, None, None),
        ("startWindowEndMinutesSinceMidnight", "startWindow", S32, OPT, "direct", 18, None, None),
        *_RULE_COMMON_TAIL_FIELDS),
    "updateAutorecEntry": _client_fields("updateAutorecEntry",
        ("id", "id", STR, REQ, "direct"), *_RULE_WIRE_FIELDS,
        ("startMinutesSinceMidnight", "start", S32, OPT, "direct", 18, None, None),
        ("startWindowEndMinutesSinceMidnight", "startWindow", S32, OPT, "direct", 18, None, None),
        *_RULE_COMMON_TAIL_FIELDS[:-2], ("title", "title", STR, OPT, "direct"),
        *_RULE_COMMON_TAIL_FIELDS[-2:]),
    "deleteAutorecEntry": _client_fields("deleteAutorecEntry", ("id", "id", STR, REQ, "direct")),
    "addTimerecEntry": _client_fields("addTimerecEntry",
        ("title", "title", STR, REQ, "direct"),
        ("channel", "channelId", S64, OPT, "recording-rule-channel"),
        ("startMinutesSinceMidnight", "start", U32, OPT, "direct"),
        ("stopMinutesSinceMidnight", "stop", U32, OPT, "direct"), *_RULE_COMMON_TAIL_FIELDS),
    "updateTimerecEntry": _client_fields("updateTimerecEntry",
        ("id", "id", STR, REQ, "direct"),
        ("channel", "channelId", S64, OPT, "recording-rule-channel"),
        ("startMinutesSinceMidnight", "start", U32, OPT, "direct"),
        ("stopMinutesSinceMidnight", "stop", U32, OPT, "direct"),
        *_RULE_COMMON_TAIL_FIELDS[:-2], ("title", "title", STR, OPT, "direct"),
        *_RULE_COMMON_TAIL_FIELDS[-2:]),
    "deleteTimerecEntry": _client_fields("deleteTimerecEntry", ("id", "id", STR, REQ, "direct")),
    "getDvrCutpoints": _client_fields("getDvrCutpoints", ("entryId", "id", U32, REQ, "direct")),
    "getTicket": _client_fields("getTicket",
        ("selector", "channelId", U32, ALT, "ticket-channel", None, "selector.channelId", None),
        ("selector", "dvrId", U32, ALT, "ticket-dvr", None, "selector.dvrId", None)),
    "subscribe": _client_fields("subscribe",
        ("subscriptionId", "subscriptionId", U32, REQ, "direct"),
        ("channel", "channelId", U32, ALT, "subscribe-id"),
        ("channel", "channelName", STR, ALT, "subscribe-name"),
        ("profile", "profile", STR, OPT, "direct", 16, None, None),
        ("weight", "weight", U32, OPT, "direct"),
        ("ninetyKhz", "90khz", U32, OPT, "direct", 7, None, None),
        ("timeshiftPeriodSeconds", "timeshiftPeriod", U32, OPT, "direct", 9, None, None),
        ("queueDepth", "queueDepth", U32, OPT, "direct", 7, None, None)),
    "unsubscribe": _client_fields("unsubscribe", ("subscriptionId", "subscriptionId", U32, REQ, "direct")),
    "subscriptionChangeWeight": _client_fields("subscriptionChangeWeight",
        ("subscriptionId", "subscriptionId", U32, REQ, "direct"),
        ("weight", "weight", U32, OPT, "direct")),
    "subscriptionSeek": _client_fields("subscriptionSeek",
        ("subscriptionId", "subscriptionId", U32, REQ, "direct"),
        ("position", "time", S64, ALT, "seek-time"), ("position", "size", S64, ALT, "seek-size"),
        ("absolute", "absolute", U32, OPT, "direct")),
    "subscriptionSkip": _client_fields("subscriptionSkip",
        ("subscriptionId", "subscriptionId", U32, REQ, "direct"),
        ("position", "time", S64, ALT, "seek-time"), ("position", "size", S64, ALT, "seek-size"),
        ("absolute", "absolute", U32, OPT, "direct")),
    "subscriptionSpeed": _client_fields("subscriptionSpeed",
        ("subscriptionId", "subscriptionId", U32, REQ, "direct"),
        ("speed", "speed", S32, REQ, "direct")),
    "subscriptionLive": _client_fields("subscriptionLive", ("subscriptionId", "subscriptionId", U32, REQ, "direct")),
    "subscriptionFilterStream": _client_fields("subscriptionFilterStream",
        ("subscriptionId", "subscriptionId", U32, REQ, "direct"),
        ("enable", "enable", LIST, OPT, "direct", None, None, "u32-list"),
        ("disable", "disable", LIST, OPT, "direct", None, None, "u32-list")),
    "fileOpen": _client_fields("fileOpen", ("file", "file", STR, REQ, "direct")),
    "fileRead": _client_fields("fileRead",
        ("id", "id", U32, REQ, "direct"), ("size", "size", S64, REQ, "bounded-file-size"),
        ("offset", "offset", S64, OPT, "direct")),
    "fileClose": _client_fields("fileClose",
        ("id", "id", U32, REQ, "direct"),
        ("playPositionSeconds", "playposition", U32, OPT, "direct", 27, None, None),
        ("playCount", "playcount", U32, OPT, "direct", 27, None, None)),
    "fileStat": _client_fields("fileStat", ("id", "id", U32, REQ, "direct")),
    "fileSeek": _client_fields("fileSeek",
        ("id", "id", U32, REQ, "direct"), ("offset", "offset", S64, REQ, "direct"),
        ("whence", "whence", STR, OPT, "file-whence")),
    "api": _client_fields("api", ("path", "path", STR, REQ, "direct"),
        ("args", "args", MAP, OPT, "api-value", None, None, "api-object")),
    "hello": _client_fields("hello", ("htspVersion", "htspversion", U32, REQ, "direct"),
        ("clientName", "clientname", STR, REQ, "direct")),
})


def _reply_fields(
    method: str,
    *rows: tuple[str, str, str, str, str] | tuple[str, str, str, str, str, int | None, str | None, bool],
) -> tuple[WireField, ...]:
    result = []
    for row in rows:
        prop, wire, wire_type, presence, decoder, *tail = row
        minimum = tail[0] if tail else None
        nested = tail[1] if len(tail) > 1 else None
        lenient = tail[2] if len(tail) > 2 else False
        result.append(W(method, prop, wire_type, presence, decoder, wire,
            minimum_version=minimum, nested_shape=nested, lenient_malformed=lenient))
    return tuple(result)


_EVENT_REPLY_FIELDS = _reply_fields("event",
    ("eventId", "eventId", U32, REQ, "u32"), ("channelId", "channelId", U32, OPT, "u32"),
    ("start", "start", S64, REQ, "s64"), ("stop", "stop", S64, REQ, "s64"),
    ("title", "title", STR, OPT, "string"), ("subtitle", "subtitle", STR, OPT, "string"),
    ("summary", "summary", STR, OPT, "string"), ("description", "description", STR, OPT, "string"),
    ("categories", "category", LIST, OPT, "string-list", None, "string-list", False),
    ("keywords", "keyword", LIST, OPT, "string-list", None, "string-list", False),
    ("seriesLinkUri", "serieslinkUri", STR, OPT, "string"), ("episodeUri", "episodeUri", STR, OPT, "string"),
    ("contentType", "contentType", U32, OPT, "u32"), ("ageRating", "ageRating", U32, OPT, "u32"),
    ("ratingLabel", "ratingLabel", STR, OPT, "string"), ("ratingIcon", "ratingIcon", STR, OPT, "string"),
    ("ratingAuthority", "ratingAuthority", STR, OPT, "string"), ("ratingCountry", "ratingCountry", STR, OPT, "string"),
    ("starRating", "starRating", U32, OPT, "u32"), ("copyrightYear", "copyrightYear", U32, OPT, "u32"),
    ("firstAired", "firstAired", S64, OPT, "s64"), ("isNew", "isNew", U32, OPT, "u32"),
    ("seasonNumber", "seasonNumber", U32, OPT, "u32"), ("seasonCount", "seasonCount", U32, OPT, "u32"),
    ("episodeNumber", "episodeNumber", U32, OPT, "u32"), ("episodeCount", "episodeCount", U32, OPT, "u32"),
    ("partNumber", "partNumber", U32, OPT, "u32"), ("partCount", "partCount", U32, OPT, "u32"),
    ("episodeOnscreen", "episodeOnscreen", STR, OPT, "string"), ("image", "image", STR, OPT, "string"),
    ("dvrId", "dvrId", U32, OPT, "u32"), ("nextEventId", "nextEventId", U32, OPT, "u32"),
)


REQUEST_REPLY_NESTED_SHAPES: tuple[NestedShape, ...] = (
    NestedShape("profile", "HtspProfile", "profileFromFields", _reply_fields("profile",
        ("profileUuid", "uuid", STR, REQ, "string"), ("name", "name", STR, REQ, "string"),
        ("comment", "comment", STR, REQ, "string")), spec_owner="profile"),
    NestedShape("dvr-config", "HtspDvrConfig", "dvrConfigFromFields", _reply_fields("dvr-config",
        ("dvrConfigUuid", "uuid", STR, REQ, "string"), ("name", "name", STR, REQ, "string"),
        ("comment", "comment", STR, REQ, "string")), spec_owner="dvrConfig"),
    NestedShape("channel-service", "HtspChannelService", "serviceFromFields", _reply_fields("channel-service",
        ("name", "name", STR, REQ, "string"), ("type", "type", STR, REQ, "string"),
        ("content", "content", U32, REQ, "u32"), ("conditionalAccessId", "caid", U32, OPT, "u32"),
        ("conditionalAccessName", "caname", STR, OPT, "string"),
        ("providerName", "providername", STR, OPT, "string")), spec_owner="service"),
    NestedShape(
        "event",
        "HtspEvent",
        "eventFromFields",
        _EVENT_REPLY_FIELDS,
        spec_owner="event",
        spec_reference_target="message:eventAdd",
    ),
    NestedShape("cutpoint", "HtspDvrCutpoint", "cutpointFromFields", _reply_fields("cutpoint",
        ("start", "start", U32, REQ, "u32"), ("end", "end", U32, REQ, "u32"),
        ("type", "type", U32, REQ, "u32")), spec_owner="cutpoint"),
    NestedShape("epg-episode-number", "HtspEpgEpisodeNumber", "optionalEpgEpisodeNumber", _reply_fields("epg-episode-number",
        ("episodeNumber", "enum", U32, OPT, "u32"), ("episodeCount", "ecnt", U32, OPT, "u32"),
        ("seasonNumber", "snum", U32, OPT, "u32"), ("seasonCount", "scnt", U32, OPT, "u32"),
        ("partNumber", "pnum", U32, OPT, "u32"), ("partCount", "pcnt", U32, OPT, "u32"),
        ("text", "text", STR, OPT, "string")), spec_owner="epgEpisodeNumber"),
    NestedShape("epg-broadcast", "HtspEpgBroadcastObject", "epgBroadcastObjectFromFields", _reply_fields("epg-broadcast",
        ("objectType", "tp", U32, REQ, "broadcast-type"), ("id", "id", U32, REQ, "u32"),
        ("updatedUnixSeconds", "up", S64, REQ, "s64"), ("startUnixSeconds", "start", S64, REQ, "s64"),
        ("stopUnixSeconds", "stop", S64, REQ, "s64"), ("grabber", "gr", STR, OPT, "string"),
        ("channelUuid", "ch", STR, OPT, "string"), ("eventId", "eid", U32, OPT, "u32"),
        ("externalEventId", "xeid", STR, OPT, "string"),
        *((prop, wire, FLAG, OPT, "true-flag") for prop, wire in (
            ("widescreen", "is_wd"), ("highDefinition", "is_hd"), ("blackAndWhite", "is_bw"),
            ("deafSigned", "is_de"), ("subtitled", "is_st"), ("audioDescribed", "is_ad"),
            ("isNew", "is_n"), ("isRepeat", "is_r"),
        )),
        ("lines", "lines", U32, OPT, "u32"), ("aspectRatio", "aspect", U32, OPT, "u32"),
        ("starRating", "star", U32, OPT, "u32"), ("ageRating", "age", U32, OPT, "u32"),
        ("ratingLabel", "ratlab", STR, OPT, "string"), ("image", "img", STR, OPT, "string"),
        ("titles", "tit", MAP, OPT, "string-map", None, "localized-string-map", False),
        ("subtitles", "sti", MAP, OPT, "string-map", None, "localized-string-map", False),
        ("summaries", "sum", MAP, OPT, "string-map", None, "localized-string-map", False),
        ("descriptions", "des", MAP, OPT, "string-map", None, "localized-string-map", False),
        ("episodeNumber", "epn", MAP, OPT, "episode-number", None, "epg-episode-number", False),
        ("genres", "genre", LIST, OPT, "u32-list", None, "u32-list", False),
        ("copyrightYear", "cyear", U32, OPT, "u32"), ("firstAiredUnixSeconds", "fair", S64, OPT, "s64"),
        ("categories", "cat", LIST, OPT, "sorted-unique-string-list", None, "string-list", False),
        ("keywords", "key", LIST, OPT, "sorted-unique-string-list", None, "string-list", False),
        ("seriesLinkUri", "slink", STR, OPT, "string"), ("episodeLinkUri", "elink", STR, OPT, "string")),
        spec_domain="reply-shape", spec_owner="getEpgObject"),
)

REQUEST_REPLY_TERMINAL_NESTED_TARGETS: tuple[str, ...] = (
    "api-value", "localized-string-map", "string-list", "u32-list",
)

# Every catalog nested link resolves through one explicit pinned-spec target.
# ``None`` records an intentionally unconstrained container with no shapeRef in
# the committed evidence. Root projections additionally require an exact
# owner/target relation below.
SPEC_SHAPE_LINK_TARGETS: dict[str, dict[str, tuple[str, str] | None]] = {
    "api.args": {"args": None},
    "api.response": {"response": None},
    "channelAdd.services": {"services": ("shape", "service")},
    "channelAdd.tagIds": {"tagIds": None, "tags": ("shape", "u32"), "channelTags": None},
    "channelUpdate.services": {"services": ("shape", "service")},
    "channelUpdate.tagIds": {"tagIds": None, "tags": ("shape", "u32"), "channelTags": None},
    "dvrEntryAdd.files": {"files": ("shape", "recordingFile")},
    "dvrEntryUpdate.files": {"files": ("shape", "recordingFile")},
    "epg-broadcast.categories": {"cat": ("shape", "str")},
    "epg-broadcast.descriptions": {"des": ("shape", "epgLanguageStrings")},
    "epg-broadcast.episodeNumber": {"epn": ("shape", "epgEpisodeNumber")},
    "epg-broadcast.genres": {"genre": ("shape", "u32")},
    "epg-broadcast.keywords": {"key": ("shape", "str")},
    "epg-broadcast.subtitles": {"sti": ("shape", "epgLanguageStrings")},
    "epg-broadcast.summaries": {"sum": ("shape", "epgLanguageStrings")},
    "epg-broadcast.titles": {"tit": ("shape", "epgLanguageStrings")},
    "epgQuery.eventIds": {"eventIds": ("shape", "u32")},
    "epgQuery.events": {"events": ("shape", "event")},
    "event.categories": {"category": ("shape", "str")},
    "event.keywords": {"keyword": ("shape", "str")},
    "eventAdd.event": {"<root>": ("message", "eventAdd")},
    "eventAdd.genre": {"genre": None, "category": ("shape", "str")},
    "eventUpdate.categories": {"category": ("shape", "str")},
    "eventUpdate.genre": {"genre": None, "category": ("shape", "str")},
    "eventUpdate.keywords": {"keyword": ("shape", "str")},
    "getChannel.services": {"services": ("shape", "service")},
    "getChannel.tagIds": {"tags": ("shape", "u32")},
    "getDvrConfigs.configurations": {"dvrconfigs": ("shape", "dvrConfig")},
    "getDvrCutpoints.cutpoints": {"cutpoints": ("shape", "cutpoint")},
    "getEpgObject.broadcast": {"<root>": ("reply", "getEpgObject")},
    "getEvent.event": {"<root>": ("reply", "getEvent")},
    "getEvents.events": {"events": ("shape", "event")},
    "getProfiles.profiles": {"profiles": ("shape", "profile")},
    "hello.serverCapabilities": {"servercapability": None},
    "subscriptionFilterStream.disable": {"disable": ("shape", "u32")},
    "subscriptionFilterStream.enable": {"enable": ("shape", "u32")},
    "subscriptionStart.sourceInfo": {"sourceinfo": ("shape", "sourceInfo")},
    "subscriptionStart.streams": {"streams": ("shape", "stream")},
    "tagAdd.channelIds": {"members": ("shape", "u32")},
    "tagUpdate.channelIds": {"members": ("shape", "u32")},
}

# Terminal decoder targets do not own catalog field declarations. Each one
# still records the complete set of pinned-spec links admitted for its uses;
# ``None`` is intentional where the evidence has no shapeRef.
TERMINAL_NESTED_SPEC_TARGETS: dict[
    str,
    tuple[tuple[str, str] | None, ...],
] = {
    "api-object": (None,),
    "api-value": (None,),
    "localized-string-map": (("shape", "epgLanguageStrings"),),
    "string-list": (("shape", "str"), None),
    "u32-list": (("shape", "u32"),),
}

# Exact catalog target identity for every occurrence that traverses another
# catalog shape or a terminal decoder target. Spec targets alone are not
# injective (for example, both API targets intentionally map to no shapeRef).
CATALOG_NESTED_LINK_IDENTITIES: dict[str, str] = {
    "message.channelAdd.services": "service",
    "message.channelUpdate.services": "service",
    "message.dvrEntryAdd.files": "dvr-file",
    "message.dvrEntryUpdate.files": "dvr-file",
    "message.eventAdd.event": "event",
    "message.subscriptionStart.sourceInfo": "source-info",
    "message.subscriptionStart.streams": "stream",
    "reply.api.response": "api-value",
    "reply.epgQuery.eventIds": "u32-list",
    "reply.epgQuery.events": "event",
    "reply.getChannel.services": "channel-service",
    "reply.getChannel.tagIds": "u32-list",
    "reply.getDvrConfigs.configurations": "dvr-config",
    "reply.getDvrCutpoints.cutpoints": "cutpoint",
    "reply.getEpgObject.broadcast": "epg-broadcast",
    "reply.getEvent.event": "event",
    "reply.getEvents.events": "event",
    "reply.getProfiles.profiles": "profile",
    "reply.hello.serverCapabilities": "string-list",
    "request.api.args": "api-object",
    "request.subscriptionFilterStream.disable": "u32-list",
    "request.subscriptionFilterStream.enable": "u32-list",
    "request-nested.epg-broadcast.categories": "string-list",
    "request-nested.epg-broadcast.descriptions": "localized-string-map",
    "request-nested.epg-broadcast.episodeNumber": "epg-episode-number",
    "request-nested.epg-broadcast.genres": "u32-list",
    "request-nested.epg-broadcast.keywords": "string-list",
    "request-nested.epg-broadcast.subtitles": "localized-string-map",
    "request-nested.epg-broadcast.summaries": "localized-string-map",
    "request-nested.epg-broadcast.titles": "localized-string-map",
    "request-nested.event.categories": "string-list",
    "request-nested.event.keywords": "string-list",
}

ROOT_SPEC_RELATIONS: tuple[tuple[str, str, str], ...] = (
    ("event", "message", "eventAdd"),
    ("event", "reply", "getEvent"),
    ("epg-broadcast", "reply", "getEpgObject"),
)

# Explicit root-owner spec fields represented by each linked domain-specific
# catalog shape. These non-waivable signatures permit unrelated extra owner
# fields while preventing an ordinary compatibility waiver from masking root
# payload drift.
ROOT_SPEC_PAYLOAD_SIGNATURES: dict[
    tuple[str, str, str],
    tuple[tuple[str, str, int | None, str | None], ...],
] = {
    ("event", "message", "eventAdd"): (
        ("ageRating", "u32", None, None),
        ("category", "list", None, "str"),
        ("channelId", "u32", None, None),
        ("contentType", "u32", None, None),
        ("copyrightYear", "u32", None, None),
        ("description", "str", None, None),
        ("dvrId", "u32", None, None),
        ("episodeCount", "u32", None, None),
        ("episodeNumber", "u32", None, None),
        ("episodeOnscreen", "str", None, None),
        ("episodeUri", "str", None, None),
        ("eventId", "u32", None, None),
        ("firstAired", "s64", None, None),
        ("image", "str", None, None),
        ("isNew", "u32", None, None),
        ("keyword", "list", None, "str"),
        ("nextEventId", "u32", None, None),
        ("partCount", "u32", None, None),
        ("partNumber", "u32", None, None),
        ("ratingAuthority", "str", 41, None),
        ("ratingCountry", "str", 41, None),
        ("ratingIcon", "str", None, None),
        ("ratingLabel", "str", None, None),
        ("seasonCount", "u32", None, None),
        ("seasonNumber", "u32", None, None),
        ("serieslinkUri", "str", None, None),
        ("starRating", "u32", None, None),
        ("start", "s64", None, None),
        ("stop", "s64", None, None),
        ("subtitle", "str", None, None),
        ("summary", "str", None, None),
        ("title", "str", None, None),
    ),
    ("event", "reply", "getEvent"): (
        ("ageRating", "u32", None, None),
        ("category", "list", None, "str"),
        ("channelId", "u32", None, None),
        ("contentType", "u32", None, None),
        ("copyrightYear", "u32", None, None),
        ("description", "str", None, None),
        ("dvrId", "u32", None, None),
        ("episodeCount", "u32", None, None),
        ("episodeNumber", "u32", None, None),
        ("episodeOnscreen", "str", None, None),
        ("episodeUri", "str", None, None),
        ("eventId", "u32", None, None),
        ("firstAired", "s64", None, None),
        ("image", "str", None, None),
        ("isNew", "u32", None, None),
        ("keyword", "list", None, "str"),
        ("nextEventId", "u32", None, None),
        ("partCount", "u32", None, None),
        ("partNumber", "u32", None, None),
        ("ratingAuthority", "str", None, None),
        ("ratingCountry", "str", None, None),
        ("ratingIcon", "str", None, None),
        ("ratingLabel", "str", None, None),
        ("seasonCount", "u32", None, None),
        ("seasonNumber", "u32", None, None),
        ("serieslinkUri", "str", None, None),
        ("starRating", "u32", None, None),
        ("start", "s64", None, None),
        ("stop", "s64", None, None),
        ("subtitle", "str", None, None),
        ("summary", "str", None, None),
        ("title", "str", None, None),
    ),
    ("epg-broadcast", "reply", "getEpgObject"): (
        ("age", "u32", None, None),
        ("aspect", "u32", None, None),
        ("cat", "list", None, "str"),
        ("ch", "str", None, None),
        ("cyear", "u32", None, None),
        ("des", "msg", None, "epgLanguageStrings"),
        ("eid", "u32", None, None),
        ("elink", "str", None, None),
        ("epn", "msg", None, "epgEpisodeNumber"),
        ("fair", "s64", None, None),
        ("genre", "list", None, "u32"),
        ("gr", "str", None, None),
        ("id", "u32", None, None),
        ("img", "str", None, None),
        ("is_ad", "u32", None, None),
        ("is_bw", "u32", None, None),
        ("is_de", "u32", None, None),
        ("is_hd", "u32", None, None),
        ("is_n", "u32", None, None),
        ("is_r", "u32", None, None),
        ("is_st", "u32", None, None),
        ("is_wd", "u32", None, None),
        ("key", "list", None, "str"),
        ("lines", "u32", None, None),
        ("ratlab", "str", None, None),
        ("slink", "str", None, None),
        ("star", "u32", None, None),
        ("start", "s64", None, None),
        ("sti", "msg", None, "epgLanguageStrings"),
        ("stop", "s64", None, None),
        ("sum", "msg", None, "epgLanguageStrings"),
        ("tit", "msg", None, "epgLanguageStrings"),
        ("tp", "u32", None, None),
        ("up", "s64", None, None),
        ("xeid", "str", None, None),
    ),
}


_MUTATION_REPLY = (
    ("success", "success", U32, ALT, "u32"), ("error", "error", STR, ALT, "string"),
)
_STRICT_SUCCESS_REPLY = (("success", "success", U32, REQ, "strict-one"),)


REPLY_FIELDS_BY_METHOD: dict[str, tuple[WireField, ...]] = {
    "getProfiles": _reply_fields("getProfiles", ("profiles", "profiles", LIST, OPT, "object-list", None, "profile", False)),
    "getDiskSpace": _reply_fields("getDiskSpace",
        ("freeBytes", "freediskspace", S64, REQ, "s64"), ("usedBytes", "useddiskspace", S64, OPT, "s64"),
        ("totalBytes", "totaldiskspace", S64, REQ, "s64")),
    "getSysTime": _reply_fields("getSysTime", ("unixTimeSeconds", "time", S32, REQ, "s32"),
        ("legacyTimezoneHoursWestOfGmt", "timezone", S32, REQ, "s32"),
        ("gmtOffsetMinutes", "gmtoffset", S32, OPT, "s32")),
    "enableAsyncMetadata": (),
    "getChannel": _reply_fields("getChannel",
        ("channelId", "channelId", U32, REQ, "u32"),
        ("channelUuid", "channelIdStr", STR, OPT, "string", 41, None, False),
        ("channelNumber", "channelNumber", U32, REQ, "u32"),
        ("channelNumberMinor", "channelNumberMinor", U32, OPT, "u32", 13, None, False),
        ("channelName", "channelName", STR, REQ, "string"),
        ("channelIcon", "channelIcon", STR, OPT, "string"),
        ("currentEventId", "eventId", U32, REQ, "u32"), ("nextEventId", "nextEventId", U32, REQ, "u32"),
        ("services", "services", LIST, REQ, "object-list", 5, "channel-service", False),
        ("tagIds", "tags", LIST, REQ, "u32-list", None, "u32-list", False)),
    "getEvent": _reply_fields("getEvent", ("event", "<root>", MAP, REQ, "root-shape", None, "event", False)),
    "getEvents": _reply_fields("getEvents", ("events", "events", LIST, REQ, "object-list", None, "event", False)),
    "epgQuery": _reply_fields("epgQuery",
        ("eventIds", "eventIds", LIST, ALT, "u32-list", None, "u32-list", False),
        ("events", "events", LIST, ALT, "object-list", None, "event", False)),
    "getEpgObject": _reply_fields("getEpgObject", ("broadcast", "<root>", MAP, REQ, "root-shape", None, "epg-broadcast", False)),
    "getDvrConfigs": _reply_fields("getDvrConfigs", ("configurations", "dvrconfigs", LIST, OPT, "object-list", None, "dvr-config", False)),
    "addDvrEntry": _reply_fields("addDvrEntry", *_MUTATION_REPLY,
        ("entryId", "id", U32, OPT, "u32"), ("entryId", "dvrId", U32, OPT, "u32")),
    **{method: _reply_fields(method, *_MUTATION_REPLY) for method in (
        "updateDvrEntry", "stopDvrEntry", "cancelDvrEntry", "deleteDvrEntry",
    )},
    "addAutorecEntry": _reply_fields("addAutorecEntry", *_STRICT_SUCCESS_REPLY, ("id", "id", STR, REQ, "string")),
    "updateAutorecEntry": _reply_fields("updateAutorecEntry", *_STRICT_SUCCESS_REPLY),
    "deleteAutorecEntry": _reply_fields("deleteAutorecEntry", *_STRICT_SUCCESS_REPLY),
    "addTimerecEntry": _reply_fields("addTimerecEntry", *_STRICT_SUCCESS_REPLY, ("id", "id", STR, REQ, "string")),
    "updateTimerecEntry": _reply_fields("updateTimerecEntry", *_STRICT_SUCCESS_REPLY),
    "deleteTimerecEntry": _reply_fields("deleteTimerecEntry", *_STRICT_SUCCESS_REPLY),
    "getDvrCutpoints": _reply_fields("getDvrCutpoints", ("cutpoints", "cutpoints", LIST, OPT, "object-list", None, "cutpoint", False)),
    "getTicket": _reply_fields("getTicket", ("path", "path", STR, REQ, "string"), ("ticket", "ticket", STR, REQ, "string")),
    "subscribe": _reply_fields("subscribe", ("ninetyKhz", "90khz", U32, OPT, "u32"),
        ("normalizedTimestamps", "normts", U32, OPT, "u32"), ("weight", "weight", U32, OPT, "u32"),
        ("timeshiftPeriodSeconds", "timeshiftPeriod", U32, OPT, "u32")),
    **{method: () for method in (
        "unsubscribe", "subscriptionChangeWeight", "subscriptionSeek", "subscriptionSkip",
        "subscriptionSpeed", "subscriptionLive", "subscriptionFilterStream",
    )},
    "fileOpen": _reply_fields("fileOpen", ("id", "id", U32, REQ, "u32"),
        ("sizeBytes", "size", S64, OPT, "non-negative-s64"),
        ("modifiedAtUnixSeconds", "mtime", S64, OPT, "s64")),
    "fileRead": _reply_fields("fileRead", ("data", "data", BIN, REQ, "binary-value")),
    "fileClose": (),
    "fileStat": _reply_fields("fileStat", ("sizeBytes", "size", S64, OPT, "non-negative-s64"),
        ("modifiedAtUnixSeconds", "mtime", S64, OPT, "s64")),
    "fileSeek": _reply_fields("fileSeek", ("offset", "offset", S64, REQ, "non-negative-s64")),
    "api": _reply_fields("api", ("response", "response", MAP, OPT, "api-container", None, "api-value", False)),
    "hello": _reply_fields("hello", ("htspVersion", "htspversion", U32, REQ, "u32"),
        ("serverName", "servername", STR, OPT, "observed-string", None, None, True),
        ("serverVersion", "serverversion", STR, OPT, "observed-string", None, None, True),
        ("challenge", "challenge", BIN, REQ, "binary-32"),
        ("webRoot", "webroot", STR, OPT, "observed-string", None, None, True),
        ("language", "language", STR, OPT, "observed-string", None, None, True),
        ("serverCapabilities", "servercapability", LIST, OPT, "observed-string-list", None, "string-list", True),
        ("apiVersion", "api_version", U32, OPT, "observed-u32", None, None, True)),
    "authenticate": _reply_fields("authenticate",
        ("noAccess", "noaccess", U32, OPT, "observed-flag", None, None, True),
        *((prop, wire, U32, OPT, "observed-flag", 26, None, True) for prop, wire in (
            ("admin", "admin"), ("streaming", "streaming"), ("dvr", "dvr"),
            ("failedDvr", "faileddvr"), ("anonymous", "anonymous"),
        )),
        *((prop, wire, U32, OPT, "observed-u32", 26, None, True) for prop, wire in (
            ("limitAll", "limitall"), ("limitDvr", "limitdvr"),
            ("limitStreaming", "limitstreaming"), ("uiLevel", "uilevel"),
        )),
        ("uiLanguage", "uilanguage", STR, OPT, "observed-string", 26, None, True)),
}


REPLY_CONDITIONAL_PRESENCE_RULES: tuple[ConditionalPresenceRule, ...] = (
    ConditionalPresenceRule(
        owner="getChannel",
        wire_name="channelIdStr",
        kind="required-at-or-above-version",
        minimum_version=41,
    ),
)

REPLY_COUPLED_PRESENCE_GROUPS: tuple[CoupledPresenceGroup, ...] = (
    CoupledPresenceGroup(
        owner="fileOpen",
        name="file-metadata",
        kind="all-or-none",
        wire_names=("size", "mtime"),
    ),
    CoupledPresenceGroup(
        owner="fileStat",
        name="file-metadata",
        kind="all-or-none",
        wire_names=("size", "mtime"),
    ),
)


REQUEST_WIRE_FIELDS: tuple[WireField, ...] = tuple(
    field for entry in CATALOG for field in REQUEST_FIELDS_BY_METHOD[entry.method]
)
REPLY_WIRE_FIELDS: tuple[WireField, ...] = tuple(
    field for entry in CATALOG for field in REPLY_FIELDS_BY_METHOD[entry.method]
) + tuple(field for shape in REQUEST_REPLY_NESTED_SHAPES for field in shape.fields)

# Exact catalog occurrences that intentionally preserve accepted wire vocabulary
# not represented identically by the pinned-v44 evidence.  G3 requires every
# entry to be consumed by one mismatch and rejects stale or canonical waivers.
REQUEST_SPEC_WAIVERS: tuple[tuple[str, str], ...] = (
    *_spec_waivers(
        "shipped field-level compatibility gate is retained; pinned-v44 field evidence records no minimum",
        "request.getEvent.language.wire.language",
        "request.addDvrEntry.selector.wire.channelId",
        "request.addDvrEntry.selector.wire.start",
        "request.addDvrEntry.selector.wire.stop",
        "request.addDvrEntry.title.wire.title",
        "request.addDvrEntry.subtitle.wire.subtitle",
        "request.addDvrEntry.description.wire.description",
        "request.addDvrEntry.ageRating.wire.ageRating",
        "request.updateDvrEntry.channelId.wire.channelId",
        "request.updateDvrEntry.subtitle.wire.subtitle",
        "request.updateDvrEntry.description.wire.description",
        "request.updateDvrEntry.comment.wire.comment",
        "request.updateDvrEntry.playCount.wire.playcount",
        "request.updateDvrEntry.playPosition.wire.playposition",
        "request.updateDvrEntry.enabled.wire.enabled",
        "request.updateDvrEntry.startExtra.wire.startExtra",
        "request.updateDvrEntry.stopExtra.wire.stopExtra",
        "request.updateDvrEntry.retention.wire.retention",
        "request.updateDvrEntry.priority.wire.priority",
        "request.updateDvrEntry.ageRating.wire.ageRating",
        "request.addTimerecEntry.name.wire.name",
        "request.updateTimerecEntry.name.wire.name",
        "request.subscribe.profile.wire.profile",
        "request.subscribe.ninetyKhz.wire.90khz",
        "request.subscribe.timeshiftPeriodSeconds.wire.timeshiftPeriod",
        "request.subscribe.queueDepth.wire.queueDepth",
    ),
    *_spec_waivers(
        "shipped reply decoder accepts this compatibility field; pinned-v44 method reply does not inventory it",
        "reply.addDvrEntry.entryId.wire.dvrId",
        "reply.updateDvrEntry.error.wire.error",
        "reply.stopDvrEntry.error.wire.error",
        "reply.cancelDvrEntry.error.wire.error",
        "reply.deleteDvrEntry.error.wire.error",
    ),
    *_spec_waivers(
        "shipped catalog distinguishes the accepted map container; pinned-v44 evidence records generic msg",
        "request.api.args.wire.args",
        "reply.api.response.wire.response",
    ),
    *_spec_waivers(
        "shipped catalog distinguishes the accepted list container; pinned-v44 evidence records generic msg",
        "reply.hello.serverCapabilities.wire.servercapability",
    ),
    *_spec_waivers(
        "shipped bounded nested mapping is retained; its pinned-v44 shape is intentionally opaque",
        "request-nested.profile.profileUuid.wire.uuid",
        "request-nested.profile.name.wire.name",
        "request-nested.profile.comment.wire.comment",
        "request-nested.dvr-config.dvrConfigUuid.wire.uuid",
        "request-nested.dvr-config.name.wire.name",
        "request-nested.dvr-config.comment.wire.comment",
    ),
    *_spec_waivers(
        "shipped versionless nested decoder remains compatible; pinned-v44 evidence records a field minimum",
        "request-nested.channel-service.providerName.wire.providername",
        "request-nested.event.ratingAuthority.wire.ratingAuthority",
        "request-nested.event.ratingCountry.wire.ratingCountry",
    ),
    *_spec_waivers(
        "shipped decoder preserves Boolean flag handling; pinned-v44 emitter evidence records u32",
        "request-nested.epg-broadcast.widescreen.wire.is_wd",
        "request-nested.epg-broadcast.highDefinition.wire.is_hd",
        "request-nested.epg-broadcast.blackAndWhite.wire.is_bw",
        "request-nested.epg-broadcast.deafSigned.wire.is_de",
        "request-nested.epg-broadcast.subtitled.wire.is_st",
        "request-nested.epg-broadcast.audioDescribed.wire.is_ad",
        "request-nested.epg-broadcast.isNew.wire.is_n",
        "request-nested.epg-broadcast.isRepeat.wire.is_r",
    ),
    *_spec_waivers(
        "shipped catalog distinguishes the accepted map container; pinned-v44 evidence records generic msg",
        "request-nested.epg-broadcast.titles.wire.tit",
        "request-nested.epg-broadcast.subtitles.wire.sti",
        "request-nested.epg-broadcast.summaries.wire.sum",
        "request-nested.epg-broadcast.descriptions.wire.des",
        "request-nested.epg-broadcast.episodeNumber.wire.epn",
    ),
)
REQUEST_SPEC_CONSISTENCY_STATUS = "verified-v44"
REQUEST_VERBATIM_ESCAPES: tuple[tuple[str, str], ...] = ()

# KDoc on nested public declarations is catalog data too; the model renderer
# verifies the complete inherited multiset rather than merely rendered types.
REQUEST_NESTED_KDOCS: tuple[str, ...] = tuple(
    nested.kdoc
    for owner in REQUEST_NESTED_DECLARATIONS.values()
    for nested in owner
    if nested.kdoc is not None
)


def _generated_type_keys() -> set[str]:
    keys: set[str] = set()

    def add(owner: str, declaration: KotlinDeclaration, parent: str = "") -> None:
        qualified = ".".join(filter(None, (parent, declaration.name)))
        package = GENERATED_OUTPUT_BY_KEY[owner].package
        key = f"type:{package}.{qualified}"
        if key in keys:
            raise ValueError(f"duplicate generated type declaration identity: {key}")
        keys.add(key)
        for nested in declaration.nested:
            add(owner, nested, qualified)

    for declaration in REQUEST_PUBLIC_DECLARATIONS:
        add(declaration.output, declaration)
    for entry in CATALOG:
        package = GENERATED_OUTPUT_BY_KEY[entry.model_output].package
        key = f"type:{package}.{entry.request}"
        if key in keys:
            raise ValueError(f"duplicate generated type declaration identity: {key}")
        keys.add(key)
    for _method, declaration in REQUEST_AUXILIARY_DECLARATIONS:
        add("request-models", declaration)
    for declaration in SERVER_DECLARATIONS:
        add("server-models", declaration)
    for declaration in SERVER_DISPATCH_DECLARATIONS:
        add("server-dispatch", declaration)
    return keys


def _generated_function_keys() -> set[str]:
    keys = {
        "fun:at.bernhardberger.tvheadend.htsp.messages.decodeHtspServerMessage(Map<String,Any?>)"
    }
    for entry in CATALOG:
        if entry.canonical_extension_projection is None:
            parameters = entry.parameters
        else:
            parameters = tuple(
                next(value for value in entry.parameters if value.name == name)
                for name in entry.canonical_extension_projection
            )
        package = GENERATED_OUTPUT_BY_KEY[entry.extension_output].package
        keys.add(f"fun:{package}.{extension_callable_signature(entry, parameters)}")
        for overload in SELECTOR_OVERLOADS:
            if overload.method != entry.method:
                continue
            if overload.parameter_projection:
                parameters = tuple(
                    next(value for value in entry.parameters if value.name == name)
                    for name in overload.parameter_projection
                )
            else:
                parameters = overload.parameters
            keys.add(
                f"fun:{package}.{extension_callable_signature(entry, parameters, overload.function_name)}"
            )
    return keys


PROHIBITED_GENERATED_KDOC_OVERSTATEMENTS: tuple[GeneratedKDocRecord, ...] = (
    (
        "server-models",
        "HtspChannelAddMessage",
        "Adds a complete channel snapshot: identity, number and name, icons, event references, services, and tag membership.",
    ),
    (
        "server-models",
        "HtspDvrEntryAddMessage",
        "Adds a complete DVR entry snapshot, including schedule, programme metadata, state, progress, errors, and recording files.",
    ),
    (
        "request-extensions",
        "HtspConnection.subscriptionChangeWeight(Long,Long?,Long,HtspConnectionGeneration?)",
        "Changes or omits the scheduling weight for one subscription through typed execution.",
    ),
    (
        "request-extensions",
        "HtspConnection.subscriptionLive(Long,Long,HtspConnectionGeneration?)",
        "Returns one timeshifted subscription to its live position through typed execution.",
    ),
)


def validate_generated_kdoc_catalog(
    type_records: tuple[GeneratedKDocRecord, ...] = GENERATED_TYPE_KDOC_RECORDS,
    function_records: tuple[GeneratedKDocRecord, ...] = GENERATED_FUNCTION_KDOC_RECORDS,
) -> None:
    try:
        type_docs = _record_map(type_records, "type")
        function_docs = _record_map(function_records, "fun")
    except KeyError as error:
        raise ValueError(f"generated KDoc record has unknown owner: {error.args[0]}") from error
    identity_errors: list[str] = []
    for kind, actual, expected in (
        ("type", set(type_docs), _generated_type_keys()),
        ("function", set(function_docs), _generated_function_keys()),
    ):
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        if missing:
            identity_errors.append(
                f"generated {kind} KDoc records are missing exact identities: {missing}"
            )
        if extra:
            identity_errors.append(
                f"generated {kind} KDoc records contain stale or wrong identities: {extra}"
            )
    if identity_errors:
        raise ValueError("; ".join(identity_errors))
    records = (*type_records, *function_records)
    prohibited = {
        (owner, signature): prose
        for owner, signature, prose in PROHIBITED_GENERATED_KDOC_OVERSTATEMENTS
    }
    normalized: dict[str, str] = {}
    forbidden_synthesis = (
        "Typed fields decoded from the `",
        "Typed reply model for `",
        "Typed `",
        "Executes `",
        "through its convenience overload with request parameters",
    )
    for owner, signature, prose in records:
        identity = f"{owner}:{signature}"
        if prohibited.get((owner, signature)) == prose:
            raise ValueError(
                "generated KDoc overstates command settlement or message completeness: "
                f"{identity}"
            )
        if not prose.strip():
            raise ValueError(f"generated KDoc record is blank: {identity}")
        if any(fragment in prose for fragment in forbidden_synthesis):
            raise ValueError(f"generated KDoc record uses synthesized fallback prose: {identity}")
        prose_key = " ".join(prose.split()).casefold()
        if prose_key in normalized:
            raise ValueError(
                f"generated KDoc prose is duplicated: {identity} duplicates {normalized[prose_key]}"
            )
        normalized[prose_key] = identity


def self_test_generated_kdoc_catalog() -> None:
    validate_generated_kdoc_catalog()

    def reject(label: str, expected: str, *, types=GENERATED_TYPE_KDOC_RECORDS, functions=GENERATED_FUNCTION_KDOC_RECORDS) -> None:
        try:
            validate_generated_kdoc_catalog(types, functions)
        except ValueError as error:
            if expected not in str(error):
                raise AssertionError(f"{label} had the wrong diagnostic: {error}") from error
        else:
            raise AssertionError(f"{label} generated KDoc mutation was accepted")

    reject("missing", "missing exact identities", types=GENERATED_TYPE_KDOC_RECORDS[:-1])
    reject(
        "extra",
        "stale or wrong identities",
        types=GENERATED_TYPE_KDOC_RECORDS + (("request-models", "StaleType", "A stale type record."),),
    )
    first_type = GENERATED_TYPE_KDOC_RECORDS[0]
    second_type = GENERATED_TYPE_KDOC_RECORDS[1]
    reject("blank", "blank", types=((first_type[0], first_type[1], " "), *GENERATED_TYPE_KDOC_RECORDS[1:]))
    reject("duplicate record", "duplicate generated type", types=GENERATED_TYPE_KDOC_RECORDS + (first_type,))
    reject(
        "wrong owner",
        "stale or wrong identities",
        types=(("request-models", first_type[1], first_type[2]), *GENERATED_TYPE_KDOC_RECORDS[1:]),
    )
    reject(
        "wrong signature",
        "stale or wrong identities",
        types=((first_type[0], first_type[1] + "Wrong", first_type[2]), *GENERATED_TYPE_KDOC_RECORDS[1:]),
    )
    reject(
        "duplicate prose",
        "prose is duplicated",
        types=(first_type, (second_type[0], second_type[1], first_type[2]), *GENERATED_TYPE_KDOC_RECORDS[2:]),
    )
    first_function = GENERATED_FUNCTION_KDOC_RECORDS[0]
    reject(
        "synthesized fallback",
        "synthesized fallback prose",
        functions=((first_function[0], first_function[1], "Executes `api` by constructing a request."), *GENERATED_FUNCTION_KDOC_RECORDS[1:]),
    )

    def with_prose(
        records: tuple[GeneratedKDocRecord, ...],
        owner: str,
        signature: str,
        prose: str,
    ) -> tuple[GeneratedKDocRecord, ...]:
        matches = [index for index, record in enumerate(records) if record[:2] == (owner, signature)]
        if len(matches) != 1:
            raise AssertionError(
                f"overstatement mutation target must be one exact record: {owner}:{signature}"
            )
        index = matches[0]
        return (*records[:index], (owner, signature, prose), *records[index + 1:])

    overstatement_diagnostic = "overstates command settlement or message completeness"
    reject(
        "channel-add completeness overstatement",
        overstatement_diagnostic,
        types=with_prose(
            GENERATED_TYPE_KDOC_RECORDS,
            "server-models",
            "HtspChannelAddMessage",
            "Adds a complete channel snapshot: identity, number and name, icons, event references, services, and tag membership.",
        ),
    )
    reject(
        "DVR-add completeness overstatement",
        overstatement_diagnostic,
        types=with_prose(
            GENERATED_TYPE_KDOC_RECORDS,
            "server-models",
            "HtspDvrEntryAddMessage",
            "Adds a complete DVR entry snapshot, including schedule, programme metadata, state, progress, errors, and recording files.",
        ),
    )
    reject(
        "subscription-weight settlement overstatement",
        overstatement_diagnostic,
        functions=with_prose(
            GENERATED_FUNCTION_KDOC_RECORDS,
            "request-extensions",
            "HtspConnection.subscriptionChangeWeight(Long,Long?,Long,HtspConnectionGeneration?)",
            "Changes or omits the scheduling weight for one subscription through typed execution.",
        ),
    )
    reject(
        "subscription-live settlement overstatement",
        overstatement_diagnostic,
        functions=with_prose(
            GENERATED_FUNCTION_KDOC_RECORDS,
            "request-extensions",
            "HtspConnection.subscriptionLive(Long,Long,HtspConnectionGeneration?)",
            "Returns one timeshifted subscription to its live position through typed execution.",
        ),
    )


def validate_generated_output_metadata(
    outputs: tuple[GeneratedOutput, ...] = GENERATED_OUTPUTS,
    entries: tuple[Entry, ...] = CATALOG,
    declarations: tuple[KotlinDeclaration, ...] = REQUEST_PUBLIC_DECLARATIONS,
) -> None:
    validate_generated_kdoc_catalog()
    expected_outputs = (
        GeneratedOutput(
            "request-models",
            "at.bernhardberger.tvheadend.htsp.requests",
            "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/requests/GeneratedHtspRequests.kt",
        ),
        GeneratedOutput(
            "request-extensions",
            "at.bernhardberger.tvheadend.htsp.requests",
            "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/requests/GeneratedHtspExtensions.kt",
        ),
        GeneratedOutput(
            "server-models",
            "at.bernhardberger.tvheadend.htsp.messages",
            "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/messages/GeneratedHtspServerMessages.kt",
        ),
        GeneratedOutput(
            "server-dispatch",
            "at.bernhardberger.tvheadend.htsp.messages",
            "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/messages/GeneratedHtspServerMessageDispatch.kt",
        ),
        GeneratedOutput(
            "jsonapi-models",
            "at.bernhardberger.tvheadend.htsp.jsonapi",
            "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/jsonapi/GeneratedHtspJsonApiModels.kt",
        ),
        GeneratedOutput(
            "jsonapi-extensions",
            "at.bernhardberger.tvheadend.htsp.jsonapi",
            "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/jsonapi/GeneratedHtspJsonApiExtensions.kt",
            "GeneratedHtspExtensionsKt",
            ("@HtspJsonApi",),
        ),
    )
    keys = tuple(output.key for output in outputs)
    if keys != tuple(output.key for output in expected_outputs):
        raise ValueError("generated output metadata must declare exactly the six reviewed owners")
    paths = tuple(output.relative_path for output in outputs)
    if len(set(keys)) != len(keys) or len(set(paths)) != len(paths):
        raise ValueError("generated output keys and paths must have unique owners")
    by_key = {output.key: output for output in outputs}
    for expected in expected_outputs:
        actual = by_key[expected.key]
        for label, expected_value, actual_value in (
            ("package", expected.package, actual.package),
            ("path", expected.relative_path, actual.relative_path),
            ("JVM name", expected.jvm_name, actual.jvm_name),
        ):
            if actual_value != expected_value:
                raise ValueError(
                    f"{expected.key}: generated output {label} mismatch; "
                    f"expected {expected_value}; found {actual_value}"
                )
        if actual.annotations != expected.annotations:
            expected_annotations = ", ".join(expected.annotations)
            actual_annotations = ", ".join(actual.annotations)
            raise ValueError(
                f"{expected.key}: generated output annotations mismatch; "
                f"expected [{expected_annotations}]; found [{actual_annotations}]"
            )
    for entry in entries:
        expected_model = "jsonapi-models" if entry.request == "ApiRequest" else "request-models"
        expected_extension = "jsonapi-extensions" if entry.request == "ApiRequest" else "request-extensions"
        if entry.model_output not in by_key or entry.extension_output not in by_key:
            raise ValueError(f"{entry.method}: generated output metadata references an unknown owner")
        if entry.model_output != expected_model or entry.extension_output != expected_extension:
            raise ValueError(f"{entry.method}: generated model/extension owner is misplaced")
    for declaration in declarations:
        expected_output = "jsonapi-models" if declaration.name == "ApiResponse" else "request-models"
        if declaration.output not in by_key:
            raise ValueError(f"{declaration.name}: generated declaration references an unknown owner")
        if declaration.output != expected_output:
            raise ValueError(f"{declaration.name}: generated declaration owner is misplaced")


def self_test_generated_output_metadata() -> None:
    self_test_generated_kdoc_catalog()
    validate_generated_output_metadata()

    def expect_rejection(label: str, expected_error: str | None = None, **changes: object) -> None:
        try:
            validate_generated_output_metadata(**changes)
        except ValueError as error:
            if expected_error is not None and str(error) != expected_error:
                raise AssertionError(
                    f"generated output metadata mutation reached the wrong validation: {label}: {error}"
                ) from error
            return
        raise AssertionError(f"generated output metadata mutation was accepted: {label}")

    expect_rejection("missing output", outputs=GENERATED_OUTPUTS[:-1])
    expect_rejection(
        "unexpected seventh output",
        outputs=(*GENERATED_OUTPUTS, GeneratedOutput("extra", "extra", "extra.kt")),
    )
    expect_rejection(
        "duplicate output ownership",
        outputs=(*GENERATED_OUTPUTS[:-1], GeneratedOutput(
            "jsonapi-extensions",
            GENERATED_OUTPUTS[0].package,
            GENERATED_OUTPUTS[0].relative_path,
        )),
    )
    expect_rejection(
        "wrong package",
        "request-models: generated output package mismatch; expected "
        "at.bernhardberger.tvheadend.htsp.requests; found "
        "at.bernhardberger.tvheadend.htsp.wire",
        outputs=(replace(
            GENERATED_OUTPUTS[0],
            package="at.bernhardberger.tvheadend.htsp.wire",
        ), *GENERATED_OUTPUTS[1:]),
    )
    expect_rejection(
        "wrong path",
        "request-models: generated output path mismatch; expected "
        "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/requests/"
        "GeneratedHtspRequests.kt; found "
        "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/wire/"
        "GeneratedHtspRequests.kt",
        outputs=(replace(
            GENERATED_OUTPUTS[0],
            relative_path=GENERATED_OUTPUTS[0].relative_path.replace("/requests/", "/wire/"),
        ), *GENERATED_OUTPUTS[1:]),
    )
    expect_rejection(
        "wrong JVM name",
        "jsonapi-extensions: generated output JVM name mismatch; expected "
        "GeneratedHtspExtensionsKt; found WrongGeneratedHtspExtensionsKt",
        outputs=(*GENERATED_OUTPUTS[:-1], replace(
            GENERATED_OUTPUTS[-1],
            jvm_name="WrongGeneratedHtspExtensionsKt",
        )),
    )
    expect_rejection(
        "wrong annotations",
        "jsonapi-extensions: generated output annotations mismatch; expected "
        "[@HtspJsonApi]; found []",
        outputs=(*GENERATED_OUTPUTS[:-1], replace(
            GENERATED_OUTPUTS[-1],
            annotations=(),
        )),
    )
    expect_rejection(
        "unknown entry output",
        entries=(replace(CATALOG[0], model_output="unknown"), *CATALOG[1:]),
    )
    api_index = next(index for index, entry in enumerate(CATALOG) if entry.request == "ApiRequest")
    expect_rejection(
        "misplaced JSON entry",
        entries=(
            *CATALOG[:api_index],
            replace(CATALOG[api_index], model_output="request-models", extension_output="request-extensions"),
            *CATALOG[api_index + 1:],
        ),
    )
    api_response_index = next(
        index for index, declaration in enumerate(REQUEST_PUBLIC_DECLARATIONS)
        if declaration.name == "ApiResponse"
    )
    expect_rejection(
        "misplaced JSON declaration",
        declarations=(
            *REQUEST_PUBLIC_DECLARATIONS[:api_response_index],
            replace(REQUEST_PUBLIC_DECLARATIONS[api_response_index], output="request-models"),
            *REQUEST_PUBLIC_DECLARATIONS[api_response_index + 1:],
        ),
    )
