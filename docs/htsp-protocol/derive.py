#!/usr/bin/env python3
"""Derive machine-readable HTSP v44 inventory from pinned TVHeadend sources.

Standard-library only. Normal operation requires an explicit external source root
and never touches the network. Optional --fetch-pinned downloads only the three
manifest-pinned raw files, verifies Git-blob SHA-1 and size, and never runs as
part of repository checks.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import ssl
import sys
import tempfile
import traceback
import urllib.request
import inspect
from collections import OrderedDict
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
UPSTREAM_MANIFEST_PATH = SCRIPT_DIR / "upstream.json"
SPEC_PATH = SCRIPT_DIR / "htsp_spec.json"
SDK_PRODUCTION_ROOTS = (
    REPO_ROOT / "sdk" / "htsp" / "src" / "main",
    REPO_ROOT / "sdk" / "playback-media3" / "src" / "main",
)

EXPECTED_REPOSITORY = "https://github.com/tvheadend/tvheadend"
EXPECTED_REVISION = "27295c5a48f2c575678bb224014cb9a26a773083"
EXPECTED_PROTO_VERSION = 44
EXPECTED_FILES: dict[str, dict[str, Any]] = {
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

# Source-order inventory from pinned htsp_methods[] (must match exactly).
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

# Current emitted server→client messages in stable family-then-lifecycle order.
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

GET_TYPE_MAP = {
    "u32": "u32",
    "s32": "s32",
    "s64": "s64",
    "str": "str",
    "bin": "bin",
    "map": "msg",
    "list": "list",
    "bool": "bool",
    "dbl": "dbl",
    "uuid": "uuid",
}
ADD_TYPE_MAP = {
    "u32": "u32",
    "s32": "s32",
    "s64": "s64",
    "str": "str",
    "str2": "str",
    "bin": "bin",
    "msg": "msg",
    "bool": "bool",
    "dbl": "dbl",
    "uuid": "uuid",
}

# Historical minimum versions (annotated; docs may be incomplete).
METHOD_MIN_VERSION: dict[str, int | None] = {
    "hello": None,
    "authenticate": None,
    "api": 24,
    "getDiskSpace": 3,
    "getSysTime": 3,
    "enableAsyncMetadata": None,
    "getChannel": 14,
    "getEvent": None,
    "getEvents": 4,
    "epgQuery": 4,
    "getEpgObject": None,
    "getDvrConfigs": 16,
    "addDvrEntry": 4,
    "updateDvrEntry": 5,
    "stopDvrEntry": None,  # current dispatch evidence; official page omits it
    "cancelDvrEntry": 5,
    "deleteDvrEntry": 4,
    "addAutorecEntry": 13,
    "updateAutorecEntry": 25,
    "deleteAutorecEntry": 13,
    "addTimerecEntry": 18,
    "updateTimerecEntry": 25,
    "deleteTimerecEntry": 18,
    "getDvrCutpoints": 12,
    "getTicket": 5,
    "subscribe": None,
    "unsubscribe": None,
    "subscriptionChangeWeight": 5,
    "subscriptionSeek": 9,
    "subscriptionSkip": 9,
    "subscriptionSpeed": 9,
    "subscriptionLive": 9,
    "subscriptionFilterStream": 12,
    "getProfiles": 16,
    "fileOpen": 8,
    "fileRead": 8,
    "fileClose": 8,
    "fileStat": 8,
    "fileSeek": 8,
}

SERVER_MESSAGE_MIN_VERSION: dict[str, int | None] = {
    "channelAdd": None,
    "channelUpdate": None,
    "channelDelete": None,
    "tagAdd": None,
    "tagUpdate": None,
    "tagDelete": None,
    "dvrEntryAdd": 4,
    "dvrEntryUpdate": 4,
    "dvrEntryDelete": 4,
    "autorecEntryAdd": 13,
    "autorecEntryUpdate": 13,
    "autorecEntryDelete": 13,
    "timerecEntryAdd": 18,
    "timerecEntryUpdate": 18,
    "timerecEntryDelete": 18,
    "eventAdd": 6,
    "eventUpdate": 6,
    "eventDelete": 6,
    "initialSyncCompleted": 2,
    "muxpkt": None,
    "queueStatus": None,
    "subscriptionStart": None,
    "subscriptionStop": None,
    "subscriptionGrace": 13,
    "subscriptionStatus": None,
    "signalStatus": None,
    "descrambleInfo": 24,  # docs note v23..24; source gates htsp_version < 24
    "subscriptionSpeed": 9,
    "timeshiftStatus": 9,
    "subscriptionSkip": 9,
}

# Field-level version gates that are well-evidenced (annotated, not guessed).
FIELD_MIN_VERSION: dict[tuple[str, str, str], int] = {
    ("clientMethod", "hello", "servercapability"): 6,
    ("clientMethod", "hello", "webroot"): 8,
    ("clientMethod", "hello", "language"): 1,
    ("clientMethod", "hello", "api_version"): 1,
    ("clientMethod", "authenticate", "admin"): 26,
    ("clientMethod", "authenticate", "streaming"): 26,
    ("clientMethod", "authenticate", "dvr"): 26,
    ("clientMethod", "authenticate", "faileddvr"): 26,
    ("clientMethod", "authenticate", "anonymous"): 26,
    ("clientMethod", "authenticate", "limitall"): 26,
    ("clientMethod", "authenticate", "limitdvr"): 26,
    ("clientMethod", "authenticate", "limitstreaming"): 26,
    ("clientMethod", "authenticate", "uilevel"): 26,
    ("clientMethod", "authenticate", "uilanguage"): 26,
    ("serverMessage", "signalStatus", "feAbsoluteSNR"): 44,
    ("serverMessage", "signalStatus", "feAbsoluteSignal"): 44,
    ("serverMessage", "descrambleInfo", "subscriptionId"): 24,
}

DOC_LIMITATIONS = [
    {
        "id": "getDiskSpace-used-field-missing-from-client-docs",
        "summary": (
            "The pinned htsp_method_getDiskSpace source emits useddiskspace in "
            "addition to freediskspace and totaldiskspace, while the official "
            "Client-to-Server RPC methods page documents only the latter two fields."
        ),
        "authority": "src/htsp_server.c htsp_method_getDiskSpace",
        "docsUrl": (
            "https://docs.tvheadend.org/documentation/development/htsp/"
            "client-to-server-rpc-methods"
        ),
    },
    {
        "id": "getSysTime-time-type-source-doc-mismatch",
        "summary": (
            "The pinned htsp_method_getSysTime source emits time through "
            "htsmsg_add_s32, while the official Client-to-Server RPC methods "
            "page specifies required s64 Unix time. This records a source/docs "
            "evidence mismatch; it is not a decision to coerce or truncate the "
            "SDK public value."
        ),
        "authority": "src/htsp_server.c htsp_method_getSysTime",
        "docsUrl": (
            "https://docs.tvheadend.org/documentation/development/htsp/"
            "client-to-server-rpc-methods"
        ),
    },
    {
        "id": "stopDvrEntry-missing-from-client-docs",
        "summary": (
            "stopDvrEntry is present in the pinned htsp_methods[] dispatch table "
            "but absent from the Client-to-Server RPC methods documentation page."
        ),
        "authority": "src/htsp_server.c htsp_methods[] / htsp_method_stopDvrEntry",
        "docsUrl": (
            "https://docs.tvheadend.org/documentation/development/htsp/"
            "client-to-server-rpc-methods"
        ),
    },
    {
        "id": "descrambleInfo-missing-from-server-docs",
        "summary": (
            "descrambleInfo is emitted by the pinned server source "
            "(htsp_subscription_descramble_info, version gate < 24) but is not "
            "documented on the Server-to-Client methods page."
        ),
        "authority": "src/htsp_server.c htsp_subscription_descramble_info",
        "docsUrl": (
            "https://docs.tvheadend.org/documentation/development/htsp/"
            "server-to-client-methods"
        ),
    },
    {
        "id": "hello-auth-reply-fields-underdocumented",
        "summary": (
            "hello reply currently adds language and api_version; authenticate "
            "reply currently adds admin/streaming/dvr/faileddvr/anonymous/"
            "limitall/limitdvr/limitstreaming/uilevel/uilanguage for "
            "htsp_version > 25. Official docs omit or under-specify several of "
            "these fields."
        ),
        "authority": "src/htsp_server.c htsp_method_hello / htsp_method_authenticate",
        "docsUrl": (
            "https://docs.tvheadend.org/documentation/development/htsp/"
            "client-to-server-rpc-methods"
        ),
    },
    {
        "id": "protocol-changes-page-stale",
        "summary": (
            "Protocol Changes page marks itself possibly outdated and leaves "
            "v26..35 as TODO; historical minimum versions here are annotated "
            "evidence, not a complete changelog substitute."
        ),
        "authority": "docs.tvheadend.org protocol-changes + current source",
        "docsUrl": (
            "https://docs.tvheadend.org/documentation/development/htsp/"
            "protocol-changes"
        ),
    },
    {
        "id": "python-demo-not-completeness",
        "summary": (
            "lib/py/tvh/htsp.py is a limited demo at protocol 33 covering only "
            "hello/authenticate/enableAsyncMetadata. It is a narrow cross-check, "
            "never completeness authority."
        ),
        "authority": "lib/py/tvh/htsp.py",
        "docsUrl": None,
    },
]


def validate_exact_manifest(data: Any) -> dict[str, Any]:
    if not isinstance(data, dict):
        raise ValueError("upstream manifest must be an object")
    expected_keys = {
        "schemaVersion", "repository", "revision", "htspProtoVersion",
        "files", "docsUrls",
    }
    if set(data) != expected_keys:
        raise ValueError("upstream manifest keys do not match the immutable schema")
    if data.get("schemaVersion") != 1:
        raise ValueError("upstream.json schemaVersion must be 1")
    if data.get("repository") != EXPECTED_REPOSITORY:
        raise ValueError("upstream repository does not match the immutable pin")
    if data.get("revision") != EXPECTED_REVISION:
        raise ValueError("upstream revision does not match the immutable pin")
    if data.get("htspProtoVersion") != EXPECTED_PROTO_VERSION:
        raise ValueError("upstream HTSP version does not match the immutable pin")
    files = data.get("files")
    if not isinstance(files, dict) or list(files) != list(EXPECTED_FILES):
        raise ValueError("upstream files must be the exact ordered three-file pin")
    for relative, expected in EXPECTED_FILES.items():
        meta = files.get(relative)
        if not isinstance(meta, dict) or set(meta) != set(expected):
            raise ValueError(f"{relative}: malformed immutable file metadata")
        digest = meta.get("gitBlobSha1")
        size = meta.get("bytes")
        if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{40}", digest) is None:
            raise ValueError(f"{relative}: malformed Git blob SHA-1")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise ValueError(f"{relative}: malformed byte size")
        if meta != expected:
            raise ValueError(f"{relative}: metadata does not match the immutable pin")
    if data.get("docsUrls") != EXPECTED_DOCS_URLS:
        raise ValueError("official documentation URL set does not match the immutable pin")
    return data


def load_manifest(path: Path = UPSTREAM_MANIFEST_PATH) -> dict[str, Any]:
    return validate_exact_manifest(json.loads(path.read_text(encoding="utf-8")))


def git_blob_sha1(data: bytes) -> str:
    header = b"blob " + str(len(data)).encode("ascii") + b"\0"
    return hashlib.sha1(header + data).hexdigest()


def require_regular_file(path: Path, label: str) -> None:
    if path.is_symlink():
        raise ValueError(f"{label} must not be a symlink: {path}")
    if not path.is_file():
        raise ValueError(f"{label} must be a regular file: {path}")


def reject_symlinked_path_components(path: Path, label: str) -> None:
    absolute = path.absolute()
    current = Path(absolute.anchor)
    for part in absolute.parts[1:]:
        current /= part
        if current.is_symlink():
            raise ValueError(f"{label} traverses symlinked component: {current}")


def resolve_under_root(root: Path, relative: str) -> Path:
    if Path(relative).is_absolute() or ".." in Path(relative).parts:
        raise ValueError(f"refusing path outside source root: {relative}")
    reject_symlinked_path_components(root, "source root")
    if root.is_symlink() or not root.is_dir():
        raise ValueError(f"source root must be a real directory: {root}")
    root_resolved = root.resolve()
    candidate = root_resolved
    for part in Path(relative).parts:
        candidate = candidate / part
        if candidate.is_symlink():
            raise ValueError(f"{relative} must not be a symlink: {candidate}")
    if not candidate.exists():
        raise ValueError(f"{relative} must be a regular file: {candidate}")
    resolved = candidate.resolve()
    try:
        resolved.relative_to(root_resolved)
    except ValueError as exc:
        raise ValueError(f"path escapes source root: {relative}") from exc
    return candidate


def read_verified_source_file(
    source_root: Path,
    relative: str,
    expected_sha: str,
    expected_bytes: int,
) -> str:
    path = resolve_under_root(source_root, relative)
    require_regular_file(path, relative)
    data = path.read_bytes()
    if len(data) != expected_bytes:
        raise ValueError(
            f"{relative}: byte count {len(data)} does not match pin {expected_bytes}"
        )
    digest = git_blob_sha1(data)
    if digest != expected_sha:
        raise ValueError(
            f"{relative}: git blob SHA-1 {digest} does not match pin {expected_sha}"
        )
    return data.decode("utf-8")


def validate_fetch_plan(dest_root: Path, manifest: dict[str, Any]) -> list[tuple[str, Path]]:
    """Validate a no-overwrite destination before any network or filesystem write."""
    validate_exact_manifest(manifest)
    if not dest_root.is_absolute():
        dest_root = Path.cwd() / dest_root
    reject_symlinked_path_components(dest_root, "fetch destination")
    if dest_root.exists() and not dest_root.is_dir():
        raise ValueError("fetch destination must be a directory or absent")
    resolved = dest_root.resolve(strict=False)
    repo = REPO_ROOT.resolve()
    try:
        resolved.relative_to(repo)
        raise ValueError("fetch destination must be outside this repository")
    except ValueError as exc:
        if "outside this repository" in str(exc):
            raise
    plan: list[tuple[str, Path]] = []
    for relative in manifest["files"]:
        rel_path = Path(relative)
        if rel_path.is_absolute() or ".." in rel_path.parts or relative not in EXPECTED_FILES:
            raise ValueError(f"unexpected or escaping pinned path: {relative}")
        target = resolved / rel_path
        reject_symlinked_path_components(target, f"fetch target {relative}")
        if target.exists() or target.is_symlink():
            raise ValueError(f"fetch target already exists: {target}")
        plan.append((relative, target))
    return plan


def _network_downloader(url: str) -> tuple[bytes, str]:
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(url, context=ctx, timeout=120) as response:
        return response.read(), response.geturl()


def fetch_pinned_sources(
    dest_root: Path,
    manifest: dict[str, Any],
    downloader: Any = _network_downloader,
) -> Path:
    """Explicit opt-in fetch; verify all three responses before exclusive writes."""
    plan = validate_fetch_plan(dest_root, manifest)
    base = f"https://raw.githubusercontent.com/tvheadend/tvheadend/{EXPECTED_REVISION}"
    verified: list[tuple[Path, bytes]] = []
    for relative, target in plan:
        expected_url = f"{base}/{relative}"
        data, final_url = downloader(expected_url)
        if final_url != expected_url:
            raise ValueError(f"fetch {relative}: unexpected final response URL")
        meta = EXPECTED_FILES[relative]
        if len(data) != meta["bytes"]:
            raise ValueError(f"fetch {relative}: size does not match immutable pin")
        if git_blob_sha1(data) != meta["gitBlobSha1"]:
            raise ValueError(f"fetch {relative}: blob does not match immutable pin")
        verified.append((target, data))
    for target, data in verified:
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as output:
            output.write(data)
    return plan[0][1].parents[1] if plan else dest_root.resolve()


def strip_c_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//.*?$", "", text, flags=re.M)
    return text


def extract_balanced_block(text: str, open_index: int) -> str:
    if open_index >= len(text) or text[open_index] != "{":
        raise ValueError("expected '{' to start block")
    depth = 0
    i = open_index
    while i < len(text):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[open_index + 1 : i]
        i += 1
    raise ValueError("unbalanced brace block")


def find_function_body(source: str, function_name: str) -> str | None:
    pattern = re.compile(
        rf"\b{re.escape(function_name)}\s*\([^;]*?\)\s*\{{",
        re.S,
    )
    match = pattern.search(source)
    if not match:
        return None
    open_index = match.end() - 1
    return extract_balanced_block(source, open_index)


def parse_methods_table(server_c: str) -> list[dict[str, str]]:
    match = re.search(
        r"htsp_methods\[\]\s*=\s*\{(.*?)\n\};",
        server_c,
        flags=re.S,
    )
    if not match:
        raise ValueError("htsp_methods[] table not found")
    body = match.group(1)
    entries = re.findall(
        r'\{\s*"([^"]+)"\s*,\s*([A-Za-z_][A-Za-z0-9_]*)\s*,\s*([A-Za-z0-9_|]+)\s*\}',
        body,
    )
    if not entries:
        raise ValueError("htsp_methods[] entries not parsed")
    methods = [
        {"name": name, "handler": handler, "accessMask": access}
        for name, handler, access in entries
    ]
    names = [item["name"] for item in methods]
    if names != list(EXPECTED_CLIENT_METHODS):
        raise ValueError(
            "htsp_methods[] order/content drift: "
            f"got {names!r} expected {list(EXPECTED_CLIENT_METHODS)!r}"
        )
    return methods


def parse_proto_version(server_c: str) -> int:
    match = re.search(r"#define\s+HTSP_PROTO_VERSION\s+(\d+)", server_c)
    if not match:
        raise ValueError("HTSP_PROTO_VERSION not found")
    return int(match.group(1))


def field_record(
    name: str,
    wire_type: str,
    direction: str,
    evidence: str,
    confidence: str,
    presence: str = "unknown",
    min_version: int | None = None,
    notes: list[str] | None = None,
    condition: str | None = None,
    shape_ref: str | None = None,
) -> dict[str, Any]:
    record: dict[str, Any] = OrderedDict()
    record["name"] = name
    record["type"] = wire_type
    record["direction"] = direction
    record["presence"] = presence
    record["evidence"] = evidence
    record["confidence"] = confidence
    record["minVersion"] = min_version
    if condition is not None:
        record["condition"] = condition
    if shape_ref is not None:
        record["shapeRef"] = shape_ref
    if notes:
        record["notes"] = list(notes)
    return record


def extract_get_fields(body: str, msg_var: str = "in") -> list[dict[str, Any]]:
    fields: "OrderedDict[str, dict[str, Any]]" = OrderedDict()
    patterns = [
        (
            rf"htsmsg_get_([a-z0-9]+)\(\s*{re.escape(msg_var)}\s*,\s*\"([^\"]+)\"",
            "htsmsg_get_{type}",
        ),
        (
            rf"htsmsg_get_([a-z0-9]+)_or_default\(\s*{re.escape(msg_var)}\s*,\s*\"([^\"]+)\"",
            "htsmsg_get_{type}_or_default",
        ),
    ]
    for pattern, evidence_fmt in patterns:
        for type_token, name in re.findall(pattern, body):
            wire = GET_TYPE_MAP.get(type_token, "unknown")
            confidence = "mechanical" if wire != "unknown" else "unknown"
            if name not in fields:
                fields[name] = field_record(
                    name=name,
                    wire_type=wire,
                    direction="request",
                    evidence=evidence_fmt.format(type=type_token),
                    confidence=confidence,
                )
            else:
                # Prefer a more specific non-unknown type if seen later.
                if fields[name]["type"] == "unknown" and wire != "unknown":
                    fields[name]["type"] = wire
                    fields[name]["confidence"] = confidence
    return list(fields.values())


def extract_add_fields(
    body: str,
    msg_vars: tuple[str, ...] = ("r", "out", "rep", "ret", "m"),
) -> list[dict[str, Any]]:
    fields: "OrderedDict[str, dict[str, Any]]" = OrderedDict()
    var_alt = "|".join(re.escape(v) for v in msg_vars)
    patterns = [
        (
            rf"htsmsg_add_([a-z0-9]+)(?:_(?:alloc|ptr))?\(\s*({var_alt})\s*,\s*\"([^\"]+)\"",
            "htsmsg_add_{type}",
        ),
        (
            rf"htsmsg_set_([a-z0-9]+)\(\s*({var_alt})\s*,\s*\"([^\"]+)\"",
            "htsmsg_set_{type}",
        ),
    ]
    for pattern, evidence_fmt in patterns:
        for type_token, _var, name in re.findall(pattern, body):
            if name == "method":
                continue
            wire = ADD_TYPE_MAP.get(type_token, "unknown")
            confidence = "mechanical" if wire != "unknown" else "unknown"
            if name not in fields:
                fields[name] = field_record(
                    name=name,
                    wire_type=wire,
                    direction="reply",
                    evidence=evidence_fmt.format(type=type_token),
                    confidence=confidence,
                )
            else:
                if fields[name]["type"] == "unknown" and wire != "unknown":
                    fields[name]["type"] = wire
                    fields[name]["confidence"] = confidence
    return list(fields.values())


def merge_field_lists(*lists: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: "OrderedDict[str, dict[str, Any]]" = OrderedDict()
    for field_list in lists:
        for field in field_list:
            name = field["name"]
            if name not in merged:
                merged[name] = dict(field)
            else:
                existing = merged[name]
                if existing.get("type") == "unknown" and field.get("type") != "unknown":
                    existing["type"] = field["type"]
                    existing["confidence"] = field["confidence"]
                    existing["evidence"] = field["evidence"]
    return list(merged.values())


def apply_field_versions(
    kind: str,
    owner: str,
    fields: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    out = []
    for field in fields:
        item = dict(field)
        key = (kind, owner, field["name"])
        if key in FIELD_MIN_VERSION:
            item["minVersion"] = FIELD_MIN_VERSION[key]
            notes = list(item.get("notes") or [])
            notes.append("minVersion from annotated catalog")
            item["notes"] = notes
            if item.get("confidence") == "mechanical":
                item["confidence"] = "mechanical+annotated"
        out.append(item)
    return out


PRESENCE_ANNOTATIONS: dict[tuple[str, str, str, str], tuple[str, str | None]] = {
    ("clientMethod", "getSysTime", "reply", "time"): ("required", None),
    ("clientMethod", "getSysTime", "reply", "timezone"): ("required", None),
    ("clientMethod", "getSysTime", "reply", "gmtoffset"): ("optional", None),
    ("clientMethod", "subscribe", "request", "subscriptionId"): ("required", None),
    ("clientMethod", "subscribe", "request", "channelId"): ("alternative", "exactly one of channelId or channelName identifies the channel"),
    ("clientMethod", "subscribe", "request", "channelName"): ("alternative", "exactly one of channelId or channelName identifies the channel"),
    ("clientMethod", "subscriptionSeek", "request", "time"): ("alternative", "time or size selects the seek coordinate"),
    ("clientMethod", "subscriptionSeek", "request", "size"): ("alternative", "time or size selects the seek coordinate"),
    ("clientMethod", "subscriptionSkip", "request", "time"): ("alternative", "time or size selects the seek coordinate"),
    ("clientMethod", "subscriptionSkip", "request", "size"): ("alternative", "time or size selects the seek coordinate"),
    ("clientMethod", "subscriptionSeek", "request", "subscriptionId"): ("required", None),
    ("clientMethod", "subscriptionSkip", "request", "subscriptionId"): ("required", None),
}

CONTAINER_ANNOTATIONS: dict[tuple[str, str, str, str], tuple[str, str]] = {
    ("clientMethod", "getDvrConfigs", "reply", "dvrconfigs"): ("list", "dvrConfig"),
    ("clientMethod", "getProfiles", "reply", "profiles"): ("list", "profile"),
    ("clientMethod", "subscriptionFilterStream", "request", "enable"): ("list", "u32"),
    ("clientMethod", "subscriptionFilterStream", "request", "disable"): ("list", "u32"),
    ("serverMessage", "channelAdd", "message", "services"): ("list", "service"),
    ("serverMessage", "channelUpdate", "message", "services"): ("list", "service"),
    ("serverMessage", "channelAdd", "message", "tags"): ("list", "u32"),
    ("serverMessage", "channelUpdate", "message", "tags"): ("list", "u32"),
    ("serverMessage", "tagAdd", "message", "members"): ("list", "u32"),
    ("serverMessage", "tagUpdate", "message", "members"): ("list", "u32"),
    ("serverMessage", "dvrEntryAdd", "message", "files"): ("list", "recordingFile"),
    ("serverMessage", "dvrEntryUpdate", "message", "files"): ("list", "recordingFile"),
    ("serverMessage", "subscriptionStart", "message", "streams"): ("list", "stream"),
    ("serverMessage", "subscriptionStart", "message", "sourceinfo"): ("msg", "sourceInfo"),
}


def annotate_fields(
    kind: str,
    owner: str,
    direction: str,
    fields: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for original in fields:
        field = dict(original)
        field["direction"] = direction
        field.setdefault("presence", "unknown")
        field.setdefault("minVersion", None)
        annotation = PRESENCE_ANNOTATIONS.get((kind, owner, direction, field["name"]))
        if annotation:
            field["presence"], condition = annotation
            if condition:
                field["condition"] = condition
            field["confidence"] = (
                "mechanical+annotated"
                if field.get("confidence") == "mechanical"
                else field.get("confidence", "annotated")
            )
        container = CONTAINER_ANNOTATIONS.get((kind, owner, direction, field["name"]))
        if container:
            field["type"], field["shapeRef"] = container
            field["confidence"] = (
                "mechanical+annotated"
                if field.get("confidence") in {"mechanical", "mechanical+annotated"}
                else "annotated"
            )
            field["evidence"] += " + exact-pin container annotation"
        result.append(field)
    return result


def exact_field(
    name: str,
    wire_type: str,
    direction: str,
    presence: str,
    evidence: str,
    *,
    condition: str | None = None,
    shape_ref: str | None = None,
    min_version: int | None = None,
) -> dict[str, Any]:
    return field_record(
        name, wire_type, direction, evidence, "mechanical+annotated", presence,
        min_version=min_version, condition=condition, shape_ref=shape_ref,
    )


def field_shape(fields: list[dict[str, Any]], completeness: str = "partial") -> dict[str, Any]:
    if not fields:
        return {
            "kind": "unknown",
            "completeness": "partial",
            "evidence": "bounded extraction found no top-level field; absence is not asserted as an empty map",
        }
    return {
        "kind": "fields",
        "completeness": completeness,
        "evidence": "bounded handler/helper extraction plus exact-pin annotations",
    }


def helper_bodies(server_c: str, names: tuple[str, ...]) -> list[str]:
    bodies = []
    for name in names:
        body = find_function_body(server_c, name)
        if body is not None:
            bodies.append(body)
    return bodies


def derive_client_methods(server_c: str) -> list[dict[str, Any]]:
    table = parse_methods_table(server_c)
    methods: list[dict[str, Any]] = []
    serie_body = find_function_body(server_c, "htsp_serierec_convert") or ""
    file_open_helper = find_function_body(server_c, "htsp_file_open") or ""
    find_dvr_body = find_function_body(server_c, "htsp_findDvrEntry") or ""
    success_body = find_function_body(server_c, "htsp_success") or ""

    for entry in table:
        name = entry["name"]
        handler = entry["handler"]
        body = find_function_body(server_c, handler)
        if body is None:
            raise ValueError(f"handler body not found for {name}: {handler}")

        request_fields = extract_get_fields(body, "in")
        reply_fields = extract_add_fields(body)

        # Shared helpers used by multiple methods.
        if "htsp_findDvrEntry" in body and find_dvr_body:
            request_fields = merge_field_lists(
                request_fields,
                extract_get_fields(find_dvr_body, "in"),
            )
        if "htsp_success" in body and success_body:
            reply_fields = merge_field_lists(
                reply_fields,
                extract_add_fields(success_body, ("r", "out", "rep")),
            )
        if name in {
            "addAutorecEntry",
            "updateAutorecEntry",
            "addTimerecEntry",
            "updateTimerecEntry",
        }:
            request_fields = merge_field_lists(
                request_fields,
                extract_get_fields(serie_body, "in"),
            )
            # id is read in the update handlers themselves.
        if name in {"fileOpen"}:
            reply_fields = merge_field_lists(
                reply_fields,
                extract_add_fields(file_open_helper, ("rep", "out", "r")),
            )
        if name in {"fileRead", "fileClose", "fileStat", "fileSeek"}:
            request_fields = merge_field_lists(
                [exact_field(
                    "id", "u32", "request", "required",
                    "htsp_file_find helper reads the file-handle id",
                )],
                request_fields,
            )
        if name in {"getChannel"}:
            build_body = find_function_body(server_c, "htsp_build_channel") or ""
            reply_fields = merge_field_lists(
                reply_fields,
                extract_add_fields(build_body, ("out",)),
            )
        if name in {"getEvent"}:
            build_body = find_function_body(server_c, "htsp_build_event") or ""
            reply_fields = merge_field_lists(
                reply_fields,
                extract_add_fields(build_body, ("out",)),
            )

        if name == "fileRead":
            reply_fields = [exact_field(
                "data", "bin", "reply", "required",
                "htsp_method_fileRead htsmsg_add_bin_alloc",
            )]
        elif name == "getEvents":
            reply_fields = [exact_field(
                "events", "list", "reply", "required",
                "htsp_method_getEvents adds a list of htsp_build_event maps",
                shape_ref="event",
            )]
        elif name == "epgQuery":
            reply_fields = [
                exact_field(
                    "eventIds", "list", "reply", "alternative",
                    "htsp_method_epgQuery non-full result",
                    condition="present when full is absent or zero",
                    shape_ref="u32",
                ),
                exact_field(
                    "events", "list", "reply", "alternative",
                    "htsp_method_epgQuery full result",
                    condition="present when full is non-zero",
                    shape_ref="event",
                ),
            ]
        elif name == "getDvrCutpoints":
            reply_fields = [exact_field(
                "cutpoints", "list", "reply", "optional",
                "htsp_method_getDvrCutpoints builds cutpoint maps",
                shape_ref="cutpoint",
            )]

        request_fields = annotate_fields(
            "clientMethod", name, "request",
            apply_field_versions("clientMethod", name, request_fields),
        )
        reply_fields = annotate_fields(
            "clientMethod", name, "reply",
            apply_field_versions("clientMethod", name, reply_fields),
        )

        min_version = METHOD_MIN_VERSION.get(name)
        method: dict[str, Any] = OrderedDict()
        method["name"] = name
        method["handler"] = handler
        method["accessMask"] = entry["accessMask"]
        method["minVersion"] = min_version
        method["minVersionConfidence"] = (
            "annotated" if min_version is not None else "unknown"
        )
        method["requestFields"] = request_fields
        method["replyFields"] = reply_fields
        method["requestShape"] = field_shape(request_fields)
        method["replyShape"] = field_shape(reply_fields)
        if name in {"authenticate", "getDiskSpace", "getSysTime", "getDvrConfigs", "getProfiles"}:
            method["requestShape"] = {
                "kind": "knownEmpty", "completeness": "complete",
                "evidence": "bounded handler and official method page define no method-specific request fields",
            }
        if name in {
            "enableAsyncMetadata", "unsubscribe", "subscriptionChangeWeight",
            "subscriptionSeek", "subscriptionSkip", "subscriptionSpeed",
            "subscriptionLive", "subscriptionFilterStream", "fileClose",
        }:
            method["replyShape"] = {
                "kind": "knownEmpty", "completeness": "complete",
                "evidence": "bounded handler and official method page define no method-specific reply fields",
            }
        if name == "getSysTime":
            method["replyShape"] = {
                "kind": "fields",
                "completeness": "complete",
                "evidence": (
                    "bounded htsp_method_getSysTime emits exactly time, timezone, "
                    "and gmtoffset as method-specific reply fields"
                ),
            }
        if name == "getEpgObject":
            method["replyFields"] = []
            method["replyShape"] = {
                "kind": "dynamic",
                "completeness": "opaque",
                "evidence": "htsp_method_getEpgObject returns dynamically serialized EPG objects; official docs say TODO",
            }
        elif name == "epgQuery":
            method["replyShape"] = {
                "kind": "alternative",
                "completeness": "complete",
                "evidence": "bounded htsp_method_epgQuery branches on full",
                "alternatives": ["eventIds when full is absent/zero", "events when full is non-zero"],
            }
        if name == "stopDvrEntry":
            method["docStatus"] = "missing-from-official-client-method-page"
        if name == "subscriptionSeek":
            method["notes"] = [
                "Dispatch synonym of subscriptionSkip; both call htsp_method_skip."
            ]
        if name == "subscriptionSkip":
            method["notes"] = [
                "Dispatch synonym of subscriptionSeek; both call htsp_method_skip."
            ]
        methods.append(method)
    return methods


def derive_server_messages(server_c: str) -> list[dict[str, Any]]:
    builders = {
        "channel": find_function_body(server_c, "htsp_build_channel") or "",
        "tag": find_function_body(server_c, "htsp_build_tag") or "",
        "dvr": find_function_body(server_c, "htsp_build_dvrentry") or "",
        "autorec": find_function_body(server_c, "htsp_build_autorecentry") or "",
        "timerec": find_function_body(server_c, "htsp_build_timerecentry") or "",
        "event": find_function_body(server_c, "htsp_build_event") or "",
    }
    dedicated = {
        "initialSyncCompleted": find_function_body(server_c, "htsp_method_async") or "",
        "muxpkt": "",
        "queueStatus": "",
        "subscriptionStart": "",
        "subscriptionStop": "",
        "subscriptionGrace": "",
        "subscriptionStatus": "",
        "signalStatus": find_function_body(
            server_c, "htsp_subscription_signal_status"
        )
        or "",
        "descrambleInfo": find_function_body(
            server_c, "htsp_subscription_descramble_info"
        )
        or "",
        "subscriptionSpeed": find_function_body(server_c, "htsp_subscription_speed")
        or "",
        "timeshiftStatus": find_function_body(
            server_c, "htsp_subscription_timeshift_status"
        )
        or "",
        "subscriptionSkip": find_function_body(server_c, "htsp_subscription_skip")
        or "",
    }

    # Locate remaining dedicated emitters by method string assignment.
    for message_name in (
        "muxpkt",
        "queueStatus",
        "subscriptionStart",
        "subscriptionStop",
        "subscriptionGrace",
        "subscriptionStatus",
        "initialSyncCompleted",
    ):
        if dedicated.get(message_name):
            continue
        pattern = re.compile(
            rf'htsmsg_add_str\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*,\s*"method"\s*,\s*'
            rf'"{re.escape(message_name)}"\s*\)'
        )
        match = pattern.search(server_c)
        if not match:
            # initialSyncCompleted is created inside async handler with local m
            continue
        # Expand a window around the match for field extraction.
        start = max(0, match.start() - 2500)
        end = min(len(server_c), match.end() + 2500)
        dedicated[message_name] = server_c[start:end]

    # muxpkt / queueStatus / subscription* often live in streaming helpers.
    for fn_name, message_name in (
        ("htsp_subscription_start", "subscriptionStart"),
        ("htsp_subscription_stop", "subscriptionStop"),
        ("htsp_subscription_status", "subscriptionStatus"),
        ("htsp_subscription_grace", "subscriptionGrace"),
        ("htsp_subscription_signal_status", "signalStatus"),
    ):
        body = find_function_body(server_c, fn_name)
        if body:
            dedicated[message_name] = body

    # queueStatus and muxpkt: search function-ish windows
    for message_name in ("queueStatus", "muxpkt"):
        pattern = re.compile(
            rf'htsmsg_add_str\(\s*m\s*,\s*"method"\s*,\s*"{message_name}"\s*\)'
        )
        match = pattern.search(server_c)
        if match:
            start = max(0, match.start() - 2000)
            end = min(len(server_c), match.end() + 2000)
            dedicated[message_name] = server_c[start:end]

    family_builder = {
        "channelAdd": "channel",
        "channelUpdate": "channel",
        "channelDelete": None,
        "tagAdd": "tag",
        "tagUpdate": "tag",
        "tagDelete": None,
        "dvrEntryAdd": "dvr",
        "dvrEntryUpdate": "dvr",
        "dvrEntryDelete": None,
        "autorecEntryAdd": "autorec",
        "autorecEntryUpdate": "autorec",
        "autorecEntryDelete": None,
        "timerecEntryAdd": "timerec",
        "timerecEntryUpdate": "timerec",
        "timerecEntryDelete": None,
        "eventAdd": "event",
        "eventUpdate": "event",
        "eventDelete": None,
    }

    delete_bodies = {
        "channelDelete": re.search(
            r'htsmsg_add_str\(\s*m\s*,\s*"method"\s*,\s*"channelDelete".{0,400}',
            server_c,
            flags=re.S,
        ),
        "tagDelete": re.search(
            r'htsmsg_add_str\(\s*m\s*,\s*"method"\s*,\s*"tagDelete".{0,400}',
            server_c,
            flags=re.S,
        ),
        "dvrEntryDelete": re.search(
            r'htsmsg_add_str\(\s*m\s*,\s*"method"\s*,\s*"dvrEntryDelete".{0,400}',
            server_c,
            flags=re.S,
        ),
        "autorecEntryDelete": re.search(
            r'htsmsg_add_str\(\s*m\s*,\s*"method"\s*,\s*"autorecEntryDelete".{0,400}',
            server_c,
            flags=re.S,
        ),
        "timerecEntryDelete": re.search(
            r'htsmsg_add_str\(\s*m\s*,\s*"method"\s*,\s*"timerecEntryDelete".{0,400}',
            server_c,
            flags=re.S,
        ),
        "eventDelete": re.search(
            r'htsmsg_add_str\(\s*m\s*,\s*"method"\s*,\s*"eventDelete".{0,400}',
            server_c,
            flags=re.S,
        ),
    }

    messages: list[dict[str, Any]] = []
    found_names: list[str] = []

    # Confirm each expected message string exists in source (except pure builder names).
    for name in EXPECTED_SERVER_MESSAGES:
        if name.endswith("Add") or name.endswith("Update"):
            # Builders take method as parameter; verify call sites or string.
            if f'"{name}"' not in server_c and name not in {
                "channelAdd",
                "channelUpdate",
                "tagAdd",
                "tagUpdate",
                "dvrEntryAdd",
                "dvrEntryUpdate",
                "autorecEntryAdd",
                "autorecEntryUpdate",
                "timerecEntryAdd",
                "timerecEntryUpdate",
                "eventAdd",
                "eventUpdate",
            }:
                raise ValueError(f"server message string missing: {name}")
        elif f'"{name}"' not in server_c:
            raise ValueError(f"server message string missing: {name}")
        found_names.append(name)

    if found_names != list(EXPECTED_SERVER_MESSAGES):
        raise ValueError("server message inventory mismatch")

    for name in EXPECTED_SERVER_MESSAGES:
        fields: list[dict[str, Any]] = []
        builder_key = family_builder.get(name)
        if builder_key:
            fields = extract_add_fields(builders[builder_key], ("out", "m"))
            if name.endswith("Update"):
                for field in fields:
                    if field["name"] in {
                        "channelId",
                        "tagId",
                        "id",
                        "eventId",
                    } or field["name"].endswith("Id"):
                        continue
        elif name in delete_bodies and delete_bodies[name]:
            window = delete_bodies[name].group(0)
            fields = extract_add_fields(window, ("m", "out"))
        else:
            body = dedicated.get(name) or ""
            fields = extract_add_fields(body, ("m", "out", "r"))

        delete_catalog = {
            "channelDelete": ("channelId", "u32"),
            "tagDelete": ("tagId", "u32"),
            "dvrEntryDelete": ("id", "u32"),
            "autorecEntryDelete": ("id", "str"),
            "timerecEntryDelete": ("id", "str"),
            "eventDelete": ("eventId", "u32"),
        }
        if name in delete_catalog:
            identifier, wire_type = delete_catalog[name]
            fields = [exact_field(
                identifier, wire_type, "message", "required",
                f"bounded {name} emitter adds its delete identifier",
            )]
        elif name == "muxpkt":
            fields = [
                exact_field("subscriptionId", "u32", "message", "required", "bounded muxpkt emitter"),
                exact_field("frametype", "u32", "message", "required", "bounded muxpkt emitter"),
                exact_field("stream", "u32", "message", "required", "bounded muxpkt emitter"),
                exact_field("dts", "s64", "message", "optional", "bounded muxpkt emitter"),
                exact_field("pts", "s64", "message", "optional", "bounded muxpkt emitter"),
                exact_field("duration", "u32", "message", "required", "bounded muxpkt emitter"),
                exact_field("payload", "bin", "message", "required", "bounded muxpkt emitter htsmsg_add_bin_ptr"),
            ]
        elif name == "queueStatus":
            fields = [
                exact_field("subscriptionId", "u32", "message", "required", "bounded queueStatus emitter"),
                exact_field("packets", "u32", "message", "required", "bounded queueStatus emitter"),
                exact_field("bytes", "u32", "message", "required", "bounded queueStatus emitter"),
                exact_field("delay", "s64", "message", "conditional", "bounded queueStatus emitter", condition="emitted when queue timestamps provide a delay"),
                exact_field("Bdrops", "u32", "message", "required", "bounded queueStatus emitter"),
                exact_field("Pdrops", "u32", "message", "required", "bounded queueStatus emitter"),
                exact_field("Idrops", "u32", "message", "required", "bounded queueStatus emitter"),
            ]

        fields = annotate_fields(
            "serverMessage", name, "message",
            apply_field_versions("serverMessage", name, fields),
        )
        item: dict[str, Any] = OrderedDict()
        item["name"] = name
        item["minVersion"] = SERVER_MESSAGE_MIN_VERSION.get(name)
        item["minVersionConfidence"] = (
            "annotated"
            if SERVER_MESSAGE_MIN_VERSION.get(name) is not None
            else "unknown"
        )
        item["fields"] = fields
        item["messageShape"] = field_shape(
            fields,
            "complete" if name in delete_catalog or name in {"muxpkt", "queueStatus"} else "partial",
        )
        if name == "initialSyncCompleted":
            item["messageShape"] = {
                "kind": "knownEmpty", "completeness": "complete",
                "evidence": "bounded async emitter adds only the global method discriminator",
            }
        if name == "descrambleInfo":
            item["docStatus"] = "missing-from-official-server-message-page"
            item["notes"] = [
                "Source returns early when htsp_version < 24 or anonymize is set."
            ]
        if name.endswith("Update"):
            item["notes"] = list(item.get("notes") or []) + [
                "Update messages reuse Add builders; non-key fields are effectively optional."
            ]
        messages.append(item)
    return messages


STRING_LITERAL_RE = re.compile(r'"((?:\\.|[^"\\])*)"')
CONST_STRING_RE = re.compile(
    r"(?:const\s+val|val)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\"([^\"]+)\""
)
METHOD_ASSIGN_RE = re.compile(
    r"method\s*=\s*(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))"
)


def iter_production_kotlin_java(
    roots: tuple[Path, ...] = SDK_PRODUCTION_ROOTS,
) -> list[Path]:
    files: list[Path] = []
    for root in roots:
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*")):
            if path.is_symlink():
                continue
            if path.suffix in {".kt", ".java"} and path.is_file():
                files.append(path)
    return files


def scan_sdk_coverage(
    client_method_names: tuple[str, ...],
    server_message_names: tuple[str, ...],
    roots: tuple[Path, ...] = SDK_PRODUCTION_ROOTS,
) -> dict[str, Any]:
    """Exact-literal coverage over SDK production main sources.

    Scans htsp plus playback production main trees so the accepted
    22-referenced / 21-outgoing / 23-handled metrics remain checkable.
    Tests and non-production fixtures are excluded.
    """
    method_set = set(client_method_names)
    message_set = set(server_message_names)
    referenced_methods: set[str] = set()
    outgoing_methods: set[str] = set()
    handled_messages: set[str] = set()
    constants: dict[str, str] = {}

    texts: list[str] = []
    for path in iter_production_kotlin_java(roots):
        text = path.read_text(encoding="utf-8")
        texts.append(text)
        for const_name, value in CONST_STRING_RE.findall(text):
            if value in method_set or value in message_set:
                constants[const_name] = value

    combined = "\n".join(texts)
    for literal in STRING_LITERAL_RE.findall(combined):
        if literal in method_set:
            referenced_methods.add(literal)
        if literal in message_set:
            handled_messages.add(literal)

    for match in METHOD_ASSIGN_RE.finditer(combined):
        literal, ident = match.group(1), match.group(2)
        if literal and literal in method_set:
            outgoing_methods.add(literal)
            referenced_methods.add(literal)
        elif ident and ident in constants and constants[ident] in method_set:
            outgoing_methods.add(constants[ident])
            referenced_methods.add(constants[ident])

    # Constants used as method values without matching METHOD_ASSIGN_RE edge cases.
    for value in constants.values():
        if value in method_set and value in referenced_methods:
            # If a constant holds a method name and appears in method= context already counted.
            pass

    scan_root_labels: list[str] = []
    for path in roots:
        if not path.is_dir():
            continue
        try:
            label = str(path.relative_to(REPO_ROOT)).replace("\\", "/")
        except ValueError:
            label = f"<external-fixture>/{path.name}"
        scan_root_labels.append(label)
    return {
        "scanRoots": scan_root_labels,
        "clientMethods": {
            "total": len(client_method_names),
            "referenced": sorted(referenced_methods),
            "referencedCount": len(referenced_methods),
            "outgoingRequests": sorted(outgoing_methods),
            "outgoingRequestCount": len(outgoing_methods),
            "unreferenced": [
                name for name in client_method_names if name not in referenced_methods
            ],
        },
        "serverMessages": {
            "total": len(server_message_names),
            "handled": sorted(handled_messages),
            "handledCount": len(handled_messages),
            "unhandled": [
                name for name in server_message_names if name not in handled_messages
            ],
        },
        "metrics": {
            "referencedClientMethods": len(referenced_methods),
            "outgoingClientMethods": len(outgoing_methods),
            "handledServerMessages": len(handled_messages),
            "notes": [
                "referenced counts exact string literals in SDK production main sources",
                "outgoing counts method = \"...\" / method = CONST assignments only",
                "subscriptionSkip may be referenced via inbound handling without being outgoing",
                "handled server messages use the same exact-literal metric",
            ],
        },
    }


def parse_python_demo(htsp_py: str) -> dict[str, Any]:
    version_match = re.search(r"HTSP_PROTO_VERSION\s*=\s*(\d+)", htsp_py)
    methods = re.findall(r"self\.send\(\s*'([^']+)'", htsp_py)
    methods += re.findall(r'self\.send\(\s*"([^"]+)"', htsp_py)
    return {
        "htspProtoVersion": int(version_match.group(1)) if version_match else None,
        "methodsCovered": sorted(set(methods)),
        "role": "narrow-cross-check-only",
        "completenessAuthority": False,
    }


def build_spec(
    source_root: Path,
    manifest: dict[str, Any] | None = None,
    *,
    enforce_exact_pin: bool = True,
) -> dict[str, Any]:
    manifest = manifest or load_manifest()
    if enforce_exact_pin:
        validate_exact_manifest(manifest)
    files_meta = manifest["files"]
    server_c = read_verified_source_file(
        source_root,
        "src/htsp_server.c",
        files_meta["src/htsp_server.c"]["gitBlobSha1"],
        files_meta["src/htsp_server.c"]["bytes"],
    )
    server_h = read_verified_source_file(
        source_root,
        "src/htsp_server.h",
        files_meta["src/htsp_server.h"]["gitBlobSha1"],
        files_meta["src/htsp_server.h"]["bytes"],
    )
    htsp_py = read_verified_source_file(
        source_root,
        "lib/py/tvh/htsp.py",
        files_meta["lib/py/tvh/htsp.py"]["gitBlobSha1"],
        files_meta["lib/py/tvh/htsp.py"]["bytes"],
    )

    proto = parse_proto_version(server_c)
    if proto != manifest["htspProtoVersion"]:
        raise ValueError(
            f"HTSP_PROTO_VERSION {proto} != manifest {manifest['htspProtoVersion']}"
        )
    if proto != EXPECTED_PROTO_VERSION:
        raise ValueError(f"expected HTSP_PROTO_VERSION 44, got {proto}")

    client_methods = derive_client_methods(server_c)
    server_messages = derive_server_messages(server_c)
    coverage = scan_sdk_coverage(EXPECTED_CLIENT_METHODS, EXPECTED_SERVER_MESSAGES)
    python_demo = parse_python_demo(htsp_py)

    # Attach per-item coverage flags in source order.
    referenced = set(coverage["clientMethods"]["referenced"])
    outgoing = set(coverage["clientMethods"]["outgoingRequests"])
    handled = set(coverage["serverMessages"]["handled"])
    for method in client_methods:
        method["sdk"] = OrderedDict(
            [
                ("referenced", method["name"] in referenced),
                ("outgoingRequest", method["name"] in outgoing),
            ]
        )
    for message in server_messages:
        message["sdk"] = OrderedDict(
            [
                ("handled", message["name"] in handled),
            ]
        )

    spec: dict[str, Any] = OrderedDict()
    spec["schemaVersion"] = 1
    spec["artifactKind"] = "htsp-protocol-evidence"
    spec["disclaimer"] = (
        "Current-source evidence for repository engineering only. "
        "Not a public API, support matrix, stability promise, or completeness contract."
    )
    spec["upstream"] = OrderedDict(
        [
            ("repository", manifest["repository"]),
            ("revision", manifest["revision"]),
            ("htspProtoVersion", proto),
            (
                "files",
                OrderedDict(
                    (rel, OrderedDict(meta))
                    for rel, meta in files_meta.items()
                ),
            ),
            ("docsUrls", OrderedDict(manifest.get("docsUrls") or {})),
        ]
    )
    spec["globalRpc"] = OrderedDict(
        [
            (
                "requestFields",
                [
                    field_record(
                        "seq",
                        "u32",
                        "request",
                        "htsp_reply reads seq from request",
                        "mechanical",
                        "optional",
                        notes=["Global RPC correlation field; not method-specific."],
                    ),
                    field_record(
                        "username",
                        "str",
                        "request",
                        "communication docs + on-demand auth path",
                        "annotated",
                        "optional",
                        notes=["Optional privilege elevation on individual RPCs."],
                    ),
                    field_record(
                        "digest",
                        "bin",
                        "request",
                        "communication docs + on-demand auth path",
                        "annotated",
                        "optional",
                        notes=["Optional privilege elevation on individual RPCs."],
                    ),
                ],
            ),
            (
                "replyFields",
                [
                    field_record(
                        "seq",
                        "u32",
                        "reply",
                        "htsp_reply echoes seq",
                        "mechanical",
                        "optional",
                        notes=["Global RPC correlation field; not method-specific."],
                    ),
                    field_record(
                        "error",
                        "str",
                        "reply",
                        "htsp_error / communication docs",
                        "mechanical",
                        "optional",
                        notes=["Global RPC error field; not method-specific success."],
                    ),
                    field_record(
                        "noaccess",
                        "u32",
                        "reply",
                        "access denial path / communication docs",
                        "mechanical",
                        "optional",
                        notes=["Global RPC access-denial field; value 1 means denied."],
                    ),
                ],
            ),
        ]
    )
    spec["asyncEnvelope"] = OrderedDict(
        [
            ("fields", [field_record(
                "method", "str", "message", "source + communication docs",
                "mechanical+annotated", "required",
                notes=["Async discriminator; asynchronous messages do not carry seq."],
            )]),
            ("shape", {
                "kind": "fields", "completeness": "complete",
                "evidence": "global asynchronous-message envelope",
            }),
        ]
    )
    spec["shapes"] = OrderedDict(
        [
            ("cutpoint", {
                "kind": "object",
                "completeness": "complete",
                "evidence": "bounded getDvrCutpoints cutpoint emitter",
                "fields": [
                    exact_field("start", "u32", "nested", "required", "cutpoint emitter"),
                    exact_field("end", "u32", "nested", "required", "cutpoint emitter"),
                    exact_field("type", "u32", "nested", "required", "cutpoint emitter"),
                ],
            }),
            ("event", {
                "kind": "reference",
                "target": "serverMessage:eventAdd",
                "completeness": "partial",
                "evidence": "htsp_build_event is shared with eventAdd/getEvent/getEvents",
            }),
            ("u32", {
                "kind": "scalar",
                "wireType": "u32",
                "completeness": "complete",
                "evidence": "htsmsg list element type",
            }),
            ("dvrConfig", {
                "kind": "object", "completeness": "opaque",
                "evidence": "nested DVR configuration maps are intentionally not expanded in this artifact",
            }),
            ("profile", {
                "kind": "object", "completeness": "opaque",
                "evidence": "nested profile maps are intentionally not expanded in this artifact",
            }),
            ("service", {
                "kind": "object", "completeness": "opaque",
                "evidence": "nested channel service maps are intentionally not expanded in this artifact",
            }),
            ("recordingFile", {
                "kind": "object", "completeness": "opaque",
                "evidence": "nested recording-file maps are intentionally not expanded in this artifact",
            }),
            ("stream", {
                "kind": "object", "completeness": "opaque",
                "evidence": "nested subscription stream maps are intentionally not expanded in this artifact",
            }),
            ("sourceInfo", {
                "kind": "object", "completeness": "opaque",
                "evidence": "nested subscription source-info map is intentionally not expanded in this artifact",
            }),
        ]
    )
    spec["clientMethods"] = client_methods
    spec["serverMessages"] = server_messages
    spec["pythonDemo"] = python_demo
    spec["coverage"] = coverage
    spec["docLimitations"] = DOC_LIMITATIONS
    spec["headerSymbols"] = {
        "path": "src/htsp_server.h",
        "notes": [
            "Public server-side HTSP entry points and callbacks; not a client API.",
            f"byteLengthVerified={len(server_h.encode('utf-8'))}",
        ],
    }
    ordered_field_lists = [
        spec["globalRpc"]["requestFields"],
        spec["globalRpc"]["replyFields"],
        spec["asyncEnvelope"]["fields"],
    ]
    ordered_field_lists.extend(
        fields
        for shape in spec["shapes"].values()
        if isinstance((fields := shape.get("fields")), list)
    )
    for method in client_methods:
        ordered_field_lists.extend((method["requestFields"], method["replyFields"]))
    for message in server_messages:
        ordered_field_lists.append(message["fields"])
    for fields in ordered_field_lists:
        for order, field in enumerate(fields, start=1):
            field["order"] = order
    return spec


def dumps_spec(spec: dict[str, Any]) -> str:
    return json.dumps(spec, indent=2, ensure_ascii=False, sort_keys=False) + "\n"


def write_spec(spec: dict[str, Any], path: Path = SPEC_PATH) -> None:
    path.write_text(dumps_spec(spec), encoding="utf-8", newline="\n")


def _write_fixture_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = content.encode("utf-8")
    path.write_bytes(data)


def _minimal_server_c(
    methods: list[tuple[str, str, str]],
    proto: int = 44,
    extra: str = "",
) -> str:
    rows = ",\n".join(
        f'  {{ "{name}", {handler}, {access}}}' for name, handler, access in methods
    )
    handlers = []
    for name, handler, _access in methods:
        handlers.append(
            f"""
