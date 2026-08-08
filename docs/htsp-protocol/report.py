#!/usr/bin/env python3
"""Validate htsp_spec.json and generate HTSP_METHOD_MATRIX.md deterministically."""

from __future__ import annotations

import argparse
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

EXPECTED_UNHANDLED_MESSAGES = (
    "autorecEntryAdd",
    "autorecEntryUpdate",
    "autorecEntryDelete",
    "timerecEntryAdd",
    "timerecEntryUpdate",
    "timerecEntryDelete",
    "descrambleInfo",
)

EXPECTED_FIELD_SHAPE_REFS: tuple[tuple[str, str, str, str, str], ...] = (
    ("clientMethod", "getEvents", "replyFields", "events", "event"),
    ("clientMethod", "epgQuery", "replyFields", "eventIds", "u32"),
    ("clientMethod", "epgQuery", "replyFields", "events", "event"),
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
}
EXPECTED_DOCS_URLS = {
    "communication": "https://docs.tvheadend.org/documentation/development/htsp/communication",
    "clientToServer": "https://docs.tvheadend.org/documentation/development/htsp/client-to-server-rpc-methods",
    "serverToClient": "https://docs.tvheadend.org/documentation/development/htsp/server-to-client-methods",
    "protocolChanges": "https://docs.tvheadend.org/documentation/development/htsp/protocol-changes",
}
EXPECTED_SCAN_ROOTS = ["sdk/htsp/src/main", "sdk/playback-media3/src/main"]
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
        errors.append(f"{label}.files must be the exact ordered three-file key set")
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
            if shape.get("kind") not in {"object", "reference", "scalar"}:
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
        allowed_method_keys = required_method_keys | {"docStatus", "notes"}
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
    mux = {f.get("name"): f for f in message_map.get("muxpkt", {}).get("fields", [])}
    if mux.get("payload", {}).get("type") != "bin" or ({"packets", "bytes", "Bdrops", "Pdrops", "Idrops"} & set(mux)):
        errors.append("muxpkt must own binary payload and no queue counters")
    queue = {f.get("name"): f for f in message_map.get("queueStatus", {}).get("fields", [])}
    if not {"packets", "bytes", "Bdrops", "Pdrops", "Idrops"} <= set(queue) or ({"stream", "dts", "pts", "duration", "payload"} & set(queue)):
        errors.append("queueStatus must own counters/drop stats and no mux fields")
    cutpoints = {f.get("name"): f for f in method_map.get("getDvrCutpoints", {}).get("replyFields", [])}
    if cutpoints.get("cutpoints", {}).get("shapeRef") != "cutpoint":
        errors.append("getDvrCutpoints.cutpoints must reference cutpoint shape")
    cut_shape = shapes.get("cutpoint") or {}
    if [(f.get("name"), f.get("type")) for f in cut_shape.get("fields", [])] != [("start", "u32"), ("end", "u32"), ("type", "u32")]:
        errors.append("cutpoint shape must be exact {start,end,type}")
    if method_map.get("getEvents", {}).get("replyShape", {}).get("kind") != "fields" or [f.get("name") for f in method_map.get("getEvents", {}).get("replyFields", [])] != ["events"]:
        errors.append("getEvents reply must contain only nested events")
    if method_map.get("epgQuery", {}).get("replyShape", {}).get("kind") != "alternative" or [f.get("name") for f in method_map.get("epgQuery", {}).get("replyFields", [])] != ["eventIds", "events"]:
        errors.append("epgQuery reply must model eventIds/events alternatives")
    if method_map.get("getEpgObject", {}).get("replyShape", {}).get("kind") != "dynamic":
        errors.append("getEpgObject reply must be explicitly dynamic/opaque")

    coverage = spec.get("coverage") or {}
    client_cov = coverage.get("clientMethods") or {}
    server_cov = coverage.get("serverMessages") or {}
    metrics = coverage.get("metrics") or {}

    ref_count = client_cov.get("referencedCount")
    out_count = client_cov.get("outgoingRequestCount")
    handled_count = server_cov.get("handledCount")

    if coverage.get("scanRoots") != EXPECTED_SCAN_ROOTS:
        errors.append(f"coverage.scanRoots must equal {EXPECTED_SCAN_ROOTS!r}")
    if client_cov.get("total") != 39:
        errors.append("coverage clientMethods.total must be 39")
    if server_cov.get("total") != 30:
        errors.append("coverage serverMessages.total must be 30")

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

    if out_count is not None and ref_count is not None and out_count > ref_count:
        errors.append("outgoingRequestCount cannot exceed referencedCount")

    # Current repository acceptance targets (exact-literal metric).
    if ref_count not in (None, 21):
        errors.append(
            f"expected referenced client methods == 21 under current metric, got {ref_count}"
        )
    if out_count not in (None, 20):
        errors.append(
            f"expected outgoing client methods == 20 under current metric, got {out_count}"
        )
    if handled_count not in (None, 23):
        errors.append(
            f"expected handled server messages == 23 under current metric, got {handled_count}"
        )

    unhandled = list(server_cov.get("unhandled") or [])
    if unhandled != list(EXPECTED_UNHANDLED_MESSAGES):
        errors.append(
            "unhandled server messages mismatch/order: "
            f"got {unhandled!r} expected {list(EXPECTED_UNHANDLED_MESSAGES)!r}"
        )

    if "subscriptionSkip" not in referenced_list or "subscriptionSkip" in outgoing_list:
        errors.append("subscriptionSkip must be referenced inbound but not outgoing")
    if "subscriptionSeek" not in outgoing_list:
        errors.append("subscriptionSeek must be the outgoing seek synonym")

    # sdk flags must match coverage lists
    ref_set = set(client_cov.get("referenced") or [])
    out_set = set(client_cov.get("outgoingRequests") or [])
    handled_set = set(server_cov.get("handled") or [])
    for method in methods:
        if not isinstance(method, dict):
            continue
        sdk = method.get("sdk") or {}
        name = method.get("name")
        if set(sdk) != {"referenced", "outgoingRequest"} or not all(isinstance(v, bool) for v in sdk.values()):
            errors.append(f"{name}: sdk method flags must be exact booleans")
        if bool(sdk.get("referenced")) != (name in ref_set):
            errors.append(f"{name}: sdk.referenced flag disagrees with coverage")
        if bool(sdk.get("outgoingRequest")) != (name in out_set):
            errors.append(f"{name}: sdk.outgoingRequest flag disagrees with coverage")
    for message in messages:
        if not isinstance(message, dict):
            continue
        sdk = message.get("sdk") or {}
        name = message.get("name")
        if set(sdk) != {"handled"} or not isinstance(sdk.get("handled"), bool):
            errors.append(f"{name}: sdk message flags must be exact booleans")
        if bool(sdk.get("handled")) != (name in handled_set):
            errors.append(f"{name}: sdk.handled flag disagrees with coverage")

    if not isinstance(spec.get("docLimitations"), list) or not spec.get("docLimitations"):
        errors.append("docLimitations must be a non-empty list")

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
    allowed = {"kind", "completeness", "evidence", "alternatives"}
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
        "- Distinguish **referenced** from **outgoing**: a name can appear "
        "because an inbound handler mentions it (for example `subscriptionSkip`) "
        "while the client sends a synonym (`subscriptionSeek`)."
    )
    lines.append(
        "- Never claim methods are implemented/called merely because they are referenced."
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
    lines.append("## Client → server methods")
    lines.append("")
    lines.append(
        "| # | Method | Access mask | Min ver | SDK ref | SDK out | Request fields | Reply fields |"
    )
    lines.append("|---:|---|---|---:|:---:|:---:|---|---|")
    for idx, method in enumerate(spec["clientMethods"], start=1):
        sdk = method.get("sdk") or {}
        min_ver = method.get("minVersion")
        min_s = "—" if min_ver is None else str(min_ver)
        conf = method.get("minVersionConfidence")
        if conf and conf not in {"mechanical", "mechanical+annotated"} and min_ver is not None:
            min_s = f"{min_s} ({conf})"
        lines.append(
            "| {idx} | `{name}` | `{access}` | {minv} | {ref} | {out} | {req} | {rep} |".format(
                idx=idx,
                name=method["name"],
                access=method.get("accessMask", ""),
                minv=min_s,
                ref="yes" if sdk.get("referenced") else "",
                out="yes" if sdk.get("outgoingRequest") else "",
                req=_fmt_shape_fields(method.get("requestFields") or [], method.get("requestShape") or {}),
                rep=_fmt_shape_fields(method.get("replyFields") or [], method.get("replyShape") or {}),
            )
        )
    lines.append("")
    lines.append("## Server → client messages")
    lines.append("")
    lines.append(
        "| # | Message | Min ver | SDK handled | Fields |"
    )
    lines.append("|---:|---|---:|:---:|---|")
    for idx, message in enumerate(spec["serverMessages"], start=1):
        sdk = message.get("sdk") or {}
        min_ver = message.get("minVersion")
        min_s = "—" if min_ver is None else str(min_ver)
        lines.append(
            "| {idx} | `{name}` | {minv} | {handled} | {fields} |".format(
                idx=idx,
                name=message["name"],
                minv=min_s,
                handled="yes" if sdk.get("handled") else "",
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
                "referencedCount": 21,
                "outgoingRequests": [],
                "outgoingRequestCount": 20,
                "unreferenced": [],
            },
            "serverMessages": {
                "total": 30,
                "handled": [],
                "handledCount": 23,
                "unhandled": list(EXPECTED_UNHANDLED_MESSAGES),
            },
            "metrics": {
                "referencedClientMethods": 21,
                "outgoingClientMethods": 20,
                "handledServerMessages": 23,
            },
        },
        "docLimitations": [{"id": "x", "summary": "y", "authority": "z"}],
        "pythonDemo": {"htspProtoVersion": 33, "methodsCovered": ["hello"]},
    }

    # Populate inventories with minimal valid entries.
    ref_names = list(EXPECTED_CLIENT_METHODS[:20]) + ["subscriptionSkip"]
    # 21 referenced including subscriptionSkip; 20 outgoing without it.
    out_names = list(EXPECTED_CLIENT_METHODS[:20])
    # Ensure subscriptionSkip is omitted from the outgoing fixture.
    out_names = [n for n in EXPECTED_CLIENT_METHODS if n != "subscriptionSkip"][:20]
    ref_names = out_names + ["subscriptionSkip"]
    handled = [n for n in EXPECTED_SERVER_MESSAGES if n not in EXPECTED_UNHANDLED_MESSAGES]

    good_spec["coverage"]["clientMethods"]["referenced"] = ref_names
    good_spec["coverage"]["clientMethods"]["referencedCount"] = 21
    good_spec["coverage"]["clientMethods"]["outgoingRequests"] = out_names
    good_spec["coverage"]["clientMethods"]["outgoingRequestCount"] = 20
    good_spec["coverage"]["serverMessages"]["handled"] = handled
    good_spec["coverage"]["serverMessages"]["handledCount"] = 23

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

    # The committed generated artifact is the complete valid-schema fixture;
    # every following test mutates one independently meaningful contract.
    good_spec = load_json(SPEC_PATH)
    ref_names = list(good_spec["coverage"]["clientMethods"]["referenced"])
    out_names = list(good_spec["coverage"]["clientMethods"]["outgoingRequests"])

    errors = validate_spec(good_spec)
    check("good-spec", errors == [], str(errors))

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
    bad["coverage"]["clientMethods"]["unreferenced"] = bad["coverage"]["clientMethods"]["unreferenced"][1:]
    err = validate_spec(bad)
    check("reject-false-coverage-complement", any("complement" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["scanRoots"] = ["sdk/domain/src/main"]
    err = validate_spec(bad)
    check("reject-wrong-scan-root", any("scanRoots" in e for e in err), str(err))

    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["serverMessages"]["unhandled"] = list(reversed(EXPECTED_UNHANDLED_MESSAGES))
    err = validate_spec(bad)
    check("reject-reordered-unhandled", any("unhandled" in e for e in err), str(err))

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
    bad["shapes"]["event"]["completeness"] = "complete"
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

    # False 21-called: outgoing forced to equal referenced without distinction.
    bad = json.loads(json.dumps(good_spec))
    bad["coverage"]["clientMethods"]["outgoingRequests"] = list(ref_names)
    bad["coverage"]["clientMethods"]["outgoingRequestCount"] = 21
    bad["coverage"]["metrics"]["outgoingClientMethods"] = 21
    for method in bad["clientMethods"]:
        method["sdk"]["outgoingRequest"] = method["name"] in ref_names
    err = validate_spec(bad)
    check(
        "reject-false-21-called",
        any("outgoing client methods == 20" in e for e in err),
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
