#!/usr/bin/env python3
"""Validate htsp_spec.json and generate HTSP_METHOD_MATRIX.md deterministically."""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SPEC_PATH = SCRIPT_DIR / "htsp_spec.json"
MATRIX_PATH = SCRIPT_DIR / "HTSP_METHOD_MATRIX.md"
UPSTREAM_PATH = SCRIPT_DIR / "upstream.json"

EXPECTED_CLIENT_METHODS: tuple[str, ...] = (
    "hello",
    "authenticate",
    "api",
    "getDiskSpace",
    "getSysTime",
    "enableAsyncMetadata",
    "getChannel",
    "getEvent",
    "getEvents",
    "epgQuery",
    "getEpgObject",
    "getDvrConfigs",
    "addDvrEntry",
    "updateDvrEntry",
    "stopDvrEntry",
    "cancelDvrEntry",
    "deleteDvrEntry",
    "addAutorecEntry",
    "updateAutorecEntry",
    "deleteAutorecEntry",
    "addTimerecEntry",
    "updateTimerecEntry",
    "deleteTimerecEntry",
    "getDvrCutpoints",
    "getTicket",
    "subscribe",
    "unsubscribe",
    "subscriptionChangeWeight",
    "subscriptionSeek",
    "subscriptionSkip",
    "subscriptionSpeed",
    "subscriptionLive",
    "subscriptionFilterStream",
    "getProfiles",
    "fileOpen",
    "fileRead",
    "fileClose",
    "fileStat",
    "fileSeek",
)

EXPECTED_TYPED_CLIENT_REQUESTS: tuple[tuple[str, str, int | None], ...] = (
    ("getProfiles", "ACCESS_HTSP_STREAMING", 16),
    ("getDiskSpace", "ACCESS_HTSP_STREAMING", 3),
    ("getSysTime", "ACCESS_HTSP_STREAMING", 3),
    ("enableAsyncMetadata", "ACCESS_HTSP_STREAMING", None),
    ("getChannel", "ACCESS_HTSP_STREAMING", 14),
    ("getEvent", "ACCESS_HTSP_STREAMING", None),
    ("getEvents", "ACCESS_HTSP_STREAMING", 4),
    ("epgQuery", "ACCESS_HTSP_STREAMING", 4),
    ("getEpgObject", "ACCESS_HTSP_STREAMING", None),
    ("getDvrConfigs", "ACCESS_HTSP_RECORDER", 16),
    ("addDvrEntry", "ACCESS_HTSP_RECORDER", 4),
    ("updateDvrEntry", "ACCESS_HTSP_RECORDER", 5),
    ("stopDvrEntry", "ACCESS_HTSP_RECORDER", None),
    ("cancelDvrEntry", "ACCESS_HTSP_RECORDER", 5),
    ("deleteDvrEntry", "ACCESS_HTSP_RECORDER", 4),
    ("addAutorecEntry", "ACCESS_HTSP_RECORDER", 13),
    ("updateAutorecEntry", "ACCESS_HTSP_RECORDER", 25),
    ("deleteAutorecEntry", "ACCESS_HTSP_RECORDER", 13),
    ("addTimerecEntry", "ACCESS_HTSP_RECORDER", 18),
    ("updateTimerecEntry", "ACCESS_HTSP_RECORDER", 25),
    ("deleteTimerecEntry", "ACCESS_HTSP_RECORDER", 18),
    ("getDvrCutpoints", "ACCESS_HTSP_RECORDER", 12),
    ("getTicket", "ACCESS_HTSP_STREAMING", 5),
    ("subscribe", "ACCESS_HTSP_STREAMING", None),
    ("unsubscribe", "ACCESS_HTSP_STREAMING", None),
    ("subscriptionChangeWeight", "ACCESS_HTSP_STREAMING", 5),
    ("subscriptionSeek", "ACCESS_HTSP_STREAMING", 9),
    ("subscriptionSkip", "ACCESS_HTSP_STREAMING", 9),
    ("subscriptionSpeed", "ACCESS_HTSP_STREAMING", 9),
    ("subscriptionLive", "ACCESS_HTSP_STREAMING", 9),
    ("subscriptionFilterStream", "ACCESS_HTSP_STREAMING", 12),
    ("fileOpen", "ACCESS_HTSP_RECORDER", 8),
    ("fileRead", "ACCESS_HTSP_RECORDER", 8),
    ("fileClose", "ACCESS_HTSP_RECORDER", 8),
    ("fileStat", "ACCESS_HTSP_RECORDER", 8),
    ("fileSeek", "ACCESS_HTSP_RECORDER", 8),
    ("api", "ACCESS_ANONYMOUS", 24),
    ("hello", "ACCESS_ANONYMOUS", None),
    ("authenticate", "ACCESS_ANONYMOUS", None),
)

EXPECTED_SERVER_MESSAGES: tuple[str, ...] = (
    "channelAdd",
    "channelUpdate",
    "channelDelete",
    "tagAdd",
    "tagUpdate",
    "tagDelete",
    "dvrEntryAdd",
    "dvrEntryUpdate",
    "dvrEntryDelete",
    "autorecEntryAdd",
    "autorecEntryUpdate",
    "autorecEntryDelete",
    "timerecEntryAdd",
    "timerecEntryUpdate",
    "timerecEntryDelete",
    "eventAdd",
    "eventUpdate",
    "eventDelete",
    "initialSyncCompleted",
    "muxpkt",
    "queueStatus",
    "subscriptionStart",
    "subscriptionStop",
    "subscriptionGrace",
    "subscriptionStatus",
    "signalStatus",
    "descrambleInfo",
    "subscriptionSpeed",
    "timeshiftStatus",
    "subscriptionSkip",
)

EXPECTED_TYPED_SERVER_MESSAGES: tuple[tuple[str, str, int | None], ...] = (
    ("channelAdd", "HtspChannelAddMessage", None),
    ("channelUpdate", "HtspChannelUpdateMessage", None),
    ("channelDelete", "HtspChannelDeleteMessage", None),
    ("tagAdd", "HtspTagAddMessage", None),
    ("tagUpdate", "HtspTagUpdateMessage", None),
    ("tagDelete", "HtspTagDeleteMessage", None),
    ("dvrEntryAdd", "HtspDvrEntryAddMessage", 4),
    ("dvrEntryUpdate", "HtspDvrEntryUpdateMessage", 4),
    ("dvrEntryDelete", "HtspDvrEntryDeleteMessage", 4),
    ("autorecEntryAdd", "HtspAutorecEntryAddMessage", 13),
    ("autorecEntryUpdate", "HtspAutorecEntryUpdateMessage", 13),
    ("autorecEntryDelete", "HtspAutorecEntryDeleteMessage", 13),
    ("timerecEntryAdd", "HtspTimerecEntryAddMessage", 18),
    ("timerecEntryUpdate", "HtspTimerecEntryUpdateMessage", 18),
    ("timerecEntryDelete", "HtspTimerecEntryDeleteMessage", 18),
    ("eventAdd", "HtspEventAddMessage", 6),
    ("eventUpdate", "HtspEventUpdateMessage", 6),
    ("eventDelete", "HtspEventDeleteMessage", 6),
    ("initialSyncCompleted", "HtspInitialSyncCompletedMessage", 2),
    ("muxpkt", "HtspMuxPacketMessage", None),
    ("queueStatus", "HtspQueueStatusMessage", None),
    ("subscriptionStart", "HtspSubscriptionStartMessage", None),
    ("subscriptionStop", "HtspSubscriptionStopMessage", None),
    ("subscriptionGrace", "HtspSubscriptionGraceMessage", 13),
    ("subscriptionStatus", "HtspSubscriptionStatusMessage", None),
    ("signalStatus", "HtspSignalStatusMessage", None),
    ("subscriptionSpeed", "HtspSubscriptionSpeedMessage", 9),
    ("timeshiftStatus", "HtspTimeshiftStatusMessage", 9),
    ("subscriptionSkip", "HtspSubscriptionSkipMessage", 9),
)

EXPECTED_UNHANDLED_MESSAGES: tuple[str, ...] = ()

AUTOREC_SERVER_MESSAGE_CONTRACT: tuple[tuple[str, str, str], ...] = (
    ("id", "str", "required"),
    ("enabled", "u32", "required"),
    ("maxDuration", "u32", "required"),
    ("minDuration", "u32", "required"),
    ("retention", "u32", "required"),
    ("removal", "u32", "required"),
    ("daysOfWeek", "u32", "required"),
    ("approxTime", "s32", "required"),
    ("start", "s32", "required"),
    ("startWindow", "s32", "required"),
    ("priority", "u32", "required"),
    ("startExtra", "s64", "required"),
    ("stopExtra", "s64", "required"),
    ("dupDetect", "u32", "required"),
    ("maxCount", "u32", "required"),
    ("broadcastType", "u32", "required"),
    ("comment", "str", "required"),
    ("title", "str", "optional"),
    ("fulltext", "u32", "optional"),
    ("mergetext", "u32", "optional"),
    ("name", "str", "required"),
    ("directory", "str", "optional"),
    ("owner", "str", "required"),
    ("creator", "str", "required"),
    ("channel", "u32", "optional"),
    ("serieslinkUri", "str", "optional"),
    ("configId", "str", "optional"),
)

EXPECTED_FIELD_SHAPE_REFS: tuple[tuple[str, str, str, str, str], ...] = (
    ("shape", "service", "fields", "hbbtv", "hbbtvDynamic"),
    ("clientMethod", "getChannel", "replyFields", "services", "service"),
    ("clientMethod", "getChannel", "replyFields", "tags", "u32"),
    ("clientMethod", "getEvent", "replyFields", "credits", "eventCreditsDynamic"),
    ("clientMethod", "getEvent", "replyFields", "category", "str"),
    ("clientMethod", "getEvent", "replyFields", "keyword", "str"),
    ("clientMethod", "getEvents", "replyFields", "events", "event"),
    ("clientMethod", "epgQuery", "replyFields", "eventIds", "u32"),
    ("clientMethod", "epgQuery", "replyFields", "events", "event"),
    ("clientMethod", "getEpgObject", "replyFields", "tit", "epgLanguageStrings"),
    ("clientMethod", "getEpgObject", "replyFields", "sti", "epgLanguageStrings"),
    ("clientMethod", "getEpgObject", "replyFields", "sum", "epgLanguageStrings"),
    ("clientMethod", "getEpgObject", "replyFields", "des", "epgLanguageStrings"),
    ("clientMethod", "getEpgObject", "replyFields", "epn", "epgEpisodeNumber"),
    ("clientMethod", "getEpgObject", "replyFields", "genre", "u32"),
    ("clientMethod", "getEpgObject", "replyFields", "cred", "epgCreditsDynamic"),
    ("clientMethod", "getEpgObject", "replyFields", "cat", "str"),
    ("clientMethod", "getEpgObject", "replyFields", "key", "str"),
    ("clientMethod", "getDvrConfigs", "replyFields", "dvrconfigs", "dvrConfig"),
    ("clientMethod", "getDvrCutpoints", "replyFields", "cutpoints", "cutpoint"),
    ("clientMethod", "subscriptionFilterStream", "requestFields", "enable", "u32"),
    ("clientMethod", "subscriptionFilterStream", "requestFields", "disable", "u32"),
    ("clientMethod", "getProfiles", "replyFields", "profiles", "profile"),
    ("serverMessage", "channelAdd", "fields", "services", "service"),
    ("serverMessage", "channelAdd", "fields", "tags", "u32"),
    ("serverMessage", "channelUpdate", "fields", "services", "service"),
    ("serverMessage", "channelUpdate", "fields", "tags", "u32"),
    ("serverMessage", "tagAdd", "fields", "members", "u32"),
    ("serverMessage", "tagUpdate", "fields", "members", "u32"),
    ("serverMessage", "dvrEntryAdd", "fields", "files", "recordingFile"),
    ("serverMessage", "dvrEntryUpdate", "fields", "files", "recordingFile"),
    ("serverMessage", "eventAdd", "fields", "credits", "eventCreditsDynamic"),
    ("serverMessage", "eventAdd", "fields", "category", "str"),
    ("serverMessage", "eventAdd", "fields", "keyword", "str"),
    ("serverMessage", "eventUpdate", "fields", "credits", "eventCreditsDynamic"),
    ("serverMessage", "eventUpdate", "fields", "category", "str"),
    ("serverMessage", "eventUpdate", "fields", "keyword", "str"),
    ("serverMessage", "subscriptionStart", "fields", "streams", "stream"),
    ("serverMessage", "subscriptionStart", "fields", "sourceinfo", "sourceInfo"),
)

EXPECTED_REPOSITORY = "https://github.com/tvheadend/tvheadend"
EXPECTED_REVISION = "27295c5a48f2c575678bb224014cb9a26a773083"
EXPECTED_PROTO_VERSION = 44
EXPECTED_FILES = {
    "src/htsp_server.c": {"gitBlobSha1": "2837efd3b41ae0ba7f82de2853d8a1d4a1ea88e1", "bytes": 134765},
    "src/htsp_server.h": {"gitBlobSha1": "3b6470d51ab45e1d9bc9bacc710b0e1f6f49b1b0", "bytes": 2050},
    "lib/py/tvh/htsp.py": {
        "gitBlobSha1": "bab234beafc924a608e830d32cc4596152df0863", "bytes": 2963,
        "notes": [
            "Limited demo client pinned at HTSP_PROTO_VERSION 33.",
            "Covers only hello, authenticate, and enableAsyncMetadata.",
            "Never treat as completeness authority.",
        ],
    },
    "src/epg.c": {"gitBlobSha1": "7d95b27466e070a6c76b37ef3a945cd9e980d683", "bytes": 88770},
    "src/epg.h": {"gitBlobSha1": "cce9c09d25612f1abc892c7a0071dca9481030e9", "bytes": 22374},
    "src/lang_str.c": {"gitBlobSha1": "c0cfbe016938472778ef6aec0e6e0b829a0abd31", "bytes": 8481},
    "src/string_list.c": {"gitBlobSha1": "cfe0fa03415abf649c94737d599561556b5e0a76", "bytes": 4655},
    "src/api.c": {"gitBlobSha1": "d86fbda01312b97c451242ee24c01a384744141b", "bytes": 4440},
    "src/api/api_idnode.c": {"gitBlobSha1": "1f0f9b697feb30e16ce9b61b4693eb2090b5ee49", "bytes": 18834},
    "src/htsmsg.h": {"gitBlobSha1": "82787d4cc4d18436653ab0c19fc2e49ee930a013", "bytes": 14265},
    "src/htsmsg_binary.c": {"gitBlobSha1": "48a1bf985ed554df473adb3a9251b479dfcdaf26", "bytes": 7750},
}
EXPECTED_DOCS_URLS = {
    "communication": "https://docs.tvheadend.org/documentation/development/htsp/communication",
    "clientToServer": "https://docs.tvheadend.org/documentation/development/htsp/client-to-server-rpc-methods",
    "serverToClient": "https://docs.tvheadend.org/documentation/development/htsp/server-to-client-methods",
    "protocolChanges": "https://docs.tvheadend.org/documentation/development/htsp/protocol-changes",
}
EXPECTED_SCAN_ROOTS = [
    "sdk/htsp-protocol/src/main",
    "sdk/htsp/src/main",
    "sdk/playback-media3/src/main",
]
EXPECTED_API_ACCEPTED_VOCABULARY = {
    "sdkAdmitted": ["map", "list", "str", "s64", "bin", "bool", "uuid"],
    "upstreamExcluded": ["dbl"],
    "roundTripEvidence": {
        "source": "src/htsmsg_binary.c",
        "decode": ["map", "list", "str", "s64", "bin", "bool", "uuid"],
        "serialize": ["map", "list", "str", "s64", "bin", "bool", "uuid"],
    },
    "uuidWidthBytes": 16,
}
SYSTEM_TIME_LIMITATION_ID = "getSysTime-time-type-source-doc-mismatch"
CHANNEL_SERVICE_LIMITATION_ID = "channel-service-fields-underdocumented"
CHANNEL_ID_STR_EVIDENCE = (
    "pinned current htsp_build_channel unconditionally emits channelIdStr; "
    "v41 is historical compatibility evidence"
)
CHANNEL_ID_STR_CONDITION = (
    "required for negotiated protocol version 41 or newer; may be absent from "
    "older server implementations supporting versions 14 through 40"
)
CHANNEL_SERVICE_DOCS_URL = (
    "https://docs.tvheadend.org/documentation/development/htsp/"
    "server-to-client-methods"
)
CHANNEL_SERVICE_SUMMARY = (
    "The pinned htsp_build_channel source always emits service name, type, and "
    "u32 content; conditionally emits caid, caname, dynamic hbbtv, and "
    "providername. The official Server-to-Client methods channelAdd section is "
    "the governing field list and omits current-source content and hbbtv; hbbtv "
    "therefore remains explicitly opaque."
)
EVENT_LIMITATION_ID = "event-fields-source-docs-mismatch"
EVENT_DOCS_URL = (
    "https://docs.tvheadend.org/documentation/development/htsp/"
    "server-to-client-methods"
)
EVENT_LIMITATION_SUMMARY = (
    "The pinned current htsp_build_event source emits start and stop through "
    "htsmsg_add_s64 and isNew through htsmsg_add_u32, while the official "
    "Server-to-Client eventAdd documentation describes start and stop as u64 "
    "and isNew as str, omits several current-source fields, and lists historical "
    "ID fields not emitted by the current builder. This records incomplete/stale "
    "official documentation and does not reconcile it into the pinned "
    "current-source contract."
)
TIMEREC_LIMITATION_ID = "timerec-fields-source-docs-mismatch"
TIMEREC_LIMITATION_SUMMARY = (
    "The official Server-to-Client timerecEntryAdd section omits the string id "
    "that pinned htsp_build_timerecentry emits and that the documented update/delete "
    "messages use, contains stale autorec and enabled-field wording, and describes "
    "start/stop as u32 while the pinned builder emits s32. The pinned builder also "
    "emits u32 removal, which that page does not document. These gaps are retained "
    "as source/docs evidence and do not imply outbound time-rule RPC support or a "
    "public removal-field contract."
)
RECORDING_RULE_METHOD_MINIMUMS = {
    "addAutorecEntry": 13,
    "updateAutorecEntry": 25,
    "deleteAutorecEntry": 13,
    "addTimerecEntry": 18,
    "updateTimerecEntry": 25,
    "deleteTimerecEntry": 18,
}
RECORDING_RULE_COMMON_FIELDS = (
    ("enabled", "u32", 19),
    ("retention", "u32", None),
    ("removal", "u32", None),
    ("priority", "u32", None),
    ("name", "str", 18),
    ("comment", "str", 42),
    ("directory", "str", 19),
    ("title", "str", None),
    ("configName", "str", None),
    ("daysOfWeek", "u32", None),
)
RECORDING_RULE_AUTOREC_FIELDS = (
    ("minduration", "u32", None),
    ("maxduration", "u32", None),
    ("fulltext", "u32", 20),
    ("mergetext", "u32", None),
    ("dupDetect", "u32", 20),
    ("maxCount", "u32", None),
    ("broadcastType", "u32", 39),
    ("startExtra", "s64", None),
    ("stopExtra", "s64", None),
    ("serieslinkUri", "str", None),
)


def recording_rule_request_contract(name: str) -> tuple[tuple[str, str, str, int | None], ...]:
    if name.startswith("delete"):
        return (("id", "str", "required", None),)
    add = name.startswith("add")
    fields: list[tuple[str, str, str, int | None]] = [
        ("title" if add else "id", "str", "required", None),
        ("channelId", "s64", "optional", None),
    ]
    if "Autorec" in name:
        fields.extend((field, wire, "optional", minimum) for field, wire, minimum in RECORDING_RULE_AUTOREC_FIELDS)
        if add:
            fields.append(("approxTime", "s32", "optional", None))
        fields.extend((
            ("start", "s32", "optional", 18),
            ("startWindow", "s32", "optional", 18),
        ))
    else:
        fields.extend((
            ("start", "u32", "optional", None),
            ("stop", "u32", "optional", None),
        ))
    fields.extend(
        (
            field,
            wire,
            "optional",
            None if "Timerec" in name and field == "name" else minimum,
        )
        for field, wire, minimum in RECORDING_RULE_COMMON_FIELDS
        if not (add and field == "title")
    )
    return tuple(fields)


def recording_rule_notes(name: str) -> list[str]:
    notes = [
        "Dispatch requires ACCESS_HTSP_RECORDER; handler errors use the global error reply rather than the method-specific success shape.",
    ]
    if name.startswith("add"):
        notes.extend((
            "Optional shared fields receive pinned source defaults when omitted on add.",
            "Creation failure is a method-specific success=0 plus fixed error string; success is exactly success=1 plus string id.",
        ))
    elif name.startswith("update"):
        notes.extend((
            "Optional shared fields are left unchanged when omitted on update, except for the pinned channel behavior described on channelId.",
            "Successful mutation is exactly the standard success=1 acknowledgement.",
        ))
    else:
        notes.append(
            "The required string id selects one rule for deletion; success is exactly the standard success=1 acknowledgement."
        )
    if "Autorec" in name and not name.startswith("delete"):
        notes.append(
            "Autorec uses lowercase minduration/maxduration source keys; approxTime is add-only and start/startWindow are signed s32."
        )
    elif "Timerec" in name and not name.startswith("delete"):
        notes.append("Timerec start/stop request values are read as unsigned u32.")
    return notes