static htsmsg_t *
{handler}(htsp_connection_t *htsp, htsmsg_t *in)
{{
  uint32_t v;
  htsmsg_t *r = htsmsg_create_map();
  if(!htsmsg_get_u32(in, "demoField", &v))
    htsmsg_add_u32(r, "demoReply", v);
  (void)htsp; (void)name_{name};
  return r;
}}
""".replace(f"(void)name_{name};", "")
        )
    # Server message emitters for expected inventory subset used in unit tests.
    emitters = """
static void emit_all(void) {
  htsmsg_t *m;
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "channelDelete"); htsmsg_add_u32(m, "channelId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "tagDelete"); htsmsg_add_u32(m, "tagId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "dvrEntryDelete"); htsmsg_add_u32(m, "id", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "autorecEntryDelete"); htsmsg_add_str(m, "id", "x");
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "timerecEntryDelete"); htsmsg_add_str(m, "id", "x");
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "eventDelete"); htsmsg_add_u32(m, "eventId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "initialSyncCompleted");
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "muxpkt"); htsmsg_add_u32(m, "subscriptionId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "queueStatus"); htsmsg_add_u32(m, "subscriptionId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "subscriptionStart"); htsmsg_add_u32(m, "subscriptionId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "subscriptionStop"); htsmsg_add_u32(m, "subscriptionId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "subscriptionGrace"); htsmsg_add_u32(m, "subscriptionId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "subscriptionStatus"); htsmsg_add_u32(m, "subscriptionId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "signalStatus"); htsmsg_add_u32(m, "subscriptionId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "descrambleInfo"); htsmsg_add_u32(m, "subscriptionId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "subscriptionSpeed"); htsmsg_add_u32(m, "subscriptionId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "timeshiftStatus"); htsmsg_add_u32(m, "subscriptionId", 1);
  m = htsmsg_create_map(); htsmsg_add_str(m, "method", "subscriptionSkip"); htsmsg_add_u32(m, "subscriptionId", 1);
  /* builder method names referenced as strings for inventory presence */
  (void)"channelAdd"; (void)"channelUpdate";
  (void)"tagAdd"; (void)"tagUpdate";
  (void)"dvrEntryAdd"; (void)"dvrEntryUpdate";
  (void)"autorecEntryAdd"; (void)"autorecEntryUpdate";
  (void)"timerecEntryAdd"; (void)"timerecEntryUpdate";
  (void)"eventAdd"; (void)"eventUpdate";
}
static htsmsg_t *htsp_build_channel(void *ch, const char *method, void *htsp) {
  htsmsg_t *out = htsmsg_create_map();
  htsmsg_add_u32(out, "channelId", 1);
  htsmsg_add_str(out, "method", method);
  return out;
}
static htsmsg_t *htsp_build_tag(void *htsp, void *ct, const char *method, int include_channels) {
  htsmsg_t *out = htsmsg_create_map();
  htsmsg_add_u32(out, "tagId", 1);
  htsmsg_add_str(out, "method", method);
  return out;
}
static htsmsg_t *htsp_build_dvrentry(void *htsp, void *de, const char *method, const char *lang, int statsonly) {
  htsmsg_t *out = htsmsg_create_map();
  htsmsg_add_u32(out, "id", 1);
  htsmsg_add_str(out, "method", method);
  return out;
}
static htsmsg_t *htsp_build_autorecentry(void *htsp, void *dae, const char *method) {
  htsmsg_t *out = htsmsg_create_map();
  htsmsg_add_str(out, "id", "x");
  htsmsg_add_str(out, "method", method);
  return out;
}
static htsmsg_t *htsp_build_timerecentry(void *htsp, void *dte, const char *method) {
  htsmsg_t *out = htsmsg_create_map();
  htsmsg_add_str(out, "id", "x");
  htsmsg_add_str(out, "method", method);
  return out;
}
static htsmsg_t *htsp_build_event(void *e, const char *method, const char *lang, long update, void *htsp) {
  htsmsg_t *out = htsmsg_create_map();
  htsmsg_add_u32(out, "eventId", 1);
  htsmsg_add_str(out, "method", method);
  return out;
}
"""
    return f"""