EVENT_FIELD_CONTRACT: tuple[tuple[str, str, str, str | None], ...] = (
    ("eventId", "u32", "required", None),
    ("channelId", "u32", "conditional", None),
    ("start", "s64", "required", None),
    ("stop", "s64", "required", None),
    ("title", "str", "conditional", None),
    ("subtitle", "str", "conditional", None),
    ("summary", "str", "conditional", None),
    ("description", "str", "conditional", None),
    ("credits", "msg", "conditional", "eventCreditsDynamic"),
    ("category", "list", "conditional", "str"),
    ("keyword", "list", "conditional", "str"),
    ("serieslinkUri", "str", "conditional", None),
    ("episodeUri", "str", "conditional", None),
    ("contentType", "u32", "conditional", None),
    ("ageRating", "u32", "conditional", None),
    ("ratingLabel", "str", "conditional", None),
    ("ratingIcon", "str", "conditional", None),
    ("ratingAuthority", "str", "conditional", None),
    ("ratingCountry", "str", "conditional", None),
    ("starRating", "u32", "conditional", None),
    ("copyrightYear", "u32", "conditional", None),
    ("firstAired", "s64", "conditional", None),
    ("isNew", "u32", "conditional", None),
    ("seasonNumber", "u32", "conditional", None),
    ("seasonCount", "u32", "conditional", None),
    ("episodeNumber", "u32", "conditional", None),
    ("episodeCount", "u32", "conditional", None),
    ("partNumber", "u32", "conditional", None),
    ("partCount", "u32", "conditional", None),
    ("episodeOnscreen", "str", "conditional", None),
    ("image", "str", "conditional", None),
    ("dvrId", "u32", "conditional", None),
    ("nextEventId", "u32", "conditional", None),
)
EVENT_UPDATE_FIELD_CONTRACT: tuple[tuple[str, str, str, str | None], ...] = tuple(
    (name, wire_type, "required" if name == "eventId" else "optional", shape_ref)
    for name, wire_type, _presence, shape_ref in EVENT_FIELD_CONTRACT
)
EVENT_UPDATE_NOTE = (
    "Pinned current eventUpdate call sites send the shared htsp_build_event "
    "snapshot; partial-update compatibility permits omission of every non-key "
    "field and consumers merge by eventId."
)
EVENT_UPDATE_SHAPE_EVIDENCE = (
    "pinned current eventUpdate call sites use the shared full htsp_build_event "
    "snapshot; compatibility remains partial by eventId"
)
GET_EVENTS_MAX_TIME_LIMITATION_ID = "getEvents-maxTime-type-source-doc-mismatch"
GET_EVENTS_FILTER_LIMITATION_ID = "getEvents-filter-interaction-underdocumented"
GET_EVENTS_DOCS_URL = (
    "https://docs.tvheadend.org/documentation/development/htsp/"
    "client-to-server-rpc-methods"
)
GET_EVENTS_MAX_TIME_LIMITATION_SUMMARY = (
    "The pinned htsp_method_getEvents source reads maxTime through "
    "htsmsg_get_s64_or_default into signed int64_t with zero as the "
    "no-time-bound sentinel, while the official Client-to-Server RPC methods "
    "page documents maxTime as optional u64 since version 6. This records a "
    "source/docs evidence mismatch; it does not coerce the pinned "
    "current-source s64 contract to u64."
)
GET_EVENTS_FILTER_LIMITATION_SUMMARY = (
    "Pinned htsp_method_getEvents source gives eventId selection precedence "
    "when both eventId and channelId are present, applies a nonzero maxTime "
    "start cutoff, treats positive numFollowing as an inclusive maximum count, "
    "resets that count per channel in all-channel mode, and filters inaccessible "
    "channels. The official Client-to-Server RPC methods page lists the optional "
    "version-6 filters but does not specify those interactions."
)
GET_EVENTS_REQUEST_CONTRACT = (
    ("channelId", "u32", "optional", 6),
    ("eventId", "u32", "optional", 6),
    ("language", "str", "optional", 6),
    ("numFollowing", "u32", "optional", 6),
    ("maxTime", "s64", "optional", 6),
)
EPG_QUERY_REQUEST_CONTRACT = (
    ("query", "str", "required", None),
    ("channelId", "u32", "optional", None),
    ("tagId", "u32", "optional", None),
    ("contentType", "u32", "optional", None),
    ("language", "str", "optional", 6),
    ("fulltext", "bool", "optional", None),
    ("mergetext", "bool", "optional", None),
    ("full", "u32", "optional", None),
    ("minduration", "u32", "optional", 13),
    ("maxduration", "u32", "optional", 13),
)
EPG_QUERY_REPLY_CONTRACT = (
    (
        "eventIds", "list", "alternative", "u32",
        "selected when full is absent or zero; omitted for zero matches",
    ),
    (
        "events", "list", "alternative", "event",
        "selected when full is non-zero; omitted for zero matches",
    ),
)
EPG_QUERY_REPLY_ALTERNATIVES = [
    "eventIds when full is absent/zero and matches exist",
    "events when full is non-zero and matches exist",
    "empty map when the selected query has zero matches",
]
EPG_QUERY_NOTES = [
    "The required query string is accepted unchanged, including an empty string.",
    "Optional fulltext and mergetext default false; optional full defaults zero and any nonzero value selects full events.",
    "channelId and tagId are independent optional selectors; contentType retains the pinned pre-v6 conversion.",
    "language has field minimum v6 and minduration/maxduration have field minimum v13; no other field raises the method minimum v4.",
    "The selected result field is omitted when the query has zero matches.",
]
GET_EPG_OBJECT_REQUEST_CONTRACT = (
    ("id", "u32", "required"),
    ("type", "u32", "optional"),
)
GET_EPG_OBJECT_REPLY_CONTRACT = (
    ("id", "u32", "required", None),
    ("tp", "u32", "required", None),
    ("gr", "str", "conditional", None),
    ("up", "s64", "required", None),
    ("start", "s64", "required", None),
    ("stop", "s64", "required", None),
    ("ch", "str", "conditional", None),
    ("eid", "u32", "conditional", None),
    ("xeid", "str", "conditional", None),
    ("is_wd", "u32", "conditional", None),
    ("is_hd", "u32", "conditional", None),
    ("is_bw", "u32", "conditional", None),
    ("lines", "u32", "conditional", None),
    ("aspect", "u32", "conditional", None),
    ("is_de", "u32", "conditional", None),
    ("is_st", "u32", "conditional", None),
    ("is_ad", "u32", "conditional", None),
    ("is_n", "u32", "conditional", None),
    ("is_r", "u32", "conditional", None),
    ("star", "u32", "conditional", None),
    ("age", "u32", "conditional", None),
    ("ratlab", "str", "conditional", None),
    ("img", "str", "conditional", None),
    ("tit", "msg", "conditional", "epgLanguageStrings"),
    ("sti", "msg", "conditional", "epgLanguageStrings"),
    ("sum", "msg", "conditional", "epgLanguageStrings"),
    ("des", "msg", "conditional", "epgLanguageStrings"),
    ("epn", "msg", "conditional", "epgEpisodeNumber"),
    ("genre", "list", "conditional", "u32"),
    ("cyear", "u32", "conditional", None),
    ("fair", "s64", "conditional", None),
    ("cred", "msg", "conditional", "epgCreditsDynamic"),
    ("cat", "list", "conditional", "str"),
    ("key", "list", "conditional", "str"),
    ("slink", "str", "conditional", None),
    ("elink", "str", "conditional", None),
)
GET_EPG_OBJECT_EPISODE_CONTRACT = (
    ("enum", "u32", "optional"),
    ("ecnt", "u32", "optional"),
    ("snum", "u32", "optional"),
    ("scnt", "u32", "optional"),
    ("pnum", "u32", "optional"),
    ("pcnt", "u32", "optional"),
    ("text", "str", "optional"),
)
GET_EPG_OBJECT_NOTES = [
    "Official RPC documentation leaves the reply literally TODO; the exact seven-file source pin is normative.",
    "EPG_UNDEF=0 and EPG_BROADCAST=1 are the complete pinned object-type vocabulary; only broadcast has a serializer.",
    "Wire cred is an unconstrained copied message and remains an explicitly opaque shape deliberately omitted from the public response model.",
    "Pinned time_t updated/start/stop/first_aired members are serialized as signed s64; the SDK exposes unchanged Unix-second values under its EPG time convention.",
]
GET_TICKET_LIMITATION_ID = "getTicket-selector-precedence-underdocumented"
GET_TICKET_DOCS_URL = (
    "https://docs.tvheadend.org/documentation/development/htsp/"
    "client-to-server-rpc-methods"
)
GET_TICKET_LIMITATION_SUMMARY = (
    "The official Client-to-Server RPC methods page marks channelId and "
    "dvrId optional and the path/ticket reply fields required, but does "
    "not state that at least one selector must decode or that channelId "
    "wins when both decode. Pinned current source establishes that "
    "either/or and channel-first behavior."
)
GET_TICKET_REQUEST_CONTRACT = (
    (
        "channelId", "u32", "alternative",
        "selected first; when both selectors decode, channelId wins",
        "bounded htsp_method_getTicket tries strict u32 channelId first",
    ),
    (
        "dvrId", "u32", "alternative",
        "selected only when channelId does not decode",
        "bounded htsp_method_getTicket tries strict u32 dvrId only as fallback",
    ),
)
GET_TICKET_REPLY_CONTRACT = (
    (
        "path", "str", "required",
        "bounded successful getTicket output adds exact string path first",
    ),
    (
        "ticket", "str", "required",
        "bounded successful getTicket output adds exact string ticket second",
    ),
)
GET_TICKET_REQUEST_SHAPE = {
    "kind": "alternative",
    "completeness": "complete",
    "evidence": "bounded handler requires one decodable u32 selector and gives channelId precedence",
    "alternatives": [
        "channelId only",
        "dvrId only",
        "channelId and dvrId; channelId wins",
    ],
    "invalidAlternatives": [
        "neither channelId nor dvrId; at least one decodable u32 selector is required",
    ],
}
GET_TICKET_REPLY_SHAPE = {
    "kind": "fields",
    "completeness": "complete",
    "evidence": "bounded successful handler emits exactly ordered required string path and ticket",
}
GET_TICKET_NOTES = [
    "Dispatch requires ACCESS_HTSP_STREAMING and the method is annotated as available since HTSP version 5.",
    "Pinned source accepts channelId only, dvrId only, or both selectors with channelId precedence; the neither-present state takes the invalid-arguments path. The stricter typed SDK models exactly one selector.",
    "Channel selection verifies channel lookup and access before creating /stream/channelid/%d; DVR selection verifies entry lookup and its channel access before creating /dvrfile/%d.",
    "Successful source output creates one map and adds required string path then required string ticket; both values are untrusted and diagnostic-sensitive, and ticket is credential-bearing.",
]
FILE_STAT_LIMITATION_ID = "fileStat-reply-source-doc-mismatch"
FILE_STAT_DOCS_URL = GET_TICKET_DOCS_URL
BOUNDED_FILE_OPERATIONS_LIMITATION_ID = "bounded-file-operations-source-doc-mismatch"
BOUNDED_FILE_OPERATIONS_LIMITATION_SUMMARY = (
    "Official docs use unsigned fileOpen/fileRead/fileSeek size, mtime, and offset types, "
    "mark fileSeek whence required despite documenting a SEEK_SET default, and do not "
    "capture the pinned source's coupled fileOpen metadata or required successful seek "
    "offset or fileClose's recording-backed DVR defaults. Pinned source instead uses "
    "signed-s64 values, optional read offset and seek whence, always emits binary read "
    "data including an empty payload, and increments recording playcount on id-only close "
    "unconditionally before v27 and by the omitted HTSP_DVR_PLAYCOUNT_INCR default at v27+."
)
BOUNDED_FILE_OPERATIONS_AUTHORITY = (
    "src/htsp_server.c htsp_file_open, htsp_method_file_open, "
    "htsp_method_file_read, htsp_method_file_close, and htsp_method_file_seek"
)
FILE_CLOSE_REQUEST_CONTRACT = (
    (
        "id", "u32", "required", None,
        "bounded htsp_file_find performs a default-zero current-connection handle lookup",
    ),
    (
        "playposition", "u32", "optional", None,
        "pinned close updates DVR play position only for a recording-backed handle at protocol v27 or newer when supplied; omission never updates position",
    ),
    (
        "playcount", "u32", "optional", None,
        "pinned close increments DVR playcount unconditionally before v27 and, at v27 or newer, defaults omission to HTSP_DVR_PLAYCOUNT_INCR and increments when equal",
    ),
)
FILE_CLOSE_REQUEST_SHAPE = {
    "kind": "fields",
    "completeness": "complete",
    "evidence": (
        "bounded helper/handler accept current-connection id plus optional DVR-only "
        "playposition and playcount inputs inside the recording-backed DVR-entry guard"
    ),
}
FILE_CLOSE_REPLY_SHAPE = {
    "kind": "knownEmpty",
    "completeness": "complete",
    "evidence": "bounded handler destroys the matched handle and returns exactly an empty map",
}
FILE_CLOSE_NOTES = [
    "The id uses the default-zero lookup helper and is meaningful only in the connection that opened it.",
    "The ordinary typed fileClose request is the exact raw v8 id-only surface and exposes no playcount or playposition controls, but an id-only close of a recording-backed handle increments playcount unconditionally before v27 and, at v27 or newer, omission defaults to HTSP_DVR_PLAYCOUNT_INCR and also increments.",
    "Omitted playposition never updates position; non-recording and image handles have no associated DVR entry to mutate. Success destroys the matched handle and returns an empty map; the existing opted-in recording close, client lifecycle, and SDK-owned progress policy remain separate.",
]
FILE_STAT_LIMITATION_SUMMARY = (
    "Official docs describe independently optional u64 size and mtime fields, while "
    "pinned source emits signed-s64 size then mtime together only when fstat succeeds "
    "and otherwise returns a successful empty map. Official docs also omit the mtime "
    "unit and epoch."
)
FILE_STAT_REQUEST_CONTRACT = (
    (
        "id", "u32", "required",
        "bounded htsp_file_find reads id with zero default and searches only this connection's handles",
    ),
)
FILE_STAT_REPLY_CONTRACT = (
    (
        "size", "s64", "conditional",
        "present together with mtime when fstat(fd, &st) returns zero",
        "bounded handler emits st.st_size first only when fstat(fd, &st) succeeds",
    ),
    (
        "mtime", "s64", "conditional",
        "present together with size when fstat(fd, &st) returns zero",
        "bounded handler emits unchanged st.st_mtime second only when fstat(fd, &st) succeeds",
    ),
)
FILE_STAT_REQUEST_SHAPE = {
    "kind": "fields",
    "completeness": "complete",
    "evidence": (
        "bounded helper accepts exactly one default-zero u32 id and searches only "
        "the current connection file list"
    ),
}
FILE_STAT_REPLY_SHAPE = {
    "kind": "alternative",
    "completeness": "complete",
    "evidence": (
        "bounded handler creates a fresh map before fstat, emits ordered signed-s64 "
        "size and mtime together on success, and returns the empty map on failure"
    ),
    "alternatives": [
        "signed-s64 size followed by unchanged signed-s64 mtime when fstat succeeds",
        "successful empty map when fstat fails",
    ],
}
FILE_STAT_NOTES = [
    "The id is a u32 handle lookup key; zero is not rejected before the owned-handle lookup.",
    "A handle is meaningful only in the HTSP connection that created it.",
    "size and mtime are coupled: both signed-s64 fields are emitted in that order, or neither is emitted.",
    "mtime is the unchanged POSIX st_mtime value serialized by pinned source; official docs omit its unit and epoch.",
    "A successful empty map is the pinned fstat-failure behavior.",
]
GET_DVR_CUTPOINTS_LIMITATION_ID = (
    "getDvrCutpoints-coordinate-order-semantics-underdocumented"
)
GET_DVR_CUTPOINTS_DOCS_URL = (
    "https://docs.tvheadend.org/documentation/development/htsp/"
    "client-to-server-rpc-methods"
)
GET_DVR_CUTPOINTS_LIMITATION_SUMMARY = (
    "The official Client-to-Server RPC methods page does not define the "
    "millisecond coordinate origin or chronological ordering, overlap, "
    "or uniqueness semantics for getDvrCutpoints. Pinned source serializes "
    "dc_start_ms and dc_end_ms and traverses the TAILQ in its observed order; "
    "the SDK preserves those values and order without interpreting them."
)
STOP_DVR_ENTRY_LIMITATION_ID = "stopDvrEntry-missing-from-client-docs"
STOP_DVR_ENTRY_DOCS_URL = GET_DVR_CUTPOINTS_DOCS_URL
STOP_DVR_ENTRY_LIMITATION_SUMMARY = (
    "stopDvrEntry is present in the pinned htsp_methods[] dispatch table "
    "but absent from the Client-to-Server RPC methods documentation page."
)
STOP_DVR_ENTRY_REQUEST_EVIDENCE = (
    "bounded htsp_findDvrEntry requires exactly u32 id before write-mode access and lookup"
)
STOP_DVR_ENTRY_REPLY_EVIDENCE = "bounded htsp_success emits exactly add-u32 success=1"
STOP_DVR_ENTRY_REQUEST_SHAPE_EVIDENCE = (
    "bounded shared helper and stop handler accept exactly required id"
)
STOP_DVR_ENTRY_REPLY_SHAPE_EVIDENCE = (
    "bounded standard-success helper emits exactly success=1"
)
STOP_DVR_ENTRY_NOTES = [
    "The handler calls the shared DVR-entry helper in write mode and returns its bounded error result.",
    "On helper success it calls exactly dvr_entry_stop; cancel and delete remain distinct operations.",
    "The standard success reply carries success=1 only; later asynchronous DVR metadata is authoritative for lifecycle state.",
]
SUBSCRIPTION_CHANGE_WEIGHT_LIMITATION_ID = (
    "subscriptionChangeWeight-default-ack-order-underdocumented"
)
SUBSCRIPTION_CHANGE_WEIGHT_DOCS_URL = GET_DVR_CUTPOINTS_DOCS_URL
SUBSCRIPTION_CHANGE_WEIGHT_LIMITATION_SUMMARY = (
    "The official Client-to-Server RPC methods page leaves the optional "
    "subscriptionChangeWeight weight field's omitted default and the "
    "acknowledgement/application ordering unspecified. Pinned current source "
    "defaults omitted weight to zero and queues an empty reply before invoking "
    "subscription_change_weight."
)
SUBSCRIPTION_CHANGE_WEIGHT_REQUEST_CONTRACT = (
    (
        "subscriptionId", "u32", "required", None,
        "bounded htsp_method_change_weight requires exactly decoded u32 subscriptionId",
    ),
    (
        "weight", "u32", "optional",
        "when omitted, pinned current source supplies wire value 0 before subscription_change_weight",
        "bounded htsp_method_change_weight reads optional u32 weight with default zero",
    ),
)
SUBSCRIPTION_CHANGE_WEIGHT_REQUEST_SHAPE = {
    "kind": "fields",
    "completeness": "complete",
    "evidence": (
        "bounded htsp_method_change_weight accepts exactly required subscriptionId "
        "and optional default-zero weight"
    ),
}
SUBSCRIPTION_CHANGE_WEIGHT_REPLY_SHAPE = {
    "kind": "knownEmpty",
    "completeness": "complete",
    "evidence": (
        "bounded htsp_method_change_weight queues exactly one empty reply map "
        "before subscription_change_weight"
    ),
}
SUBSCRIPTION_CHANGE_WEIGHT_NOTES = [
    "Dispatch requires ACCESS_HTSP_STREAMING and the method is annotated as available since HTSP version 5.",
    "Pinned current source defaults an omitted weight to zero before looking up the exact subscription ID.",
    "Pinned current source queues the empty acknowledgement before exactly one subscription_change_weight call; acknowledgement does not prove settled or applied weight state.",
]
SUBSCRIPTION_SKIP_LIMITATION_ID = "subscriptionSkip-seek-coordinate-source-doc-mismatch"
SUBSCRIPTION_SKIP_DOCS_URL = GET_DVR_CUTPOINTS_DOCS_URL
SUBSCRIPTION_SKIP_LIMITATION_SUMMARY = (
    "The official Client-to-Server RPC methods page calls subscriptionSeek a "
    "synonym of subscriptionSkip and lists time/size as optional u64 values "
    "without stating the pinned either/or rule. Pinned current source maps both "
    "dispatch names to htsp_method_skip, requires exact u32 subscriptionId, "
    "reads optional u32 absolute with default 0, and accepts signed-s64 time "
    "first otherwise signed-s64 size, erroring when neither coordinate exists."
)
SUBSCRIPTION_SKIP_REQUEST_CONTRACT = (
    (
        "subscriptionId", "u32", "required", None,
        "bounded htsp_method_skip requires exactly decoded u32 subscriptionId",
    ),
    (
        "time", "s64", "alternative",
        "time or size selects the seek coordinate",
        "bounded htsp_method_skip reads signed-s64 time before size",
    ),
    (
        "size", "s64", "alternative",
        "time or size selects the seek coordinate",
        "bounded htsp_method_skip reads signed-s64 size only when time is absent",
    ),
    (
        "absolute", "u32", "optional",
        "when omitted, pinned current source supplies wire value 0 before absolute/relative type selection",
        "bounded htsp_method_skip reads optional u32 absolute with default zero",
    ),
)
SUBSCRIPTION_SKIP_REQUEST_SHAPE = {
    "kind": "fields",
    "completeness": "complete",
    "evidence": (
        "bounded htsp_method_skip accepts required subscriptionId, optional "
        "default-zero absolute, and signed time-or-size coordinates"
    ),
}
SUBSCRIPTION_SKIP_REPLY_SHAPE = {
    "kind": "knownEmpty",
    "completeness": "complete",
    "evidence": (
        "bounded htsp_method_skip queues exactly one empty reply map after "
        "subscription_set_skip"
    ),
}
SUBSCRIPTION_SKIP_NOTES = [
    "Dispatch synonym of subscriptionSeek; both call htsp_method_skip with streaming access and annotated minimum v9.",
    "Pinned current source requires exact u32 subscriptionId, defaults omitted absolute to zero, and accepts signed-s64 time first otherwise signed-s64 size.",
    "Pinned current source calls subscription_set_skip before queuing the empty RPC reply; the separate asynchronous subscriptionSkip message remains authoritative, with no on-wire ordering or settled-skip guarantee.",
]
SUBSCRIPTION_SEEK_NOTES = [
    "Dispatch synonym of subscriptionSkip; both call htsp_method_skip with streaming access and annotated minimum v9.",
    "Pinned current source requires exact u32 subscriptionId, defaults omitted absolute to zero, and accepts signed-s64 time first otherwise signed-s64 size.",
    "Pinned current source calls subscription_set_skip before queuing the empty RPC reply; the separate asynchronous subscriptionSkip message remains authoritative, with no on-wire ordering or settled-skip guarantee.",
]
SUBSCRIPTION_LIVE_LIMITATION_ID = "subscriptionLive-rpc-async-order-underdocumented"
SUBSCRIPTION_LIVE_DOCS_URL = GET_DVR_CUTPOINTS_DOCS_URL
SUBSCRIPTION_LIVE_LIMITATION_SUMMARY = (
    "The official Client-to-Server RPC methods page does not clearly "
    "distinguish the empty subscriptionLive RPC acknowledgement from the "
    "separate asynchronous subscriptionSkip outcome or define their delivery "
    "ordering or settled-live semantics. Pinned current source calls "
    "subscription_set_skip before queuing the empty RPC reply; that source "
    "topology is not promoted to an on-wire ordering or settled-state guarantee."
)
SUBSCRIPTION_LIVE_REQUEST_CONTRACT = (
    (
        "subscriptionId", "u32", "required", None,
        "bounded htsp_method_live requires exactly decoded u32 subscriptionId",
    ),
)
SUBSCRIPTION_LIVE_REQUEST_SHAPE = {
    "kind": "fields",
    "completeness": "complete",
    "evidence": "bounded htsp_method_live accepts exactly required subscriptionId",
}
SUBSCRIPTION_LIVE_REPLY_SHAPE = {
    "kind": "knownEmpty",
    "completeness": "complete",
    "evidence": (
        "bounded htsp_method_live queues exactly one empty reply map after "
        "subscription_set_skip"
    ),
}
SUBSCRIPTION_LIVE_NOTES = [
    "Dispatch requires ACCESS_HTSP_STREAMING and the method is annotated as available since HTSP version 9.",
    "Pinned current source zero-initializes one streaming_skip_t, sets only SMT_SKIP_LIVE, and calls subscription_set_skip on the exact matched subscription.",
    "Pinned current source calls subscription_set_skip before queuing the empty RPC reply; the separate asynchronous subscriptionSkip message remains authoritative, with no on-wire ordering or settled-live guarantee.",
]
SUBSCRIPTION_FILTER_STREAM_LIMITATION_ID = (
    "subscriptionFilterStream-range-overlap-underdocumented"
)
SUBSCRIPTION_FILTER_STREAM_DOCS_URL = GET_DVR_CUTPOINTS_DOCS_URL
SUBSCRIPTION_FILTER_STREAM_LIMITATION_SUMMARY = (
    "The official Client-to-Server RPC methods page omits the pinned "
    "subscriptionFilterStream 512-index effective range, disable-wins "
    "precedence for indexes present in both lists, and the no-change "
    "behavior of omitted or empty enable/disable lists."
)
SUBSCRIPTION_FILTER_STREAM_AUTHORITY = (
    "src/htsp_server.c htsp_method_filter_stream / "
    "htsp_enable_stream / htsp_disable_stream"
)
SUBSCRIPTION_FILTER_STREAM_REQUEST_CONTRACT = (
    (
        "subscriptionId", "u32", "required", None, None,
        "bounded htsp_method_filter_stream requires exactly decoded u32 subscriptionId",
    ),
    (
        "enable", "list", "optional",
        "omitted or empty changes no enabled-stream bitmap entries", "u32",
        "bounded handler iterates only HMF_S64 members through htsp_enable_stream + exact-pin container annotation",
    ),
    (
        "disable", "list", "optional",
        "omitted or empty changes no disabled-stream bitmap entries", "u32",
        "bounded handler iterates only HMF_S64 members through htsp_disable_stream after enable + exact-pin container annotation",
    ),
)
SUBSCRIPTION_FILTER_STREAM_REQUEST_SHAPE = {
    "kind": "fields",
    "completeness": "complete",
    "evidence": (
        "bounded htsp_method_filter_stream accepts exactly required "
        "subscriptionId and optional enable/disable u32 lists"
    ),
}
SUBSCRIPTION_FILTER_STREAM_REPLY_SHAPE = {
    "kind": "knownEmpty",
    "completeness": "complete",
    "evidence": (
        "bounded htsp_method_filter_stream returns exactly one empty map "
        "after optional enable then disable processing"
    ),
}
SUBSCRIPTION_FILTER_STREAM_NOTES = [
    "Dispatch requires ACCESS_HTSP_STREAMING and the method is annotated as available since HTSP version 12.",
    "Pinned current source accepts only HMF_S64 list members, processes enable before disable, and mutates the filtered-stream bitmap only for unsigned indexes below NUM_FILTERED_STREAMS=(64*8).",
    "For this pin, indexes 0..511 can affect the bitmap, 512 and larger are ignored, overlap ends disabled, and omitted or empty lists make no change for that side; these are current-source facts, not a support or settlement promise.",
]
WIRE_TYPES = {"u32", "s32", "s64", "str", "bin", "msg", "list", "bool", "dbl", "uuid", "unknown"}
PRESENCE_VALUES = {"required", "optional", "conditional", "alternative", "unknown"}
COMPLETENESS_VALUES = {"complete", "partial", "opaque"}
SHAPE_KINDS = {"fields", "knownEmpty", "dynamic", "alternative", "unknown"}

CONFIDENCE_VALUES = {
    "mechanical",
    "mechanical+annotated",
    "annotated",
    "approximate",
    "unknown",
}


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_exact_upstream(data: Any, label: str) -> list[str]:
    errors: list[str] = []
    if not isinstance(data, dict):
        return [f"{label} must be an object"]
    expected_keys = {"schemaVersion", "repository", "revision", "htspProtoVersion", "files", "docsUrls"}
    if set(data) != expected_keys:
        errors.append(f"{label} keys do not match immutable schema")
    if data.get("schemaVersion") != 1:
        errors.append(f"{label}.schemaVersion must be 1")
    if data.get("repository") != EXPECTED_REPOSITORY:
        errors.append(f"{label}.repository does not match immutable pin")
    if data.get("revision") != EXPECTED_REVISION:
        errors.append(f"{label}.revision does not match immutable pin")
    if data.get("htspProtoVersion") != EXPECTED_PROTO_VERSION:
        errors.append(f"{label}.htspProtoVersion does not match immutable pin")
    files = data.get("files")
    if not isinstance(files, dict) or list(files) != list(EXPECTED_FILES):
        errors.append(f"{label}.files must be the exact ordered eleven-file key set")
    else:
        for relative, expected in EXPECTED_FILES.items():
            meta = files.get(relative)
            if not isinstance(meta, dict) or set(meta) != set(expected):
                errors.append(f"{label}.{relative} metadata schema mismatch")
                continue
            digest = meta.get("gitBlobSha1")
            size = meta.get("bytes")
            if not isinstance(digest, str) or len(digest) != 40 or any(c not in "0123456789abcdef" for c in digest):
                errors.append(f"{label}.{relative} malformed Git blob SHA-1")
            if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
                errors.append(f"{label}.{relative} malformed byte size")
            if meta != expected:
                errors.append(f"{label}.{relative} does not match immutable pin")
    if data.get("docsUrls") != EXPECTED_DOCS_URLS:
        errors.append(f"{label}.docsUrls does not match immutable official URL set")
    return errors


def validate_spec(spec: dict[str, Any], upstream: dict[str, Any] | None = None) -> list[str]:
    errors: list[str] = []

    expected_top_keys = {
        "schemaVersion", "artifactKind", "disclaimer", "upstream", "globalRpc",
        "asyncEnvelope", "shapes", "clientMethods", "serverMessages", "pythonDemo",
        "coverage", "docLimitations", "headerSymbols",
    }
    if set(spec) != expected_top_keys:
        errors.append("spec top-level keys do not match schema")

    if spec.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")
    if spec.get("artifactKind") != "htsp-protocol-evidence":
        errors.append("artifactKind must be htsp-protocol-evidence")

    upstream_spec = spec.get("upstream") or {}
    errors.extend(validate_exact_upstream({"schemaVersion": 1, **upstream_spec}, "spec.upstream"))
    if upstream is not None:
        errors.extend(validate_exact_upstream(upstream, "upstream.json"))
        if upstream_spec != {k: v for k, v in upstream.items() if k != "schemaVersion"}:
            errors.append("spec upstream does not exactly match upstream.json")

    if upstream_spec.get("htspProtoVersion") != 44:
        errors.append("htspProtoVersion must be 44")

    methods = spec.get("clientMethods")
    messages = spec.get("serverMessages")
    shapes = spec.get("shapes")
    if not isinstance(shapes, dict) or not shapes:
        errors.append("shapes must be a non-empty object")
        shapes = {}
    else:
        for shape_name, shape in shapes.items():
            if not isinstance(shape_name, str) or not shape_name:
                errors.append("shape names must be non-empty strings")
                continue
            if not isinstance(shape, dict):
                errors.append(f"shape {shape_name} must be an object")
                continue
            if shape.get("kind") not in {"object", "reference", "scalar", "stringMap"}:
                errors.append(f"shape {shape_name}: invalid kind")
            if shape.get("completeness") not in COMPLETENESS_VALUES:
                errors.append(f"shape {shape_name}: invalid completeness")
            if not shape.get("evidence"):
                errors.append(f"shape {shape_name}: missing evidence")
            if shape.get("kind") == "object":
                nested_fields = shape.get("fields")
                if shape.get("completeness") == "opaque" and nested_fields is None:
                    pass
                elif not isinstance(nested_fields, list) or not nested_fields:
                    errors.append(f"shape {shape_name}: object fields must be non-empty")
                else:
                    for field in nested_fields:
                        errors.extend(_validate_field(shape_name, "nested", field, "nested", shapes))
                    if [field.get("order") for field in nested_fields] != list(range(1, len(nested_fields) + 1)):
                        errors.append(f"shape {shape_name}: field order metadata mismatch")
            elif shape.get("kind") == "reference" and not str(shape.get("target", "")).startswith("serverMessage:"):
                errors.append(f"shape {shape_name}: invalid reference target")
            elif shape.get("kind") == "scalar" and shape.get("wireType") not in WIRE_TYPES:
                errors.append(f"shape {shape_name}: invalid scalar wireType")
            elif shape.get("kind") == "stringMap" and (
                shape.get("keyWireType") != "str"
                or shape.get("valueWireType") != "str"
            ):
                errors.append(f"shape {shape_name}: stringMap must have strict str keys and values")
    if not isinstance(methods, list):
        errors.append("clientMethods must be a list")
        methods = []
    if not isinstance(messages, list):
        errors.append("serverMessages must be a list")
        messages = []

    method_names = [m.get("name") for m in methods if isinstance(m, dict)]
    message_names = [m.get("name") for m in messages if isinstance(m, dict)]

    if method_names != list(EXPECTED_CLIENT_METHODS):
        errors.append(
            "clientMethods inventory/order mismatch: "
            f"got {method_names!r}"
        )
    if message_names != list(EXPECTED_SERVER_MESSAGES):
        errors.append(
            "serverMessages inventory/order mismatch: "
            f"got {message_names!r}"
        )

    if len(method_names) != len(set(method_names)):
        dupes = [n for n, c in Counter(method_names).items() if c > 1]
        errors.append(f"duplicate client methods: {dupes}")
    if len(message_names) != len(set(message_names)):
        dupes = [n for n, c in Counter(message_names).items() if c > 1]
        errors.append(f"duplicate server messages: {dupes}")

    for method in methods:
        if not isinstance(method, dict):
            errors.append("client method entry is not an object")
            continue
        name = method.get("name")
        required_method_keys = {
            "name", "handler", "accessMask", "minVersion", "minVersionConfidence",
            "requestFields", "replyFields", "requestShape", "replyShape", "sdk",
        }
        allowed_method_keys = required_method_keys | {"docStatus", "notes", "acceptedVocabulary"}
        if not required_method_keys <= set(method) or not set(method) <= allowed_method_keys:
            errors.append(f"{name}: method keys do not match schema")
        errors.extend(_validate_version_metadata(name, method))
        if not method.get("accessMask"):
            errors.append(f"{name}: missing accessMask provenance")
        if not method.get("handler"):
            errors.append(f"{name}: missing handler")
        for section in ("requestFields", "replyFields"):
            fields = method.get(section) or []
            if not isinstance(fields, list):
                errors.append(f"{name}: {section} must be a list")
                continue
            field_names = []
            for field in fields:
                expected_direction = "request" if section == "requestFields" else "reply"
                ferr = _validate_field(name, section, field, expected_direction, shapes)
                errors.extend(ferr)
                if isinstance(field, dict) and "name" in field:
                    field_names.append(field["name"])
            if len(field_names) != len(set(field_names)):
                errors.append(f"{name}: duplicate fields in {section}")
            if [field.get("order") for field in fields if isinstance(field, dict)] != list(range(1, len(fields) + 1)):
                errors.append(f"{name}: {section} field order metadata mismatch")
        errors.extend(_validate_shape_descriptor(name, "requestShape", method.get("requestShape"), method.get("requestFields"), shapes))
        errors.extend(_validate_shape_descriptor(name, "replyShape", method.get("replyShape"), method.get("replyFields"), shapes))

    for message in messages:
        if not isinstance(message, dict):
            errors.append("server message entry is not an object")
            continue
        name = message.get("name")
        required_message_keys = {"name", "minVersion", "minVersionConfidence", "fields", "messageShape", "sdk"}
        allowed_message_keys = required_message_keys | {"docStatus", "notes"}
        if not required_message_keys <= set(message) or not set(message) <= allowed_message_keys:
            errors.append(f"{name}: message keys do not match schema")
        errors.extend(_validate_version_metadata(name, message))
        fields = message.get("fields") or []
        if not isinstance(fields, list):
            errors.append(f"{name}: fields must be a list")
            continue
        field_names = []
        for field in fields:
            errors.extend(_validate_field(name, "fields", field, "message", shapes))
            if isinstance(field, dict) and "name" in field:
                field_names.append(field["name"])
        if len(field_names) != len(set(field_names)):
            errors.append(f"{name}: duplicate fields")
        if [field.get("order") for field in fields if isinstance(field, dict)] != list(range(1, len(fields) + 1)):
            errors.append(f"{name}: field order metadata mismatch")
        errors.extend(_validate_shape_descriptor(name, "messageShape", message.get("messageShape"), fields, shapes))

    method_map = {m.get("name"): m for m in methods if isinstance(m, dict)}
    message_map = {m.get("name"): m for m in messages if isinstance(m, dict)}
    hello = method_map.get("hello", {})
    authenticate = method_map.get("authenticate", {})
    hello_request = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("minVersion"))
        for field in hello.get("requestFields", [])
    ]
    if hello_request != [
        ("htspversion", "u32", "required", None),
        ("clientname", "str", "required", None),
    ]:
        errors.append("hello exact required request field evidence drift")
    hello_reply = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("condition"), field.get("minVersion"))
        for field in hello.get("replyFields", [])
    ]
    if hello_reply != [
        ("htspversion", "u32", "required", None, None),
        ("servername", "str", "required", None, None),
        ("serverversion", "str", "required", None, None),
        ("challenge", "bin", "required", "exactly 32 bytes", None),
        ("webroot", "str", "conditional", "present when tvheadend_webroot is non-null", None),
        ("language", "str", "conditional", "present when config_get_language returns non-null", None),
        ("servercapability", "msg", "required", None, None),
        ("api_version", "u32", "required", None, None),
    ]:
        errors.append("hello exact unconditional/conditional reply topology drift")
    if hello.get("handler") != "htsp_method_hello":
        errors.append("hello handler dispatch drift")
    if hello.get("accessMask") != "ACCESS_ANONYMOUS":
        errors.append("hello access dispatch drift")
    if hello.get("minVersion") is not None:
        errors.append("hello minimum protocol version must remain unknown")
    if (
        hello.get("requestShape") != {
            "kind": "fields",
            "completeness": "complete",
            "evidence": "bounded htsp_method_hello reads exactly required htspversion and clientname and never clientversion",
        }
        or hello.get("replyShape") != {
            "kind": "fields",
            "completeness": "complete",
            "evidence": "bounded htsp_method_hello complete unconditional/conditional output topology and exact 32-byte challenge",
        }
    ):
        errors.append("hello exact complete shape evidence drift")
    authenticate_reply = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("condition"), field.get("minVersion"))
        for field in authenticate.get("replyFields", [])
    ]
    expected_authenticate_reply = [
        ("noaccess", "u32", "alternative", "present only when granted rights have no HTSP_PRIV_MASK bit", None),
        *[
            (
                name,
                wire_type,
                "conditional",
                "present with granted rights only when negotiated protocol version is greater than 25",
                26,
            )
            for name, wire_type in (
                ("admin", "u32"), ("streaming", "u32"), ("dvr", "u32"),
                ("faileddvr", "u32"), ("anonymous", "u32"), ("limitall", "u32"),
                ("limitdvr", "u32"), ("limitstreaming", "u32"),
                ("uilevel", "u32"), ("uilanguage", "str"),
            )
        ],
    ]
    if authenticate_reply != expected_authenticate_reply:
        errors.append("authenticate exact noaccess/>25/<=25 reply field evidence drift")
    if authenticate.get("handler") != "htsp_method_authenticate":
        errors.append("authenticate handler dispatch drift")
    if authenticate.get("accessMask") != "ACCESS_ANONYMOUS":
        errors.append("authenticate access dispatch drift")
    if authenticate.get("minVersion") is not None:
        errors.append("authenticate minimum protocol version must remain unknown")
    if (
        authenticate.get("requestFields") != []
        or authenticate.get("requestShape") != {
            "kind": "knownEmpty",
            "completeness": "complete",
            "evidence": "bounded htsp_method_authenticate reads no method-specific request fields",
        }
        or authenticate.get("replyShape") != {
            "kind": "alternative",
            "completeness": "complete",
            "evidence": "bounded htsp_method_authenticate noaccess, rights>25 complete fields, and rights<=25 empty topology",
            "alternatives": [
                "no privilege mask: noaccess=1 only",
                "granted rights and negotiated version >25: complete access observation fields",
                "granted rights and negotiated version <=25: empty method payload",
            ],
        }
    ):
        errors.append("authenticate exact no-fields or alternative shape evidence drift")
    api = method_map.get("api", {})
    if (
        api.get("handler") != "htsp_method_api"
        or api.get("accessMask") != "ACCESS_ANONYMOUS"
        or api.get("minVersion") != 24
        or api.get("minVersionConfidence") != "annotated"
    ):
        errors.append("api must preserve exact handler, anonymous access, and annotated minimum v24")
    if [
        (field.get("name"), field.get("type"), field.get("presence"))
        for field in api.get("requestFields", [])
    ] != [("path", "str", "required"), ("args", "msg", "optional")]:
        errors.append("api request must preserve exact required path and optional args map")
    if [
        (field.get("name"), field.get("type"), field.get("presence"))
        for field in api.get("replyFields", [])
    ] != [("response", "msg", "alternative"), ("noaccess", "u32", "alternative")]:
        errors.append("api reply fields must preserve response and noaccess alternatives")
    if api.get("requestShape") != {
        "kind": "fields",
        "completeness": "complete",
        "evidence": "bounded htsp_method_api accepts exact required path and optional args map only",
    }:
        errors.append("api request shape must preserve its complete finite topology")
    if api.get("replyShape") != {
        "kind": "alternative",
        "completeness": "complete",
        "evidence": "bounded htsp_method_api complete response-map/list, noaccess, error-envelope, and no-payload topology",
        "alternatives": [
            "success with response map",
            "success with response list",
            "EPERM or EACCES: noaccess=1",
            "ENOENT or ENOSYS, or successful callback with null response: no method payload",
            "other error: shared global error envelope",
        ],
    }:
        errors.append("api reply shape must preserve complete finite alternatives")
    if api.get("notes") != [
        "Dispatch requires ACCESS_ANONYMOUS and the method is annotated as available since HTSP version 24.",
        "Unknown endpoint and successful no-response callbacks are intentionally indistinguishable as a successful no-payload HTSP reply.",
        "The response is an opaque closed HTSP value tree; endpoint schemas and compatibility are not negotiated by HTSP.",
    ]:
        errors.append("api notes must preserve no-payload ambiguity and permanent non-modeling boundary")
    if api.get("acceptedVocabulary") != EXPECTED_API_ACCEPTED_VOCABULARY:
        errors.append("api accepted vocabulary evidence drift")
    expected_server_field_versions = {
        ("channelAdd", "channelIdStr"): 41,
        ("channelUpdate", "channelIdStr"): 41,
        ("channelAdd", "channelNumberMinor"): 13,
        ("channelUpdate", "channelNumberMinor"): 13,
        ("channelAdd", "services"): 5,
        ("channelUpdate", "services"): 5,
        ("tagAdd", "tagIdStr"): 41,
        ("tagUpdate", "tagIdStr"): 41,
        ("tagAdd", "tagIndex"): 18,
        ("tagUpdate", "tagIndex"): 18,
        ("dvrEntryAdd", "idStr"): 41,
        ("dvrEntryUpdate", "idStr"): 41,
        ("dvrEntryAdd", "startExtra"): 13,
        ("dvrEntryUpdate", "startExtra"): 13,
        ("dvrEntryAdd", "ratingAuthority"): 41,
        ("dvrEntryUpdate", "ratingAuthority"): 41,
        ("eventAdd", "ratingAuthority"): 41,
        ("eventUpdate", "ratingAuthority"): 41,
        ("subscriptionStart", "meta"): 17,
        ("subscriptionStop", "subscriptionError"): 20,
        ("subscriptionStatus", "subscriptionError"): 20,
        ("signalStatus", "feAbsoluteSNR"): 44,
        ("signalStatus", "feAbsoluteSignal"): 44,
    }
    for (message_name, field_name), minimum in expected_server_field_versions.items():
        field = next(
            (
                candidate
                for candidate in message_map.get(message_name, {}).get("fields", [])
                if candidate.get("name") == field_name
            ),
            None,
        )
        if field is None or field.get("minVersion") != minimum:
            errors.append(
                f"{message_name}.{field_name} must preserve minimum-version evidence {minimum}"
            )
    errors.extend(_validate_reference_shapes(shapes, message_map))
    errors.extend(_validate_field_shape_refs(spec))
    for method_name in ("fileRead", "fileClose", "fileStat", "fileSeek"):
        names = {f.get("name") for f in method_map.get(method_name, {}).get("requestFields", [])}
        if "id" not in names:
            errors.append(f"{method_name}: helper-derived id request field is required")
    file_read_fields = {f.get("name"): f for f in method_map.get("fileRead", {}).get("replyFields", [])}
    if file_read_fields.get("data", {}).get("type") != "bin":
        errors.append("fileRead.data must be bin")
    for message_name, identifier in (
        ("channelDelete", "channelId"), ("tagDelete", "tagId"),
        ("dvrEntryDelete", "id"), ("autorecEntryDelete", "id"),
        ("timerecEntryDelete", "id"), ("eventDelete", "eventId"),
    ):
        names = {f.get("name") for f in message_map.get(message_name, {}).get("fields", [])}
        if names != {identifier}:
            errors.append(f"{message_name}: delete shape must contain only {identifier}")
    autorec_add = message_map.get("autorecEntryAdd", {})
    autorec_add_contract = [
        (field.get("name"), field.get("type"), field.get("presence"))
        for field in autorec_add.get("fields", [])
    ]
    if autorec_add_contract != list(AUTOREC_SERVER_MESSAGE_CONTRACT):
        errors.append("autorecEntryAdd must preserve exact pinned field order, types, and requiredness")
    if (
        autorec_add.get("messageShape", {}).get("kind") != "fields"
        or autorec_add.get("messageShape", {}).get("completeness") != "complete"
    ):
        errors.append("autorecEntryAdd must preserve the complete bounded builder shape")
    autorec_update = message_map.get("autorecEntryUpdate", {})
    autorec_update_contract = [
        (field.get("name"), field.get("type"), field.get("presence"))
        for field in autorec_update.get("fields", [])
    ]
    expected_autorec_update = [
        (name, wire_type, "required" if name == "id" else "optional")
        for name, wire_type, _presence in AUTOREC_SERVER_MESSAGE_CONTRACT
    ]
    if autorec_update_contract != expected_autorec_update:
        errors.append("autorecEntryUpdate must require exact string id and keep all other fields optional")
    if (
        autorec_update.get("messageShape", {}).get("kind") != "fields"
        or autorec_update.get("messageShape", {}).get("completeness") != "partial"
    ):
        errors.append("autorecEntryUpdate must preserve the partial-update shape")
    autorec_delete = message_map.get("autorecEntryDelete", {})
    autorec_delete_contract = [
        (field.get("name"), field.get("type"), field.get("presence"))
        for field in autorec_delete.get("fields", [])
    ]
    if autorec_delete_contract != [("id", "str", "required")]:
        errors.append("autorecEntryDelete must preserve exactly required string id")
    if (
        autorec_delete.get("messageShape", {}).get("kind") != "fields"
        or autorec_delete.get("messageShape", {}).get("completeness") != "complete"
    ):
        errors.append("autorecEntryDelete must preserve the complete delete shape")
    mux = {f.get("name"): f for f in message_map.get("muxpkt", {}).get("fields", [])}
    if mux.get("payload", {}).get("type") != "bin" or ({"packets", "bytes", "Bdrops", "Pdrops", "Idrops"} & set(mux)):
        errors.append("muxpkt must own binary payload and no queue counters")
    queue = {f.get("name"): f for f in message_map.get("queueStatus", {}).get("fields", [])}
    if not {"packets", "bytes", "Bdrops", "Pdrops", "Idrops"} <= set(queue) or ({"stream", "dts", "pts", "duration", "payload"} & set(queue)):
        errors.append("queueStatus must own counters/drop stats and no mux fields")
    get_dvr_cutpoints = method_map.get("getDvrCutpoints", {})
    if get_dvr_cutpoints.get("accessMask") != "ACCESS_HTSP_RECORDER":
        errors.append("getDvrCutpoints must preserve recorder dispatch access")
    if (
        get_dvr_cutpoints.get("minVersion") != 12
        or get_dvr_cutpoints.get("minVersionConfidence") != "annotated"
    ):
        errors.append("getDvrCutpoints must preserve annotated minimum version 12")
    cutpoint_request = [
        (f.get("name"), f.get("type"), f.get("presence"))
        for f in get_dvr_cutpoints.get("requestFields", [])
    ]
    if cutpoint_request != [("id", "u32", "required")]:
        errors.append("getDvrCutpoints request must contain exactly required u32 id")
    if (
        get_dvr_cutpoints.get("requestShape", {}).get("kind") != "fields"
        or get_dvr_cutpoints.get("requestShape", {}).get("completeness") != "complete"
    ):
        errors.append("getDvrCutpoints request shape must be fields/complete")
    cutpoint_reply = [
        (f.get("name"), f.get("type"), f.get("presence"), f.get("shapeRef"))
        for f in get_dvr_cutpoints.get("replyFields", [])
    ]
    if cutpoint_reply != [("cutpoints", "list", "optional", "cutpoint")]:
        errors.append("getDvrCutpoints reply must contain exactly optional cutpoints:list -> cutpoint")
    if (
        get_dvr_cutpoints.get("replyShape", {}).get("kind") != "fields"
        or get_dvr_cutpoints.get("replyShape", {}).get("completeness") != "complete"
    ):
        errors.append("getDvrCutpoints reply shape must be fields/complete")
    stop_dvr_entry = method_map.get("stopDvrEntry", {})
    if stop_dvr_entry.get("accessMask") != "ACCESS_HTSP_RECORDER":
        errors.append("stopDvrEntry must preserve recorder dispatch access")
    if (
        stop_dvr_entry.get("minVersion") is not None
        or stop_dvr_entry.get("minVersionConfidence") != "unknown"
    ):
        errors.append("stopDvrEntry must preserve unknown minimum-version evidence")
    stop_request = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("evidence"))
        for field in stop_dvr_entry.get("requestFields", [])
    ]
    if stop_request != [("id", "u32", "required", STOP_DVR_ENTRY_REQUEST_EVIDENCE)]:
        errors.append("stopDvrEntry request must contain exactly required u32 id with bounded evidence")
    stop_request_shape = stop_dvr_entry.get("requestShape", {})
    if (
        stop_request_shape.get("kind") != "fields"
        or stop_request_shape.get("completeness") != "complete"
        or stop_request_shape.get("evidence") != STOP_DVR_ENTRY_REQUEST_SHAPE_EVIDENCE
    ):
        errors.append("stopDvrEntry request shape must preserve exact complete helper evidence")
    stop_reply = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("evidence"))
        for field in stop_dvr_entry.get("replyFields", [])
    ]
    if stop_reply != [("success", "u32", "required", STOP_DVR_ENTRY_REPLY_EVIDENCE)]:
        errors.append("stopDvrEntry reply must contain exactly required u32 success with bounded evidence")
    stop_reply_shape = stop_dvr_entry.get("replyShape", {})
    if (
        stop_reply_shape.get("kind") != "fields"
        or stop_reply_shape.get("completeness") != "complete"
        or stop_reply_shape.get("evidence") != STOP_DVR_ENTRY_REPLY_SHAPE_EVIDENCE
    ):
        errors.append("stopDvrEntry reply shape must preserve exact complete standard-success evidence")
    if stop_dvr_entry.get("docStatus") != "missing-from-official-client-method-page":
        errors.append("stopDvrEntry must preserve the official-client-doc omission status")
    if stop_dvr_entry.get("notes") != STOP_DVR_ENTRY_NOTES:
        errors.append("stopDvrEntry notes must preserve exact stop/helper/async-state semantics")
    subscription_change_weight = method_map.get("subscriptionChangeWeight", {})
    if subscription_change_weight.get("handler") != "htsp_method_change_weight":
        errors.append("subscriptionChangeWeight must preserve exact dispatch handler")
    if subscription_change_weight.get("accessMask") != "ACCESS_HTSP_STREAMING":
        errors.append("subscriptionChangeWeight must preserve streaming dispatch access")
    if (
        subscription_change_weight.get("minVersion") != 5
        or subscription_change_weight.get("minVersionConfidence") != "annotated"
    ):
        errors.append("subscriptionChangeWeight must preserve annotated minimum version 5")
    weight_request = [
        (
            field.get("name"), field.get("type"), field.get("presence"),
            field.get("condition"), field.get("evidence"),
        )
        for field in subscription_change_weight.get("requestFields", [])
    ]
    if weight_request != list(SUBSCRIPTION_CHANGE_WEIGHT_REQUEST_CONTRACT):
        errors.append(
            "subscriptionChangeWeight request must preserve exact required ID and optional default-zero weight"
        )
    if subscription_change_weight.get("requestShape") != SUBSCRIPTION_CHANGE_WEIGHT_REQUEST_SHAPE:
        errors.append("subscriptionChangeWeight request shape must preserve exact complete evidence")
    if subscription_change_weight.get("replyFields") != []:
        errors.append("subscriptionChangeWeight method-specific reply must remain exactly empty")
    if subscription_change_weight.get("replyShape") != SUBSCRIPTION_CHANGE_WEIGHT_REPLY_SHAPE:
        errors.append(
            "subscriptionChangeWeight reply shape must preserve exact empty acknowledgement ordering evidence"
        )
    if subscription_change_weight.get("notes") != SUBSCRIPTION_CHANGE_WEIGHT_NOTES:
        errors.append(
            "subscriptionChangeWeight notes must preserve exact default, acknowledgement, and non-settlement semantics"
        )
    subscription_live = method_map.get("subscriptionLive", {})
    if subscription_live.get("handler") != "htsp_method_live":
        errors.append("subscriptionLive must preserve exact dispatch handler")
    if subscription_live.get("accessMask") != "ACCESS_HTSP_STREAMING":
        errors.append("subscriptionLive must preserve streaming dispatch access")
    if (
        subscription_live.get("minVersion") != 9
        or subscription_live.get("minVersionConfidence") != "annotated"
    ):
        errors.append("subscriptionLive must preserve annotated minimum version 9")
    live_request = [
        (
            field.get("name"), field.get("type"), field.get("presence"),
            field.get("condition"), field.get("evidence"),
        )
        for field in subscription_live.get("requestFields", [])
    ]
    if live_request != list(SUBSCRIPTION_LIVE_REQUEST_CONTRACT):
        errors.append("subscriptionLive request must preserve exact required u32 subscriptionId")
    if subscription_live.get("requestShape") != SUBSCRIPTION_LIVE_REQUEST_SHAPE:
        errors.append("subscriptionLive request shape must preserve exact complete evidence")
    if subscription_live.get("replyFields") != []:
        errors.append("subscriptionLive method-specific reply must remain exactly empty")
    if subscription_live.get("replyShape") != SUBSCRIPTION_LIVE_REPLY_SHAPE:
        errors.append(
            "subscriptionLive reply shape must preserve exact action-before-empty-reply evidence"
        )
    if subscription_live.get("notes") != SUBSCRIPTION_LIVE_NOTES:
        errors.append(
            "subscriptionLive notes must preserve exact async-authority and non-settlement semantics"
        )
    for method_name, expected_notes in (
        ("subscriptionSeek", SUBSCRIPTION_SEEK_NOTES),
        ("subscriptionSkip", SUBSCRIPTION_SKIP_NOTES),
    ):
        skip_method = method_map.get(method_name, {})
        if skip_method.get("handler") != "htsp_method_skip":
            errors.append(f"{method_name} must preserve exact shared dispatch handler")
        if skip_method.get("accessMask") != "ACCESS_HTSP_STREAMING":
            errors.append(f"{method_name} must preserve streaming dispatch access")
        if (
            skip_method.get("minVersion") != 9
            or skip_method.get("minVersionConfidence") != "annotated"
        ):
            errors.append(f"{method_name} must preserve annotated minimum version 9")
        skip_request = [
            (
                field.get("name"), field.get("type"), field.get("presence"),
                field.get("condition"), field.get("evidence"),
            )
            for field in skip_method.get("requestFields", [])
        ]
        if skip_request != list(SUBSCRIPTION_SKIP_REQUEST_CONTRACT):
            errors.append(
                f"{method_name} request must preserve exact shared skip coordinate contract"
            )
        if skip_method.get("requestShape") != SUBSCRIPTION_SKIP_REQUEST_SHAPE:
            errors.append(f"{method_name} request shape must preserve exact complete evidence")
        if skip_method.get("replyFields") != []:
            errors.append(f"{method_name} method-specific reply must remain exactly empty")
        if skip_method.get("replyShape") != SUBSCRIPTION_SKIP_REPLY_SHAPE:
            errors.append(
                f"{method_name} reply shape must preserve exact action-before-empty-reply evidence"
            )
        if skip_method.get("notes") != expected_notes:
            errors.append(
                f"{method_name} notes must preserve exact shared-handler and non-settlement semantics"
            )
    subscription_filter = method_map.get("subscriptionFilterStream", {})
    if subscription_filter.get("handler") != "htsp_method_filter_stream":
        errors.append("subscriptionFilterStream must preserve exact dispatch handler")
    if subscription_filter.get("accessMask") != "ACCESS_HTSP_STREAMING":
        errors.append("subscriptionFilterStream must preserve streaming dispatch access")
    if (
        subscription_filter.get("minVersion") != 12
        or subscription_filter.get("minVersionConfidence") != "annotated"
    ):
        errors.append("subscriptionFilterStream must preserve annotated minimum version 12")
    filter_request = [
        (
            field.get("name"), field.get("type"), field.get("presence"),
            field.get("condition"), field.get("shapeRef"), field.get("evidence"),
        )
        for field in subscription_filter.get("requestFields", [])
    ]
    if filter_request != list(SUBSCRIPTION_FILTER_STREAM_REQUEST_CONTRACT):
        errors.append(
            "subscriptionFilterStream request must preserve exact ID and ordered optional u32 lists"
        )
    if subscription_filter.get("requestShape") != SUBSCRIPTION_FILTER_STREAM_REQUEST_SHAPE:
        errors.append("subscriptionFilterStream request shape must preserve exact complete evidence")
    if subscription_filter.get("replyFields") != []:
        errors.append("subscriptionFilterStream method-specific reply must remain exactly empty")
    if subscription_filter.get("replyShape") != SUBSCRIPTION_FILTER_STREAM_REPLY_SHAPE:
        errors.append(
            "subscriptionFilterStream reply shape must preserve exact action-before-empty-return evidence"
        )
    if subscription_filter.get("notes") != SUBSCRIPTION_FILTER_STREAM_NOTES:
        errors.append(
            "subscriptionFilterStream notes must preserve exact bounded and non-settlement semantics"
        )
    cut_shape = shapes.get("cutpoint") or {}
    if (
        cut_shape.get("kind") != "object"
        or cut_shape.get("completeness") != "complete"
        or [
            (f.get("name"), f.get("type"), f.get("presence"))
            for f in cut_shape.get("fields", [])
        ] != [
            ("start", "u32", "required"),
            ("end", "u32", "required"),
            ("type", "u32", "required"),
        ]
    ):
        errors.append("getDvrCutpoints cutpoint shape must be exact complete required u32 {start,end,type}")
    if method_map.get("getEvents", {}).get("replyShape", {}).get("kind") != "fields" or [f.get("name") for f in method_map.get("getEvents", {}).get("replyFields", [])] != ["events"]:
        errors.append("getEvents reply must contain only nested events")
    epg_query = method_map.get("epgQuery", {})
    if (
        epg_query.get("handler") != "htsp_method_epgQuery"
        or epg_query.get("accessMask") != "ACCESS_HTSP_STREAMING"
        or epg_query.get("minVersion") != 4
        or epg_query.get("minVersionConfidence") != "annotated"
    ):
        errors.append("epgQuery must preserve exact handler, streaming access, and method minimum v4")
    epg_query_request = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("minVersion"))
        for field in epg_query.get("requestFields", [])
    ]
    if epg_query_request != list(EPG_QUERY_REQUEST_CONTRACT):
        errors.append("epgQuery request must preserve required query and ordered optional field/minimum contract")
    if (
        epg_query.get("requestShape", {}).get("kind") != "fields"
        or epg_query.get("requestShape", {}).get("completeness") != "complete"
    ):
        errors.append("epgQuery request shape must be fields/complete")
    epg_query_reply = [
        (
            field.get("name"), field.get("type"), field.get("presence"),
            field.get("shapeRef"), field.get("condition"),
        )
        for field in epg_query.get("replyFields", [])
    ]
    if epg_query_reply != list(EPG_QUERY_REPLY_CONTRACT):
        errors.append("epgQuery reply must preserve strict eventIds/events alternatives and zero-match omission")
    if (
        epg_query.get("replyShape", {}).get("kind") != "alternative"
        or epg_query.get("replyShape", {}).get("completeness") != "complete"
        or epg_query.get("replyShape", {}).get("alternatives") != EPG_QUERY_REPLY_ALTERNATIVES
    ):
        errors.append("epgQuery reply shape must preserve complete selected alternatives and empty-map success")
    if epg_query.get("notes") != EPG_QUERY_NOTES:
        errors.append("epgQuery notes must preserve defaults, nonzero full, field minima, and zero-match behavior")
    get_epg_object = method_map.get("getEpgObject", {})
    if get_epg_object.get("accessMask") != "ACCESS_HTSP_STREAMING" or get_epg_object.get("minVersion") is not None:
        errors.append("getEpgObject must preserve streaming access with no invented method minimum")
    if [
        (field.get("name"), field.get("type"), field.get("presence"))
        for field in get_epg_object.get("requestFields", [])
    ] != list(GET_EPG_OBJECT_REQUEST_CONTRACT):
        errors.append("getEpgObject request must preserve required id and optional type")
    if [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("shapeRef"))
        for field in get_epg_object.get("replyFields", [])
    ] != list(GET_EPG_OBJECT_REPLY_CONTRACT):
        errors.append("getEpgObject reply must preserve the finite complete broadcast serializer contract")
    if (
        get_epg_object.get("requestShape", {}).get("kind") != "fields"
        or get_epg_object.get("requestShape", {}).get("completeness") != "complete"
        or get_epg_object.get("replyShape", {}).get("kind") != "fields"
        or get_epg_object.get("replyShape", {}).get("completeness") != "complete"
    ):
        errors.append("getEpgObject request/reply shapes must be fields/complete")
    if get_epg_object.get("notes") != GET_EPG_OBJECT_NOTES:
        errors.append("getEpgObject notes must preserve source authority, finite type, opaque credits, and time semantics")
    get_ticket = method_map.get("getTicket", {})
    if (
        get_ticket.get("handler") != "htsp_method_getTicket"
        or get_ticket.get("accessMask") != "ACCESS_HTSP_STREAMING"
        or get_ticket.get("minVersion") != 5
        or get_ticket.get("minVersionConfidence") != "annotated"
    ):
        errors.append("getTicket must preserve exact handler, streaming access, and annotated minimum v5")
    ticket_request = [
        (
            field.get("name"), field.get("type"), field.get("presence"),
            field.get("condition"), field.get("evidence"),
        )
        for field in get_ticket.get("requestFields", [])
    ]
    if ticket_request != list(GET_TICKET_REQUEST_CONTRACT):
        errors.append("getTicket request must preserve exact channel-first alternative u32 selector contract")
    ticket_reply = [
        (
            field.get("name"), field.get("type"), field.get("presence"),
            field.get("evidence"),
        )
        for field in get_ticket.get("replyFields", [])
    ]
    if ticket_reply != list(GET_TICKET_REPLY_CONTRACT):
        errors.append("getTicket reply must preserve exact ordered required string path and ticket")
    if get_ticket.get("requestShape") != GET_TICKET_REQUEST_SHAPE:
        errors.append("getTicket request shape must preserve complete alternative-selector evidence")
    if get_ticket.get("replyShape") != GET_TICKET_REPLY_SHAPE:
        errors.append("getTicket reply shape must preserve complete ordered-field evidence")
    if get_ticket.get("docStatus") != "selector-requirement-and-precedence-underdocumented":
        errors.append("getTicket must preserve the official selector-documentation gap status")
    if get_ticket.get("notes") != GET_TICKET_NOTES:
        errors.append("getTicket notes must preserve selector, branch, path, output, and credential facts")
    bounded_file_contracts = {
        "fileOpen": (
            "htsp_method_file_open",
            [("file", "str", "required")],
            [("id", "u32", "required"), ("size", "s64", "conditional"), ("mtime", "s64", "conditional")],
            "alternative",
        ),
        "fileRead": (
            "htsp_method_file_read",
            [("id", "u32", "required"), ("size", "s64", "required"), ("offset", "s64", "optional")],
            [("data", "bin", "required")],
            "fields",
        ),
        "fileClose": (
            "htsp_method_file_close",
            [("id", "u32", "required"), ("playposition", "u32", "optional"), ("playcount", "u32", "optional")],
            [],
            "knownEmpty",
        ),
        "fileSeek": (
            "htsp_method_file_seek",
            [("id", "u32", "required"), ("offset", "s64", "required"), ("whence", "str", "optional")],
            [("offset", "s64", "required")],
            "fields",
        ),
    }
    for method_name, (handler, request_contract, reply_contract, reply_kind) in bounded_file_contracts.items():
        method = method_map.get(method_name, {})
        if (
            method.get("handler") != handler
            or method.get("accessMask") != "ACCESS_HTSP_RECORDER"
            or method.get("minVersion") != 8
            or method.get("minVersionConfidence") != "annotated"
        ):
            errors.append(f"{method_name} must preserve exact handler, recorder access, and annotated minimum v8")
        actual_request = [
            (field.get("name"), field.get("type"), field.get("presence"))
            for field in method.get("requestFields", [])
        ]
        actual_reply = [
            (field.get("name"), field.get("type"), field.get("presence"))
            for field in method.get("replyFields", [])
        ]
        if actual_request != request_contract or actual_reply != reply_contract:
            errors.append(f"{method_name} must preserve its exact bounded request/reply field contract")
        if (
            method.get("requestShape", {}).get("kind") != "fields"
            or method.get("requestShape", {}).get("completeness") != "complete"
            or method.get("replyShape", {}).get("kind") != reply_kind
            or method.get("replyShape", {}).get("completeness") != "complete"
        ):
            errors.append(f"{method_name} must preserve complete bounded request/reply shapes")
        if len(method.get("notes", [])) != 3:
            errors.append(f"{method_name} notes must preserve exact bounded file-operation facts")
    file_close = method_map.get("fileClose", {})
    if [
        (
            field.get("name"), field.get("type"), field.get("presence"),
            field.get("condition"), field.get("evidence"),
        )
        for field in file_close.get("requestFields", [])
    ] != list(FILE_CLOSE_REQUEST_CONTRACT):
        errors.append("fileClose request evidence must preserve exact recording-backed version/default progress behavior")
    if file_close.get("requestShape") != FILE_CLOSE_REQUEST_SHAPE:
        errors.append("fileClose request shape must preserve the recording-backed DVR-entry guard")
    if file_close.get("replyShape") != FILE_CLOSE_REPLY_SHAPE:
        errors.append("fileClose reply must preserve exact empty acknowledgement evidence")
    if file_close.get("notes") != FILE_CLOSE_NOTES:
        errors.append("fileClose notes must preserve exact id-only server-default mutation and separation facts")
    file_stat = method_map.get("fileStat", {})
    if (
        file_stat.get("handler") != "htsp_method_file_stat"
        or file_stat.get("accessMask") != "ACCESS_HTSP_RECORDER"
        or file_stat.get("minVersion") != 8
        or file_stat.get("minVersionConfidence") != "annotated"
    ):
        errors.append("fileStat must preserve exact handler, recorder access, and annotated minimum v8")
    if [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("evidence"))
        for field in file_stat.get("requestFields", [])
    ] != list(FILE_STAT_REQUEST_CONTRACT):
        errors.append("fileStat request must preserve exact owned-handle default-zero u32 id contract")
    if [
        (
            field.get("name"), field.get("type"), field.get("presence"),
            field.get("condition"), field.get("evidence"),
        )
        for field in file_stat.get("replyFields", [])
    ] != list(FILE_STAT_REPLY_CONTRACT):
        errors.append("fileStat reply must preserve exact coupled ordered signed-s64 size and mtime contract")
    if file_stat.get("requestShape") != FILE_STAT_REQUEST_SHAPE:
        errors.append("fileStat request shape must preserve complete same-connection handle evidence")
    if file_stat.get("replyShape") != FILE_STAT_REPLY_SHAPE:
        errors.append("fileStat reply shape must preserve complete fields-or-empty success evidence")
    if file_stat.get("notes") != FILE_STAT_NOTES:
        errors.append("fileStat notes must preserve zero, ownership, coupling, mtime, and empty-success facts")
    language_shape = shapes.get("epgLanguageStrings") or {}
    if (
        language_shape.get("kind") != "stringMap"
        or language_shape.get("completeness") != "complete"
        or language_shape.get("keyWireType") != "str"
        or language_shape.get("valueWireType") != "str"
    ):
        errors.append("epgLanguageStrings must remain a complete strict string map")
    episode_shape = shapes.get("epgEpisodeNumber") or {}
    if (
        episode_shape.get("kind") != "object"
        or episode_shape.get("completeness") != "complete"
        or [
            (field.get("name"), field.get("type"), field.get("presence"))
            for field in episode_shape.get("fields", [])
        ] != list(GET_EPG_OBJECT_EPISODE_CONTRACT)
    ):
        errors.append("epgEpisodeNumber must preserve the finite optional field contract")
    epg_credits = shapes.get("epgCreditsDynamic") or {}
    if epg_credits.get("kind") != "object" or epg_credits.get("completeness") != "opaque" or "fields" in epg_credits:
        errors.append("epgCreditsDynamic must remain explicitly opaque without invented fields")
    system_time = method_map.get("getSysTime", {})
    system_time_fields = [
        (field.get("name"), field.get("type"), field.get("presence"))
        for field in system_time.get("replyFields", [])
    ]
    if system_time_fields != [
        ("time", "s32", "required"),
        ("timezone", "s32", "required"),
        ("gmtoffset", "s32", "optional"),
    ]:
        errors.append("getSysTime reply must preserve exact s32 source types and required/required/optional presence")
    if system_time.get("requestShape", {}).get("kind") != "knownEmpty":
        errors.append("getSysTime request shape must be known-empty")
    if system_time.get("replyShape", {}).get("kind") != "fields" or system_time.get("replyShape", {}).get("completeness") != "complete":
        errors.append("getSysTime reply shape must be fields/complete")

    get_channel = method_map.get("getChannel", {})
    channel_request_fields = [
        (field.get("name"), field.get("type"), field.get("presence"))
        for field in get_channel.get("requestFields", [])
    ]
    if channel_request_fields != [("channelId", "u32", "required")]:
        errors.append("getChannel request must contain exactly required u32 channelId")
    if get_channel.get("requestShape", {}).get("kind") != "fields" or get_channel.get("requestShape", {}).get("completeness") != "complete":
        errors.append("getChannel request shape must be fields/complete")
    channel_reply_fields = [
        (
            field.get("name"),
            field.get("type"),
            field.get("presence"),
            field.get("shapeRef"),
            field.get("minVersion"),
        )
        for field in get_channel.get("replyFields", [])
    ]
    if channel_reply_fields != [
        ("channelId", "u32", "required", None, None),
        ("channelIdStr", "str", "conditional", None, 41),
        ("channelNumber", "u32", "required", None, None),
        ("channelNumberMinor", "u32", "conditional", None, 13),
        ("channelName", "str", "required", None, None),
        ("channelIcon", "str", "conditional", None, None),
        ("eventId", "u32", "required", None, None),
        ("nextEventId", "u32", "required", None, None),
        ("services", "list", "required", "service", 5),
        ("tags", "list", "required", "u32", None),
    ]:
        errors.append("getChannel reply fields must preserve exact bounded shape, requiredness, and shape refs")
    channel_id_str = next(
        (field for field in get_channel.get("replyFields", []) if field.get("name") == "channelIdStr"),
        {},
    )
    if channel_id_str.get("evidence") != CHANNEL_ID_STR_EVIDENCE:
        errors.append("getChannel.channelIdStr must distinguish unconditional pinned-current-source emission from historical v41 evidence")
    if channel_id_str.get("condition") != CHANNEL_ID_STR_CONDITION:
        errors.append("getChannel.channelIdStr must preserve exact v41 and older-server compatibility wording")
    if get_channel.get("replyShape", {}).get("kind") != "fields" or get_channel.get("replyShape", {}).get("completeness") != "complete":
        errors.append("getChannel reply shape must be fields/complete")
    channel_update_shape = message_map.get("channelUpdate", {}).get("messageShape", {})
    if channel_update_shape.get("kind") != "fields" or channel_update_shape.get("completeness") != "partial":
        errors.append("channelUpdate message shape must remain fields/partial")

    service_shape = shapes.get("service") or {}
    service_fields = [
        (
            field.get("name"),
            field.get("type"),
            field.get("presence"),
            field.get("shapeRef"),
            field.get("minVersion"),
        )
        for field in service_shape.get("fields", [])
    ]
    if service_shape.get("kind") != "object" or service_shape.get("completeness") != "complete" or service_fields != [
        ("name", "str", "required", None, None),
        ("type", "str", "required", None, None),
        ("content", "u32", "required", None, None),
        ("caid", "u32", "conditional", None, None),
        ("caname", "str", "conditional", None, None),
        ("hbbtv", "msg", "conditional", "hbbtvDynamic", None),
        ("providername", "str", "conditional", None, 38),
    ]:
        errors.append("service shape must preserve the complete bounded current-source object")
    hbbtv_shape = shapes.get("hbbtvDynamic") or {}
    if hbbtv_shape.get("kind") != "object" or hbbtv_shape.get("completeness") != "opaque" or "fields" in hbbtv_shape:
        errors.append("hbbtvDynamic must remain an explicit opaque object shape")

    stream_shape = shapes.get("stream") or {}
    stream_fields = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("minVersion"))
        for field in stream_shape.get("fields", [])
    ]
    if stream_shape.get("kind") != "object" or stream_shape.get("completeness") != "partial" or stream_fields != [
        ("index", "u32", "required", None),
        ("type", "str", "required", None),
        ("language", "str", "optional", None),
        ("composition_id", "u32", "optional", 5),
        ("ancillary_id", "u32", "optional", 5),
        ("width", "u32", "optional", None),
        ("height", "u32", "optional", None),
        ("duration", "u32", "optional", None),
        ("aspect_num", "u32", "optional", 5),
        ("aspect_den", "u32", "optional", 5),
        ("audio_type", "u32", "optional", 11),
        ("audio_version", "u32", "optional", None),
        ("channels", "u32", "optional", 5),
        ("rate", "u32", "optional", 5),
        ("rds_uecp", "u32", "optional", None),
    ]:
        errors.append("stream shape must preserve bounded partial field/version evidence")

    source_shape = shapes.get("sourceInfo") or {}
    source_fields = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("minVersion"))
        for field in source_shape.get("fields", [])
    ]
    if source_shape.get("kind") != "object" or source_shape.get("completeness") != "partial" or source_fields != [
        ("adapter_uuid", "str", "optional", None),
        ("mux_uuid", "str", "optional", None),
        ("network_uuid", "str", "optional", None),
        ("adapter", "str", "optional", None),
        ("mux", "str", "optional", None),
        ("network", "str", "optional", None),
        ("network_type", "str", "optional", None),
        ("provider", "str", "optional", None),
        ("service", "str", "optional", None),
        ("satpos", "str", "optional", 20),
    ]:
        errors.append("sourceInfo shape must preserve bounded partial field/version evidence")

    get_event = method_map.get("getEvent", {})
    event_request_fields = [
        (field.get("name"), field.get("type"), field.get("presence"))
        for field in get_event.get("requestFields", [])
    ]
    if event_request_fields != [
        ("eventId", "u32", "required"),
        ("language", "str", "optional"),
    ]:
        errors.append("getEvent request must contain required u32 eventId and optional str language")
    if get_event.get("requestShape", {}).get("kind") != "fields" or get_event.get("requestShape", {}).get("completeness") != "complete":
        errors.append("getEvent request shape must be fields/complete")
    event_reply_fields = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("shapeRef"))
        for field in get_event.get("replyFields", [])
    ]
    if event_reply_fields != list(EVENT_FIELD_CONTRACT):
        errors.append("getEvent reply must preserve the complete bounded current event field contract")
    if get_event.get("replyShape", {}).get("kind") != "fields" or get_event.get("replyShape", {}).get("completeness") != "complete":
        errors.append("getEvent reply shape must be fields/complete")

    get_events = method_map.get("getEvents", {})
    get_events_request = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("minVersion"))
        for field in get_events.get("requestFields", [])
    ]
    if get_events.get("minVersion") != 4:
        errors.append("getEvents method minimum version must remain 4")
    if get_events_request != list(GET_EVENTS_REQUEST_CONTRACT):
        errors.append("getEvents request must preserve exact optional version-6 filter contract")
    if get_events.get("requestShape", {}).get("kind") != "fields" or get_events.get("requestShape", {}).get("completeness") != "complete":
        errors.append("getEvents request shape must be fields/complete")
    get_events_reply = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("shapeRef"))
        for field in get_events.get("replyFields", [])
    ]
    if get_events_reply != [("events", "list", "required", "event")]:
        errors.append("getEvents reply must be exactly required events:list -> event")
    if get_events.get("replyShape", {}).get("kind") != "fields" or get_events.get("replyShape", {}).get("completeness") != "complete":
        errors.append("getEvents reply shape must be fields/complete")

    event_add = message_map.get("eventAdd", {})
    event_add_fields = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("shapeRef"))
        for field in event_add.get("fields", [])
    ]
    if event_add_fields != list(EVENT_FIELD_CONTRACT):
        errors.append("eventAdd must preserve the complete bounded current event field contract")
    if event_add.get("messageShape", {}).get("kind") != "fields" or event_add.get("messageShape", {}).get("completeness") != "complete":
        errors.append("eventAdd message shape must be fields/complete")
    event_update = message_map.get("eventUpdate", {})
    event_update_fields = [
        (field.get("name"), field.get("type"), field.get("presence"), field.get("shapeRef"))
        for field in event_update.get("fields", [])
    ]
    if event_update_fields != list(EVENT_UPDATE_FIELD_CONTRACT):
        errors.append("eventUpdate partial-update field contract must require only eventId and make every non-key field optional")
    if event_update.get("messageShape", {}).get("kind") != "fields" or event_update.get("messageShape", {}).get("completeness") != "partial":
        errors.append("eventUpdate message shape must remain fields/partial")
    if event_update.get("messageShape", {}).get("evidence") != EVENT_UPDATE_SHAPE_EVIDENCE:
        errors.append("eventUpdate shape evidence must distinguish pinned shared-builder emission from partial compatibility")
    if event_update.get("notes") != [EVENT_UPDATE_NOTE]:
        errors.append("eventUpdate current-source and compatibility note must remain exact")
    credits_shape = shapes.get("eventCreditsDynamic") or {}
    if credits_shape.get("kind") != "object" or credits_shape.get("completeness") != "opaque" or "fields" in credits_shape:
        errors.append("eventCreditsDynamic must remain an explicit opaque object shape")
    string_shape = shapes.get("str") or {}
    if string_shape.get("kind") != "scalar" or string_shape.get("wireType") != "str" or string_shape.get("completeness") != "complete":
        errors.append("str must remain the exact event list-element scalar shape")

    for name, minimum in RECORDING_RULE_METHOD_MINIMUMS.items():
        method = method_map.get(name, {})
        if (
            method.get("handler") != f"htsp_method_{name}"
            or method.get("accessMask") != "ACCESS_HTSP_RECORDER"
            or method.get("minVersion") != minimum
            or method.get("minVersionConfidence") != "annotated"
        ):
            errors.append(f"{name} must preserve exact handler, recorder access, and method minimum")
        request_contract = [
            (field.get("name"), field.get("type"), field.get("presence"), field.get("minVersion"))
            for field in method.get("requestFields", [])
        ]
        if request_contract != list(recording_rule_request_contract(name)):
            errors.append(f"{name} request must preserve its exact complete family field contract")
        request_shape = method.get("requestShape", {})
        if request_shape.get("kind") != "fields" or request_shape.get("completeness") != "complete":
            errors.append(f"{name} request shape must remain fields/complete")
        reply_contract = [
            (field.get("name"), field.get("type"), field.get("presence"), field.get("condition"))
            for field in method.get("replyFields", [])
        ]
        expected_reply = (
            [
                ("id", "str", "conditional", "present exactly when success equals 1"),
                ("success", "u32", "required", None),
                ("error", "str", "conditional", "present exactly when success equals 0"),
            ]
            if name.startswith("add")
            else [("success", "u32", "required", None)]
        )
        if reply_contract != expected_reply:
            errors.append(f"{name} reply must preserve its strict finite success topology")
        reply_shape = method.get("replyShape", {})
        if (
            reply_shape.get("kind") != ("alternative" if name.startswith("add") else "fields")
            or reply_shape.get("completeness") != "complete"
            or (
                name.startswith("add")
                and reply_shape.get("alternatives") != [
                    "success=1 with required string id",
                    "success=0 with required fixed error string",
                ]
            )
        ):
            errors.append(f"{name} reply shape must preserve its complete finite topology")
        if method.get("notes") != recording_rule_notes(name):
            errors.append(f"{name} notes must preserve source defaults, errors, and family semantics")
        if not name.startswith("delete"):
            channel = next(
                (field for field in method.get("requestFields", []) if field.get("name") == "channelId"),
                {},
            )
            condition = channel.get("condition", "")
            if name.startswith("add"):
                if "omitted add selector means any channel" not in condition or "-1" not in condition:
                    errors.append(f"{name} channel selector must preserve omitted/explicit-any semantics")
            elif not all(token in condition for token in ("omission keep-old", "omitted and -1", "clear")):
                errors.append(f"{name} channel selector must preserve pinned omission/clear contradiction")

    coverage = spec.get("coverage") or {}
    client_cov = coverage.get("clientMethods") or {}
    typed_cov = coverage.get("typedClientRequests") or {}
    typed_server_cov = coverage.get("typedServerMessages") or {}
    server_cov = coverage.get("serverMessages") or {}
    metrics = coverage.get("metrics") or {}

    ref_count = client_cov.get("referencedCount")
    out_count = client_cov.get("outgoingRequestCount")
    handled_count = server_cov.get("handledCount")
    typed_count = typed_cov.get("count")
    typed_server_count = typed_server_cov.get("count")

    if coverage.get("scanRoots") != EXPECTED_SCAN_ROOTS:
        errors.append(f"coverage.scanRoots must equal {EXPECTED_SCAN_ROOTS!r}")
    if client_cov.get("total") != 39:
        errors.append("coverage clientMethods.total must be 39")
    if server_cov.get("total") != 30:
        errors.append("coverage serverMessages.total must be 30")
    typed_methods = typed_cov.get("methods") or []
    typed_contract = [
        (item.get("name"), item.get("accessMask"), item.get("methodMinVersion"))
        for item in typed_methods
        if isinstance(item, dict)
    ]
    if typed_contract != list(EXPECTED_TYPED_CLIENT_REQUESTS):
        errors.append("coverage typedClientRequests must match the exact reviewed catalog")
    for name, access, method_min_version in EXPECTED_TYPED_CLIENT_REQUESTS:
        method = method_map.get(name) or {}
        if method.get("accessMask") != access:
            errors.append(f"{name}: typed catalog access mask disagrees with pinned method")
        if method.get("minVersion") != method_min_version:
            errors.append(f"{name}: typed catalog method minimum disagrees with pinned method")
    if typed_count != len(typed_methods) or typed_count != 39:
        errors.append("coverage typedClientRequests.count must match exactly 39 methods")
    if typed_cov.get("catalog") != "docs/htsp-protocol/generate_typed_requests.py":
        errors.append("coverage typedClientRequests.catalog must name the reviewed generator")
    if typed_cov.get("meaning") != (
        "Public typed HtspRequest models and generated HtspConnection extensions; "
        "not a support or completeness claim"
    ):
        errors.append("coverage typedClientRequests.meaning must retain its disclaimer")
    typed_server_messages = typed_server_cov.get("messages") or []
    typed_server_contract = [
        (item.get("name"), item.get("messageType"), item.get("minVersion"))
        for item in typed_server_messages
        if isinstance(item, dict)
    ]
    if typed_server_contract != list(EXPECTED_TYPED_SERVER_MESSAGES):
        errors.append("coverage typedServerMessages must match the exact reviewed catalog")
    if typed_server_count != len(typed_server_messages) or typed_server_count != 29:
        errors.append("coverage typedServerMessages.count must match exactly 29 messages")
    if typed_server_cov.get("catalog") != "docs/htsp-protocol/generate_typed_server_messages.py":
        errors.append("coverage typedServerMessages.catalog must name the reviewed generator")
    if typed_server_cov.get("meaning") != (
        "Public typed asynchronous HtspServerMessage models with a public "
        "finite decoder; not runtime wiring, support, or a completeness claim"
    ):
        errors.append("coverage typedServerMessages.meaning must retain its disclaimer")
    for name, _message_type, minimum_version in EXPECTED_TYPED_SERVER_MESSAGES:
        message = message_map.get(name) or {}
        if message.get("minVersion") != minimum_version:
            errors.append(f"{name}: typed server catalog minimum disagrees with pinned message")

    referenced_list = client_cov.get("referenced") or []
    outgoing_list = client_cov.get("outgoingRequests") or []
    handled_list = server_cov.get("handled") or []
    for label, values in (
        ("referenced", referenced_list),
        ("outgoingRequests", outgoing_list),
        ("handled", handled_list),
    ):
        if len(values) != len(set(values)):
            errors.append(f"coverage {label} contains duplicates")
        if values != sorted(values):
            errors.append(f"coverage {label} is not deterministically ordered")
    if not set(referenced_list) <= set(EXPECTED_CLIENT_METHODS):
        errors.append("coverage referenced contains unknown method")
    if not set(outgoing_list) <= set(referenced_list):
        errors.append("coverage outgoingRequests must be a subset of referenced")
    if not set(handled_list) <= set(EXPECTED_SERVER_MESSAGES):
        errors.append("coverage handled contains unknown message")
    expected_unref = [name for name in EXPECTED_CLIENT_METHODS if name not in set(referenced_list)]
    if client_cov.get("unreferenced") != expected_unref:
        errors.append("coverage unreferenced is not the exact source-order complement")
    expected_unhandled = [name for name in EXPECTED_SERVER_MESSAGES if name not in set(handled_list)]
    if server_cov.get("unhandled") != expected_unhandled:
        errors.append("coverage unhandled is not the exact source-order complement")

    if ref_count != len(client_cov.get("referenced") or []):
        errors.append("referencedCount does not match referenced list length")
    if out_count != len(client_cov.get("outgoingRequests") or []):
        errors.append("outgoingRequestCount does not match outgoingRequests length")
    if handled_count != len(server_cov.get("handled") or []):
        errors.append("handledCount does not match handled list length")

    if metrics.get("referencedClientMethods") != ref_count:
        errors.append("metrics.referencedClientMethods mismatch")
    if metrics.get("outgoingClientMethods") != out_count:
        errors.append("metrics.outgoingClientMethods mismatch")
    if metrics.get("handledServerMessages") != handled_count:
        errors.append("metrics.handledServerMessages mismatch")
    if metrics.get("typedClientRequests") != typed_count:
        errors.append("metrics.typedClientRequests mismatch")
    if metrics.get("typedServerMessages") != typed_server_count:
        errors.append("metrics.typedServerMessages mismatch")

    if out_count is not None and ref_count is not None and out_count > ref_count:
        errors.append("outgoingRequestCount cannot exceed referencedCount")

    # Current repository acceptance targets (exact-literal metric).
    if ref_count not in (None, 39):
        errors.append(
            f"expected referenced client methods == 39 under current metric, got {ref_count}"
        )
    if out_count not in (None, 39):
        errors.append(
            f"expected outgoing client methods == 39 under current metric, got {out_count}"
        )
    if handled_count not in (None, 30):
        errors.append(
            f"expected handled server messages == 30 under current metric, got {handled_count}"
        )

    unhandled = list(server_cov.get("unhandled") or [])
    if unhandled != list(EXPECTED_UNHANDLED_MESSAGES):
        errors.append(
            "unhandled server messages mismatch/order: "
            f"got {unhandled!r} expected {list(EXPECTED_UNHANDLED_MESSAGES)!r}"
        )

    if "subscriptionSkip" not in referenced_list or "subscriptionSkip" not in outgoing_list:
        errors.append("subscriptionSkip must be fresh referenced and outgoing production coverage")
    if "subscriptionSeek" not in referenced_list or "subscriptionSeek" not in outgoing_list:
        errors.append("subscriptionSeek must remain fresh referenced and outgoing production coverage")
    if "api" not in referenced_list or "api" not in outgoing_list:
        errors.append("api must be fresh referenced and outgoing production coverage")
    if "getSysTime" not in referenced_list or "getSysTime" not in outgoing_list:
        errors.append("getSysTime must be fresh referenced and outgoing production coverage")
    if "getChannel" not in referenced_list or "getChannel" not in outgoing_list:
        errors.append("getChannel must be fresh referenced and outgoing production coverage")
    if "getEvent" not in referenced_list or "getEvent" not in outgoing_list:
        errors.append("getEvent must be fresh referenced and outgoing production coverage")
    if "getEvents" not in referenced_list or "getEvents" not in outgoing_list:
        errors.append("getEvents must remain fresh referenced and outgoing production coverage")
    if "getDvrCutpoints" not in referenced_list or "getDvrCutpoints" not in outgoing_list:
        errors.append("getDvrCutpoints must be fresh referenced and outgoing production coverage")
    if "stopDvrEntry" not in referenced_list or "stopDvrEntry" not in outgoing_list:
        errors.append("stopDvrEntry must be fresh referenced and outgoing production coverage")
    if (
        "subscriptionChangeWeight" not in referenced_list
        or "subscriptionChangeWeight" not in outgoing_list
    ):
        errors.append(
            "subscriptionChangeWeight must be fresh referenced and outgoing production coverage"
        )
    if "subscriptionLive" not in referenced_list or "subscriptionLive" not in outgoing_list:
        errors.append("subscriptionLive must be fresh referenced and outgoing production coverage")
    if "getTicket" not in referenced_list or "getTicket" not in outgoing_list:
        errors.append("getTicket must be fresh referenced and outgoing production coverage")
    if "fileStat" not in referenced_list or "fileStat" not in outgoing_list:
        errors.append("fileStat must remain referenced and outgoing production coverage")
    if (
        "subscriptionFilterStream" not in referenced_list
        or "subscriptionFilterStream" not in outgoing_list
    ):
        errors.append(
            "subscriptionFilterStream must be fresh referenced and outgoing production coverage"
        )
    for recording_rule_method in (
        "addAutorecEntry", "updateAutorecEntry", "deleteAutorecEntry",
        "addTimerecEntry", "updateTimerecEntry", "deleteTimerecEntry",
    ):
        if recording_rule_method not in referenced_list or recording_rule_method not in outgoing_list:
            errors.append(
                f"{recording_rule_method} must be fresh referenced and outgoing production coverage"
            )

    # sdk flags must match coverage lists
    ref_set = set(client_cov.get("referenced") or [])
    out_set = set(client_cov.get("outgoingRequests") or [])
    typed_set = {item[0] for item in EXPECTED_TYPED_CLIENT_REQUESTS}
    handled_set = set(server_cov.get("handled") or [])
    typed_server_set = {item[0] for item in EXPECTED_TYPED_SERVER_MESSAGES}
    for method in methods:
        if not isinstance(method, dict):
            continue
        sdk = method.get("sdk") or {}
        name = method.get("name")
        if set(sdk) != {"referenced", "outgoingRequest", "typedRequest"} or not all(isinstance(v, bool) for v in sdk.values()):
            errors.append(f"{name}: sdk method flags must be exact booleans")
        if bool(sdk.get("referenced")) != (name in ref_set):
            errors.append(f"{name}: sdk.referenced flag disagrees with coverage")
        if bool(sdk.get("outgoingRequest")) != (name in out_set):
            errors.append(f"{name}: sdk.outgoingRequest flag disagrees with coverage")
        if bool(sdk.get("typedRequest")) != (name in typed_set):
            errors.append(f"{name}: sdk.typedRequest flag disagrees with reviewed catalog")
    for message in messages:
        if not isinstance(message, dict):
            continue
        sdk = message.get("sdk") or {}
        name = message.get("name")
        if set(sdk) != {"handled", "typedServerMessage"} or not all(isinstance(v, bool) for v in sdk.values()):
            errors.append(f"{name}: sdk message flags must be exact booleans")
        if bool(sdk.get("handled")) != (name in handled_set):
            errors.append(f"{name}: sdk.handled flag disagrees with coverage")
        if bool(sdk.get("typedServerMessage")) != (name in typed_server_set):
            errors.append(f"{name}: sdk.typedServerMessage flag disagrees with reviewed catalog")

    limitations = spec.get("docLimitations")
    if not isinstance(limitations, list) or not limitations:
        errors.append("docLimitations must be a non-empty list")
    else:
        limitation_ids = []
        for limitation in limitations:
            if not isinstance(limitation, dict) or set(limitation) != {"id", "summary", "authority", "docsUrl"}:
                errors.append("docLimitations entries must use the exact evidence schema")
                continue
            limitation_ids.append(limitation.get("id"))
            if not all(
                isinstance(limitation.get(key), str) and limitation.get(key)
                for key in ("id", "summary", "authority")
            ) or not (
                limitation.get("docsUrl") is None
                or isinstance(limitation.get("docsUrl"), str) and limitation.get("docsUrl")
            ):
                errors.append("docLimitations entries must contain required text and an optional docs URL")
        if len(limitation_ids) != len(set(limitation_ids)):
            errors.append("docLimitations IDs must be unique")
        system_time_limitation = next(
            (item for item in limitations if isinstance(item, dict) and item.get("id") == SYSTEM_TIME_LIMITATION_ID),
            None,
        )
        summary = system_time_limitation.get("summary", "") if system_time_limitation else ""
        if not system_time_limitation or not all(
            token in summary
            for token in ("htsmsg_add_s32", "s64 Unix time", "not a decision to coerce or truncate")
        ):
            errors.append("getSysTime source/docs type-mismatch limitation is missing or incomplete")
        channel_service_limitation = next(
            (item for item in limitations if isinstance(item, dict) and item.get("id") == CHANNEL_SERVICE_LIMITATION_ID),
            None,
        )
        if not channel_service_limitation or (
            channel_service_limitation.get("summary") != CHANNEL_SERVICE_SUMMARY
            or channel_service_limitation.get("authority") != "src/htsp_server.c htsp_build_channel"
            or channel_service_limitation.get("docsUrl") != CHANNEL_SERVICE_DOCS_URL
        ):
            errors.append("channel service limitation must identify the governing Server-to-Client channelAdd documentation")
        event_limitation = next(
            (item for item in limitations if isinstance(item, dict) and item.get("id") == EVENT_LIMITATION_ID),
            None,
        )
        if not event_limitation or (
            event_limitation.get("summary") != EVENT_LIMITATION_SUMMARY
            or event_limitation.get("authority") != "src/htsp_server.c htsp_build_event"
            or event_limitation.get("docsUrl") != EVENT_DOCS_URL
        ):
            errors.append("event limitation must preserve pinned s64/u32 source facts and governing Server-to-Client URL")
        timerec_limitation = next(
            (item for item in limitations if isinstance(item, dict) and item.get("id") == TIMEREC_LIMITATION_ID),
            None,
        )
        if not timerec_limitation or (
            timerec_limitation.get("summary") != TIMEREC_LIMITATION_SUMMARY
            or timerec_limitation.get("authority") != "src/htsp_server.c htsp_build_timerecentry"
            or timerec_limitation.get("docsUrl") != EVENT_DOCS_URL
        ):
            errors.append(
                "timerec limitation must preserve missing/stale docs, pinned s32/removal source facts, and non-support boundaries"
            )
        get_events_max_time = next(
            (item for item in limitations if isinstance(item, dict) and item.get("id") == GET_EVENTS_MAX_TIME_LIMITATION_ID),
            None,
        )
        if not get_events_max_time or (
            get_events_max_time.get("summary") != GET_EVENTS_MAX_TIME_LIMITATION_SUMMARY
            or get_events_max_time.get("authority") != "src/htsp_server.c htsp_method_getEvents"
            or get_events_max_time.get("docsUrl") != GET_EVENTS_DOCS_URL
        ):
            errors.append("getEvents maxTime limitation must preserve pinned s64 source type and governing Client-to-Server URL")
        get_events_filters = next(
            (item for item in limitations if isinstance(item, dict) and item.get("id") == GET_EVENTS_FILTER_LIMITATION_ID),
            None,
        )
        if not get_events_filters or (
            get_events_filters.get("summary") != GET_EVENTS_FILTER_LIMITATION_SUMMARY
            or get_events_filters.get("authority") != "src/htsp_server.c htsp_method_getEvents"
            or get_events_filters.get("docsUrl") != GET_EVENTS_DOCS_URL
        ):
            errors.append("getEvents filter-interaction limitation must preserve exact pinned behavior and governing Client-to-Server URL")
        get_dvr_cutpoints_limitation = next(
            (
                item for item in limitations
                if isinstance(item, dict)
                and item.get("id") == GET_DVR_CUTPOINTS_LIMITATION_ID
            ),
            None,
        )
        if not get_dvr_cutpoints_limitation or (
            get_dvr_cutpoints_limitation.get("summary")
            != GET_DVR_CUTPOINTS_LIMITATION_SUMMARY
            or get_dvr_cutpoints_limitation.get("authority")
            != "src/htsp_server.c htsp_method_getDvrCutpoints"
            or get_dvr_cutpoints_limitation.get("docsUrl")
            != GET_DVR_CUTPOINTS_DOCS_URL
        ):
            errors.append(
                "getDvrCutpoints limitation must preserve exact coordinate/order uncertainty, "
                "pinned source facts, SDK preservation policy, and governing Client-to-Server URL"
            )
        get_ticket_limitation = next(
            (
                item for item in limitations
                if isinstance(item, dict) and item.get("id") == GET_TICKET_LIMITATION_ID
            ),
            None,
        )
        if not get_ticket_limitation or (
            get_ticket_limitation.get("summary") != GET_TICKET_LIMITATION_SUMMARY
            or get_ticket_limitation.get("authority")
            != "src/htsp_server.c htsp_method_getTicket"
            or get_ticket_limitation.get("docsUrl") != GET_TICKET_DOCS_URL
        ):
            errors.append(
                "getTicket limitation must preserve exact selector requirement, precedence gap, and pinned authority"
            )
        bounded_file_limitation = next(
            (
                item for item in limitations
                if isinstance(item, dict)
                and item.get("id") == BOUNDED_FILE_OPERATIONS_LIMITATION_ID
            ),
            None,
        )
        if not bounded_file_limitation or (
            bounded_file_limitation.get("summary") != BOUNDED_FILE_OPERATIONS_LIMITATION_SUMMARY
            or bounded_file_limitation.get("authority") != BOUNDED_FILE_OPERATIONS_AUTHORITY
            or bounded_file_limitation.get("docsUrl") != FILE_STAT_DOCS_URL
        ):
            errors.append(
                "bounded file-operation limitation must preserve exact signed/requiredness source-doc mismatches"
            )
        file_stat_limitation = next(
            (
                item for item in limitations
                if isinstance(item, dict) and item.get("id") == FILE_STAT_LIMITATION_ID
            ),
            None,
        )
        if not file_stat_limitation or (
            file_stat_limitation.get("summary") != FILE_STAT_LIMITATION_SUMMARY
            or file_stat_limitation.get("authority")
            != "src/htsp_server.c htsp_file_find and htsp_method_file_stat"
            or file_stat_limitation.get("docsUrl") != FILE_STAT_DOCS_URL
        ):
            errors.append(
                "fileStat limitation must preserve exact signed/coupled/empty-success source-doc mismatch"
            )
        stop_dvr_entry_limitation = next(
            (
                item for item in limitations
                if isinstance(item, dict)
                and item.get("id") == STOP_DVR_ENTRY_LIMITATION_ID
            ),
            None,
        )
        if not stop_dvr_entry_limitation or (
            stop_dvr_entry_limitation.get("summary") != STOP_DVR_ENTRY_LIMITATION_SUMMARY
            or stop_dvr_entry_limitation.get("authority")
            != "src/htsp_server.c htsp_methods[] / htsp_method_stopDvrEntry"
            or stop_dvr_entry_limitation.get("docsUrl") != STOP_DVR_ENTRY_DOCS_URL
        ):
            errors.append(
                "stopDvrEntry limitation must preserve exact official-doc omission and pinned authority"
            )
        weight_limitation = next(
            (
                item for item in limitations
                if isinstance(item, dict)
                and item.get("id") == SUBSCRIPTION_CHANGE_WEIGHT_LIMITATION_ID
            ),
            None,
        )
        if not weight_limitation or (
            weight_limitation.get("summary")
            != SUBSCRIPTION_CHANGE_WEIGHT_LIMITATION_SUMMARY
            or weight_limitation.get("authority")
            != "src/htsp_server.c htsp_method_change_weight"
            or weight_limitation.get("docsUrl")
            != SUBSCRIPTION_CHANGE_WEIGHT_DOCS_URL
        ):
            errors.append(
                "subscriptionChangeWeight limitation must preserve exact docs uncertainty and pinned source facts"
            )
        live_limitation = next(
            (
                item for item in limitations
                if isinstance(item, dict) and item.get("id") == SUBSCRIPTION_LIVE_LIMITATION_ID
            ),
            None,
        )
        if not live_limitation or (
            live_limitation.get("summary") != SUBSCRIPTION_LIVE_LIMITATION_SUMMARY
            or live_limitation.get("authority") != "src/htsp_server.c htsp_method_live"
            or live_limitation.get("docsUrl") != SUBSCRIPTION_LIVE_DOCS_URL
        ):
            errors.append(
                "subscriptionLive limitation must preserve exact RPC/async uncertainty and pinned source facts"
            )
        skip_limitation = next(
            (
                item for item in limitations
                if isinstance(item, dict) and item.get("id") == SUBSCRIPTION_SKIP_LIMITATION_ID
            ),
            None,
        )
        if not skip_limitation or (
            skip_limitation.get("summary") != SUBSCRIPTION_SKIP_LIMITATION_SUMMARY
            or skip_limitation.get("authority") != "src/htsp_server.c htsp_method_skip"
            or skip_limitation.get("docsUrl") != SUBSCRIPTION_SKIP_DOCS_URL
        ):
            errors.append(
                "subscriptionSkip limitation must preserve exact coordinate source/docs mismatch facts"
            )
        filter_limitation = next(
            (
                item for item in limitations
                if isinstance(item, dict)
                and item.get("id") == SUBSCRIPTION_FILTER_STREAM_LIMITATION_ID
            ),
            None,
        )
        if not filter_limitation or (
            filter_limitation.get("summary")
            != SUBSCRIPTION_FILTER_STREAM_LIMITATION_SUMMARY
            or filter_limitation.get("authority")
            != SUBSCRIPTION_FILTER_STREAM_AUTHORITY
            or filter_limitation.get("docsUrl")
            != SUBSCRIPTION_FILTER_STREAM_DOCS_URL
        ):
            errors.append(
                "subscriptionFilterStream limitation must preserve exact range, overlap, and empty-list uncertainty"
            )

    global_rpc = spec.get("globalRpc") or {}
    if set(global_rpc) != {"requestFields", "replyFields"}:
        errors.append("globalRpc keys do not match schema")
    for section in ("requestFields", "replyFields"):
        fields = global_rpc.get(section) or []
        if not isinstance(fields, list) or not fields:
            errors.append(f"globalRpc.{section} must be a non-empty list")
        else:
            direction = "request" if section == "requestFields" else "reply"
            for field in fields:
                errors.extend(_validate_field("globalRpc", section, field, direction, shapes))
            if [field.get("order") for field in fields if isinstance(field, dict)] != list(range(1, len(fields) + 1)):
                errors.append(f"globalRpc.{section} field order metadata mismatch")

    async_envelope = spec.get("asyncEnvelope") or {}
    if set(async_envelope) != {"fields", "shape"}:
        errors.append("asyncEnvelope keys do not match schema")
    async_fields = async_envelope.get("fields") or []
    if [f.get("name") for f in async_fields if isinstance(f, dict)] != ["method"]:
        errors.append("asyncEnvelope must contain only the method discriminator")
    for field in async_fields:
        errors.extend(_validate_field("asyncEnvelope", "fields", field, "message", shapes))
    if [field.get("order") for field in async_fields if isinstance(field, dict)] != list(range(1, len(async_fields) + 1)):
        errors.append("asyncEnvelope field order metadata mismatch")
    errors.extend(_validate_shape_descriptor("asyncEnvelope", "shape", async_envelope.get("shape"), async_fields, shapes))

    demo = spec.get("pythonDemo") or {}
    if demo != {
        "htspProtoVersion": 33,
        "methodsCovered": ["authenticate", "enableAsyncMetadata", "hello"],
        "role": "narrow-cross-check-only",
        "completenessAuthority": False,
    }:
        errors.append("pythonDemo must remain the exact protocol-33 three-method narrow cross-check")

    return errors