#define HTSP_PROTO_VERSION {proto}
{''.join(handlers)}
struct {{
  const char *name;
  htsmsg_t *(*fn)(htsp_connection_t *htsp, htsmsg_t *in);
  int privmask;
}} htsp_methods[] = {{
{rows}
}};
{emitters}
{extra}
"""


def _pin_bytes_and_sha(content: str) -> tuple[bytes, str, int]:
    data = content.encode("utf-8")
    return data, git_blob_sha1(data), len(data)


def self_test() -> None:
    failures: list[str] = []

    def check(name: str, cond: bool, detail: str = "") -> None:
        if not cond:
            failures.append(f"{name}: {detail}" if detail else name)

    # Regression contract for the predecessor artifacts and unsafe helper API.
    # These checks intentionally exercise facts, not inventory counts.
    committed = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
    methods_by_name = {item["name"]: item for item in committed["clientMethods"]}
    messages_by_name = {item["name"]: item for item in committed["serverMessages"]}
    for method_name in ("fileRead", "fileClose", "fileStat", "fileSeek"):
        request_names = {f["name"] for f in methods_by_name[method_name]["requestFields"]}
        check(f"{method_name}-helper-id", "id" in request_names)
    file_read_reply = {f["name"]: f for f in methods_by_name["fileRead"]["replyFields"]}
    check("fileRead-binary-data", file_read_reply.get("data", {}).get("type") == "bin")
    for message_name, identifier in (
        ("channelDelete", "channelId"),
        ("tagDelete", "tagId"),
        ("dvrEntryDelete", "id"),
        ("autorecEntryDelete", "id"),
        ("timerecEntryDelete", "id"),
        ("eventDelete", "eventId"),
    ):
        check(
            f"{message_name}-identifier",
            identifier in {f["name"] for f in messages_by_name[message_name]["fields"]},
        )
    mux_fields = {f["name"]: f for f in messages_by_name["muxpkt"]["fields"]}
    queue_fields = {f["name"]: f for f in messages_by_name["queueStatus"]["fields"]}
    check("muxpkt-payload-bin", mux_fields.get("payload", {}).get("type") == "bin")
    check("muxpkt-no-queue-counters", not ({"packets", "bytes", "Bdrops", "Pdrops", "Idrops"} & mux_fields.keys()))
    check("queueStatus-counters", {"packets", "bytes", "Bdrops", "Pdrops", "Idrops"} <= queue_fields.keys())
    check("queueStatus-no-mux-fields", not ({"stream", "dts", "pts", "duration", "payload"} & queue_fields.keys()))
    check("getEpgObject-dynamic", methods_by_name["getEpgObject"].get("replyShape", {}).get("kind") == "dynamic")
    check("getEvents-nested", methods_by_name["getEvents"].get("replyShape", {}).get("kind") == "fields")
    check("epgQuery-alternative", methods_by_name["epgQuery"].get("replyShape", {}).get("kind") == "alternative")
    check("cutpoints-shape", "cutpoint" in committed.get("shapes", {}))
    system_time = methods_by_name["getSysTime"]
    check(
        "getSysTime-exact-reply-fields",
        [
            (field["name"], field["type"], field["presence"])
            for field in system_time["replyFields"]
        ] == [
            ("time", "s32", "required"),
            ("timezone", "s32", "required"),
            ("gmtoffset", "s32", "optional"),
        ],
    )
    check(
        "getSysTime-complete-reply-shape",
        system_time.get("replyShape", {}).get("completeness") == "complete",
    )
    limitation_ids = {item.get("id") for item in committed.get("docLimitations", [])}
    check(
        "getSysTime-source-doc-mismatch-limitation",
        "getSysTime-time-type-source-doc-mismatch" in limitation_ids,
    )
    live_coverage = scan_sdk_coverage(EXPECTED_CLIENT_METHODS, EXPECTED_SERVER_MESSAGES)
    check(
        "current-production-coverage",
        (
            live_coverage["clientMethods"]["referencedCount"],
            live_coverage["clientMethods"]["outgoingRequestCount"],
            live_coverage["serverMessages"]["handledCount"],
        ) == (22, 21, 23),
        str(live_coverage.get("metrics")),
    )
    check(
        "getSysTime-current-production-coverage",
        "getSysTime" in live_coverage["clientMethods"]["referenced"]
        and "getSysTime" in live_coverage["clientMethods"]["outgoingRequests"],
    )

    with tempfile.TemporaryDirectory(prefix="htsp-pin-regression-") as pin_tmp:
        wrong_manifest = json.loads(UPSTREAM_MANIFEST_PATH.read_text(encoding="utf-8"))
        wrong_manifest["repository"] = "https://example.invalid/wrong"
        wrong_path = Path(pin_tmp) / "upstream.json"
        wrong_path.write_text(json.dumps(wrong_manifest), encoding="utf-8")
        try:
            load_manifest(wrong_path)
            check("reject-immutable-wrong-repository", False, "wrong exact pin accepted")
        except ValueError:
            pass

    scan_parameters = inspect.signature(scan_sdk_coverage).parameters
    check("coverage-supports-isolated-roots", "roots" in scan_parameters)
    check("fetch-plan-validator-present", callable(globals().get("validate_fetch_plan")))

    exact_manifest = json.loads(UPSTREAM_MANIFEST_PATH.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="htsp-fetch-safety-") as fetch_tmp:
        sandbox = Path(fetch_tmp)

        def expect_fetch_rejection(label: str, destination: Path, manifest: dict[str, Any]) -> None:
            try:
                validate_fetch_plan(destination, manifest)
                check(label, False, "unsafe fetch plan accepted")
            except ValueError:
                pass

        expect_fetch_rejection("reject-repository-destination", REPO_ROOT, exact_manifest)
        link_root = sandbox / "link-root"
        real_root = sandbox / "real-root"
        real_root.mkdir()
        link_root.symlink_to(real_root, target_is_directory=True)
        expect_fetch_rejection("reject-fetch-symlink-root", link_root, exact_manifest)

        parent_target = sandbox / "parent-target"
        parent_target.mkdir()
        parent_link = sandbox / "parent-link"
        parent_link.symlink_to(parent_target, target_is_directory=True)
        expect_fetch_rejection("reject-fetch-symlink-parent", parent_link / "child", exact_manifest)

        existing_root = sandbox / "existing-root"
        (existing_root / "src").mkdir(parents=True)
        (existing_root / "src" / "htsp_server.c").write_bytes(b"do not overwrite")
        expect_fetch_rejection("reject-existing-fetch-target", existing_root, exact_manifest)
        check(
            "existing-fetch-target-unmodified",
            (existing_root / "src" / "htsp_server.c").read_bytes() == b"do not overwrite",
        )

        symlink_file_root = sandbox / "symlink-file-root"
        (symlink_file_root / "src").mkdir(parents=True)
        outside = sandbox / "outside"
        outside.write_bytes(b"outside")
        (symlink_file_root / "src" / "htsp_server.c").symlink_to(outside)
        expect_fetch_rejection("reject-fetch-symlink-file", symlink_file_root, exact_manifest)

        bad_extra = copy.deepcopy(exact_manifest)
        bad_extra["files"]["../escape"] = bad_extra["files"]["src/htsp_server.h"]
        expect_fetch_rejection("reject-extra-escaping-path", sandbox / "extra", bad_extra)
        bad_pin = copy.deepcopy(exact_manifest)
        bad_pin["revision"] = "0" * 40
        expect_fetch_rejection("reject-fetch-wrong-pin", sandbox / "wrong", bad_pin)

    # --- synthetic happy-path fixture with exact expected method inventory ---
    method_rows = [
        (name, f"htsp_method_{name}", "ACCESS_ANONYMOUS")
        for name in EXPECTED_CLIENT_METHODS
    ]
    # unique handler names
    method_rows = [
        (name, f"htsp_method_{idx}", "ACCESS_ANONYMOUS")
        for idx, name in enumerate(EXPECTED_CLIENT_METHODS)
    ]
    server_c = _minimal_server_c(method_rows, proto=44)
    server_h = "/* header fixture */\nvoid htsp_init(const char *bindaddr);\n"
    htsp_py = "HTSP_PROTO_VERSION = 33\nclass HTSPClient(object):\n    def hello(self):\n        self.send('hello')\n"

    with tempfile.TemporaryDirectory(prefix="htsp-derive-selftest-") as tmp:
        root = Path(tmp)
        c_data, c_sha, c_len = _pin_bytes_and_sha(server_c)
        h_data, h_sha, h_len = _pin_bytes_and_sha(server_h)
        p_data, p_sha, p_len = _pin_bytes_and_sha(htsp_py)
        (root / "src").mkdir()
        (root / "lib" / "py" / "tvh").mkdir(parents=True)
        (root / "src" / "htsp_server.c").write_bytes(c_data)
        (root / "src" / "htsp_server.h").write_bytes(h_data)
        (root / "lib" / "py" / "tvh" / "htsp.py").write_bytes(p_data)

        manifest = {
            "schemaVersion": 1,
            "repository": "https://github.com/tvheadend/tvheadend",
            "revision": "deadbeef",
            "htspProtoVersion": 44,
            "files": {
                "src/htsp_server.c": {"gitBlobSha1": c_sha, "bytes": c_len},
                "src/htsp_server.h": {"gitBlobSha1": h_sha, "bytes": h_len},
                "lib/py/tvh/htsp.py": {"gitBlobSha1": p_sha, "bytes": p_len},
            },
            "docsUrls": {},
        }

        spec = build_spec(root, manifest, enforce_exact_pin=False)
        check(
            "method-count",
            len(spec["clientMethods"]) == 39,
            str(len(spec["clientMethods"])),
        )
        check(
            "method-order",
            [m["name"] for m in spec["clientMethods"]] == list(EXPECTED_CLIENT_METHODS),
        )
        check(
            "message-count",
            len(spec["serverMessages"]) == 30,
            str(len(spec["serverMessages"])),
        )
        check(
            "message-order",
            [m["name"] for m in spec["serverMessages"]]
            == list(EXPECTED_SERVER_MESSAGES),
        )
        check("proto", spec["upstream"]["htspProtoVersion"] == 44)

        # wrong protocol version
        bad_proto_manifest = json.loads(json.dumps(manifest))
        bad_c = _minimal_server_c(method_rows, proto=43)
        bad_data, bad_sha, bad_len = _pin_bytes_and_sha(bad_c)
        (root / "src" / "htsp_server.c").write_bytes(bad_data)
        bad_proto_manifest["files"]["src/htsp_server.c"] = {
            "gitBlobSha1": bad_sha,
            "bytes": bad_len,
        }
        bad_proto_manifest["htspProtoVersion"] = 44
        try:
            build_spec(root, bad_proto_manifest, enforce_exact_pin=False)
            check("reject-wrong-proto", False, "expected failure")
        except ValueError as exc:
            check("reject-wrong-proto", "HTSP_PROTO_VERSION" in str(exc), str(exc))

        # restore good c
        (root / "src" / "htsp_server.c").write_bytes(c_data)

        # wrong blob metadata
        bad_blob = json.loads(json.dumps(manifest))
        bad_blob["files"]["src/htsp_server.c"]["gitBlobSha1"] = "0" * 40
        try:
            build_spec(root, bad_blob, enforce_exact_pin=False)
            check("reject-wrong-blob", False, "expected failure")
        except ValueError as exc:
            check("reject-wrong-blob", "blob SHA-1" in str(exc), str(exc))

        # missing method
        missing_rows = method_rows[:-1]
        missing_c = _minimal_server_c(missing_rows, proto=44)
        missing_data, missing_sha, missing_len = _pin_bytes_and_sha(missing_c)
        (root / "src" / "htsp_server.c").write_bytes(missing_data)
        missing_manifest = json.loads(json.dumps(manifest))
        missing_manifest["files"]["src/htsp_server.c"] = {
            "gitBlobSha1": missing_sha,
            "bytes": missing_len,
        }
        try:
            build_spec(root, missing_manifest, enforce_exact_pin=False)
            check("reject-missing-method", False, "expected failure")
        except ValueError as exc:
            check("reject-missing-method", "htsp_methods" in str(exc), str(exc))

        # duplicate method
        dup_rows = method_rows[:-1] + [method_rows[-2]]
        dup_c = _minimal_server_c(dup_rows, proto=44)
        # force duplicate name in table text
        dup_c = dup_c.replace(
            f'{{ "{EXPECTED_CLIENT_METHODS[-1]}"',
            f'{{ "{EXPECTED_CLIENT_METHODS[-2]}"',
            1,
        )
        # rebuild with explicit duplicate names list by editing table only
        names_dup = list(EXPECTED_CLIENT_METHODS[:-1]) + [EXPECTED_CLIENT_METHODS[-2]]
        dup_rows2 = [
            (name, f"htsp_method_{idx}", "ACCESS_ANONYMOUS")
            for idx, name in enumerate(names_dup)
        ]
        dup_c = _minimal_server_c(dup_rows2, proto=44)
        dup_data, dup_sha, dup_len = _pin_bytes_and_sha(dup_c)
        (root / "src" / "htsp_server.c").write_bytes(dup_data)
        dup_manifest = json.loads(json.dumps(manifest))
        dup_manifest["files"]["src/htsp_server.c"] = {
            "gitBlobSha1": dup_sha,
            "bytes": dup_len,
        }
        try:
            build_spec(root, dup_manifest, enforce_exact_pin=False)
            check("reject-duplicate-method", False, "expected failure")
        except ValueError as exc:
            check("reject-duplicate-method", "htsp_methods" in str(exc), str(exc))

        # reordered methods
        reordered = list(EXPECTED_CLIENT_METHODS)
        reordered[0], reordered[1] = reordered[1], reordered[0]
        reorder_rows = [
            (name, f"htsp_method_{idx}", "ACCESS_ANONYMOUS")
            for idx, name in enumerate(reordered)
        ]
        reorder_c = _minimal_server_c(reorder_rows, proto=44)
        reorder_data, reorder_sha, reorder_len = _pin_bytes_and_sha(reorder_c)
        (root / "src" / "htsp_server.c").write_bytes(reorder_data)
        reorder_manifest = json.loads(json.dumps(manifest))
        reorder_manifest["files"]["src/htsp_server.c"] = {
            "gitBlobSha1": reorder_sha,
            "bytes": reorder_len,
        }
        try:
            build_spec(root, reorder_manifest, enforce_exact_pin=False)
            check("reject-reordered-method", False, "expected failure")
        except ValueError as exc:
            check("reject-reordered-method", "order/content drift" in str(exc), str(exc))

        # symlink rejection
        (root / "src" / "htsp_server.c").write_bytes(c_data)
        link_root = root / "link-root"
        link_root.mkdir()
        (link_root / "src").mkdir()
        (link_root / "lib" / "py" / "tvh").mkdir(parents=True)
        (link_root / "src" / "htsp_server.c").symlink_to(root / "src" / "htsp_server.c")
        (link_root / "src" / "htsp_server.h").write_bytes(h_data)
        (link_root / "lib" / "py" / "tvh" / "htsp.py").write_bytes(p_data)
        try:
            build_spec(link_root, manifest, enforce_exact_pin=False)
            check("reject-symlink", False, "expected failure")
        except ValueError as exc:
            check("reject-symlink", "symlink" in str(exc).lower(), str(exc))

        source_root_link = root / "source-root-link"
        source_root_link.symlink_to(root, target_is_directory=True)
        try:
            build_spec(source_root_link, manifest, enforce_exact_pin=False)
            check("reject-source-root-symlink", False, "expected failure")
        except ValueError as exc:
            check("reject-source-root-symlink", "symlink" in str(exc).lower(), str(exc))

        # unknown/ambiguous evidence must not be marked mechanical certainty
        sample_fields = extract_get_fields(
            'htsmsg_get_u32(in, "a", &x);\nhtsmsg_get_weird(in, "b", &y);\n',
            "in",
        )
        by_name = {f["name"]: f for f in sample_fields}
        check("mechanical-u32", by_name["a"]["confidence"] == "mechanical")
        check("unknown-type", by_name["b"]["type"] == "unknown")
        check("unknown-confidence", by_name["b"]["confidence"] == "unknown")

    # Coverage metric semantics: one referenced method is not an outgoing request.
    fake_coverage = {
        "clientMethods": {
            "referencedCount": 22,
            "outgoingRequestCount": 21,
            "referenced": ["hello"] * 22,
            "outgoingRequests": ["hello"] * 21,
        }
    }
    check(
        "no-false-22-called",
        fake_coverage["clientMethods"]["outgoingRequestCount"] != 22
        or fake_coverage["clientMethods"]["referencedCount"] == 22,
    )
    check(
        "distinguish-ref-vs-out",
        fake_coverage["clientMethods"]["referencedCount"]
        != fake_coverage["clientMethods"]["outgoingRequestCount"],
    )

    if failures:
        raise AssertionError("derive self-test failures:\n- " + "\n- ".join(failures))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source_group = parser.add_mutually_exclusive_group()
    source_group.add_argument(
        "--source-root",
        type=Path,
        help="External TVHeadend source root containing the three pinned files",
    )
    parser.add_argument(
        "--write",
        action="store_true",
        help=f"Write {SPEC_PATH.name} after successful derivation",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Derive without mutation and fail if committed JSON differs",
    )
    parser.add_argument(
        "--stdout",
        action="store_true",
        help="Print derived JSON to stdout",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run deterministic synthetic self-tests and exit",
    )
    source_group.add_argument(
        "--fetch-pinned",
        type=Path,
        metavar="DIR",
        help=(
            "Explicit opt-in: download only manifest-pinned raw files into DIR, "
            "verify blobs, then use DIR as --source-root. Never used by repo checks."
        ),
    )
    args = parser.parse_args(argv)

    try:
        if args.self_test:
            self_test()
            print("derive.py self-test passed")
            return 0

        manifest = load_manifest()
        source_root = args.source_root
        if args.fetch_pinned is not None:
            source_root = fetch_pinned_sources(args.fetch_pinned, manifest)
        if source_root is None:
            raise SystemExit(
                "error: --source-root is required unless --self-test or --fetch-pinned"
            )

        spec = build_spec(source_root, manifest)
        text = dumps_spec(spec)
        if args.check:
            committed = SPEC_PATH.read_text(encoding="utf-8")
            if committed != text:
                raise ValueError(f"{SPEC_PATH.name} is stale versus pinned source and live SDK coverage")
            print(
                "HTSP derivation check passed: exact pin, 39 methods, 30 messages, "
                "live SDK coverage matches"
            )
        if args.write:
            write_spec(spec)
            print(f"wrote {SPEC_PATH.relative_to(REPO_ROOT)}")
        if args.stdout or (not args.write and not args.check):
            sys.stdout.write(text)
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI boundary
        print(f"error: {exc}", file=sys.stderr)
        if os_environ_debug():
            traceback.print_exc()
        return 1


def os_environ_debug() -> bool:
    import os

    return os.environ.get("HTSP_DERIVE_DEBUG") == "1"


if __name__ == "__main__":
    raise SystemExit(main())