def _validate_reference_shapes(
    shapes: dict[str, Any],
    message_map: dict[Any, dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    compatible_completeness = {
        "complete": {"complete", "partial"},
        "partial": {"partial"},
        "opaque": {"opaque"},
    }
    for shape_name, shape in shapes.items():
        if not isinstance(shape, dict) or shape.get("kind") != "reference":
            continue
        target = shape.get("target")
        if not isinstance(target, str) or not target.startswith("serverMessage:"):
            continue
        target_name = target.removeprefix("serverMessage:")
        target_message = message_map.get(target_name)
        if target_message is None:
            errors.append(f"shape {shape_name}: reference target {target!r} does not exist")
            continue
        target_descriptor = target_message.get("messageShape")
        if not isinstance(target_descriptor, dict):
            errors.append(f"shape {shape_name}: reference target {target!r} has no valid messageShape")
            continue
        target_completeness = target_descriptor.get("completeness")
        reference_completeness = shape.get("completeness")
        if reference_completeness not in compatible_completeness.get(target_completeness, set()):
            errors.append(
                f"shape {shape_name}: reference completeness {reference_completeness!r} "
                f"contradicts target {target!r} completeness {target_completeness!r}"
            )
    return errors


def _validate_field_shape_refs(spec: dict[str, Any]) -> list[str]:
    actual: list[tuple[str, str, str, str, Any]] = []

    def collect(kind: str, owner: Any, section: str, fields: Any) -> None:
        if not isinstance(fields, list):
            return
        for field in fields:
            if isinstance(field, dict) and "shapeRef" in field:
                actual.append((kind, str(owner), section, str(field.get("name")), field.get("shapeRef")))

    shapes = spec.get("shapes")
    if isinstance(shapes, dict):
        for shape_name, shape in shapes.items():
            if isinstance(shape, dict):
                collect("shape", shape_name, "fields", shape.get("fields"))
    global_rpc = spec.get("globalRpc") or {}
    collect("globalRpc", "globalRpc", "requestFields", global_rpc.get("requestFields"))
    collect("globalRpc", "globalRpc", "replyFields", global_rpc.get("replyFields"))
    async_envelope = spec.get("asyncEnvelope") or {}
    collect("asyncEnvelope", "asyncEnvelope", "fields", async_envelope.get("fields"))
    for method in spec.get("clientMethods") or []:
        if isinstance(method, dict):
            collect("clientMethod", method.get("name"), "requestFields", method.get("requestFields"))
            collect("clientMethod", method.get("name"), "replyFields", method.get("replyFields"))
    for message in spec.get("serverMessages") or []:
        if isinstance(message, dict):
            collect("serverMessage", message.get("name"), "fields", message.get("fields"))

    if actual != list(EXPECTED_FIELD_SHAPE_REFS):
        return [
            "field shapeRef relationships do not match accepted generated mapping: "
            f"got {actual!r}"
        ]
    return []


def _validate_version_metadata(owner: str, item: dict[str, Any]) -> list[str]:
    version = item.get("minVersion")
    confidence = item.get("minVersionConfidence")
    if version is None:
        return [] if confidence == "unknown" else [f"{owner}: null minVersion requires unknown confidence"]
    if not isinstance(version, int) or isinstance(version, bool) or version < 1 or version > 44:
        return [f"{owner}: invalid minVersion"]
    if confidence != "annotated":
        return [f"{owner}: evidenced minVersion requires annotated confidence"]
    return []


def _validate_shape_descriptor(
    owner: str,
    section: str,
    descriptor: Any,
    fields: Any,
    shapes: dict[str, Any],
) -> list[str]:
    if not isinstance(descriptor, dict):
        return [f"{owner}.{section}: shape descriptor must be an object"]
    allowed = {
        "kind", "completeness", "evidence", "alternatives", "invalidAlternatives",
    }
    if not {"kind", "completeness", "evidence"} <= set(descriptor) or not set(descriptor) <= allowed:
        return [f"{owner}.{section}: shape descriptor keys do not match schema"]
    errors: list[str] = []
    kind = descriptor.get("kind")
    completeness = descriptor.get("completeness")
    if kind not in SHAPE_KINDS:
        errors.append(f"{owner}.{section}: invalid shape kind")
    if completeness not in COMPLETENESS_VALUES:
        errors.append(f"{owner}.{section}: invalid completeness")
    if not descriptor.get("evidence"):
        errors.append(f"{owner}.{section}: missing shape evidence")
    has_fields = isinstance(fields, list) and bool(fields)
    if kind in {"knownEmpty", "dynamic", "unknown"} and has_fields:
        errors.append(f"{owner}.{section}: {kind} shape cannot have top-level fields")
    if kind in {"fields", "alternative"} and not has_fields:
        errors.append(f"{owner}.{section}: {kind} shape requires top-level fields")
    if kind == "knownEmpty" and completeness != "complete":
        errors.append(f"{owner}.{section}: knownEmpty must be complete")
    if kind == "dynamic" and completeness != "opaque":
        errors.append(f"{owner}.{section}: dynamic must be opaque")
    if kind == "unknown" and completeness != "partial":
        errors.append(f"{owner}.{section}: unknown shape must be partial")
    if kind == "alternative" and not descriptor.get("alternatives"):
        errors.append(f"{owner}.{section}: alternative shape requires alternatives")
    if "invalidAlternatives" in descriptor:
        invalid_alternatives = descriptor.get("invalidAlternatives")
        if kind != "alternative":
            errors.append(f"{owner}.{section}: invalidAlternatives requires alternative kind")
        if (
            not isinstance(invalid_alternatives, list)
            or not invalid_alternatives
            or any(not isinstance(item, str) or not item for item in invalid_alternatives)
        ):
            errors.append(
                f"{owner}.{section}: invalidAlternatives must be non-empty strings when present"
            )
    return errors


def _validate_field(
    owner: str,
    section: str,
    field: Any,
    expected_direction: str,
    shapes: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    if not isinstance(field, dict):
        return [f"{owner}.{section}: field is not an object"]
    name = field.get("name")
    if not name:
        errors.append(f"{owner}.{section}: field missing name")
    ftype = field.get("type")
    if ftype not in WIRE_TYPES:
        errors.append(f"{owner}.{section}.{name}: type {ftype!r} is not allowlisted")
    if field.get("direction") != expected_direction:
        errors.append(f"{owner}.{section}.{name}: direction must be {expected_direction}")
    presence = field.get("presence")
    if presence not in PRESENCE_VALUES:
        errors.append(f"{owner}.{section}.{name}: presence {presence!r} is not allowlisted")
    if presence in {"conditional", "alternative"} and not field.get("condition"):
        errors.append(f"{owner}.{section}.{name}: {presence} presence requires condition")
    confidence = field.get("confidence")
    if confidence not in CONFIDENCE_VALUES:
        errors.append(
            f"{owner}.{section}.{name}: confidence {confidence!r} is not allowlisted"
        )
    if confidence in {"mechanical", "mechanical+annotated"} and ftype == "unknown":
        errors.append(
            f"{owner}.{section}.{name}: unknown type cannot be mechanical certainty"
        )
    if not field.get("evidence"):
        errors.append(f"{owner}.{section}.{name}: missing evidence")
    if "minVersion" not in field:
        errors.append(f"{owner}.{section}.{name}: minVersion key is required")
    else:
        min_version = field.get("minVersion")
        if min_version is not None and (
            not isinstance(min_version, int) or isinstance(min_version, bool) or min_version < 1 or min_version > 44
        ):
            errors.append(f"{owner}.{section}.{name}: invalid minVersion")
        if min_version is not None and confidence not in {"annotated", "mechanical+annotated"}:
            errors.append(f"{owner}.{section}.{name}: minVersion lacks annotated confidence")
    shape_ref = field.get("shapeRef")
    if shape_ref is not None:
        if ftype not in {"msg", "list"}:
            errors.append(f"{owner}.{section}.{name}: shapeRef requires msg/list type")
        if shape_ref not in shapes:
            errors.append(f"{owner}.{section}.{name}: unknown shapeRef {shape_ref!r}")
    allowed_keys = {"name", "type", "direction", "presence", "evidence", "confidence", "minVersion", "notes", "condition", "shapeRef", "order"}
    if not isinstance(field.get("order"), int) or isinstance(field.get("order"), bool) or field.get("order") < 1:
        errors.append(f"{owner}.{section}.{name}: order must be a positive integer")
    if not set(field) <= allowed_keys:
        errors.append(f"{owner}.{section}.{name}: field keys do not match schema")
    return errors


def _fmt_fields(fields: list[dict[str, Any]]) -> str:
    if not fields:
        return "—"
    parts = []
    for field in fields:
        bit = f"`{field['name']}`:{field.get('type', '?')}"
        bit += f" [{field.get('presence', 'unknown')}]"
        if field.get("shapeRef"):
            bit += f" → `{field['shapeRef']}`"
        conf = field.get("confidence")
        if conf and conf not in {"mechanical", "mechanical+annotated"}:
            bit += f" ({conf})"
        if field.get("minVersion") is not None:
            bit += f" ≥v{field['minVersion']}"
        parts.append(bit)
    return ", ".join(parts)


def _fmt_shape_fields(fields: list[dict[str, Any]], shape: dict[str, Any]) -> str:
    kind = shape.get("kind", "unknown")
    completeness = shape.get("completeness", "unknown")
    if fields:
        return f"{_fmt_fields(fields)} — {kind}/{completeness}"
    return f"_{kind}/{completeness}_"


def render_matrix(spec: dict[str, Any]) -> str:
    upstream = spec["upstream"]
    coverage = spec["coverage"]
    client_cov = coverage["clientMethods"]
    typed_cov = coverage["typedClientRequests"]
    typed_server_cov = coverage["typedServerMessages"]
    server_cov = coverage["serverMessages"]
    lines: list[str] = []
    lines.append("# HTSP method matrix (generated)")
    lines.append("")
    lines.append(
        "This file is generated by `report.py` from `htsp_spec.json`. "
        "Do not edit by hand. Regenerate with the commands in "
        "[README.md](README.md)."
    )
    lines.append("")
    lines.append("## Provenance")
    lines.append("")
    lines.append(f"- Upstream repository: `{upstream['repository']}`")
    lines.append(f"- Revision: `{upstream['revision']}`")
    lines.append(f"- `HTSP_PROTO_VERSION`: **{upstream['htspProtoVersion']}**")
    lines.append("- Pinned inputs:")
    for rel, meta in upstream["files"].items():
        lines.append(
            f"  - `{rel}` — git blob `{meta['gitBlobSha1']}`, {meta['bytes']} bytes"
        )
    lines.append("")
    lines.append(
        "Primary authority is the pinned TVHeadend server source. Official HTSP "
        "documentation is secondary and may be incomplete. This matrix is "
        "**current-source evidence**, not a public API, support, stability, or "
        "completeness promise."
    )
    lines.append("")
    lines.append("## SDK coverage (exact-literal metric)")
    lines.append("")
    lines.append(
        "- Production scan roots: "
        + ", ".join(f"`{root}`" for root in coverage["scanRoots"])
    )
    lines.append(
        f"- Client→server methods referenced in production sources: "
        f"**{client_cov['referencedCount']} / {client_cov['total']}**"
    )
    lines.append(
        f"- Distinct outgoing request names: "
        f"**{client_cov['outgoingRequestCount']} / {client_cov['total']}**"
    )
    lines.append(
        f"- Server→client messages handled (exact literal): "
        f"**{server_cov['handledCount']} / {server_cov['total']}**"
    )
    lines.append(
        f"- Public typed client requests from the reviewed catalog: "
        f"**{typed_cov['count']} / {client_cov['total']}**"
    )
    lines.append(
        f"- Public typed server messages from the separate reviewed catalog: "
        f"**{typed_server_cov['count']} / {server_cov['total']}**"
    )
    lines.append(
        "- Distinguish **referenced** from **outgoing** and from typed coverage: "
        "all pinned methods are now referenced and outgoing; both `subscriptionSeek` and `subscriptionSkip` "
        "are distinct outgoing wire names for one shared pinned handler."
    )
    lines.append(
        "- Never claim methods are implemented/called merely because they are referenced."
    )
    lines.append(
        "- Typed request coverage means a public `HtspRequest` model plus a generated "
        "`HtspConnection` extension. It is not a support, stability, or completeness claim."
    )
    lines.append(
        "- Typed server-message coverage means public payload models plus a public "
        "finite decoder; the metric alone does not prove support, runtime consumption, "
        "stability, or completeness. Selected client metadata/status consumers use the "
        "decoder while raw transport and playback integration remain unchanged."
    )
    lines.append("")
    lines.append("Unhandled server messages:")
    lines.append("")
    for name in server_cov.get("unhandled") or []:
        lines.append(f"- `{name}`")
    lines.append("")
    lines.append("## Global RPC fields")
    lines.append("")
    lines.append(
        "These fields are protocol-wide and are **not** method-specific success fields."
    )
    lines.append("")
    lines.append("| Direction | Fields |")
    lines.append("|---|---|")
    global_rpc = spec.get("globalRpc") or {}
    lines.append(
        f"| request | {_fmt_fields(global_rpc.get('requestFields') or [])} |"
    )
    lines.append(
        f"| reply | {_fmt_fields(global_rpc.get('replyFields') or [])} |"
    )
    lines.append("")
    api = next(method for method in spec["clientMethods"] if method["name"] == "api")
    vocabulary = api["acceptedVocabulary"]
    round_trip = vocabulary["roundTripEvidence"]
    lines.append("## HTSP JSON API accepted value vocabulary")
    lines.append("")
    lines.append(
        "- SDK-admitted exact types: "
        + ", ".join(f"`{value}`" for value in vocabulary["sdkAdmitted"])
    )
    lines.append(
        "- Upstream type deliberately excluded by the SDK bridge: "
        + ", ".join(f"`{value}`" for value in vocabulary["upstreamExcluded"])
    )
    lines.append(
        f"- Round-trip source: `{round_trip['source']}`; decode and serialization "
        "are both evidenced for every admitted type."
    )
    lines.append(f"- UUID width: **{vocabulary['uuidWidthBytes']} bytes**")
    lines.append("")
    lines.append("## Client → server methods")
    lines.append("")
    lines.append(
        "| # | Method | Access mask | Min ver | SDK ref | SDK out | SDK typed | Request fields | Reply fields |"
    )
    lines.append("|---:|---|---|---:|:---:|:---:|:---:|---|---|")
    for idx, method in enumerate(spec["clientMethods"], start=1):
        sdk = method.get("sdk") or {}
        min_ver = method.get("minVersion")
        min_s = "—" if min_ver is None else str(min_ver)
        conf = method.get("minVersionConfidence")
        if conf and conf not in {"mechanical", "mechanical+annotated"} and min_ver is not None:
            min_s = f"{min_s} ({conf})"
        lines.append(
            "| {idx} | `{name}` | `{access}` | {minv} | {ref} | {out} | {typed} | {req} | {rep} |".format(
                idx=idx,
                name=method["name"],
                access=method.get("accessMask", ""),
                minv=min_s,
                ref="yes" if sdk.get("referenced") else "",
                out="yes" if sdk.get("outgoingRequest") else "",
                typed="yes" if sdk.get("typedRequest") else "",
                req=_fmt_shape_fields(method.get("requestFields") or [], method.get("requestShape") or {}),
                rep=_fmt_shape_fields(method.get("replyFields") or [], method.get("replyShape") or {}),
            )
        )
    lines.append("")
    lines.append("## Server → client messages")
    lines.append("")
    lines.append(
        "| # | Message | Min ver | SDK handled | SDK typed | Fields |"
    )
    lines.append("|---:|---|---:|:---:|:---:|---|")
    for idx, message in enumerate(spec["serverMessages"], start=1):
        sdk = message.get("sdk") or {}
        min_ver = message.get("minVersion")
        min_s = "—" if min_ver is None else str(min_ver)
        lines.append(
            "| {idx} | `{name}` | {minv} | {handled} | {typed} | {fields} |".format(
                idx=idx,
                name=message["name"],
                minv=min_s,
                handled="yes" if sdk.get("handled") else "",
                typed="yes" if sdk.get("typedServerMessage") else "",
                fields=_fmt_shape_fields(message.get("fields") or [], message.get("messageShape") or {}),
            )
        )
    lines.append("")
    lines.append("## Documentation limitations (source-derived)")
    lines.append("")
    for item in spec.get("docLimitations") or []:
        lines.append(f"### `{item.get('id')}`")
        lines.append("")
        lines.append(item.get("summary") or "")
        lines.append("")
        lines.append(f"- Authority: {item.get('authority')}")
        if item.get("docsUrl"):
            lines.append(f"- Docs URL: {item['docsUrl']}")
        lines.append("")
    lines.append("## Python demo cross-check")
    lines.append("")
    demo = spec.get("pythonDemo") or {}
    lines.append(
        f"- Demo `HTSP_PROTO_VERSION`: {demo.get('htspProtoVersion')}"
    )
    lines.append(
        f"- Methods touched: {', '.join(f'`{m}`' for m in demo.get('methodsCovered') or []) or '—'}"
    )
    lines.append(
        "- Role: narrow cross-check only; **not** completeness authority."
    )
    lines.append("")
    lines.append("## Approximation boundaries")
    lines.append("")
    lines.append(
        "- Field types marked `mechanical` come from `htsmsg_get_*` / `htsmsg_add_*` evidence."
    )
    lines.append(
        "- `unknown` / `approximate` / annotated minimum versions are explicit uncertainty, "
        "not silent contracts."
    )
    lines.append(
        "- Every field records presence and evidence; nested references and shape completeness prevent flattening or false empty-map claims."
    )
    lines.append(
        "- Access masks are raw dispatch-table provenance, not an SDK authorization API."
    )
    return "\n".join(lines) + "\n"


def write_matrix(spec: dict[str, Any], path: Path = MATRIX_PATH) -> str:
    text = render_matrix(spec)
    path.write_text(text, encoding="utf-8", newline="\n")
    return text


def check_matrix(spec: dict[str, Any], path: Path = MATRIX_PATH) -> list[str]:
    errors: list[str] = []
    if not path.is_file() or path.is_symlink():
        return [f"{path.name}: must be a present regular file"]
    actual = path.read_text(encoding="utf-8")
    expected = render_matrix(spec)
    if actual != expected:
        errors.append(
            f"{path.name}: stale or non-deterministic drift versus htsp_spec.json"
        )
    if not actual.endswith("\n"):
        errors.append(f"{path.name}: missing final newline")
    if "\r" in actual:
        errors.append(f"{path.name}: CR characters are not allowed")
    return errors


def validate_repository_artifacts(
    spec_path: Path = SPEC_PATH,
    matrix_path: Path = MATRIX_PATH,
    upstream_path: Path = UPSTREAM_PATH,
) -> list[str]:
    errors: list[str] = []
    for path in (spec_path, matrix_path, upstream_path):
        if path.is_symlink():
            errors.append(f"{path}: must not be a symlink")
        elif not path.is_file():
            errors.append(f"missing required file: {path}")
    if errors:
        return errors
    try:
        spec = load_json(spec_path)
        upstream = load_json(upstream_path)
    except json.JSONDecodeError as exc:
        return [f"invalid JSON: {exc}"]
    errors.extend(validate_spec(spec, upstream))
    errors.extend(check_matrix(spec, matrix_path))
    return errors


def _derive_fresh_self_test_spec(fresh_mutator: Any = None) -> dict[str, Any]:
    """Build report validation input from an isolated exact-topology source fixture."""
    module_name = "_htsp_report_self_test_derive"
    if module_name in sys.modules:
        raise AssertionError("isolated derive self-test module name is already loaded")
    module_spec = importlib.util.spec_from_file_location(module_name, SCRIPT_DIR / "derive.py")
    if module_spec is None or module_spec.loader is None:
        raise AssertionError("unable to create isolated derive self-test import")
    derive_module = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(derive_module)
    if module_name in sys.modules:
        raise AssertionError("isolated derive self-test import polluted sys.modules")

    method_rows = [
        (
            name,
            "htsp_method_change_weight"
            if name == "subscriptionChangeWeight"
            else "htsp_method_file_open"
            if name == "fileOpen"
            else "htsp_method_file_read"
            if name == "fileRead"
            else "htsp_method_file_close"
            if name == "fileClose"
            else "htsp_method_file_stat"
            if name == "fileStat"
            else "htsp_method_file_seek"
            if name == "fileSeek"
            else "htsp_method_skip"
            if name in {"subscriptionSeek", "subscriptionSkip"}
            else "htsp_method_live"
            if name == "subscriptionLive"
            else "htsp_method_filter_stream"
            if name == "subscriptionFilterStream"
            else f"htsp_method_{name}"
            if name in {
                "hello", "authenticate", "api", "getEvent", "getEvents", "getEpgObject", "epgQuery", "stopDvrEntry",
                "getDvrCutpoints", "getTicket", "fileOpen", "fileRead", "fileClose",
                "fileStat", "fileSeek", *derive_module.RECORDING_RULE_METHODS,
            }
            else f"htsp_method_{index}",
            "ACCESS_HTSP_RECORDER"
            if name in {
                "stopDvrEntry", "getDvrCutpoints", "fileOpen", "fileRead", "fileClose",
                "fileStat", "fileSeek", *derive_module.RECORDING_RULE_METHODS,
            }
            else "ACCESS_HTSP_STREAMING"
            if name in {
                "getEvents", "epgQuery", "getEpgObject", "getTicket", "subscriptionChangeWeight",
                "subscriptionSeek", "subscriptionSkip", "subscriptionLive",
                "subscriptionFilterStream",
            }
            else "ACCESS_ANONYMOUS",
        )
        for index, name in enumerate(EXPECTED_CLIENT_METHODS)
    ]
    server_c = derive_module._minimal_server_c(method_rows, proto=44)
    server_h = "/* report self-test header fixture */\nvoid htsp_init(const char *bindaddr);\n"
    htsp_py = (
        "HTSP_PROTO_VERSION = 33\n"
        "class HTSPClient(object):\n"
        "    def fixture(self):\n"
        "        self.send('hello')\n"
        "        self.send('authenticate')\n"
        "        self.send('enableAsyncMetadata')\n"
    )
    epg_c, epg_h, lang_str_c, string_list_c = derive_module._minimal_epg_sources()

    with tempfile.TemporaryDirectory(prefix="htsp-report-derived-selftest-") as tmp:
        root = Path(tmp)
        (root / "src").mkdir()
        (root / "lib" / "py" / "tvh").mkdir(parents=True)
        fixture_files = {
            "src/htsp_server.c": server_c,
            "src/htsp_server.h": server_h,
            "lib/py/tvh/htsp.py": htsp_py,
            "src/epg.c": epg_c,
            "src/epg.h": epg_h,
            "src/lang_str.c": lang_str_c,
            "src/string_list.c": string_list_c,
        }
        api_c, api_idnode_c, htsmsg_h, htsmsg_binary_c = derive_module._minimal_api_sources()
        fixture_files.update({
            "src/api.c": api_c,
            "src/api/api_idnode.c": api_idnode_c,
            "src/htsmsg.h": htsmsg_h,
            "src/htsmsg_binary.c": htsmsg_binary_c,
        })
        file_metadata: dict[str, dict[str, Any]] = {}
        for relative, content in fixture_files.items():
            data, digest, size = derive_module._pin_bytes_and_sha(content)
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            file_metadata[relative] = {"gitBlobSha1": digest, "bytes": size}
        fixture_manifest = {
            "schemaVersion": 1,
            "repository": EXPECTED_REPOSITORY,
            "revision": "self-test-exact-topology",
            "htspProtoVersion": EXPECTED_PROTO_VERSION,
            "files": file_metadata,
            "docsUrls": {},
        }
        fresh = derive_module.build_spec(
            root,
            fixture_manifest,
            enforce_exact_pin=False,
        )
        if fresh_mutator is not None:
            fresh_mutator(fresh)

    # Report validation owns schema/policy, not fixture-byte pin verification.
    # Evaluate freshly derived getEvents/epgQuery/getEpgObject/getTicket/fileStat/event/stopDvrEntry/getDvrCutpoints,
    # six recording-rule methods, and subscription control evidence
    # inside the complete committed-schema baseline; unrelated minimal fixture
    # handlers deliberately do not pretend to model every pinned method.
    upstream = load_json(UPSTREAM_PATH)
    fresh["upstream"] = {key: value for key, value in upstream.items() if key != "schemaVersion"}
    candidate = load_json(SPEC_PATH)
    fresh_methods = {item["name"]: item for item in fresh["clientMethods"]}
    candidate["clientMethods"] = [
        fresh_methods[item["name"]]
        if item["name"] in {
            "hello", "authenticate", "api", "getEvents", "epgQuery", "getEpgObject", "getTicket", "fileOpen", "fileRead",
            "fileClose", "fileStat", "fileSeek", "stopDvrEntry", "getDvrCutpoints",
            "addAutorecEntry", "updateAutorecEntry", "deleteAutorecEntry",
            "addTimerecEntry", "updateTimerecEntry", "deleteTimerecEntry",
            "subscriptionChangeWeight", "subscriptionLive",
            "subscriptionFilterStream",
        }
        else item
        for item in candidate["clientMethods"]
    ]
    fresh_messages = {item["name"]: item for item in fresh["serverMessages"]}
    candidate["serverMessages"] = [
        fresh_messages[item["name"]]
        if item["name"] in {
            "autorecEntryAdd",
            "autorecEntryUpdate",
            "autorecEntryDelete",
            "eventAdd",
            "eventUpdate",
        }
        else item
        for item in candidate["serverMessages"]
    ]
    for shape_name in (
        "cutpoint", "event", "str", "eventCreditsDynamic", "epgCreditsDynamic",
        "epgLanguageStrings", "epgEpisodeNumber",
    ):
        candidate["shapes"][shape_name] = fresh["shapes"][shape_name]
    candidate["coverage"] = fresh["coverage"]
    candidate["pythonDemo"] = fresh["pythonDemo"]
    candidate["docLimitations"] = fresh["docLimitations"]
    return candidate


def self_test() -> None:
    failures: list[str] = []

    def check(name: str, cond: bool, detail: str = "") -> None:
        if not cond:
            failures.append(f"{name}: {detail}" if detail else name)

    good_spec = {
        "schemaVersion": 1,
        "artifactKind": "htsp-protocol-evidence",
        "upstream": {
            "repository": "https://github.com/tvheadend/tvheadend",
            "revision": "27295c5a48f2c575678bb224014cb9a26a773083",
            "htspProtoVersion": 44,
            "files": {
                "src/htsp_server.c": {
                    "gitBlobSha1": "2837efd3b41ae0ba7f82de2853d8a1d4a1ea88e1",
                    "bytes": 134765,
                },
                "src/htsp_server.h": {
                    "gitBlobSha1": "3b6470d51ab45e1d9bc9bacc710b0e1f6f49b1b0",
                    "bytes": 2050,
                },
                "lib/py/tvh/htsp.py": {
                    "gitBlobSha1": "bab234beafc924a608e830d32cc4596152df0863",
                    "bytes": 2963,
                },
            },
        },
        "globalRpc": {
            "requestFields": [
                {
                    "name": "seq",
                    "type": "u32",
                    "direction": "request",
                    "evidence": "x",
                    "confidence": "mechanical",
                }
            ],
            "replyFields": [
                {
                    "name": "error",
                    "type": "str",
                    "direction": "reply",
                    "evidence": "x",
                    "confidence": "mechanical",
                }
            ],
        },
        "clientMethods": [],
        "serverMessages": [],
        "coverage": {
            "clientMethods": {
                "total": 39,
                "referenced": [],
                "referencedCount": 22,
                "outgoingRequests": [],
                "outgoingRequestCount": 21,
                "unreferenced": [],
            },
            "serverMessages": {
                "total": 30,
                "handled": [],
                "handledCount": 30,
                "unhandled": list(EXPECTED_UNHANDLED_MESSAGES),
            },
            "metrics": {
                "referencedClientMethods": 22,
                "outgoingClientMethods": 21,
                "handledServerMessages": 30,
            },
        },
        "docLimitations": [{"id": "x", "summary": "y", "authority": "z"}],
        "pythonDemo": {"htspProtoVersion": 33, "methodsCovered": ["hello"]},
    }

    # Populate inventories with minimal valid entries.
    ref_names = list(EXPECTED_CLIENT_METHODS[:20]) + ["subscriptionSkip"]
    # 22 referenced including subscriptionSkip; 21 outgoing without it.
    out_names = list(EXPECTED_CLIENT_METHODS[:20])
    # Ensure subscriptionSkip is omitted from the outgoing fixture.
    out_names = [n for n in EXPECTED_CLIENT_METHODS if n != "subscriptionSkip"][:21]
    ref_names = out_names + ["subscriptionSkip"]
    handled = [n for n in EXPECTED_SERVER_MESSAGES if n not in EXPECTED_UNHANDLED_MESSAGES]

    good_spec["coverage"]["clientMethods"]["referenced"] = ref_names
    good_spec["coverage"]["clientMethods"]["referencedCount"] = 22
    good_spec["coverage"]["clientMethods"]["outgoingRequests"] = out_names
    good_spec["coverage"]["clientMethods"]["outgoingRequestCount"] = 21
    good_spec["coverage"]["serverMessages"]["handled"] = handled
    good_spec["coverage"]["serverMessages"]["handledCount"] = 30

    for name in EXPECTED_CLIENT_METHODS:
        good_spec["clientMethods"].append(
            {
                "name": name,
                "handler": f"h_{name}",
                "accessMask": "ACCESS_ANONYMOUS",
                "minVersion": 1,
                "minVersionConfidence": "annotated",
                "requestFields": [],
                "replyFields": [],
                "sdk": {
                    "referenced": name in ref_names,
                    "outgoingRequest": name in out_names,
                },
            }
        )
    for name in EXPECTED_SERVER_MESSAGES:
        good_spec["serverMessages"].append(
            {
                "name": name,
                "minVersion": 1,
                "minVersionConfidence": "annotated",
                "fields": [],
                "sdk": {"handled": name in handled},
            }
        )

    # The positive fixture must be freshly derived from exact-pin-shaped source,
    # independently of the committed generated artifact. Every following test
    # mutates one independently meaningful contract.
    good_spec = _derive_fresh_self_test_spec()
    committed_spec = load_json(SPEC_PATH)
    check(
        "committed-spec-still-valid",
        validate_spec(committed_spec) == [],
        str(validate_spec(committed_spec)),
    )
    ref_names = list(good_spec["coverage"]["clientMethods"]["referenced"])
    out_names = list(good_spec["coverage"]["clientMethods"]["outgoingRequests"])

    errors = validate_spec(good_spec)
    check("good-spec", errors == [], str(errors))

    fresh_handshake = {
        method["name"]: method
        for method in good_spec["clientMethods"]
        if method["name"] in {"hello", "authenticate"}
    }
    check(
        "handshake-fresh-complete-contracts",
        set(fresh_handshake) == {"hello", "authenticate"}
        and all(
            method.get("requestShape", {}).get("completeness") == "complete"
            and method.get("replyShape", {}).get("completeness") == "complete"
            and method.get("sdk") == {
                "referenced": True,
                "outgoingRequest": True,
                "typedRequest": True,
            }
            for method in fresh_handshake.values()
        ),
    )
    fresh_api = next(method for method in good_spec["clientMethods"] if method["name"] == "api")
    expected_api_vocabulary = EXPECTED_API_ACCEPTED_VOCABULARY
    check(
        "api-fresh-complete-contract",
        fresh_api.get("handler") == "htsp_method_api"
        and fresh_api.get("accessMask") == "ACCESS_ANONYMOUS"
        and fresh_api.get("minVersion") == 24
        and fresh_api.get("requestShape", {}).get("completeness") == "complete"
        and fresh_api.get("replyShape", {}).get("completeness") == "complete"
        and fresh_api.get("acceptedVocabulary") == expected_api_vocabulary
        and fresh_api.get("sdk") == {
            "referenced": True,
            "outgoingRequest": True,
            "typedRequest": True,
        },
    )
    api_vocabulary_mutations = []
    for section in ("sdkAdmitted",):
        for value in expected_api_vocabulary[section]:
            api_vocabulary_mutations.append(
                (f"{section}-{value}", lambda method, section=section, value=value: method["acceptedVocabulary"][section].remove(value))
            )
    for section in ("decode", "serialize"):
        for value in expected_api_vocabulary["roundTripEvidence"][section]:
            api_vocabulary_mutations.append(
                (f"roundtrip-{section}-{value}", lambda method, section=section, value=value: method["acceptedVocabulary"]["roundTripEvidence"][section].remove(value))
            )
    api_vocabulary_mutations.extend((
        ("excluded-dbl", lambda method: method["acceptedVocabulary"].update({"upstreamExcluded": []})),
        ("roundtrip-source", lambda method: method["acceptedVocabulary"]["roundTripEvidence"].update({"source": "src/htsmsg.h"})),
        ("uuid-width", lambda method: method["acceptedVocabulary"].update({"uuidWidthBytes": 15})),
    ))
    for label, mutate in api_vocabulary_mutations:
        mutated = json.loads(json.dumps(good_spec))
        mutate(next(method for method in mutated["clientMethods"] if method["name"] == "api"))
        mutation_errors = validate_spec(mutated)
        check(
            f"api-vocabulary-mutation-rejected-{label}",
            "api accepted vocabulary evidence drift" in mutation_errors,
            str(mutation_errors),
        )
    for label, mutate, expected in (
        ("path-requiredness", lambda method: method["requestFields"][0].update({"presence": "optional"}), "api request must preserve exact required path and optional args map"),
        ("args-type", lambda method: method["requestFields"][1].update({"type": "list"}), "api request must preserve exact required path and optional args map"),
        ("reply-field", lambda method: method["replyFields"][0].update({"name": "payload"}), "api reply fields must preserve response and noaccess alternatives"),
        ("reply-alternatives", lambda method: method["replyShape"].update({"alternatives": ["success"]}), "api reply shape must preserve complete finite alternatives"),
        ("no-payload-note", lambda method: method.update({"notes": []}), "api notes must preserve no-payload ambiguity and permanent non-modeling boundary"),
    ):
        mutated = json.loads(json.dumps(good_spec))
        mutate(next(method for method in mutated["clientMethods"] if method["name"] == "api"))
        mutation_errors = validate_spec(mutated)
        check(
            f"api-report-mutation-rejected-{label}",
            expected in mutation_errors,
            str(mutation_errors),
        )
    handshake_report_mutations = (
        (
            "hello-handler",
            "hello",
            lambda method: method.update({"handler": "htsp_method_authenticate"}),
            "hello handler dispatch drift",
        ),
        (
            "hello-access",
            "hello",
            lambda method: method.update({"accessMask": "ACCESS_HTSP_STREAMING"}),
            "hello access dispatch drift",
        ),
        (
            "hello-no-minimum",
            "hello",
            lambda method: method.update({"minVersion": 1}),
            "hello minimum protocol version must remain unknown",
        ),
        (
            "authenticate-handler",
            "authenticate",
            lambda method: method.update({"handler": "htsp_method_hello"}),
            "authenticate handler dispatch drift",
        ),
        (
            "authenticate-access",
            "authenticate",
            lambda method: method.update({"accessMask": "ACCESS_HTSP_STREAMING"}),
            "authenticate access dispatch drift",
        ),
        (
            "authenticate-no-minimum",
            "authenticate",
            lambda method: method.update({"minVersion": 1}),
            "authenticate minimum protocol version must remain unknown",
        ),
        ("hello-required-request", "hello", lambda method: method["requestFields"][0].update({"presence": "optional"}), None),
        ("hello-challenge-size", "hello", lambda method: method["replyFields"][3].update({"condition": "variable"}), None),
        ("hello-complete-reply", "hello", lambda method: method["replyShape"].update({"completeness": "partial"}), None),
        ("authenticate-no-fields", "authenticate", lambda method: method["requestShape"].update({"kind": "fields"}), None),
        ("authenticate-noaccess", "authenticate", lambda method: method["replyFields"][0].update({"presence": "optional"}), None),
        ("authenticate-version-branch", "authenticate", lambda method: method["replyFields"][1].update({"minVersion": 25}), None),
        ("authenticate-low-version-empty", "authenticate", lambda method: method["replyShape"]["alternatives"].pop(), None),
    )
    for label, method_name, mutate, expected_diagnostic in handshake_report_mutations:
        mutated = json.loads(json.dumps(good_spec))
        method = next(method for method in mutated["clientMethods"] if method["name"] == method_name)
        before = json.dumps(method, sort_keys=True)
        mutate(method)
        check(
            f"handshake-report-mutation-target-{label}",
            json.dumps(method, sort_keys=True) != before,
        )
        mutation_errors = validate_spec(mutated)
        check(
            f"handshake-report-mutation-rejected-{label}",
            expected_diagnostic in mutation_errors
            if expected_diagnostic is not None
            else any(method_name in error for error in mutation_errors),
            str(mutation_errors),
        )

    fresh_autorec = {
        message["name"]: message
        for message in good_spec["serverMessages"]
        if message["name"] in {
            "autorecEntryAdd",
            "autorecEntryUpdate",
            "autorecEntryDelete",
        }
    }
    check(
        "autorec-server-messages-fresh-contracts",
        set(fresh_autorec) == {
            "autorecEntryAdd",
            "autorecEntryUpdate",
            "autorecEntryDelete",
        }
        and fresh_autorec["autorecEntryAdd"].get("messageShape", {}).get("completeness") == "complete"
        and fresh_autorec["autorecEntryUpdate"].get("messageShape", {}).get("completeness") == "partial"
        and all(
            message.get("sdk") == {"handled": True, "typedServerMessage": True}
            for message in fresh_autorec.values()
        ),
    )
    for name, mutate in (
        (
            "autorecEntryAdd",
            lambda message: next(
                field for field in message["fields"] if field["name"] == "maxDuration"
            ).update({"presence": "optional"}),
        ),
        (
            "autorecEntryUpdate",
            lambda message: next(
                field for field in message["fields"] if field["name"] == "title"
            ).update({"presence": "required"}),
        ),
        (
            "autorecEntryDelete",
            lambda message: message["fields"][0].update({"type": "u32"}),
        ),
    ):
        mutated = json.loads(json.dumps(good_spec))
        mutate(next(message for message in mutated["serverMessages"] if message["name"] == name))
        mutation_errors = validate_spec(mutated)
        check(
            f"{name}-fresh-contract-mutation-rejected",
            any(name in error for error in mutation_errors),
            str(mutation_errors),
        )

    fresh_recording_rules = {
        method["name"]: method
        for method in good_spec["clientMethods"]
        if method["name"] in RECORDING_RULE_METHOD_MINIMUMS
    }
    check(
        "recording-rules-fresh-complete-contracts",
        set(fresh_recording_rules) == set(RECORDING_RULE_METHOD_MINIMUMS)
        and all(
            method.get("requestShape", {}).get("completeness") == "complete"
            and method.get("replyShape", {}).get("completeness") == "complete"
            and method.get("sdk") == {
                "referenced": True,
                "outgoingRequest": True,
                "typedRequest": True,
            }
            for method in fresh_recording_rules.values()
        ),
    )
    recording_rule_mutations = (
        (
            "addAutorecEntry",
            lambda method: method["requestFields"][2].update({"type": "s64"}),
        ),
        (
            "updateAutorecEntry",
            lambda method: method["requestFields"].pop(
                next(i for i, field in enumerate(method["requestFields"]) if field["name"] == "title")
            ),
        ),
        (
            "deleteAutorecEntry",
            lambda method: method["requestFields"][0].update({"type": "u32"}),
        ),
        (
            "addTimerecEntry",
            lambda method: method["requestFields"][2].update({"type": "s32"}),
        ),
        (
            "updateTimerecEntry",
            lambda method: method["replyFields"][0].update({"presence": "optional"}),
        ),
        (
            "deleteTimerecEntry",
            lambda method: method.update({"accessMask": "ACCESS_ANONYMOUS"}),
        ),
    )
    for name, mutate in recording_rule_mutations:
        mutated = json.loads(json.dumps(good_spec))
        mutate(next(method for method in mutated["clientMethods"] if method["name"] == name))
        mutation_errors = validate_spec(mutated)
        check(
            f"{name}-fresh-contract-mutation-rejected",
            any(name in error for error in mutation_errors),
            str(mutation_errors),
        )

    get_channel = next(m for m in good_spec["clientMethods"] if m["name"] == "getChannel")
    check(
        "getChannel-fresh-shape",
        get_channel.get("requestShape", {}).get("completeness") == "complete"
        and get_channel.get("replyShape", {}).get("completeness") == "complete"
        and next(f for f in get_channel["replyFields"] if f["name"] == "services").get("shapeRef") == "service"
        and next(f for f in get_channel["replyFields"] if f["name"] == "tags").get("shapeRef") == "u32",
    )
    check(
        "getChannel-fresh-coverage",
        good_spec["coverage"]["clientMethods"]["referencedCount"] == 39
        and good_spec["coverage"]["clientMethods"]["outgoingRequestCount"] == 39
        and "getChannel" in good_spec["coverage"]["clientMethods"]["outgoingRequests"],
    )

    get_event = next(m for m in good_spec["clientMethods"] if m["name"] == "getEvent")
    check(
        "getEvent-fresh-shape",
        get_event.get("requestShape", {}).get("completeness") == "complete"
        and get_event.get("replyShape", {}).get("completeness") == "complete"
        and [(f["name"], f["type"], f["presence"], f.get("shapeRef")) for f in get_event["replyFields"]]
        == list(EVENT_FIELD_CONTRACT),
    )
    check(
        "getEvent-fresh-coverage",
        "getEvent" in good_spec["coverage"]["clientMethods"]["referenced"]
        and "getEvent" in good_spec["coverage"]["clientMethods"]["outgoingRequests"],
    )
    get_events = next(m for m in good_spec["clientMethods"] if m["name"] == "getEvents")
    check(
        "getEvents-fresh-complete-contract",
        get_events.get("minVersion") == 4
        and [
            (f["name"], f["type"], f["presence"], f.get("minVersion"))
            for f in get_events["requestFields"]
        ] == list(GET_EVENTS_REQUEST_CONTRACT)
        and get_events.get("requestShape", {}).get("completeness") == "complete"
        and [
            (f["name"], f["type"], f["presence"], f.get("shapeRef"))
            for f in get_events["replyFields"]
        ] == [("events", "list", "required", "event")]
        and get_events.get("replyShape", {}).get("completeness") == "complete",
    )
    check(
        "getEvents-fresh-unchanged-coverage",
        good_spec["coverage"]["clientMethods"]["referencedCount"] == 39
        and good_spec["coverage"]["clientMethods"]["outgoingRequestCount"] == 39
        and good_spec["coverage"]["serverMessages"]["handledCount"] == 30
        and "getEvents" in good_spec["coverage"]["clientMethods"]["outgoingRequests"],
    )
    epg_query = next(m for m in good_spec["clientMethods"] if m["name"] == "epgQuery")
    check(
        "epgQuery-fresh-complete-contract",
        [
            (f["name"], f["type"], f["presence"], f.get("minVersion"))
            for f in epg_query["requestFields"]
        ] == list(EPG_QUERY_REQUEST_CONTRACT)
        and epg_query.get("requestShape", {}).get("completeness") == "complete"
        and [
            (f["name"], f["type"], f["presence"], f.get("shapeRef"), f.get("condition"))
            for f in epg_query["replyFields"]
        ] == list(EPG_QUERY_REPLY_CONTRACT)
        and epg_query.get("replyShape", {}).get("alternatives") == EPG_QUERY_REPLY_ALTERNATIVES
        and epg_query.get("notes") == EPG_QUERY_NOTES
        and epg_query.get("sdk") == {
            "referenced": True,
            "outgoingRequest": True,
            "typedRequest": True,
        },
    )
    mutated_fresh_epg = _derive_fresh_self_test_spec(
        lambda fresh: next(
            method for method in fresh["clientMethods"] if method["name"] == "epgQuery"
        )["requestFields"][0].update({"type": "uuid"}),
    )
    mutated_fresh_epg_errors = validate_spec(mutated_fresh_epg)
    check(
        "epgQuery-fresh-contract-mutation-rejected",
        any("epgQuery request" in error for error in mutated_fresh_epg_errors),
        str(mutated_fresh_epg_errors),
    )
    get_epg_object = next(
        method for method in good_spec["clientMethods"] if method["name"] == "getEpgObject"
    )
    check(
        "getEpgObject-fresh-complete-contract",
        get_epg_object.get("accessMask") == "ACCESS_HTSP_STREAMING"
        and get_epg_object.get("minVersion") is None
        and [
            (field["name"], field["type"], field["presence"])
            for field in get_epg_object["requestFields"]
        ] == list(GET_EPG_OBJECT_REQUEST_CONTRACT)
        and [
            (field["name"], field["type"], field["presence"], field.get("shapeRef"))
            for field in get_epg_object["replyFields"]
        ] == list(GET_EPG_OBJECT_REPLY_CONTRACT)
        and get_epg_object.get("replyShape", {}).get("completeness") == "complete"
        and get_epg_object.get("notes") == GET_EPG_OBJECT_NOTES
        and get_epg_object.get("sdk") == {
            "referenced": True,
            "outgoingRequest": True,
            "typedRequest": True,
        },
    )
    mutated_fresh_get_epg = _derive_fresh_self_test_spec(
        lambda fresh: next(
            method for method in fresh["clientMethods"] if method["name"] == "getEpgObject"
        )["replyFields"][0].update({"presence": "optional"}),
    )
    mutated_fresh_get_epg_errors = validate_spec(mutated_fresh_get_epg)
    check(
        "getEpgObject-fresh-contract-mutation-rejected",
        any("getEpgObject reply" in error for error in mutated_fresh_get_epg_errors),
        str(mutated_fresh_get_epg_errors),
    )
    get_ticket = next(
        method for method in good_spec["clientMethods"] if method["name"] == "getTicket"
    )
    check(
        "getTicket-fresh-complete-contract",
        get_ticket.get("handler") == "htsp_method_getTicket"
        and get_ticket.get("accessMask") == "ACCESS_HTSP_STREAMING"
        and get_ticket.get("minVersion") == 5
        and get_ticket.get("minVersionConfidence") == "annotated"
        and [
            (
                field["name"], field["type"], field["presence"],
                field.get("condition"), field["evidence"],
            )
            for field in get_ticket["requestFields"]
        ] == list(GET_TICKET_REQUEST_CONTRACT)
        and [
            (
                field["name"], field["type"], field["presence"],
                field["evidence"],
            )
            for field in get_ticket["replyFields"]
        ] == list(GET_TICKET_REPLY_CONTRACT)
        and get_ticket.get("requestShape") == GET_TICKET_REQUEST_SHAPE
        and get_ticket.get("replyShape") == GET_TICKET_REPLY_SHAPE
        and get_ticket.get("docStatus")
        == "selector-requirement-and-precedence-underdocumented"
        and get_ticket.get("notes") == GET_TICKET_NOTES
        and get_ticket.get("sdk") == {
            "referenced": True,
            "outgoingRequest": True,
            "typedRequest": True,
        },
        str(get_ticket),
    )
    mutated_fresh_ticket = _derive_fresh_self_test_spec(
        lambda fresh: next(
            method for method in fresh["clientMethods"] if method["name"] == "getTicket"
        )["requestFields"][0].update({"condition": "no precedence"}),
    )
    mutated_fresh_ticket_errors = validate_spec(mutated_fresh_ticket)
    check(
        "getTicket-fresh-contract-mutation-rejected",
        any("getTicket request" in error for error in mutated_fresh_ticket_errors),
        str(mutated_fresh_ticket_errors),
    )

    for label, mutate in (
        ("handler", lambda method: method.update({"handler": "htsp_method_ticket_alias"})),
        ("access", lambda method: method.update({"accessMask": "ACCESS_ANONYMOUS"})),
        ("minimum", lambda method: method.update({"minVersion": 4})),
        ("minimum-confidence", lambda method: method.update({"minVersionConfidence": "unknown"})),
        ("channel-name", lambda method: method["requestFields"][0].update({"name": "channel"})),
        ("channel-type", lambda method: method["requestFields"][0].update({"type": "s64"})),
        ("channel-presence", lambda method: method["requestFields"][0].update({"presence": "optional"})),
        ("channel-condition", lambda method: method["requestFields"][0].update({"condition": "no precedence"})),
        ("channel-evidence", lambda method: method["requestFields"][0].update({"evidence": "generic getter"})),
        ("dvr-name", lambda method: method["requestFields"][1].update({"name": "entryId"})),
        ("dvr-condition", lambda method: method["requestFields"][1].update({"condition": "selected first"})),
        ("request-order", lambda method: method["requestFields"].reverse()),
        ("path-name", lambda method: method["replyFields"][0].update({"name": "url"})),
        ("path-type", lambda method: method["replyFields"][0].update({"type": "bin"})),
        ("path-presence", lambda method: method["replyFields"][0].update({"presence": "optional"})),
        ("path-evidence", lambda method: method["replyFields"][0].update({"evidence": "generic output"})),
        ("ticket-name", lambda method: method["replyFields"][1].update({"name": "token"})),
        ("reply-order", lambda method: method["replyFields"].reverse()),
        ("request-shape-kind", lambda method: method["requestShape"].update({"kind": "fields"})),
        ("request-shape-completeness", lambda method: method["requestShape"].update({"completeness": "partial"})),
        ("request-shape-evidence", lambda method: method["requestShape"].update({"evidence": "generic"})),
        ("reply-shape-completeness", lambda method: method["replyShape"].update({"completeness": "partial"})),
        ("reply-shape-evidence", lambda method: method["replyShape"].update({"evidence": "generic"})),
        ("doc-status", lambda method: method.update({"docStatus": "documented"})),
        ("notes", lambda method: method["notes"].__setitem__(1, "selectors are interchangeable")),
    ):
        bad = json.loads(json.dumps(good_spec))
        method = next(item for item in bad["clientMethods"] if item["name"] == "getTicket")
        mutate(method)
        err = validate_spec(bad)
        check(
            f"reject-getTicket-{label}",
            any("getTicket" in error for error in err),
            str(err),
        )

    for label, mutate in (
        ("remove-both-present", lambda shape: shape["alternatives"].pop()),
        ("weaken-channel-wins", lambda shape: shape["alternatives"].__setitem__(2, "channelId and dvrId; dvrId wins")),
        ("remove-neither-invalid", lambda shape: shape.pop("invalidAlternatives")),
        ("weaken-neither-invalid", lambda shape: shape["invalidAlternatives"].__setitem__(0, "neither selector is accepted")),
    ):
        bad = json.loads(json.dumps(good_spec))
        method = next(item for item in bad["clientMethods"] if item["name"] == "getTicket")
        original = json.loads(json.dumps(method))
        mutate(method["requestShape"])
        check(
            f"getTicket-request-shape-mutation-exact-target-{label}",
            [key for key in method if method[key] != original[key]] == ["requestShape"],
            str(method),
        )
        err = validate_spec(bad)
        check(
            f"getTicket-request-shape-mutation-exact-diagnostic-{label}",
            err == [
                "getTicket request shape must preserve complete alternative-selector evidence",
            ],
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"].remove("getTicket")
    bad["coverage"]["clientMethods"]["referencedCount"] -= 1
    bad["coverage"]["clientMethods"]["outgoingRequests"].remove("getTicket")
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] -= 1
    bad["coverage"]["metrics"]["referencedClientMethods"] -= 1
    bad["coverage"]["metrics"]["outgoingClientMethods"] -= 1
    next(
        method for method in bad["clientMethods"] if method["name"] == "getTicket"
    )["sdk"] = {"referenced": False, "outgoingRequest": False, "typedRequest": True}
    err = validate_spec(bad)
    check(
        "reject-getTicket-stale-live-coverage",
        any("getTicket must be fresh" in error for error in err),
        str(err),
    )

    for label, key, replacement in (
        ("summary", "summary", "selectors are fully documented"),
        ("authority", "authority", "src/htsp_server.c decoy"),
        ("docs-url", "docsUrl", "https://example.invalid/ticket"),
    ):
        bad = json.loads(json.dumps(good_spec))
        limitation = next(
            item for item in bad["docLimitations"]
            if item["id"] == GET_TICKET_LIMITATION_ID
        )
        limitation[key] = replacement
        err = validate_spec(bad)
        check(
            f"reject-getTicket-limitation-{label}",
            any("getTicket limitation" in error for error in err),
            str(err),
        )

    bounded_file_report_mutations = (
        (
            "fileOpen-required-file", "fileOpen",
            lambda method: method["requestFields"][0].update({"presence": "optional"}),
            "fileOpen must preserve its exact bounded request/reply field contract",
        ),
        (
            "fileRead-binary-data", "fileRead",
            lambda method: method["replyFields"][0].update({"type": "str"}),
            [
                "fileRead.data must be bin",
                "fileRead must preserve its exact bounded request/reply field contract",
            ],
        ),
        (
            "fileClose-empty-reply", "fileClose",
            lambda method: method["replyShape"].update({"kind": "fields"}),
            [
                "fileClose.replyShape: fields shape requires top-level fields",
                "fileClose must preserve complete bounded request/reply shapes",
                "fileClose reply must preserve exact empty acknowledgement evidence",
            ],
        ),
        (
            "fileClose-playcount-evidence", "fileClose",
            lambda method: method["requestFields"][2].update({"evidence": "drifted playcount evidence"}),
            "fileClose request evidence must preserve exact recording-backed version/default progress behavior",
        ),
        (
            "fileClose-recording-guard-shape", "fileClose",
            lambda method: method["requestShape"].update({"evidence": "unguarded progress inputs"}),
            "fileClose request shape must preserve the recording-backed DVR-entry guard",
        ),
        (
            "fileClose-server-default-notes", "fileClose",
            lambda method: method["notes"].__setitem__(1, "drifted fileClose server-default note"),
            "fileClose notes must preserve exact id-only server-default mutation and separation facts",
        ),
        (
            "fileSeek-required-offset", "fileSeek",
            lambda method: method["replyFields"][0].update({"presence": "optional"}),
            "fileSeek must preserve its exact bounded request/reply field contract",
        ),
        (
            "fileOpen-notes", "fileOpen",
            lambda method: method["notes"].pop(),
            "fileOpen notes must preserve exact bounded file-operation facts",
        ),
    )
    for label, method_name, mutate, expected_diagnostic in bounded_file_report_mutations:
        bad = json.loads(json.dumps(good_spec))
        method = next(item for item in bad["clientMethods"] if item["name"] == method_name)
        mutate(method)
        err = validate_spec(bad)
        check(
            f"bounded-file-report-mutation-exact-diagnostic-{label}",
            err == (expected_diagnostic if isinstance(expected_diagnostic, list) else [expected_diagnostic]),
            str(err),
        )

    for label, key, replacement in (
        ("summary", "summary", "official and source agree"),
        ("authority", "authority", "src/htsp_server.c decoy"),
        ("docs-url", "docsUrl", "https://example.invalid/file-operations"),
    ):
        bad = json.loads(json.dumps(good_spec))
        limitation = next(
            item for item in bad["docLimitations"]
            if item["id"] == BOUNDED_FILE_OPERATIONS_LIMITATION_ID
        )
        limitation[key] = replacement
        err = validate_spec(bad)
        check(
            f"bounded-file-limitation-mutation-exact-diagnostic-{label}",
            err == [
                "bounded file-operation limitation must preserve exact signed/requiredness source-doc mismatches"
            ],
            str(err),
        )

    file_stat = next(
        method for method in good_spec["clientMethods"] if method["name"] == "fileStat"
    )
    check(
        "fileStat-fresh-complete-contract",
        file_stat.get("handler") == "htsp_method_file_stat"
        and file_stat.get("accessMask") == "ACCESS_HTSP_RECORDER"
        and file_stat.get("minVersion") == 8
        and file_stat.get("minVersionConfidence") == "annotated"
        and [
            (field["name"], field["type"], field["presence"], field["evidence"])
            for field in file_stat["requestFields"]
        ] == list(FILE_STAT_REQUEST_CONTRACT)
        and [
            (
                field["name"], field["type"], field["presence"],
                field["condition"], field["evidence"],
            )
            for field in file_stat["replyFields"]
        ] == list(FILE_STAT_REPLY_CONTRACT)
        and file_stat.get("requestShape") == FILE_STAT_REQUEST_SHAPE
        and file_stat.get("replyShape") == FILE_STAT_REPLY_SHAPE
        and file_stat.get("notes") == FILE_STAT_NOTES
        and file_stat.get("sdk") == {
            "referenced": True,
            "outgoingRequest": True,
            "typedRequest": True,
        },
        str(file_stat),
    )
    mutated_fresh_file_stat = _derive_fresh_self_test_spec(
        lambda fresh: next(
            method for method in fresh["clientMethods"] if method["name"] == "fileStat"
        )["replyShape"]["alternatives"].pop(),
    )
    mutated_fresh_file_stat_errors = validate_spec(mutated_fresh_file_stat)
    check(
        "fileStat-fresh-contract-mutation-exact-diagnostic",
        mutated_fresh_file_stat_errors
        == ["fileStat reply shape must preserve complete fields-or-empty success evidence"],
        str(mutated_fresh_file_stat_errors),
    )

    def reverse_file_stat_reply(method: dict[str, Any]) -> None:
        method["replyFields"].reverse()
        for order, field in enumerate(method["replyFields"], start=1):
            field["order"] = order

    file_stat_mutations = (
        (
            "handler", "handler",
            lambda method: method.update({"handler": "htsp_method_file_stat_alias"}),
            "fileStat must preserve exact handler, recorder access, and annotated minimum v8",
        ),
        (
            "access", "accessMask",
            lambda method: method.update({"accessMask": "ACCESS_ANONYMOUS"}),
            [
                "fileStat must preserve exact handler, recorder access, and annotated minimum v8",
                "fileStat: typed catalog access mask disagrees with pinned method",
            ],
        ),
        (
            "minimum", "minVersion",
            lambda method: method.update({"minVersion": 9}),
            [
                "fileStat must preserve exact handler, recorder access, and annotated minimum v8",
                "fileStat: typed catalog method minimum disagrees with pinned method",
            ],
        ),
        (
            "minimum-confidence", "minVersionConfidence",
            lambda method: method.update({"minVersionConfidence": "unknown"}),
            [
                "fileStat: evidenced minVersion requires annotated confidence",
                "fileStat must preserve exact handler, recorder access, and annotated minimum v8",
            ],
        ),
        (
            "request-name", "requestFields",
            lambda method: method["requestFields"][0].update({"name": "handle"}),
            [
                "fileStat: helper-derived id request field is required",
                "fileStat request must preserve exact owned-handle default-zero u32 id contract",
            ],
        ),
        (
            "request-type", "requestFields",
            lambda method: method["requestFields"][0].update({"type": "s64"}),
            "fileStat request must preserve exact owned-handle default-zero u32 id contract",
        ),
        (
            "request-presence", "requestFields",
            lambda method: method["requestFields"][0].update({"presence": "optional"}),
            "fileStat request must preserve exact owned-handle default-zero u32 id contract",
        ),
        (
            "request-evidence", "requestFields",
            lambda method: method["requestFields"][0].update({"evidence": "generic helper"}),
            "fileStat request must preserve exact owned-handle default-zero u32 id contract",
        ),
        (
            "size-expression-evidence", "replyFields",
            lambda method: method["replyFields"][0].update({"evidence": "generic s64 output"}),
            "fileStat reply must preserve exact coupled ordered signed-s64 size and mtime contract",
        ),
        (
            "size-condition", "replyFields",
            lambda method: method["replyFields"][0].update({"condition": "independent"}),
            "fileStat reply must preserve exact coupled ordered signed-s64 size and mtime contract",
        ),
        (
            "mtime-type", "replyFields",
            lambda method: method["replyFields"][1].update({"type": "u64"}),
            [
                "fileStat.replyFields.mtime: type 'u64' is not allowlisted",
                "fileStat reply must preserve exact coupled ordered signed-s64 size and mtime contract",
            ],
        ),
        (
            "mtime-expression-evidence", "replyFields",
            lambda method: method["replyFields"][1].update({"evidence": "converted timestamp"}),
            "fileStat reply must preserve exact coupled ordered signed-s64 size and mtime contract",
        ),
        (
            "reply-order", "replyFields", reverse_file_stat_reply,
            "fileStat reply must preserve exact coupled ordered signed-s64 size and mtime contract",
        ),
        (
            "request-shape-completeness", "requestShape",
            lambda method: method["requestShape"].update({"completeness": "partial"}),
            "fileStat request shape must preserve complete same-connection handle evidence",
        ),
        (
            "request-shape-evidence", "requestShape",
            lambda method: method["requestShape"].update({"evidence": "generic getter"}),
            "fileStat request shape must preserve complete same-connection handle evidence",
        ),
        (
            "reply-shape-kind", "replyShape",
            lambda method: method["replyShape"].update({"kind": "fields"}),
            "fileStat reply shape must preserve complete fields-or-empty success evidence",
        ),
        (
            "reply-shape-completeness", "replyShape",
            lambda method: method["replyShape"].update({"completeness": "partial"}),
            "fileStat reply shape must preserve complete fields-or-empty success evidence",
        ),
        (
            "reply-shape-evidence", "replyShape",
            lambda method: method["replyShape"].update({"evidence": "generic outputs"}),
            "fileStat reply shape must preserve complete fields-or-empty success evidence",
        ),
        (
            "reply-shape-no-empty", "replyShape",
            lambda method: method["replyShape"]["alternatives"].pop(),
            "fileStat reply shape must preserve complete fields-or-empty success evidence",
        ),
        (
            "reply-shape-uncoupled", "replyShape",
            lambda method: method["replyShape"]["alternatives"].__setitem__(0, "independent fields"),
            "fileStat reply shape must preserve complete fields-or-empty success evidence",
        ),
        (
            "zero-note", "notes",
            lambda method: method["notes"].__setitem__(0, "zero is rejected"),
            "fileStat notes must preserve zero, ownership, coupling, mtime, and empty-success facts",
        ),
        (
            "ownership-note", "notes",
            lambda method: method["notes"].__setitem__(1, "handles are global"),
            "fileStat notes must preserve zero, ownership, coupling, mtime, and empty-success facts",
        ),
        (
            "coupling-note", "notes",
            lambda method: method["notes"].__setitem__(2, "fields are independent"),
            "fileStat notes must preserve zero, ownership, coupling, mtime, and empty-success facts",
        ),
        (
            "mtime-note", "notes",
            lambda method: method["notes"].__setitem__(3, "mtime is converted"),
            "fileStat notes must preserve zero, ownership, coupling, mtime, and empty-success facts",
        ),
        (
            "empty-success-note", "notes",
            lambda method: method["notes"].__setitem__(4, "empty is malformed"),
            "fileStat notes must preserve zero, ownership, coupling, mtime, and empty-success facts",
        ),
    )
    for label, changed_key, mutate, expected_diagnostic in file_stat_mutations:
        bad = json.loads(json.dumps(good_spec))
        method = next(item for item in bad["clientMethods"] if item["name"] == "fileStat")
        original = json.loads(json.dumps(method))
        mutate(method)
        check(
            f"fileStat-report-mutation-exact-target-{label}",
            [key for key in method if method[key] != original[key]] == [changed_key],
            str(method),
        )
        err = validate_spec(bad)
        expected_errors = (
            expected_diagnostic
            if isinstance(expected_diagnostic, list)
            else [expected_diagnostic]
        )
        check(
            f"fileStat-report-mutation-exact-diagnostic-{label}",
            err == expected_errors,
            str(err),
        )

    for label, key, replacement in (
        ("summary", "summary", "official and source agree"),
        ("authority", "authority", "src/htsp_server.c decoy"),
        ("docs-url", "docsUrl", "https://example.invalid/file-stat"),
    ):
        bad = json.loads(json.dumps(good_spec))
        limitation = next(
            item for item in bad["docLimitations"] if item["id"] == FILE_STAT_LIMITATION_ID
        )
        original = json.loads(json.dumps(limitation))
        limitation[key] = replacement
        check(
            f"fileStat-limitation-mutation-exact-target-{label}",
            [item_key for item_key in limitation if limitation[item_key] != original[item_key]] == [key],
            str(limitation),
        )
        err = validate_spec(bad)
        check(
            f"fileStat-limitation-mutation-exact-diagnostic-{label}",
            err == [
                "fileStat limitation must preserve exact signed/coupled/empty-success source-doc mismatch"
            ],
            str(err),
        )

    stop_dvr_entry = next(
        method for method in good_spec["clientMethods"] if method["name"] == "stopDvrEntry"
    )
    check(
        "stopDvrEntry-fresh-complete-contract",
        stop_dvr_entry.get("accessMask") == "ACCESS_HTSP_RECORDER"
        and stop_dvr_entry.get("minVersion") is None
        and stop_dvr_entry.get("minVersionConfidence") == "unknown"
        and [
            (field["name"], field["type"], field["presence"], field["evidence"])
            for field in stop_dvr_entry["requestFields"]
        ] == [("id", "u32", "required", STOP_DVR_ENTRY_REQUEST_EVIDENCE)]
        and stop_dvr_entry.get("requestShape") == {
            "kind": "fields",
            "completeness": "complete",
            "evidence": STOP_DVR_ENTRY_REQUEST_SHAPE_EVIDENCE,
        }
        and [
            (field["name"], field["type"], field["presence"], field["evidence"])
            for field in stop_dvr_entry["replyFields"]
        ] == [("success", "u32", "required", STOP_DVR_ENTRY_REPLY_EVIDENCE)]
        and stop_dvr_entry.get("replyShape") == {
            "kind": "fields",
            "completeness": "complete",
            "evidence": STOP_DVR_ENTRY_REPLY_SHAPE_EVIDENCE,
        }
        and stop_dvr_entry.get("docStatus") == "missing-from-official-client-method-page"
        and stop_dvr_entry.get("notes") == STOP_DVR_ENTRY_NOTES,
    )

    for label, mutate in (
        ("false-minimum-version", lambda method: method.update({"minVersion": 5, "minVersionConfidence": "annotated"})),
        ("wrong-access", lambda method: method.update({"accessMask": "ACCESS_ANONYMOUS"})),
        ("optional-id", lambda method: method["requestFields"][0].update({"presence": "optional"})),
        ("renamed-id", lambda method: method["requestFields"][0].update({"name": "entryId"})),
        ("wrong-id-type", lambda method: method["requestFields"][0].update({"type": "s64"})),
        ("wrong-id-evidence", lambda method: method["requestFields"][0].update({"evidence": "generic getter"})),
        ("partial-request", lambda method: method["requestShape"].update({"completeness": "partial"})),
        ("wrong-request-evidence", lambda method: method["requestShape"].update({"evidence": "handler only"})),
        ("optional-success", lambda method: method["replyFields"][0].update({"presence": "optional"})),
        ("renamed-success", lambda method: method["replyFields"][0].update({"name": "stopped"})),
        ("wrong-success-type", lambda method: method["replyFields"][0].update({"type": "s64"})),
        ("wrong-success-evidence", lambda method: method["replyFields"][0].update({"evidence": "handler reply"})),
        ("partial-reply", lambda method: method["replyShape"].update({"completeness": "partial"})),
        ("wrong-reply-evidence", lambda method: method["replyShape"].update({"evidence": "generic success"})),
        ("malformed-doc-status", lambda method: method.update({"docStatus": "documented"})),
        (
            "wrong-stop-semantics",
            lambda method: method["notes"].__setitem__(
                1,
                "On helper success it calls dvr_entry_cancel and removes the entry.",
            ),
        ),
    ):
        bad = json.loads(json.dumps(good_spec))
        method = next(item for item in bad["clientMethods"] if item["name"] == "stopDvrEntry")
        mutate(method)
        err = validate_spec(bad)
        check(
            f"reject-stopDvrEntry-{label}",
            any("stopDvrEntry" in error for error in err),
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"].remove("stopDvrEntry")
    bad["coverage"]["clientMethods"]["referencedCount"] -= 1
    bad["coverage"]["clientMethods"]["outgoingRequests"].remove("stopDvrEntry")
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] -= 1
    bad["coverage"]["metrics"]["referencedClientMethods"] -= 1
    bad["coverage"]["metrics"]["outgoingClientMethods"] -= 1
    next(
        method for method in bad["clientMethods"] if method["name"] == "stopDvrEntry"
    )["sdk"] = {"referenced": False, "outgoingRequest": False}
    err = validate_spec(bad)
    check(
        "reject-stopDvrEntry-stale-live-coverage",
        any("stopDvrEntry must be fresh" in error for error in err),
        str(err),
    )

    for label, key, replacement in (
        ("summary", "summary", "stop is documented and cancels the entry"),
        ("authority", "authority", "src/htsp_server.c decoy"),
        ("docs-url", "docsUrl", "https://example.invalid/stop"),
    ):
        bad = json.loads(json.dumps(good_spec))
        limitation = next(
            item for item in bad["docLimitations"]
            if item["id"] == STOP_DVR_ENTRY_LIMITATION_ID
        )
        limitation[key] = replacement
        err = validate_spec(bad)
        check(
            f"reject-stopDvrEntry-limitation-{label}",
            any("stopDvrEntry limitation" in error for error in err),
            str(err),
        )

    weight_method = next(
        method for method in good_spec["clientMethods"]
        if method["name"] == "subscriptionChangeWeight"
    )
    check(
        "subscriptionChangeWeight-fresh-complete-contract",
        weight_method.get("handler") == "htsp_method_change_weight"
        and weight_method.get("accessMask") == "ACCESS_HTSP_STREAMING"
        and weight_method.get("minVersion") == 5
        and weight_method.get("minVersionConfidence") == "annotated"
        and [
            (
                field.get("name"), field.get("type"), field.get("presence"),
                field.get("condition"), field.get("evidence"),
            )
            for field in weight_method["requestFields"]
        ] == list(SUBSCRIPTION_CHANGE_WEIGHT_REQUEST_CONTRACT)
        and weight_method.get("requestShape") == SUBSCRIPTION_CHANGE_WEIGHT_REQUEST_SHAPE
        and weight_method.get("replyFields") == []
        and weight_method.get("replyShape") == SUBSCRIPTION_CHANGE_WEIGHT_REPLY_SHAPE
        and weight_method.get("notes") == SUBSCRIPTION_CHANGE_WEIGHT_NOTES,
    )
    check(
        "subscriptionChangeWeight-fresh-coverage",
        "subscriptionChangeWeight"
        in good_spec["coverage"]["clientMethods"]["referenced"]
        and "subscriptionChangeWeight"
        in good_spec["coverage"]["clientMethods"]["outgoingRequests"],
    )

    for label, mutate in (
        ("false-min-version", lambda method: method.update({"minVersion": 4})),
        ("wrong-handler", lambda method: method.update({"handler": "decoy"})),
        ("wrong-access", lambda method: method.update({"accessMask": "ACCESS_ANONYMOUS"})),
        ("optional-subscription-id", lambda method: method["requestFields"][0].update({"presence": "optional"})),
        ("renamed-subscription-id", lambda method: method["requestFields"][0].update({"name": "sid"})),
        ("wrong-subscription-id-type", lambda method: method["requestFields"][0].update({"type": "s64"})),
        ("wrong-subscription-id-evidence", lambda method: method["requestFields"][0].update({"evidence": "generic getter"})),
        ("required-weight", lambda method: method["requestFields"][1].update({"presence": "required"})),
        ("wrong-weight-type", lambda method: method["requestFields"][1].update({"type": "s64"})),
        ("wrong-weight-default-condition", lambda method: method["requestFields"][1].update({"condition": "defaults to one"})),
        ("wrong-weight-evidence", lambda method: method["requestFields"][1].update({"evidence": "optional getter"})),
        ("partial-request", lambda method: method["requestShape"].update({"completeness": "partial"})),
        ("wrong-request-shape-evidence", lambda method: method["requestShape"].update({"evidence": "generic extraction"})),
        ("invented-reply-field", lambda method: method["replyFields"].append({"name": "success"})),
        ("partial-reply", lambda method: method["replyShape"].update({"completeness": "partial"})),
        ("false-reply-order", lambda method: method["replyShape"].update({"evidence": "reply after change"})),
        ("false-settlement-note", lambda method: method["notes"].__setitem__(2, "Acknowledgement proves applied weight.")),
    ):
        bad = json.loads(json.dumps(good_spec))
        method = next(
            item for item in bad["clientMethods"]
            if item["name"] == "subscriptionChangeWeight"
        )
        mutate(method)
        err = validate_spec(bad)
        check(
            f"reject-subscriptionChangeWeight-{label}",
            any("subscriptionChangeWeight" in error for error in err),
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"].remove("subscriptionChangeWeight")
    bad["coverage"]["clientMethods"]["referencedCount"] -= 1
    bad["coverage"]["clientMethods"]["outgoingRequests"].remove("subscriptionChangeWeight")
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] -= 1
    bad["coverage"]["metrics"]["referencedClientMethods"] -= 1
    bad["coverage"]["metrics"]["outgoingClientMethods"] -= 1
    next(
        method for method in bad["clientMethods"]
        if method["name"] == "subscriptionChangeWeight"
    )["sdk"] = {"referenced": False, "outgoingRequest": False}
    err = validate_spec(bad)
    check(
        "reject-subscriptionChangeWeight-stale-live-coverage",
        any("subscriptionChangeWeight must be fresh" in error for error in err),
        str(err),
    )

    for label, key, replacement in (
        ("summary", "summary", "weight default and ordering are documented"),
        ("authority", "authority", "src/htsp_server.c decoy"),
        ("docs-url", "docsUrl", "https://example.invalid/weight"),
    ):
        bad = json.loads(json.dumps(good_spec))
        limitation = next(
            item for item in bad["docLimitations"]
            if item["id"] == SUBSCRIPTION_CHANGE_WEIGHT_LIMITATION_ID
        )
        limitation[key] = replacement
        err = validate_spec(bad)
        check(
            f"reject-subscriptionChangeWeight-limitation-{label}",
            any("subscriptionChangeWeight limitation" in error for error in err),
            str(err),
        )

    live_method = next(
        method for method in good_spec["clientMethods"]
        if method["name"] == "subscriptionLive"
    )
    check(
        "subscriptionLive-fresh-complete-contract",
        live_method.get("handler") == "htsp_method_live"
        and live_method.get("accessMask") == "ACCESS_HTSP_STREAMING"
        and live_method.get("minVersion") == 9
        and live_method.get("minVersionConfidence") == "annotated"
        and [
            (
                field.get("name"), field.get("type"), field.get("presence"),
                field.get("condition"), field.get("evidence"),
            )
            for field in live_method["requestFields"]
        ] == list(SUBSCRIPTION_LIVE_REQUEST_CONTRACT)
        and live_method.get("requestShape") == SUBSCRIPTION_LIVE_REQUEST_SHAPE
        and live_method.get("replyFields") == []
        and live_method.get("replyShape") == SUBSCRIPTION_LIVE_REPLY_SHAPE
        and live_method.get("notes") == SUBSCRIPTION_LIVE_NOTES,
    )
    check(
        "subscriptionLive-fresh-coverage",
        "subscriptionLive" in good_spec["coverage"]["clientMethods"]["referenced"]
        and "subscriptionLive"
        in good_spec["coverage"]["clientMethods"]["outgoingRequests"],
    )

    for method_name, expected_notes in (
        ("subscriptionSeek", SUBSCRIPTION_SEEK_NOTES),
        ("subscriptionSkip", SUBSCRIPTION_SKIP_NOTES),
    ):
        skip_method = next(
            method for method in good_spec["clientMethods"]
            if method["name"] == method_name
        )
        check(
            f"{method_name}-fresh-complete-contract",
            skip_method.get("handler") == "htsp_method_skip"
            and skip_method.get("accessMask") == "ACCESS_HTSP_STREAMING"
            and skip_method.get("minVersion") == 9
            and skip_method.get("minVersionConfidence") == "annotated"
            and [
                (
                    field.get("name"), field.get("type"), field.get("presence"),
                    field.get("condition"), field.get("evidence"),
                )
                for field in skip_method["requestFields"]
            ] == list(SUBSCRIPTION_SKIP_REQUEST_CONTRACT)
            and skip_method.get("requestShape") == SUBSCRIPTION_SKIP_REQUEST_SHAPE
            and skip_method.get("replyFields") == []
            and skip_method.get("replyShape") == SUBSCRIPTION_SKIP_REPLY_SHAPE
            and skip_method.get("notes") == expected_notes,
        )
        check(
            f"{method_name}-fresh-coverage",
            method_name in good_spec["coverage"]["clientMethods"]["referenced"]
            and method_name in good_spec["coverage"]["clientMethods"]["outgoingRequests"],
        )

    for label, mutate in (
        ("false-min-version", lambda method: method.update({"minVersion": 8})),
        ("wrong-handler", lambda method: method.update({"handler": "decoy"})),
        ("wrong-access", lambda method: method.update({"accessMask": "ACCESS_ANONYMOUS"})),
        ("optional-subscription-id", lambda method: method["requestFields"][0].update({"presence": "optional"})),
        ("renamed-time", lambda method: method["requestFields"][1].update({"name": "pts"})),
        ("unsigned-time", lambda method: method["requestFields"][1].update({"type": "u64"})),
        ("optional-time", lambda method: method["requestFields"][1].update({"presence": "optional"})),
        ("required-absolute", lambda method: method["requestFields"][3].update({"presence": "required"})),
        ("partial-request", lambda method: method["requestShape"].update({"completeness": "partial"})),
        ("invented-reply-field", lambda method: method["replyFields"].append({"name": "success"})),
        ("false-async-note", lambda method: method["notes"].__setitem__(2, "Reply proves settled skip state.")),
    ):
        bad = json.loads(json.dumps(good_spec))
        method = next(
            item for item in bad["clientMethods"] if item["name"] == "subscriptionSkip"
        )
        mutate(method)
        err = validate_spec(bad)
        check(
            f"reject-subscriptionSkip-{label}",
            any("subscriptionSkip" in error for error in err),
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"].remove("subscriptionSkip")
    bad["coverage"]["clientMethods"]["referencedCount"] -= 1
    bad["coverage"]["clientMethods"]["outgoingRequests"].remove("subscriptionSkip")
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] -= 1
    bad["coverage"]["metrics"]["referencedClientMethods"] -= 1
    bad["coverage"]["metrics"]["outgoingClientMethods"] -= 1
    next(
        method for method in bad["clientMethods"] if method["name"] == "subscriptionSkip"
    )["sdk"] = {"referenced": False, "outgoingRequest": False, "typedRequest": True}
    err = validate_spec(bad)
    check(
        "reject-subscriptionSkip-stale-live-coverage",
        any("subscriptionSkip must be fresh" in error for error in err),
        str(err),
    )

    for label, key, replacement in (
        ("summary", "summary", "Official docs fully specify either/or skip coordinates"),
        ("authority", "authority", "src/htsp_server.c decoy"),
        ("docs-url", "docsUrl", "https://example.invalid/skip"),
    ):
        bad = json.loads(json.dumps(good_spec))
        limitation = next(
            item for item in bad["docLimitations"]
            if item["id"] == SUBSCRIPTION_SKIP_LIMITATION_ID
        )
        limitation[key] = replacement
        err = validate_spec(bad)
        check(
            f"reject-subscriptionSkip-limitation-{label}",
            any("subscriptionSkip limitation" in error for error in err),
            str(err),
        )

    for label, mutate in (
        ("false-min-version", lambda method: method.update({"minVersion": 8})),
        ("wrong-handler", lambda method: method.update({"handler": "decoy"})),
        ("wrong-access", lambda method: method.update({"accessMask": "ACCESS_ANONYMOUS"})),
        ("optional-subscription-id", lambda method: method["requestFields"][0].update({"presence": "optional"})),
        ("renamed-subscription-id", lambda method: method["requestFields"][0].update({"name": "sid"})),
        ("wrong-subscription-id-type", lambda method: method["requestFields"][0].update({"type": "s64"})),
        ("wrong-subscription-id-evidence", lambda method: method["requestFields"][0].update({"evidence": "generic getter"})),
        ("partial-request", lambda method: method["requestShape"].update({"completeness": "partial"})),
        ("wrong-request-shape-evidence", lambda method: method["requestShape"].update({"evidence": "generic extraction"})),
        ("invented-reply-field", lambda method: method["replyFields"].append({"name": "success"})),
        ("partial-reply", lambda method: method["replyShape"].update({"completeness": "partial"})),
        ("false-reply-order", lambda method: method["replyShape"].update({"evidence": "reply before action"})),
        ("false-async-note", lambda method: method["notes"].__setitem__(2, "Reply proves settled live state.")),
    ):
        bad = json.loads(json.dumps(good_spec))
        method = next(
            item for item in bad["clientMethods"] if item["name"] == "subscriptionLive"
        )
        mutate(method)
        err = validate_spec(bad)
        check(
            f"reject-subscriptionLive-{label}",
            any("subscriptionLive" in error for error in err),
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"].remove("subscriptionLive")
    bad["coverage"]["clientMethods"]["referencedCount"] -= 1
    bad["coverage"]["clientMethods"]["outgoingRequests"].remove("subscriptionLive")
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] -= 1
    bad["coverage"]["metrics"]["referencedClientMethods"] -= 1
    bad["coverage"]["metrics"]["outgoingClientMethods"] -= 1
    next(
        method for method in bad["clientMethods"] if method["name"] == "subscriptionLive"
    )["sdk"] = {"referenced": False, "outgoingRequest": False}
    err = validate_spec(bad)
    check(
        "reject-subscriptionLive-stale-live-coverage",
        any("subscriptionLive must be fresh" in error for error in err),
        str(err),
    )

    for label, key, replacement in (
        ("summary", "summary", "RPC reply proves settled live state"),
        ("authority", "authority", "src/htsp_server.c decoy"),
        ("docs-url", "docsUrl", "https://example.invalid/live"),
    ):
        bad = json.loads(json.dumps(good_spec))
        limitation = next(
            item for item in bad["docLimitations"]
            if item["id"] == SUBSCRIPTION_LIVE_LIMITATION_ID
        )
        limitation[key] = replacement
        err = validate_spec(bad)
        check(
            f"reject-subscriptionLive-limitation-{label}",
            any("subscriptionLive limitation" in error for error in err),
            str(err),
        )

    filter_method = next(
        method for method in good_spec["clientMethods"]
        if method["name"] == "subscriptionFilterStream"
    )
    check(
        "subscriptionFilterStream-fresh-complete-contract",
        filter_method.get("handler") == "htsp_method_filter_stream"
        and filter_method.get("accessMask") == "ACCESS_HTSP_STREAMING"
        and filter_method.get("minVersion") == 12
        and filter_method.get("minVersionConfidence") == "annotated"
        and [
            (
                field.get("name"), field.get("type"), field.get("presence"),
                field.get("condition"), field.get("shapeRef"), field.get("evidence"),
            )
            for field in filter_method["requestFields"]
        ] == list(SUBSCRIPTION_FILTER_STREAM_REQUEST_CONTRACT)
        and filter_method.get("requestShape") == SUBSCRIPTION_FILTER_STREAM_REQUEST_SHAPE
        and filter_method.get("replyFields") == []
        and filter_method.get("replyShape") == SUBSCRIPTION_FILTER_STREAM_REPLY_SHAPE
        and filter_method.get("notes") == SUBSCRIPTION_FILTER_STREAM_NOTES,
        str(filter_method),
    )
    check(
        "subscriptionFilterStream-fresh-coverage",
        "subscriptionFilterStream"
        in good_spec["coverage"]["clientMethods"]["referenced"]
        and "subscriptionFilterStream"
        in good_spec["coverage"]["clientMethods"]["outgoingRequests"],
    )

    for label, mutate in (
        ("false-min-version", lambda method: method.update({"minVersion": 11})),
        ("wrong-handler", lambda method: method.update({"handler": "decoy"})),
        ("wrong-access", lambda method: method.update({"accessMask": "ACCESS_ANONYMOUS"})),
        ("optional-subscription-id", lambda method: method["requestFields"][0].update({"presence": "optional"})),
        ("renamed-subscription-id", lambda method: method["requestFields"][0].update({"name": "sid"})),
        ("wrong-subscription-id-type", lambda method: method["requestFields"][0].update({"type": "s64"})),
        ("wrong-subscription-id-evidence", lambda method: method["requestFields"][0].update({"evidence": "generic getter"})),
        ("required-enable", lambda method: method["requestFields"][1].update({"presence": "required"})),
        ("wrong-enable-type", lambda method: method["requestFields"][1].update({"type": "msg"})),
        ("wrong-enable-shape", lambda method: method["requestFields"][1].update({"shapeRef": "s64"})),
        ("wrong-enable-condition", lambda method: method["requestFields"][1].update({"condition": "empty clears all"})),
        ("wrong-enable-evidence", lambda method: method["requestFields"][1].update({"evidence": "generic list"})),
        ("required-disable", lambda method: method["requestFields"][2].update({"presence": "required"})),
        ("wrong-disable-type", lambda method: method["requestFields"][2].update({"type": "msg"})),
        ("wrong-disable-shape", lambda method: method["requestFields"][2].update({"shapeRef": "s64"})),
        ("wrong-disable-condition", lambda method: method["requestFields"][2].update({"condition": "empty clears all"})),
        ("wrong-disable-evidence", lambda method: method["requestFields"][2].update({"evidence": "generic list"})),
        ("reordered-lists", lambda method: method["requestFields"].reverse()),
        ("partial-request", lambda method: method["requestShape"].update({"completeness": "partial"})),
        ("wrong-request-evidence", lambda method: method["requestShape"].update({"evidence": "generic extraction"})),
        ("invented-reply-field", lambda method: method["replyFields"].append({"name": "success"})),
        ("partial-reply", lambda method: method["replyShape"].update({"completeness": "partial"})),
        ("wrong-reply-evidence", lambda method: method["replyShape"].update({"evidence": "generic empty"})),
        ("false-range-note", lambda method: method["notes"].__setitem__(1, "All nonnegative indexes are effective.")),
        ("false-settlement-note", lambda method: method["notes"].__setitem__(2, "Reply proves settled stream selection.")),
    ):
        bad = json.loads(json.dumps(good_spec))
        method = next(
            item for item in bad["clientMethods"]
            if item["name"] == "subscriptionFilterStream"
        )
        mutate(method)
        err = validate_spec(bad)
        check(
            f"reject-subscriptionFilterStream-{label}",
            any("subscriptionFilterStream" in error for error in err),
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"].remove("subscriptionFilterStream")
    bad["coverage"]["clientMethods"]["referencedCount"] -= 1
    bad["coverage"]["clientMethods"]["outgoingRequests"].remove("subscriptionFilterStream")
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] -= 1
    bad["coverage"]["metrics"]["referencedClientMethods"] -= 1
    bad["coverage"]["metrics"]["outgoingClientMethods"] -= 1
    next(
        method for method in bad["clientMethods"]
        if method["name"] == "subscriptionFilterStream"
    )["sdk"] = {"referenced": False, "outgoingRequest": False}
    err = validate_spec(bad)
    check(
        "reject-subscriptionFilterStream-stale-live-coverage",
        any("subscriptionFilterStream must be fresh" in error for error in err),
        str(err),
    )

    for label, key, replacement in (
        ("summary", "summary", "all stream-filter semantics are documented"),
        ("authority", "authority", "src/htsp_server.c decoy"),
        ("docs-url", "docsUrl", "https://example.invalid/filter"),
    ):
        bad = json.loads(json.dumps(good_spec))
        limitation = next(
            item for item in bad["docLimitations"]
            if item["id"] == SUBSCRIPTION_FILTER_STREAM_LIMITATION_ID
        )
        limitation[key] = replacement
        err = validate_spec(bad)
        check(
            f"reject-subscriptionFilterStream-limitation-{label}",
            any("subscriptionFilterStream limitation" in error for error in err),
            str(err),
        )

    get_dvr_cutpoints = next(
        m for m in good_spec["clientMethods"] if m["name"] == "getDvrCutpoints"
    )
    check(
        "getDvrCutpoints-fresh-complete-contract",
        get_dvr_cutpoints.get("accessMask") == "ACCESS_HTSP_RECORDER"
        and get_dvr_cutpoints.get("minVersion") == 12
        and get_dvr_cutpoints.get("minVersionConfidence") == "annotated"
        and [
            (f["name"], f["type"], f["presence"])
            for f in get_dvr_cutpoints["requestFields"]
        ] == [("id", "u32", "required")]
        and get_dvr_cutpoints.get("requestShape", {}).get("completeness") == "complete"
        and [
            (f["name"], f["type"], f["presence"], f.get("shapeRef"))
            for f in get_dvr_cutpoints["replyFields"]
        ] == [("cutpoints", "list", "optional", "cutpoint")]
        and get_dvr_cutpoints.get("replyShape", {}).get("completeness") == "complete"
        and [
            (f["name"], f["type"], f["presence"])
            for f in good_spec["shapes"]["cutpoint"]["fields"]
        ] == [
            ("start", "u32", "required"),
            ("end", "u32", "required"),
            ("type", "u32", "required"),
        ],
    )

    for label, mutate in (
        (
            "optional-id",
            lambda method, shape: method["requestFields"][0].update({"presence": "optional"}),
        ),
        (
            "wrong-id-type",
            lambda method, shape: method["requestFields"][0].update({"type": "s64"}),
        ),
        (
            "partial-request-shape",
            lambda method, shape: method["requestShape"].update({"completeness": "partial"}),
        ),
        (
            "required-cutpoints",
            lambda method, shape: method["replyFields"][0].update({"presence": "required"}),
        ),
        (
            "wrong-cutpoints-type",
            lambda method, shape: method["replyFields"][0].update({"type": "msg"}),
        ),
        (
            "partial-reply-shape",
            lambda method, shape: method["replyShape"].update({"completeness": "partial"}),
        ),
        (
            "optional-nested-start",
            lambda method, shape: shape["fields"][0].update({"presence": "optional"}),
        ),
        (
            "wrong-nested-type",
            lambda method, shape: shape["fields"][1].update({"type": "s64"}),
        ),
        (
            "partial-nested-shape",
            lambda method, shape: shape.update({"completeness": "partial"}),
        ),
        (
            "wrong-access-mask",
            lambda method, shape: method.update({"accessMask": "ACCESS_ANONYMOUS"}),
        ),
        (
            "wrong-min-version",
            lambda method, shape: method.update({"minVersion": 11}),
        ),
        (
            "wrong-min-version-confidence",
            lambda method, shape: method.update({"minVersionConfidence": "unknown"}),
        ),
    ):
        bad = json.loads(json.dumps(good_spec))
        method = next(m for m in bad["clientMethods"] if m["name"] == "getDvrCutpoints")
        mutate(method, bad["shapes"]["cutpoint"])
        err = validate_spec(bad)
        check(
            f"reject-getDvrCutpoints-{label}",
            any("getDvrCutpoints" in error or "cutpoint shape" in error for error in err),
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"].remove("getDvrCutpoints")
    bad["coverage"]["clientMethods"]["referencedCount"] -= 1
    bad["coverage"]["clientMethods"]["outgoingRequests"].remove("getDvrCutpoints")
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] -= 1
    bad["coverage"]["metrics"]["referencedClientMethods"] -= 1
    bad["coverage"]["metrics"]["outgoingClientMethods"] -= 1
    next(
        method for method in bad["clientMethods"]
        if method["name"] == "getDvrCutpoints"
    )["sdk"] = {"referenced": False, "outgoingRequest": False}
    err = validate_spec(bad)
    check(
        "reject-getDvrCutpoints-stale-live-coverage",
        any("getDvrCutpoints must be fresh" in error for error in err),
        str(err),
    )
    event_update = next(m for m in good_spec["serverMessages"] if m["name"] == "eventUpdate")
    check(
        "eventUpdate-partial-merge-contract",
        event_update.get("messageShape", {}).get("completeness") == "partial"
        and event_update.get("notes") == [
            "Pinned current eventUpdate call sites send the shared htsp_build_event snapshot; partial-update compatibility permits omission of every non-key field and consumers merge by eventId."
        ]
        and event_update["fields"][0].get("name") == "eventId"
        and event_update["fields"][0].get("presence") == "required"
        and all(field.get("presence") == "optional" for field in event_update["fields"][1:]),
    )

    channel_id_str = next(f for f in get_channel["replyFields"] if f["name"] == "channelIdStr")
    check(
        "getChannel-channelIdStr-current-source-history-distinction",
        channel_id_str.get("evidence") == CHANNEL_ID_STR_EVIDENCE
        and channel_id_str.get("condition") == CHANNEL_ID_STR_CONDITION,
    )

    bad = json.loads(json.dumps(good_spec))
    get_channel_bad = next(m for m in bad["clientMethods"] if m["name"] == "getChannel")
    next(f for f in get_channel_bad["replyFields"] if f["name"] == "channelIdStr")["evidence"] = "htsp_build_channel protocol-version branch"
    err = validate_spec(bad)
    check(
        "reject-invented-channelIdStr-current-source-version-branch",
        any("unconditional pinned-current-source" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    get_channel_bad = next(m for m in bad["clientMethods"] if m["name"] == "getChannel")
    next(f for f in get_channel_bad["replyFields"] if f["name"] == "channelIdStr")["condition"] = "required for negotiated protocol version 41 or newer; absent on older supported versions"
    err = validate_spec(bad)
    check(
        "reject-categorical-channelIdStr-older-version-absence",
        any("older-server compatibility wording" in e for e in err),
        str(err),
    )

    channel_limitation = next(
        item for item in good_spec["docLimitations"]
        if item["id"] == CHANNEL_SERVICE_LIMITATION_ID
    )
    check(
        "channel-service-governing-server-to-client-channelAdd-docs",
        channel_limitation.get("summary") == CHANNEL_SERVICE_SUMMARY
        and channel_limitation.get("docsUrl") == CHANNEL_SERVICE_DOCS_URL,
    )

    bad = json.loads(json.dumps(good_spec))
    next(
        item for item in bad["docLimitations"]
        if item["id"] == CHANNEL_SERVICE_LIMITATION_ID
    )["docsUrl"] = EXPECTED_DOCS_URLS["clientToServer"]
    err = validate_spec(bad)
    check(
        "reject-channel-service-client-to-server-url",
        any("governing Server-to-Client channelAdd" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    next(
        item for item in bad["docLimitations"]
        if item["id"] == CHANNEL_SERVICE_LIMITATION_ID
    )["summary"] = CHANNEL_SERVICE_SUMMARY.replace(
        "Server-to-Client methods channelAdd section",
        "Client-to-Server RPC methods page",
    )
    err = validate_spec(bad)
    check(
        "reject-channel-service-client-rpc-governing-wording",
        any("governing Server-to-Client channelAdd" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    next(m for m in bad["clientMethods"] if m["name"] == "getChannel")["requestFields"][0]["presence"] = "optional"
    err = validate_spec(bad)
    check("reject-getChannel-requiredness-drift", any("exactly required" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    get_channel_bad = next(m for m in bad["clientMethods"] if m["name"] == "getChannel")
    next(f for f in get_channel_bad["replyFields"] if f["name"] == "services").pop("shapeRef")
    err = validate_spec(bad)
    check("reject-getChannel-missing-shape-ref", any("shape ref" in e or "shapeRef" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    get_channel_bad = next(m for m in bad["clientMethods"] if m["name"] == "getChannel")
    next(f for f in get_channel_bad["replyFields"] if f["name"] == "tags")["shapeRef"] = "service"
    err = validate_spec(bad)
    check("reject-getChannel-wrong-shape-ref", any("shape ref" in e or "shapeRef" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["shapes"]["service"]["fields"] = [
        field for field in bad["shapes"]["service"]["fields"] if field["name"] != "content"
    ]
    for order, field in enumerate(bad["shapes"]["service"]["fields"], start=1):
        field["order"] = order
    err = validate_spec(bad)
    check("reject-omitted-service-source-field", any("service shape" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    next(m for m in bad["clientMethods"] if m["name"] == "getChannel")["replyShape"]["completeness"] = "partial"
    err = validate_spec(bad)
    check("reject-getChannel-stale-incompleteness", any("reply shape" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    next(m for m in bad["serverMessages"] if m["name"] == "channelUpdate")["messageShape"]["completeness"] = "complete"
    err = validate_spec(bad)
    check("reject-channelUpdate-false-completeness", any("channelUpdate message shape" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["docLimitations"] = [
        item for item in bad["docLimitations"] if item["id"] != CHANNEL_SERVICE_LIMITATION_ID
    ]
    err = validate_spec(bad)
    check("reject-channel-service-missing-limitation", any("channel service" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"].remove("getChannel")
    bad["coverage"]["clientMethods"]["referencedCount"] -= 1
    bad["coverage"]["clientMethods"]["outgoingRequests"].remove("getChannel")
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] -= 1
    bad["coverage"]["metrics"]["referencedClientMethods"] -= 1
    bad["coverage"]["metrics"]["outgoingClientMethods"] -= 1
    get_channel_bad = next(m for m in bad["clientMethods"] if m["name"] == "getChannel")
    get_channel_bad["sdk"] = {"referenced": False, "outgoingRequest": False}
    err = validate_spec(bad)
    check("reject-getChannel-stale-live-coverage", any("getChannel must be fresh" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    next(m for m in bad["clientMethods"] if m["name"] == "getEvent")["requestFields"][0]["presence"] = "optional"
    err = validate_spec(bad)
    check("reject-getEvent-optional-eventId", any("required u32 eventId" in e for e in err), str(err))

    for field_name, replacement in (
        ("eventId", "s64"),
        ("start", "u32"),
        ("stop", "u32"),
    ):
        bad = json.loads(json.dumps(good_spec))
        get_event_bad = next(m for m in bad["clientMethods"] if m["name"] == "getEvent")
        next(f for f in get_event_bad["replyFields"] if f["name"] == field_name)["type"] = replacement
        err = validate_spec(bad)
        check(
            f"reject-getEvent-wrong-required-{field_name}",
            any("complete bounded current event field contract" in e for e in err),
            str(err),
        )

    for field_name in ("category", "keyword"):
        bad = json.loads(json.dumps(good_spec))
        get_event_bad = next(m for m in bad["clientMethods"] if m["name"] == "getEvent")
        next(f for f in get_event_bad["replyFields"] if f["name"] == field_name)["shapeRef"] = "u32"
        err = validate_spec(bad)
        check(
            f"reject-getEvent-wrong-{field_name}-element-shape",
            any("event field contract" in e or "shapeRef relationship" in e for e in err),
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    bad["shapes"]["eventCreditsDynamic"]["completeness"] = "complete"
    err = validate_spec(bad)
    check(
        "reject-event-credits-losing-opaque-shape",
        any("eventCreditsDynamic" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    get_event_bad = next(m for m in bad["clientMethods"] if m["name"] == "getEvent")
    get_event_bad["replyFields"] = [
        f for f in get_event_bad["replyFields"] if f["name"] != "seasonNumber"
    ]
    for order, field in enumerate(get_event_bad["replyFields"], start=1):
        field["order"] = order
    err = validate_spec(bad)
    check(
        "reject-omitted-event-episode-helper-field",
        any("event field contract" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    next(m for m in bad["serverMessages"] if m["name"] == "eventUpdate")["messageShape"]["completeness"] = "complete"
    err = validate_spec(bad)
    check(
        "reject-eventUpdate-false-completeness",
        any("eventUpdate message shape" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    event_update_bad = next(m for m in bad["serverMessages"] if m["name"] == "eventUpdate")
    next(f for f in event_update_bad["fields"] if f["name"] == "start")["presence"] = "required"
    err = validate_spec(bad)
    check(
        "reject-eventUpdate-required-non-key-field",
        any("eventUpdate partial-update field contract" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    event_update_bad = next(m for m in bad["serverMessages"] if m["name"] == "eventUpdate")
    next(f for f in event_update_bad["fields"] if f["name"] == "eventId")["presence"] = "optional"
    err = validate_spec(bad)
    check(
        "reject-eventUpdate-optional-eventId",
        any("eventUpdate partial-update field contract" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    event_update_bad = next(m for m in bad["serverMessages"] if m["name"] == "eventUpdate")
    event_update_bad["notes"] = [
        "Update messages omit non-key fields and consumers merge by eventId."
    ]
    err = validate_spec(bad)
    check(
        "reject-eventUpdate-categorical-current-omission-note",
        any("eventUpdate current-source and compatibility note" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"].remove("getEvent")
    bad["coverage"]["clientMethods"]["referencedCount"] -= 1
    bad["coverage"]["clientMethods"]["outgoingRequests"].remove("getEvent")
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] -= 1
    bad["coverage"]["metrics"]["referencedClientMethods"] -= 1
    bad["coverage"]["metrics"]["outgoingClientMethods"] -= 1
    next(m for m in bad["clientMethods"] if m["name"] == "getEvent")["sdk"] = {
        "referenced": False,
        "outgoingRequest": False,
    }
    err = validate_spec(bad)
    check("reject-getEvent-stale-live-coverage", any("getEvent must be fresh" in e for e in err), str(err))

    for label, mutate in (
        (
            "required-channelId",
            lambda method: next(f for f in method["requestFields"] if f["name"] == "channelId").update({"presence": "required"}),
        ),
        (
            "wrong-numFollowing-type",
            lambda method: next(f for f in method["requestFields"] if f["name"] == "numFollowing").update({"type": "s64"}),
        ),
        (
            "wrong-maxTime-type",
            lambda method: next(f for f in method["requestFields"] if f["name"] == "maxTime").update({"type": "u32"}),
        ),
        (
            "wrong-filter-min-version",
            lambda method: next(f for f in method["requestFields"] if f["name"] == "language").update({"minVersion": 4}),
        ),
        (
            "wrong-method-min-version",
            lambda method: method.update({"minVersion": 6}),
        ),
        (
            "partial-request-shape",
            lambda method: method["requestShape"].update({"completeness": "partial"}),
        ),
        (
            "optional-events-reply",
            lambda method: method["replyFields"][0].update({"presence": "optional"}),
        ),
        (
            "wrong-events-reply-type",
            lambda method: method["replyFields"][0].update({"type": "msg"}),
        ),
        (
            "partial-reply-shape",
            lambda method: method["replyShape"].update({"completeness": "partial"}),
        ),
    ):
        bad = json.loads(json.dumps(good_spec))
        mutate(next(m for m in bad["clientMethods"] if m["name"] == "getEvents"))
        err = validate_spec(bad)
        check(
            f"reject-getEvents-{label}",
            any("getEvents" in error for error in err),
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    get_events_bad = next(m for m in bad["clientMethods"] if m["name"] == "getEvents")
    get_events_bad["replyFields"].append(json.loads(json.dumps(get_events_bad["replyFields"][0])))
    get_events_bad["replyFields"][1].update({"name": "extra", "order": 2})
    err = validate_spec(bad)
    check("reject-getEvents-extra-reply-field", any("getEvents reply" in e for e in err), str(err))

    for limitation_id, key, value, expected in (
        (
            GET_EVENTS_MAX_TIME_LIMITATION_ID,
            "summary",
            GET_EVENTS_MAX_TIME_LIMITATION_SUMMARY.replace("s64", "u64"),
            "maxTime limitation",
        ),
        (
            GET_EVENTS_MAX_TIME_LIMITATION_ID,
            "docsUrl",
            EXPECTED_DOCS_URLS["serverToClient"],
            "maxTime limitation",
        ),
        (
            GET_EVENTS_FILTER_LIMITATION_ID,
            "summary",
            GET_EVENTS_FILTER_LIMITATION_SUMMARY.replace("inclusive maximum", "unbounded"),
            "filter-interaction limitation",
        ),
    ):
        bad = json.loads(json.dumps(good_spec))
        next(item for item in bad["docLimitations"] if item["id"] == limitation_id)[key] = value
        err = validate_spec(bad)
        check(
            f"reject-{limitation_id}-{key}",
            any(expected in error for error in err),
            str(err),
        )

    get_dvr_cutpoints_limitation = next(
        item for item in good_spec["docLimitations"]
        if item["id"] == GET_DVR_CUTPOINTS_LIMITATION_ID
    )
    check(
        "getDvrCutpoints-exact-limitation",
        get_dvr_cutpoints_limitation.get("summary")
        == GET_DVR_CUTPOINTS_LIMITATION_SUMMARY
        and get_dvr_cutpoints_limitation.get("authority")
        == "src/htsp_server.c htsp_method_getDvrCutpoints"
        and get_dvr_cutpoints_limitation.get("docsUrl")
        == GET_DVR_CUTPOINTS_DOCS_URL,
    )
    for label, key, replacement in (
        (
            "authority",
            "authority",
            "official Client-to-Server RPC methods page",
        ),
        (
            "url",
            "docsUrl",
            EXPECTED_DOCS_URLS["serverToClient"],
        ),
        (
            "invented-coordinate-origin",
            "summary",
            GET_DVR_CUTPOINTS_LIMITATION_SUMMARY.replace(
                "does not define the millisecond coordinate origin",
                "defines the millisecond coordinate origin as recording-relative",
            ),
        ),
        (
            "invented-sorting",
            "summary",
            GET_DVR_CUTPOINTS_LIMITATION_SUMMARY.replace(
                "traverses the TAILQ in its observed order",
                "sorts the TAILQ chronologically",
            ),
        ),
        (
            "lost-sdk-preservation-policy",
            "summary",
            GET_DVR_CUTPOINTS_LIMITATION_SUMMARY.replace(
                "the SDK preserves those values and order without interpreting them",
                "the SDK normalizes those values and order",
            ),
        ),
    ):
        bad = json.loads(json.dumps(good_spec))
        limitation = next(
            item for item in bad["docLimitations"]
            if item["id"] == GET_DVR_CUTPOINTS_LIMITATION_ID
        )
        limitation[key] = replacement
        err = validate_spec(bad)
        check(
            f"reject-getDvrCutpoints-limitation-{label}",
            any("getDvrCutpoints limitation" in error for error in err),
            str(err),
        )

    for mutation_name, mutate in (
        (
            "client-page-attribution",
            lambda item: item.update({"docsUrl": EXPECTED_DOCS_URLS["clientToServer"]}),
        ),
        (
            "swapped-source-types",
            lambda item: item.update({
                "summary": EVENT_LIMITATION_SUMMARY.replace(
                    "start and stop through htsmsg_add_s64 and isNew through htsmsg_add_u32",
                    "start and stop through htsmsg_add_u32 and isNew through htsmsg_add_s64",
                )
            }),
        ),
    ):
        bad = json.loads(json.dumps(good_spec))
        limitation = next(item for item in bad["docLimitations"] if item["id"] == EVENT_LIMITATION_ID)
        mutate(limitation)
        err = validate_spec(bad)
        check(
            f"reject-event-limitation-{mutation_name}",
            any("event limitation" in e for e in err),
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    get_system_time = next(m for m in bad["clientMethods"] if m["name"] == "getSysTime")
    get_system_time["replyFields"][0]["presence"] = "optional"
    err = validate_spec(bad)
    check("reject-getSysTime-presence-drift", any("getSysTime reply" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    get_system_time = next(m for m in bad["clientMethods"] if m["name"] == "getSysTime")
    get_system_time["replyShape"]["completeness"] = "partial"
    err = validate_spec(bad)
    check("reject-getSysTime-shape-drift", any("getSysTime reply shape" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["docLimitations"] = [
        item for item in bad["docLimitations"] if item["id"] != SYSTEM_TIME_LIMITATION_ID
    ]
    err = validate_spec(bad)
    check("reject-getSysTime-limitation-drift", any("type-mismatch limitation" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["outgoingRequests"].remove("getSysTime")
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] -= 1
    bad["coverage"]["metrics"]["outgoingClientMethods"] -= 1
    next(m for m in bad["clientMethods"] if m["name"] == "getSysTime")["sdk"]["outgoingRequest"] = False
    err = validate_spec(bad)
    check("reject-getSysTime-production-coverage-drift", any("getSysTime must be fresh" in e for e in err), str(err))

    # Schema/governance mutation regressions. Each mutation must be rejected
    # independently rather than relying on generated counts.
    bad = json.loads(json.dumps(good_spec))
    bad["clientMethods"][0]["requestFields"] = [{
        "name": "x", "type": "arbitrary", "direction": "request",
        "presence": "required", "evidence": "x", "confidence": "annotated",
    }]
    err = validate_spec(bad)
    check("reject-arbitrary-wire-type", any("type" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["clientMethods"][0]["requestFields"] = [{
        "name": "x", "type": "u32", "direction": "reply",
        "presence": "required", "evidence": "x", "confidence": "annotated",
    }]
    err = validate_spec(bad)
    check("reject-wrong-field-direction", any("direction" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"][0] = "notAProtocolMethod"
    err = validate_spec(bad)
    check("reject-unknown-coverage-item", any("coverage" in e or "referenced" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["referenced"][1] = bad["coverage"]["clientMethods"]["referenced"][0]
    err = validate_spec(bad)
    check("reject-duplicate-coverage-item", any("duplicate" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["total"] = 38
    err = validate_spec(bad)
    check("reject-false-coverage-total", any("total" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["unreferenced"] = ["api"]
    err = validate_spec(bad)
    check("reject-false-coverage-complement", any("complement" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["scanRoots"] = ["sdk/domain/src/main"]
    err = validate_spec(bad)
    check("reject-wrong-scan-root", any("scanRoots" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["serverMessages"]["unhandled"] = ["autorecEntryAdd"]
    err = validate_spec(bad)
    check("reject-nonempty-unhandled", any("unhandled" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["shapes"]["event"]["target"] = "serverMessage:notReal"
    err = validate_spec(bad)
    check(
        "reject-nonexistent-reference-target",
        any("reference target" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    get_events = next(m for m in bad["clientMethods"] if m["name"] == "getEvents")
    get_events["replyFields"][0]["shapeRef"] = "u32"
    err = validate_spec(bad)
    check(
        "reject-wrong-field-shape-reference",
        any("shapeRef relationship" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    bad["shapes"]["event"]["completeness"] = "opaque"
    err = validate_spec(bad)
    check(
        "reject-false-reference-completeness",
        any("reference completeness" in e for e in err),
        str(err),
    )

    bad = json.loads(json.dumps(good_spec))
    bad["shapes"] = [{"kind": "object"}]
    try:
        err = validate_spec(bad)
    except Exception as exc:  # noqa: BLE001 - regression records unexpected validator exceptions
        check(
            "reject-truthy-non-object-shapes-without-throwing",
            False,
            f"raised {type(exc).__name__}: {exc}",
        )
    else:
        check(
            "reject-truthy-non-object-shapes-without-throwing",
            "shapes must be a non-empty object" in err,
            str(err),
        )

    bad = json.loads(json.dumps(good_spec))
    target_fields = next(m for m in bad["clientMethods"] if len(m["requestFields"]) >= 2)["requestFields"]
    target_fields[0], target_fields[1] = target_fields[1], target_fields[0]
    err = validate_spec(bad)
    check("reject-reordered-fields", any("field order" in e for e in err), str(err))

    # wrong protocol
    bad = json.loads(json.dumps(good_spec))
    bad["upstream"]["htspProtoVersion"] = 43
    err = validate_spec(bad)
    check("reject-wrong-proto", any("htspProtoVersion" in e for e in err), str(err))

    # Independent pin constants must reject coordinated manifest+spec tampering.
    bad = json.loads(json.dumps(good_spec))
    wrong_upstream = {"schemaVersion": 1, **json.loads(json.dumps(bad["upstream"]))}
    for value in (bad["upstream"], wrong_upstream):
        value["repository"] = "https://example.invalid/coordinated-wrong"
        value["revision"] = "0" * 40
        value["files"]["src/htsp_server.c"]["gitBlobSha1"] = "0" * 40
    err = validate_spec(bad, wrong_upstream)
    check("reject-coordinated-wrong-pin", any("immutable pin" in e for e in err), str(err))

    # missing method
    bad = json.loads(json.dumps(good_spec))
    bad["clientMethods"] = bad["clientMethods"][:-1]
    err = validate_spec(bad)
    check("reject-missing-method", any("inventory/order" in e for e in err), str(err))

    # duplicate method
    bad = json.loads(json.dumps(good_spec))
    bad["clientMethods"][-1]["name"] = bad["clientMethods"][-2]["name"]
    err = validate_spec(bad)
    check(
        "reject-duplicate-method",
        any("duplicate" in e or "inventory/order" in e for e in err),
        str(err),
    )

    # reordered method
    bad = json.loads(json.dumps(good_spec))
    bad["clientMethods"][0], bad["clientMethods"][1] = (
        bad["clientMethods"][1],
        bad["clientMethods"][0],
    )
    err = validate_spec(bad)
    check("reject-reordered-method", any("inventory/order" in e for e in err), str(err))

    # False completeness: invent an outgoing count beyond the accepted metric.
    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] = 40
    bad["coverage"]["metrics"]["outgoingClientMethods"] = 40
    err = validate_spec(bad)
    check(
        "reject-false-all-called",
        any(
            "outgoing client methods == 39" in e
            or "outgoingRequestCount does not match" in e
            for e in err
        ),
        str(err),
    )

    # malformed unknown marked mechanical
    bad = json.loads(json.dumps(good_spec))
    bad["clientMethods"][0]["requestFields"] = [
        {
            "name": "x",
            "type": "unknown",
            "direction": "request",
            "evidence": "x",
            "confidence": "mechanical",
        }
    ]
    err = validate_spec(bad)
    check(
        "reject-unknown-as-mechanical",
        any("unknown type cannot be mechanical" in e for e in err),
        str(err),
    )

    # stale matrix
    with tempfile.TemporaryDirectory(prefix="htsp-report-selftest-") as tmp:
        tmp_path = Path(tmp)
        matrix = tmp_path / "HTSP_METHOD_MATRIX.md"
        matrix.write_text("stale\n", encoding="utf-8")
        err = check_matrix(good_spec, matrix)
        check("reject-stale-matrix", any("stale" in e for e in err), str(err))

        text = write_matrix(good_spec, matrix)
        err = check_matrix(good_spec, matrix)
        check("fresh-matrix", err == [], str(err))
        check(
            "matrix-exactly-one-terminal-newline",
            text.endswith("\n") and not text.endswith("\n\n"),
        )
        check("matrix-lf-only", "\r" not in text)
        # deterministic regeneration
        text2 = render_matrix(good_spec)
        check("matrix-deterministic", text == text2)

    if failures:
        raise AssertionError("report self-test failures:\n- " + "\n- ".join(failures))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--write",
        action="store_true",
        help=f"Regenerate {MATRIX_PATH.name} from {SPEC_PATH.name}",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Validate spec and fail when the matrix drifts",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run deterministic synthetic self-tests",
    )
    parser.add_argument(
        "--spec",
        type=Path,
        default=SPEC_PATH,
        help="Path to htsp_spec.json",
    )
    parser.add_argument(
        "--matrix",
        type=Path,
        default=MATRIX_PATH,
        help="Path to HTSP_METHOD_MATRIX.md",
    )
    args = parser.parse_args(argv)

    try:
        if args.self_test:
            self_test()
            print("report.py self-test passed")
            return 0

        if not args.check and not args.write:
            args.check = True

        upstream = load_json(UPSTREAM_PATH) if UPSTREAM_PATH.is_file() else None
        if not args.spec.is_file():
            print(f"error: missing {args.spec}", file=sys.stderr)
            return 1
        spec = load_json(args.spec)
        errors = validate_spec(spec, upstream)

        if args.write:
            write_matrix(spec, args.matrix)
            print(f"wrote {args.matrix}")

        if args.check:
            errors.extend(check_matrix(spec, args.matrix))

        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        if errors:
            return 1

        print(
            "HTSP protocol artifacts OK: "
            f"{len(EXPECTED_CLIENT_METHODS)} methods, "
            f"{len(EXPECTED_SERVER_MESSAGES)} messages"
        )
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI boundary
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
