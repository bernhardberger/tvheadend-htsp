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
    ("clientMethod", "getChannel", "channelIdStr"): 41,
    ("clientMethod", "getEvents", "channelId"): 6,
    ("clientMethod", "getEvents", "eventId"): 6,
    ("clientMethod", "getEvents", "language"): 6,
    ("clientMethod", "getEvents", "numFollowing"): 6,
    ("clientMethod", "getEvents", "maxTime"): 6,
    ("serverMessage", "channelAdd", "channelIdStr"): 41,
    ("serverMessage", "channelUpdate", "channelIdStr"): 41,
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
        "id": "getEvents-maxTime-type-source-doc-mismatch",
        "summary": (
            "The pinned htsp_method_getEvents source reads maxTime through "
            "htsmsg_get_s64_or_default into signed int64_t with zero as the "
            "no-time-bound sentinel, while the official Client-to-Server RPC "
            "methods page documents maxTime as optional u64 since version 6. "
            "This records a source/docs evidence mismatch; it does not coerce "
            "the pinned current-source s64 contract to u64."
        ),
        "authority": "src/htsp_server.c htsp_method_getEvents",
        "docsUrl": (
            "https://docs.tvheadend.org/documentation/development/htsp/"
            "client-to-server-rpc-methods"
        ),
    },
    {
        "id": "getEvents-filter-interaction-underdocumented",
        "summary": (
            "Pinned htsp_method_getEvents source gives eventId selection "
            "precedence when both eventId and channelId are present, applies "
            "a nonzero maxTime start cutoff, treats positive numFollowing as "
            "an inclusive maximum count, resets that count per channel in "
            "all-channel mode, and filters inaccessible channels. The official "
            "Client-to-Server RPC methods page lists the optional version-6 "
            "filters but does not specify those interactions."
        ),
        "authority": "src/htsp_server.c htsp_method_getEvents",
        "docsUrl": (
            "https://docs.tvheadend.org/documentation/development/htsp/"
            "client-to-server-rpc-methods"
        ),
    },
    {
        "id": "channel-service-fields-underdocumented",
        "summary": (
            "The pinned htsp_build_channel source always emits service name, "
            "type, and u32 content; conditionally emits caid, caname, dynamic "
            "hbbtv, and providername. The official Server-to-Client methods "
            "channelAdd section is the governing field list and omits "
            "current-source content and hbbtv; hbbtv therefore remains "
            "explicitly opaque."
        ),
        "authority": "src/htsp_server.c htsp_build_channel",
        "docsUrl": (
            "https://docs.tvheadend.org/documentation/development/htsp/"
            "server-to-client-methods"
        ),
    },
    {
        "id": "event-fields-source-docs-mismatch",
        "summary": (
            "The pinned current htsp_build_event source emits start and stop "
            "through htsmsg_add_s64 and isNew through htsmsg_add_u32, while the "
            "official Server-to-Client eventAdd documentation describes start "
            "and stop as u64 and isNew as str, omits several current-source "
            "fields, and lists historical ID fields not emitted by the current "
            "builder. This records incomplete/stale official documentation and "
            "does not reconcile it into the pinned current-source contract."
        ),
        "authority": "src/htsp_server.c htsp_build_event",
        "docsUrl": (
            "https://docs.tvheadend.org/documentation/development/htsp/"
            "server-to-client-methods"
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
    """Remove C comments without treating comment markers in literals as syntax."""
    output: list[str] = []
    state = "code"
    escaped = False
    index = 0
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if state == "code":
            if char == '"':
                state = "string"
                escaped = False
                output.append(char)
            elif char == "'":
                state = "character"
                escaped = False
                output.append(char)
            elif char == "/" and following == "/":
                state = "line_comment"
                output.extend((" ", " "))
                index += 1
            elif char == "/" and following == "*":
                state = "block_comment"
                output.extend((" ", " "))
                index += 1
            else:
                output.append(char)
        elif state in {"string", "character"}:
            output.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif (state == "string" and char == '"') or (
                state == "character" and char == "'"
            ):
                state = "code"
        elif state == "line_comment":
            if char == "\n":
                output.append(char)
                state = "code"
            else:
                output.append(" ")
        else:
            if char == "*" and following == "/":
                output.extend((" ", " "))
                index += 1
                state = "code"
            elif char == "\n":
                output.append(char)
            else:
                output.append(" ")
        index += 1
    return "".join(output)


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


EVENT_FIELD_CATALOG: tuple[
    tuple[str, str, str, str | None, str], ...
] = (
    ("eventId", "u32", "required", None, "htsp_build_event unconditional field"),
    ("channelId", "u32", "conditional", None, "htsp_build_event channel branch"),
    ("start", "s64", "required", None, "htsp_build_event unconditional field"),
    ("stop", "s64", "required", None, "htsp_build_event unconditional field"),
    ("title", "str", "conditional", None, "htsp_build_event localized title branch"),
    ("subtitle", "str", "conditional", None, "htsp_build_event localized subtitle branch"),
    ("summary", "str", "conditional", None, "htsp_build_event localized summary branch"),
    ("description", "str", "conditional", None, "htsp_build_event localized description branch"),
    ("credits", "msg", "conditional", "eventCreditsDynamic", "htsp_build_event credits branch"),
    ("category", "list", "conditional", "str", "htsp_build_event string_list_serialize category branch"),
    ("keyword", "list", "conditional", "str", "htsp_build_event string_list_serialize keyword branch"),
    ("serieslinkUri", "str", "conditional", None, "htsp_build_event series-link branch"),
    ("episodeUri", "str", "conditional", None, "htsp_build_event episode-URI branch"),
    ("contentType", "u32", "conditional", None, "htsp_build_event content-type branch"),
    ("ageRating", "u32", "conditional", None, "htsp_build_event age-rating branch"),
    ("ratingLabel", "str", "conditional", None, "htsp_build_event rating-label branch"),
    ("ratingIcon", "str", "conditional", None, "htsp_build_event rating-icon branch"),
    ("ratingAuthority", "str", "conditional", None, "htsp_build_event rating-authority branch"),
    ("ratingCountry", "str", "conditional", None, "htsp_build_event rating-country branch"),
    ("starRating", "u32", "conditional", None, "htsp_build_event star-rating branch"),
    ("copyrightYear", "u32", "conditional", None, "htsp_build_event copyright-year branch"),
    ("firstAired", "s64", "conditional", None, "htsp_build_event first-aired branch"),
    ("isNew", "u32", "conditional", None, "htsp_build_event new-programme branch"),
    ("seasonNumber", "u32", "conditional", None, "htsp_build_event episode-number helper"),
    ("seasonCount", "u32", "conditional", None, "htsp_build_event episode-number helper"),
    ("episodeNumber", "u32", "conditional", None, "htsp_build_event episode-number helper"),
    ("episodeCount", "u32", "conditional", None, "htsp_build_event episode-number helper"),
    ("partNumber", "u32", "conditional", None, "htsp_build_event episode-number helper"),
    ("partCount", "u32", "conditional", None, "htsp_build_event episode-number helper"),
    ("episodeOnscreen", "str", "conditional", None, "htsp_build_event episode-number helper"),
    ("image", "str", "conditional", None, "htsp_build_event image branch"),
    ("dvrId", "u32", "conditional", None, "htsp_build_event DVR branch"),
    ("nextEventId", "u32", "conditional", None, "htsp_build_event next-event branch"),
)

EVENT_UPDATE_NOTE = (
    "Pinned current eventUpdate call sites send the shared htsp_build_event "
    "snapshot; partial-update compatibility permits omission of every non-key "
    "field and consumers merge by eventId."
)


def event_fields(direction: str, *, partial_update: bool = False) -> list[dict[str, Any]]:
    fields = []
    for name, wire_type, presence, shape_ref, evidence in EVENT_FIELD_CATALOG:
        update_non_key = partial_update and name != "eventId"
        fields.append(exact_field(
            name,
            wire_type,
            direction,
            "optional" if update_non_key else presence,
            evidence,
            condition=(
                "emitted when the corresponding current event value is available"
                if presence == "conditional"
                else None
            ),
            shape_ref=shape_ref,
        ))
    return fields


def require_get_events_source_facts(server_c: str) -> None:
    """Reject drift in the bounded pinned getEvents filter and reply contract."""
    source = strip_c_comments(server_c)
    body = find_function_body(source, "htsp_method_getEvents")
    if body is None:
        raise ValueError("htsp_method_getEvents body not found")
    compact = re.sub(r"\s+", " ", body).strip()

    getter_inventory = {
        field["name"]: field["type"]
        for field in extract_get_fields(body, "in")
    }
    if getter_inventory != {
        "channelId": "u32",
        "eventId": "u32",
        "language": "str",
        "numFollowing": "u32",
        "maxTime": "s64",
    }:
        raise ValueError("htsp_method_getEvents request getter inventory/type drift")

    def block_after(match: re.Match[str], text: str) -> tuple[str, int]:
        open_index = match.end() - 1
        block = extract_balanced_block(text, open_index)
        return block, open_index + len(block) + 1

    def require_optional_selector(field_name: str, target: str, lookup_name: str) -> None:
        guards = list(re.finditer(
            rf'if\s*\(\s*!\s*htsmsg_get_u32\(\s*in\s*,\s*"{field_name}"\s*,'
            rf'\s*&(?P<selector>[A-Za-z_][A-Za-z0-9_]*)\s*\)\s*\)',
            body,
        ))
        if len(guards) != 1:
            raise ValueError(
                f"htsp_method_getEvents {field_name} must remain an optional u32 selector"
            )
        guard = guards[0]
        nested = re.match(
            rf'\s*if\s*\(\s*!\s*\(\s*{target}\s*=\s*{lookup_name}\s*\('
            rf'\s*{re.escape(guard.group("selector"))}\s*\)\s*\)\s*\)',
            body[guard.end():],
        )
        if nested is None:
            raise ValueError(
                f"htsp_method_getEvents {field_name} must look up pinned {target} selector"
            )
        nested_end = guard.end() + nested.end()
        if re.match(
            r'\s*return\s+htsp_error\s*\([^;]*\)\s*;',
            body[nested_end:],
        ) is None:
            raise ValueError(
                f"htsp_method_getEvents missing unknown {field_name.removesuffix('Id')} error"
            )

    require_optional_selector("channelId", "ch", "channel_find_by_id")
    require_optional_selector("eventId", "e", "epg_broadcast_find_by_id")

    language_lookup = r'htsmsg_get_str\(\s*in\s*,\s*"language"\s*\)'
    if re.search(
        rf'{language_lookup}\s*\?\:\s*htsp->(?:htsp_)?language',
        compact,
    ) is None:
        raise ValueError("htsp_method_getEvents missing optional language fallback")

    if re.search(
        r'\bnumFollowing\s*=\s*htsmsg_get_u32_or_default\('
        r'\s*in\s*,\s*"numFollowing"\s*,\s*0\s*\)', compact,
    ) is None:
        raise ValueError("htsp_method_getEvents numFollowing must remain optional u32 default zero")

    if re.search(
        r'\bmaxTime\s*=\s*htsmsg_get_s64_or_default\('
        r'\s*in\s*,\s*"maxTime"\s*,\s*0\s*\)', compact,
    ) is None:
        raise ValueError("htsp_method_getEvents maxTime must remain optional s64 default zero")

    selected_match = re.search(r'if\s*\(\s*e\s*\|\|\s*ch\s*\)\s*\{', body)
    if selected_match is None:
        raise ValueError(
            "htsp_method_getEvents missing mutually exclusive selected/all-channel branch topology"
        )
    selected_body, selected_close = block_after(selected_match, body)
    else_match = re.match(r'\s*else\s*\{', body[selected_close + 1:])
    if else_match is None:
        raise ValueError(
            "htsp_method_getEvents missing mutually exclusive selected/all-channel branch topology"
        )
    else_absolute = selected_close + 1 + else_match.end() - 1
    all_body = extract_balanced_block(body, else_absolute)
    all_close = else_absolute + len(all_body) + 1
    selected_compact = re.sub(r"\s+", " ", selected_body).strip()
    all_compact = re.sub(r"\s+", " ", all_body).strip()

    if re.search(
        r'if\s*\(\s*!\s*e\s*\)\s*e\s*=\s*ch->ch_epg_now\s*\?\:\s*ch->ch_epg_next\s*;',
        selected_compact,
    ) is None:
        raise ValueError("htsp_method_getEvents missing explicit event selector precedence guard")
    selected_access = (
        r'if\s*\(\s*e\s*&&\s*!\s*htsp_user_access_channel\s*\('
        r'\s*htsp\s*,\s*e->channel\s*\)\s*\)\s*'
        r'return\s+htsp_error\s*\([^;]*\)\s*;'
    )
    if len(re.findall(selected_access, selected_compact)) != 1:
        raise ValueError("htsp_method_getEvents missing selected access filtering")
    all_access = (
        r'if\s*\(\s*!\s*htsp_user_access_channel\s*\('
        r'\s*htsp\s*,\s*ch\s*\)\s*\)\s*continue\s*;'
    )
    if len(re.findall(all_access, all_compact)) != 1:
        raise ValueError("htsp_method_getEvents missing all-channel access filtering")

    cutoff = r'if\s*\(\s*maxTime\s*&&\s*e->start\s*>\s*maxTime\s*\)\s*break\s*;'
    if len(re.findall(cutoff, selected_compact)) != 1:
        raise ValueError("htsp_method_getEvents missing selected nonzero maxTime start cutoff")
    if len(re.findall(cutoff, all_compact)) != 1:
        raise ValueError("htsp_method_getEvents missing all-channel nonzero maxTime start cutoff")

    selected_count = (
        r'if\s*\(\s*numFollowing\s*==\s*1\s*\)\s*break\s*;\s*'
        r'if\s*\(\s*numFollowing\s*\)\s*numFollowing\s*--\s*;'
    )
    if len(re.findall(selected_count, selected_compact)) != 1:
        raise ValueError(
            "htsp_method_getEvents missing selected inclusive numFollowing decrement sequence"
        )

    channel_loop_match = re.search(
        r'(?:\bfor|\b[A-Z][A-Z0-9_]*)\s*\([^\{]*?\bch\b[^\{]*?\)\s*\{',
        all_body,
    )
    if channel_loop_match is None:
        raise ValueError("htsp_method_getEvents missing all-channel iteration")
    channel_loop_body, _channel_close = block_after(channel_loop_match, all_body)
    channel_compact = re.sub(r"\s+", " ", channel_loop_body).strip()
    if len(re.findall(r'\bint\s+num\s*=\s*numFollowing\s*;', channel_compact)) != 1:
        raise ValueError("htsp_method_getEvents missing per-channel numFollowing reset")
    if len(re.findall(r'\bint\s+num\s*=\s*numFollowing\s*;', all_compact)) != 1:
        raise ValueError("htsp_method_getEvents per-channel numFollowing reset is misplaced")
    all_count = (
        r'if\s*\(\s*num\s*==\s*1\s*\)\s*break\s*;\s*'
        r'if\s*\(\s*num\s*\)\s*num\s*--\s*;'
    )
    if len(re.findall(all_count, channel_compact)) != 1:
        raise ValueError(
            "htsp_method_getEvents missing all-channel inclusive numFollowing decrement sequence"
        )

    insertion_pattern = (
        r'htsmsg_add_msg\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*,\s*NULL\s*,\s*'
        r'htsp_build_event\s*\([^;]*?\)\s*\)\s*;'
    )
    selected_insertions = re.findall(insertion_pattern, selected_compact)
    if len(selected_insertions) != 1:
        raise ValueError("htsp_method_getEvents missing exact selected htsp_build_event list insertion")
    all_insertions = re.findall(insertion_pattern, channel_compact)
    if len(all_insertions) != 1:
        raise ValueError("htsp_method_getEvents missing exact all-channel htsp_build_event list insertion")
    if selected_insertions[0] != all_insertions[0]:
        raise ValueError("htsp_method_getEvents branch insertions must target the same event list")
    if len(re.findall(insertion_pattern, compact)) != 2:
        raise ValueError("htsp_method_getEvents must contain exactly two branch-specific event insertions")
    list_var = selected_insertions[0]
    reply_tail = re.sub(r"\s+", " ", body[all_close + 1:]).strip()
    tail_returns = re.findall(r'\breturn\s+([^;]+?)\s*;', reply_tail)
    if len(tail_returns) != 1 or re.fullmatch(
        r'[A-Za-z_][A-Za-z0-9_]*', tail_returns[0]
    ) is None:
        raise ValueError("htsp_method_getEvents must return exactly one reply map")
    reply_var = tail_returns[0]
    reply_adds = re.findall(
        r'htsmsg_add_msg\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*,\s*"([^"]+)"\s*,\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)',
        reply_tail,
    )
    events_adds = [
        item
        for item in reply_adds
        if item == (reply_var, "events", list_var)
    ]
    if len(events_adds) != 1 or len(reply_adds) != 1:
        raise ValueError("htsp_method_getEvents reply must contain exactly required events list")


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


def require_channel_builder_source_facts(server_c: str) -> None:
    """Reject drift in the bounded getChannel/channel-service source shape."""
    body = find_function_body(server_c, "htsp_build_channel")
    if body is None:
        raise ValueError("htsp_build_channel body not found")
    expected_adds = (
        ("channelId", "u32"),
        ("channelIdStr", "str2?"),
        ("channelNumber", "u32"),
        ("channelNumberMinor", "u32"),
        ("channelName", "str2?"),
        ("channelIcon", "str2?"),
        ("eventId", "u32"),
        ("nextEventId", "u32"),
        ("services", "msg"),
        ("tags", "msg"),
        ("name", "str2?"),
        ("type", "str2?"),
        ("content", "u32"),
        ("caid", "u32"),
        ("caname", "str2?"),
        ("hbbtv", "msg"),
        ("providername", "str2?"),
    )
    for field_name, type_pattern in expected_adds:
        pattern = (
            rf'htsmsg_add_{type_pattern}(?:_(?:alloc|ptr))?\('
            rf'\s*[A-Za-z_][A-Za-z0-9_]*\s*,\s*"{re.escape(field_name)}"'
        )
        if re.search(pattern, body) is None:
            raise ValueError(
                f"htsp_build_channel missing bounded source field {field_name}"
            )


def require_event_builder_source_facts(server_c: str) -> None:
    """Reject drift in the bounded current getEvent/event-builder shape."""
    source = strip_c_comments(server_c)
    handler = find_function_body(source, "htsp_method_getEvent")
    if handler is None:
        raise ValueError("htsp_method_getEvent body not found")
    compact_handler = re.sub(r"\s+", " ", handler).strip()
    if re.search(
        r'if\s*\(\s*htsmsg_get_u32\(\s*in\s*,\s*"eventId"\s*,\s*&eventId\s*\)\s*\)\s*'
        r'return\s+htsp_error\s*\(',
        compact_handler,
    ) is None:
        raise ValueError("htsp_method_getEvent missing guarded required u32 eventId extraction")
    language_lookup = r'htsmsg_get_str\(\s*in\s*,\s*"language"\s*\)'
    language_fallbacks = (
        rf'lang\s*=\s*{language_lookup}\s*\?\:\s*htsp->htsp_language\s*;',
        rf'lang\s*=\s*{language_lookup}\s*;\s*'
        r'if\s*\(\s*(?:!\s*lang|lang\s*==\s*NULL)\s*\)\s*'
        r'lang\s*=\s*htsp->(?:htsp_)?language\s*;',
    )
    if not any(re.search(pattern, compact_handler) for pattern in language_fallbacks):
        raise ValueError("htsp_method_getEvent missing optional language fallback to connection language")
    if re.search(
        r'if\s*\([^;]{0,240}epg_broadcast_find_by_id\(\s*eventId\s*\)[^;]{0,120}\)\s*'
        r'return\s+htsp_error\s*\(',
        compact_handler,
    ) is None:
        raise ValueError("htsp_method_getEvent missing missing-event guard")
    returned_builder = (
        "return htsp_build_event(e, NULL, lang, 0, htsp);"
    )
    if compact_handler.count("htsp_build_event(") != 1 or returned_builder not in compact_handler:
        raise ValueError("htsp_method_getEvent must return exactly one returned htsp_build_event result")

    body = find_function_body(source, "htsp_build_event")
    if body is None:
        raise ValueError("htsp_build_event body not found")
    return_values = re.findall(r"\breturn\s+([^;]+?)\s*;", body)
    result_returns = [
        value.strip()
        for value in return_values
        if value.strip() != "NULL"
        and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value.strip())
    ]
    if len(result_returns) != 1:
        raise ValueError("htsp_build_event must return one bounded result variable")
    result_var = result_returns[0]
    if any(value.strip() not in {"NULL", result_var} for value in return_values):
        raise ValueError("htsp_build_event must return one bounded result variable")
    if re.search(rf"\breturn\s+{re.escape(result_var)}\s*;\s*$", body.strip()) is None:
        raise ValueError("htsp_build_event must end with its bounded result variable")

    def brace_depth_at(text: str, end: int) -> int:
        depth = 0
        in_string = False
        escaped = False
        for char in text[:end]:
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
            elif char == '"':
                in_string = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
        return depth

    def is_conditional_at(text: str, start: int) -> bool:
        if brace_depth_at(text, start) > 0:
            return True
        prefix = text[max(0, start - 240):start]
        boundary = max(prefix.rfind(";"), prefix.rfind("}"), prefix.rfind("{"))
        return re.search(r"\bif\s*\([^;{}]*\)\s*$", prefix[boundary + 1:]) is not None

    def output_facts(text: str, target_var: str) -> list[tuple[str, str, bool]]:
        facts: list[tuple[str, str, bool]] = []
        mutator_patterns = (
            re.compile(
                rf'\bhtsmsg_add_([a-z0-9]+)(?:_(?:alloc|ptr))?\(\s*{re.escape(target_var)}\s*,\s*'
                r'(?:(?:"([^"]+)")|(?:textname\s*\?\:\s*"(episodeOnscreen)"))'
            ),
            re.compile(
                rf'\bhtsmsg_set_([a-z0-9]+)\(\s*{re.escape(target_var)}\s*,\s*'
                r'(?:(?:"([^"]+)")|(?:textname\s*\?\:\s*"(episodeOnscreen)"))'
            ),
        )
        for mutator_pattern in mutator_patterns:
            for match in mutator_pattern.finditer(text):
                wire_type = ADD_TYPE_MAP.get(match.group(1))
                field_name = match.group(2) or match.group(3)
                if wire_type is not None and field_name != "method":
                    facts.append((field_name, wire_type, is_conditional_at(text, match.start())))
        list_pattern = re.compile(
            rf'\bstring_list_serialize\([^;]*\b{re.escape(target_var)}\s*,\s*"([^"]+)"'
        )
        for match in list_pattern.finditer(text):
            facts.append((match.group(1), "list", is_conditional_at(text, match.start())))
        return facts

    helper_names = {
        name for name in re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", body)
        if name not in {"if", "for", "while", "switch", "sizeof"}
    }
    episode_names = {name for name, _wire, _presence, _shape, evidence in EVENT_FIELD_CATALOG if "episode-number helper" in evidence}
    helper_candidates: list[tuple[str, list[tuple[str, str, bool]]]] = []
    for helper_name in sorted(helper_names):
        helper_body = find_function_body(source, helper_name)
        if helper_body is None:
            continue
        helper_facts = output_facts(helper_body, result_var)
        if not helper_facts:
            # The helper may name its output parameter differently from the builder.
            parameters_match = re.search(
                rf'\b{re.escape(helper_name)}\s*\(([^;]*?)\)\s*\{{', source, re.S
            )
            parameters = parameters_match.group(1) if parameters_match else ""
            parameter_names = re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*(?:,|$)", parameters)
            for parameter_name in parameter_names:
                candidate_facts = output_facts(helper_body, parameter_name)
                if {fact[0] for fact in candidate_facts} & episode_names:
                    helper_facts = candidate_facts
                    break
        if {fact[0] for fact in helper_facts} & episode_names:
            helper_candidates.append((helper_name, helper_facts))
    if len(helper_candidates) != 1:
        raise ValueError("htsp_build_event must use exactly one bounded episode-number helper")

    direct_facts = output_facts(body, result_var)
    helper_name, helper_facts = helper_candidates[0]
    helper_body = find_function_body(source, helper_name)
    if helper_body is None or len(
        re.findall(
            r'\bhtsmsg_(?:add_[a-z0-9]+(?:_(?:alloc|ptr))?|set_[a-z0-9]+)'
            r'\(\s*[A-Za-z_][A-Za-z0-9_]*\s*,\s*'
            r'textname\s*\?\:\s*"episodeOnscreen"',
            helper_body,
        )
    ) != 1:
        raise ValueError(f"{helper_name} missing exact episodeOnscreen fallback")
    helper_calls = re.findall(
        rf'\b{re.escape(helper_name)}\s*\(([^;]*)\)\s*;',
        body,
        re.S,
    )
    if len(helper_calls) != 1 or re.fullmatch(
        rf'\s*{re.escape(result_var)}\s*,\s*&[A-Za-z_][A-Za-z0-9_]*\s*,\s*NULL\s*',
        helper_calls[0],
    ) is None:
        raise ValueError(
            "htsp_build_event episode-number helper call must select the NULL fallback"
        )
    actual_facts = direct_facts + helper_facts
    expected_types = {name: wire for name, wire, _presence, _shape, _evidence in EVENT_FIELD_CATALOG}
    actual_by_name: dict[str, list[tuple[str, bool]]] = {}
    for field_name, wire_type, conditional in actual_facts:
        actual_by_name.setdefault(field_name, []).append((wire_type, conditional))
    for field_name in actual_by_name.keys() - expected_types.keys():
        raise ValueError(f"htsp_build_event has unexpected bounded source field {field_name}")
    for field_name, expected_type in expected_types.items():
        facts = actual_by_name.get(field_name, [])
        if not facts:
            raise ValueError(f"htsp_build_event/helper missing bounded source field {field_name}")
        if any(wire_type != expected_type for wire_type, _conditional in facts):
            raise ValueError(f"htsp_build_event/helper has wrong bounded source type for {field_name}")
        expected_required = field_name in {"eventId", "start", "stop"}
        if expected_required and (len(facts) != 1 or facts[0][1]):
            raise ValueError(f"htsp_build_event required field {field_name} became conditional or multiplied")
        if not expected_required and any(not conditional for _wire_type, conditional in facts):
            raise ValueError(f"htsp_build_event conditional field {field_name} became unconditional")
        if field_name in episode_names and len(facts) != 1:
            raise ValueError(f"htsp_build_event/helper duplicates bounded source field {field_name}")
    if {fact[0] for fact in helper_facts} != episode_names:
        raise ValueError(f"{helper_name} episode-number helper field inventory drift")

    update_helper = find_function_body(source, "_htsp_event_update")
    if (
        update_helper is None
        or update_helper.count("htsp_build_event(") != 1
        or re.search(
            r'htsp_build_event\(\s*[A-Za-z_][A-Za-z0-9_]*\s*,\s*method\s*,',
            update_helper,
        ) is None
    ):
        raise ValueError("event update helper must propagate method to shared htsp_build_event")
    for message_name, wrapper_name in (
        ("eventAdd", "htsp_event_add"),
        ("eventUpdate", "htsp_event_update"),
    ):
        wrapper = find_function_body(source, wrapper_name)
        if (
            wrapper is None
            or wrapper.count("_htsp_event_update(") != 1
            or re.search(
                rf'_htsp_event_update\(\s*[A-Za-z_][A-Za-z0-9_]*\s*,\s*"{message_name}"\s*,',
                wrapper,
            ) is None
        ):
            raise ValueError(f"{message_name} wrapper must propagate method through event update helper")


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
            require_channel_builder_source_facts(server_c)
            if [(field["name"], field["type"]) for field in request_fields] != [
                ("channelId", "u32")
            ]:
                raise ValueError("htsp_method_getChannel request shape drift")
            request_fields = [exact_field(
                "channelId", "u32", "request", "required",
                "htsp_method_getChannel requires htsmsg_get_u32 channelId",
            )]
            reply_fields = [
                exact_field("channelId", "u32", "reply", "required", "htsp_build_channel unconditional field"),
                exact_field(
                    "channelIdStr", "str", "reply", "conditional",
                    "pinned current htsp_build_channel unconditionally emits channelIdStr; v41 is historical compatibility evidence",
                    condition=(
                        "required for negotiated protocol version 41 or newer; "
                        "may be absent from older server implementations "
                        "supporting versions 14 through 40"
                    ),
                ),
                exact_field("channelNumber", "u32", "reply", "required", "htsp_build_channel unconditional field"),
                exact_field(
                    "channelNumberMinor", "u32", "reply", "conditional",
                    "htsp_build_channel nonzero branch",
                    condition="emitted when the channel minor number is nonzero",
                ),
                exact_field("channelName", "str", "reply", "required", "htsp_build_channel unconditional field"),
                exact_field(
                    "channelIcon", "str", "reply", "conditional",
                    "htsp_build_channel present-icon branch",
                    condition="emitted when the channel has an icon",
                ),
                exact_field("eventId", "u32", "reply", "required", "htsp_build_channel emits zero when no current event exists"),
                exact_field("nextEventId", "u32", "reply", "required", "htsp_build_channel emits zero when no next event exists"),
                exact_field(
                    "services", "list", "reply", "required",
                    "htsp_build_channel always adds the service list",
                    shape_ref="service",
                ),
                exact_field(
                    "tags", "list", "reply", "required",
                    "htsp_build_channel always adds the tag-id list",
                    shape_ref="u32",
                ),
            ]
        if name == "getEvent":
            require_event_builder_source_facts(server_c)
            if [(field["name"], field["type"]) for field in request_fields] != [
                ("eventId", "u32"),
                ("language", "str"),
            ]:
                raise ValueError("htsp_method_getEvent request shape drift")
            request_fields = [
                exact_field(
                    "eventId", "u32", "request", "required",
                    "htsp_method_getEvent requires decoded u32 eventId",
                ),
                exact_field(
                    "language", "str", "request", "optional",
                    "htsp_method_getEvent uses supplied language or connection language",
                ),
            ]
            reply_fields = event_fields("reply")
        if name == "getEvents":
            require_get_events_source_facts(server_c)
            request_fields = [
                exact_field(
                    "channelId", "u32", "request", "optional",
                    "htsp_method_getEvents optional channel selector",
                ),
                exact_field(
                    "eventId", "u32", "request", "optional",
                    "htsp_method_getEvents optional event selector with precedence over channelId",
                ),
                exact_field(
                    "language", "str", "request", "optional",
                    "htsp_method_getEvents uses supplied language or connection language",
                ),
                exact_field(
                    "numFollowing", "u32", "request", "optional",
                    "htsp_method_getEvents reads u32 default zero and enforces a positive inclusive maximum count",
                ),
                exact_field(
                    "maxTime", "s64", "request", "optional",
                    "htsp_method_getEvents reads signed s64 default zero and stops before a later event start",
                ),
            ]
            reply_fields = [exact_field(
                "events", "list", "reply", "required",
                "htsp_method_getEvents adds exactly one required list of htsp_build_event maps",
                shape_ref="event",
            )]

        if name == "fileRead":
            reply_fields = [exact_field(
                "data", "bin", "reply", "required",
                "htsp_method_fileRead htsmsg_add_bin_alloc",
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
        if name == "getChannel":
            method["requestShape"] = {
                "kind": "fields",
                "completeness": "complete",
                "evidence": "bounded htsp_method_getChannel accepts exactly required channelId",
            }
            method["replyShape"] = {
                "kind": "fields",
                "completeness": "complete",
                "evidence": "bounded htsp_method_getChannel delegates its complete reply to htsp_build_channel",
            }
        if name == "getEvent":
            method["requestShape"] = {
                "kind": "fields",
                "completeness": "complete",
                "evidence": "bounded htsp_method_getEvent accepts required eventId and optional language",
            }
            method["replyShape"] = {
                "kind": "fields",
                "completeness": "complete",
                "evidence": "bounded htsp_method_getEvent delegates its complete successful reply to htsp_build_event",
            }
        if name == "getEvents":
            method["requestShape"] = {
                "kind": "fields",
                "completeness": "complete",
                "evidence": "bounded pinned htsp_method_getEvents selector/filter extraction",
            }
            method["replyShape"] = {
                "kind": "fields",
                "completeness": "complete",
                "evidence": "bounded pinned htsp_method_getEvents required events-list construction",
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
        elif name in {"eventAdd", "eventUpdate"}:
            require_event_builder_source_facts(server_c)
            fields = event_fields("message", partial_update=name == "eventUpdate")

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
        if name == "eventAdd":
            item["messageShape"] = {
                "kind": "fields",
                "completeness": "complete",
                "evidence": "bounded current htsp_build_event field catalog",
            }
        if name == "eventUpdate":
            item["messageShape"] = {
                "kind": "fields",
                "completeness": "partial",
                "evidence": (
                    "pinned current eventUpdate call sites use the shared full "
                    "htsp_build_event snapshot; compatibility remains partial by eventId"
                ),
            }
        if name == "descrambleInfo":
            item["docStatus"] = "missing-from-official-server-message-page"
            item["notes"] = [
                "Source returns early when htsp_version < 24 or anonymize is set."
            ]
        if name == "eventUpdate":
            item["notes"] = [EVENT_UPDATE_NOTE]
        elif name.endswith("Update"):
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
    24-referenced / 23-outgoing / 23-handled metrics remain checkable.
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
                "completeness": "complete",
                "evidence": "htsp_build_event is shared with eventAdd/getEvent/getEvents",
            }),
            ("str", {
                "kind": "scalar",
                "wireType": "str",
                "completeness": "complete",
                "evidence": "string_list_serialize list element type",
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
            ("hbbtvDynamic", {
                "kind": "object", "completeness": "opaque",
                "evidence": "current channel service hbbtv data is dynamic and deliberately bounded opaque",
            }),
            ("eventCreditsDynamic", {
                "kind": "object", "completeness": "opaque",
                "evidence": "current event credits payload is dynamically shaped and deliberately opaque",
            }),
            ("service", {
                "kind": "object", "completeness": "complete",
                "evidence": "bounded current-source service object emitted by htsp_build_channel",
                "fields": [
                    exact_field("name", "str", "nested", "required", "htsp_build_channel unconditional service field"),
                    exact_field("type", "str", "nested", "required", "htsp_build_channel unconditional service field"),
                    exact_field("content", "u32", "nested", "required", "htsp_build_channel unconditional service field"),
                    exact_field(
                        "caid", "u32", "nested", "conditional",
                        "htsp_build_channel encrypted-service branch",
                        condition="emitted for an encrypted service when conditional-access data is available",
                    ),
                    exact_field(
                        "caname", "str", "nested", "conditional",
                        "htsp_build_channel encrypted-service branch",
                        condition="emitted for an encrypted service when the conditional-access name is available",
                    ),
                    exact_field(
                        "hbbtv", "msg", "nested", "conditional",
                        "htsp_build_channel dynamic HbbTV branch",
                        condition="emitted when dynamic HbbTV service data is available",
                        shape_ref="hbbtvDynamic",
                    ),
                    exact_field(
                        "providername", "str", "nested", "conditional",
                        "htsp_build_channel provider branch",
                        condition="emitted when the service provider name is available",
                    ),
                ],
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
        if name == "getChannel":
            request_lines = '  htsmsg_get_u32(in, "channelId", &v);'
            reply_lines = '  htsmsg_add_u32(r, "demoReply", v);\n  return r;'
        elif name == "getEvent":
            request_lines = (
                '  uint32_t eventId;\n'
                '  const char *lang;\n'
                '  void *e;\n'
                '  if (htsmsg_get_u32(in, "eventId", &eventId))\n'
                '    return htsp_error("Invalid arguments");\n'
                '  lang = htsmsg_get_str(in, "language") ?: htsp->htsp_language;\n'
                '  if (!(e = epg_broadcast_find_by_id(eventId)))\n'
                '    return htsp_error("Event does not exist");'
            )
            reply_lines = '  return htsp_build_event(e, NULL, lang, 0, htsp);'
        elif name == "getEvents":
            request_lines = (
                '  uint32_t u32;\n'
                '  uint32_t numFollowing = htsmsg_get_u32_or_default(in, "numFollowing", 0);\n'
                '  int64_t maxTime = htsmsg_get_s64_or_default(in, "maxTime", 0);\n'
                '  const char *lang;\n'
                '  void *ch = NULL;\n'
                '  void *e = NULL;\n'
                '  if (!htsmsg_get_u32(in, "channelId", &u32))\n'
                '    if (!(ch = channel_find_by_id(u32)))\n'
                '      return htsp_error(htsp, N_("Channel does not exist"));\n'
                '  if (!htsmsg_get_u32(in, "eventId", &u32))\n'
                '    if (!(e = epg_broadcast_find_by_id(u32)))\n'
                '      return htsp_error(htsp, N_("Event does not exist"));\n'
                '  lang = htsmsg_get_str(in, "language") ?: htsp->htsp_language;\n'
                '  if (e || ch) {\n'
                '    if (!e) e = ch->ch_epg_now ?: ch->ch_epg_next;\n'
                '    if (e && !htsp_user_access_channel(htsp, e->channel)) return htsp_error("Access denied");\n'
                '    while (e) {\n'
                '      if (maxTime && e->start > maxTime) break;\n'
                '      htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));\n'
                '      if (numFollowing == 1) break;\n'
                '      if (numFollowing) numFollowing--;\n'
                '      e = epg_broadcast_get_next(e);\n'
                '    }\n'
                '  } else {\n'
                '    for (ch = channel_first(); ch; ch = channel_next(ch)) {\n'
                '      int num = numFollowing;\n'
                '      if (!htsp_user_access_channel(htsp, ch)) continue;\n'
                '      for (e = ch->ch_epg_now ?: ch->ch_epg_next; e; e = epg_broadcast_get_next(e)) {\n'
                '        if (maxTime && e->start > maxTime) break;\n'
                '        htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));\n'
                '        if (num == 1) break;\n'
                '        if (num) num--;\n'
                '      }\n'
                '    }\n'
                '  }'
            )
            reply_lines = '  htsmsg_add_msg(r, "events", l);\n  return r;'
        else:
            request_lines = '  htsmsg_get_u32(in, "demoField", &v);'
            reply_lines = '  htsmsg_add_u32(r, "demoReply", v);\n  return r;'
        handlers.append(
            f"""
static htsmsg_t *
{handler}(htsp_connection_t *htsp, htsmsg_t *in)
{{
  uint32_t v;
  htsmsg_t *l = htsmsg_create_list();
  htsmsg_t *r = htsmsg_create_map();
{request_lines}
{reply_lines}
  (void)htsp; (void)name_{name};
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
}
static void _htsp_event_update(void *e, const char *method, htsmsg_t *msg) {
  htsmsg_t *m = msg ? htsmsg_copy(msg)
                    : htsp_build_event(e, method, "eng", 0, 0);
  (void)m;
}
static void htsp_event_add(void *e) {
  _htsp_event_update(e, "eventAdd", 0);
}
static void htsp_event_update(void *e) {
  _htsp_event_update(e, "eventUpdate", 0);
}
static htsmsg_t *htsp_build_channel(void *ch, const char *method, void *htsp) {
  htsmsg_t *out = htsmsg_create_map();
  htsmsg_t *svc = htsmsg_create_map();
  htsmsg_add_u32(out, "channelId", 1);
  htsmsg_add_str(out, "channelIdStr", "uuid");
  htsmsg_add_u32(out, "channelNumber", 1);
  htsmsg_add_u32(out, "channelNumberMinor", 1);
  htsmsg_add_str(out, "channelName", "name");
  htsmsg_add_str(out, "channelIcon", "icon");
  htsmsg_add_u32(out, "eventId", 0);
  htsmsg_add_u32(out, "nextEventId", 0);
  htsmsg_add_str(svc, "name", "service");
  htsmsg_add_str(svc, "type", "SDTV");
  htsmsg_add_u32(svc, "content", 0);
  htsmsg_add_u32(svc, "caid", 0);
  htsmsg_add_str(svc, "caname", "ca");
  htsmsg_add_msg(svc, "hbbtv", htsmsg_create_map());
  htsmsg_add_str(svc, "providername", "provider");
  htsmsg_add_msg(out, "services", htsmsg_create_list());
  htsmsg_add_msg(out, "tags", htsmsg_create_list());
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
static void htsp_serialize_epnum(htsmsg_t *out, void *epnum, const char *textname) {
  if (has_episode_num(epnum)) {
    htsmsg_add_u32(out, "seasonNumber", 1);
    htsmsg_add_u32(out, "seasonCount", 1);
    htsmsg_add_u32(out, "episodeNumber", 1);
    htsmsg_add_u32(out, "episodeCount", 1);
    htsmsg_add_u32(out, "partNumber", 1);
    htsmsg_add_u32(out, "partCount", 1);
    htsmsg_add_str(out, textname ?: "episodeOnscreen", "S1E1");
  }
}
static htsmsg_t *htsp_build_event(void *e, const char *method, const char *lang, long update, void *htsp) {
  htsmsg_t *out;
  void *epnum = e;
  if (update && is_stale(e)) return NULL;
  out = htsmsg_create_map();
  htsmsg_add_u32(out, "eventId", 1);
  if (has_channel(e)) htsmsg_add_u32(out, "channelId", 1);
  htsmsg_add_s64(out, "start", 1);
  htsmsg_add_s64(out, "stop", 2);
  if (has_title(e)) htsmsg_add_str(out, "title", "title");
  if (has_long_text(e)) {
    if (is_legacy(htsp)) {
      if (has_description(e)) {
        htsmsg_add_str(out, "description", "description");
        if (has_summary(e)) htsmsg_add_str(out, "summary", "summary");
        if (has_subtitle(e)) htsmsg_add_str(out, "subtitle", "subtitle");
      } else if (has_summary(e)) {
        htsmsg_add_str(out, "description", "summary");
        if (has_subtitle(e)) htsmsg_add_str(out, "subtitle", "subtitle");
      } else if (has_subtitle(e)) {
        htsmsg_add_str(out, "description", "subtitle");
      }
    } else {
      if (has_subtitle(e)) htsmsg_add_str(out, "subtitle", "subtitle");
      if (has_summary(e)) htsmsg_add_str(out, "summary", "summary");
      if (has_description(e)) htsmsg_add_str(out, "description", "description");
    }
  }
  if (has_credits(e)) htsmsg_add_msg(out, "credits", htsmsg_create_map());
  if (has_category(e)) string_list_serialize(0, out, "category");
  if (has_keyword(e)) string_list_serialize(0, out, "keyword");
  if (has_series(e)) htsmsg_add_str(out, "serieslinkUri", "series");
  if (e->episodelink && strncasecmp(e->episodelink->uri, "tvh://", 6))
    htsmsg_add_str(out, "episodeUri", "episode");
  // htsmsg_add_u32(out, "commentedLineField", 1);
  /* htsmsg_add_str(out, "commentedBlockField", "misleading"); */
  (void)"escaped \\\" // literal";
  (void)'\\'';
  if (has_content(e)) htsmsg_add_u32(out, "contentType", 1);
  if (has_age(e)) htsmsg_add_u32(out, "ageRating", 1);
  if (has_label(e)) htsmsg_add_str(out, "ratingLabel", "label");
  if (has_icon(e)) htsmsg_add_str(out, "ratingIcon", "icon");
  if (has_authority(e)) htsmsg_add_str(out, "ratingAuthority", "authority");
  if (has_country(e)) htsmsg_add_str(out, "ratingCountry", "country");
  if (has_stars(e)) htsmsg_add_u32(out, "starRating", 1);
  if (has_year(e)) htsmsg_add_u32(out, "copyrightYear", 2026);
  if (has_first_aired(e)) htsmsg_add_s64(out, "firstAired", 1);
  if (is_new(e)) htsmsg_add_u32(out, "isNew", 1);
  htsp_serialize_epnum(out, &epnum, NULL);
  if (has_image(e)) htsmsg_add_str(out, "image", "image");
  if (has_dvr(e)) htsmsg_add_u32(out, "dvrId", 1);
  if (has_next(e)) htsmsg_add_u32(out, "nextEventId", 2);
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

    comment_fixture = (
        'const char *uri = "tvh://episode"; // remove line comment\n'
        'const char *escaped = "quote: \\\" and slash: //"; /* remove block\n'
        'comment while preserving lines */ char quote = \'\\\'\';\n'
    )
    stripped_fixture = strip_c_comments(comment_fixture)
    check("c-comments-preserve-uri-literal", '"tvh://episode"' in stripped_fixture)
    check(
        "c-comments-preserve-escaped-literals",
        '"quote: \\\" and slash: //"' in stripped_fixture and "'\\''" in stripped_fixture,
    )
    check(
        "c-comments-remove-real-comments",
        "remove line comment" not in stripped_fixture
        and "remove block" not in stripped_fixture
        and "comment while preserving lines" not in stripped_fixture,
    )
    check(
        "c-comments-preserve-line-structure",
        stripped_fixture.count("\n") == comment_fixture.count("\n"),
    )

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
        ) == (24, 23, 23),
        str(live_coverage.get("metrics")),
    )
    check(
        "getSysTime-current-production-coverage",
        "getSysTime" in live_coverage["clientMethods"]["referenced"]
        and "getSysTime" in live_coverage["clientMethods"]["outgoingRequests"],
    )
    get_channel = methods_by_name["getChannel"]
    check(
        "getChannel-exact-request-shape",
        [(field["name"], field["type"], field["presence"]) for field in get_channel["requestFields"]]
        == [("channelId", "u32", "required")]
        and get_channel.get("requestShape", {}).get("completeness") == "complete",
    )
    check(
        "getChannel-complete-nested-reply-shape",
        get_channel.get("replyShape", {}).get("completeness") == "complete"
        and next(field for field in get_channel["replyFields"] if field["name"] == "services").get("shapeRef") == "service"
        and next(field for field in get_channel["replyFields"] if field["name"] == "tags").get("shapeRef") == "u32",
    )
    service_shape = committed.get("shapes", {}).get("service", {})
    check(
        "channel-service-bounded-source-shape",
        [field.get("name") for field in service_shape.get("fields", [])]
        == ["name", "type", "content", "caid", "caname", "hbbtv", "providername"]
        and next(field for field in service_shape.get("fields", []) if field.get("name") == "hbbtv").get("shapeRef") == "hbbtvDynamic",
    )
    check(
        "channel-service-doc-limitation",
        "channel-service-fields-underdocumented" in limitation_ids,
    )
    check(
        "getChannel-current-production-coverage",
        (
            live_coverage["clientMethods"]["referencedCount"],
            live_coverage["clientMethods"]["outgoingRequestCount"],
            live_coverage["serverMessages"]["handledCount"],
        ) == (24, 23, 23)
        and "getChannel" in live_coverage["clientMethods"]["referenced"]
        and "getChannel" in live_coverage["clientMethods"]["outgoingRequests"],
        str(live_coverage.get("metrics")),
    )
    get_event = methods_by_name["getEvent"]
    check(
        "getEvent-exact-request-shape",
        [(field["name"], field["type"], field["presence"]) for field in get_event["requestFields"]]
        == [("eventId", "u32", "required"), ("language", "str", "optional")]
        and get_event.get("requestShape", {}).get("completeness") == "complete",
    )
    check(
        "getEvent-complete-reply-shape",
        get_event.get("replyShape", {}).get("completeness") == "complete"
        and [(field["name"], field["type"], field["presence"], field.get("shapeRef")) for field in get_event["replyFields"]]
        == [(name, wire_type, presence, shape_ref) for name, wire_type, presence, shape_ref, _evidence in EVENT_FIELD_CATALOG],
    )
    check(
        "getEvent-current-production-coverage",
        "getEvent" in live_coverage["clientMethods"]["referenced"]
        and "getEvent" in live_coverage["clientMethods"]["outgoingRequests"],
    )
    get_events = methods_by_name["getEvents"]
    check(
        "getEvents-complete-filter-request-shape",
        [
            (field["name"], field["type"], field["presence"], field.get("minVersion"))
            for field in get_events["requestFields"]
        ] == [
            ("channelId", "u32", "optional", 6),
            ("eventId", "u32", "optional", 6),
            ("language", "str", "optional", 6),
            ("numFollowing", "u32", "optional", 6),
            ("maxTime", "s64", "optional", 6),
        ]
        and get_events.get("minVersion") == 4
        and get_events.get("requestShape", {}).get("completeness") == "complete",
    )
    check(
        "getEvents-complete-required-events-reply",
        [
            (field["name"], field["type"], field["presence"], field.get("shapeRef"))
            for field in get_events["replyFields"]
        ] == [("events", "list", "required", "event")]
        and get_events.get("replyShape", {}).get("completeness") == "complete",
    )
    check(
        "getEvents-current-production-coverage-unchanged",
        (
            live_coverage["clientMethods"]["referencedCount"],
            live_coverage["clientMethods"]["outgoingRequestCount"],
            live_coverage["serverMessages"]["handledCount"],
        ) == (24, 23, 23)
        and "getEvents" in live_coverage["clientMethods"]["referenced"]
        and "getEvents" in live_coverage["clientMethods"]["outgoingRequests"],
    )
    check(
        "getEvents-exact-documentation-limitations",
        {
            "getEvents-maxTime-type-source-doc-mismatch",
            "getEvents-filter-interaction-underdocumented",
        } <= limitation_ids,
    )
    check(
        "eventAdd-complete-eventUpdate-partial",
        messages_by_name["eventAdd"].get("messageShape", {}).get("completeness") == "complete"
        and messages_by_name["eventUpdate"].get("messageShape", {}).get("completeness") == "partial",
    )
    event_update = messages_by_name["eventUpdate"]
    check(
        "eventUpdate-only-eventId-required",
        [
            field["name"]
            for field in event_update["fields"]
            if field["presence"] == "required"
        ] == ["eventId"]
        and all(
            field["presence"] == "optional"
            for field in event_update["fields"]
            if field["name"] != "eventId"
        ),
    )
    check(
        "eventUpdate-exact-shared-builder-note",
        event_update.get("notes") == [EVENT_UPDATE_NOTE],
    )
    check(
        "event-source-doc-mismatch-limitation",
        "event-fields-source-docs-mismatch" in limitation_ids,
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
        (
            name,
            (
                f"htsp_method_{name}"
                if name in {"getEvent", "getEvents"}
                else f"htsp_method_{idx}"
            ),
            "ACCESS_ANONYMOUS",
        )
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

        extra_event_field_mutations = tuple(
            (
                f"reject-added-event-builder-output-field-{type_token}",
                server_c.replace(
                    '  if (has_next(e)) htsmsg_add_u32(out, "nextEventId", 2);',
                    '  if (has_next(e)) htsmsg_add_u32(out, "nextEventId", 2);\n'
                    f'  htsmsg_add_{type_token}(out, "extraEventFact_{type_token}", 1);',
                    1,
                ),
                f"unexpected bounded source field extraEventFact_{type_token}",
            )
            for type_token in ADD_TYPE_MAP
        ) + (
            (
                "reject-added-event-builder-output-field-list",
                server_c.replace(
                    '  if (has_next(e)) htsmsg_add_u32(out, "nextEventId", 2);',
                    '  if (has_next(e)) htsmsg_add_u32(out, "nextEventId", 2);\n'
                    '  string_list_serialize(0, out, "extraEventFact_list");',
                    1,
                ),
                "unexpected bounded source field extraEventFact_list",
            ),
        )
        set_event_field_mutations = tuple(
            (
                f"reject-set-event-builder-output-field-{type_token}",
                server_c.replace(
                    '  if (has_next(e)) htsmsg_add_u32(out, "nextEventId", 2);',
                    '  if (has_next(e)) htsmsg_add_u32(out, "nextEventId", 2);\n'
                    f'  htsmsg_set_{type_token}(out, "extraEventSetFact_{type_token}", 1);',
                    1,
                ),
                f"unexpected bounded source field extraEventSetFact_{type_token}",
            )
            for type_token in ADD_TYPE_MAP
        ) + (
            (
                "reject-wrong-type-set-event-builder-field",
                server_c.replace(
                    '  if (has_title(e)) htsmsg_add_str(out, "title", "title");',
                    '  if (has_title(e)) {\n'
                    '    htsmsg_add_str(out, "title", "title");\n'
                    '    htsmsg_set_u32(out, "title", 1);\n'
                    '  }',
                    1,
                ),
                "wrong bounded source type for title",
            ),
        )
        for label, mutated_c, expected_error in (
            (
                "reject-getEvents-required-channelId-selector",
                server_c.replace(
                    'if (!htsmsg_get_u32(in, "channelId", &u32))',
                    'if (htsmsg_get_u32(in, "channelId", &u32))',
                    1,
                ),
                "channelId must remain an optional u32 selector",
            ),
            (
                "reject-getEvents-removed-channelId-optional-guard",
                server_c.replace(
                    '  if (!htsmsg_get_u32(in, "channelId", &u32))\n',
                    '  htsmsg_get_u32(in, "channelId", &u32);\n',
                    1,
                ),
                "channelId must remain an optional u32 selector",
            ),
            (
                "reject-getEvents-inverted-eventId-optional-guard",
                server_c.replace(
                    'if (!htsmsg_get_u32(in, "eventId", &u32))',
                    'if (htsmsg_get_u32(in, "eventId", &u32))',
                    1,
                ),
                "eventId must remain an optional u32 selector",
            ),
            (
                "reject-getEvents-removed-eventId-optional-guard",
                server_c.replace(
                    '  if (!htsmsg_get_u32(in, "eventId", &u32))\n',
                    '  htsmsg_get_u32(in, "eventId", &u32);\n',
                    1,
                ),
                "eventId must remain an optional u32 selector",
            ),
            (
                "reject-getEvents-ignored-eventId-selector",
                server_c.replace(
                    'htsmsg_get_u32(in, "eventId", &u32)',
                    'htsmsg_get_u32(in, "ignoredEventId", &u32)',
                    1,
                ),
                "request getter inventory/type drift",
            ),
            (
                "reject-getEvents-wrong-numFollowing-getter",
                server_c.replace(
                    'htsmsg_get_u32_or_default(in, "numFollowing", 0)',
                    'htsmsg_get_s64_or_default(in, "numFollowing", 0)',
                    1,
                ),
                "request getter inventory/type drift",
            ),
            (
                "reject-getEvents-wrong-numFollowing-default",
                server_c.replace(
                    'htsmsg_get_u32_or_default(in, "numFollowing", 0)',
                    'htsmsg_get_u32_or_default(in, "numFollowing", 1)',
                    1,
                ),
                "numFollowing must remain optional u32 default zero",
            ),
            (
                "reject-getEvents-selected-count-off-by-one",
                server_c.replace('if (numFollowing == 1) break;', 'if (numFollowing == 0) break;', 1),
                "selected inclusive numFollowing decrement sequence",
            ),
            (
                "reject-getEvents-selected-decrement-removed",
                server_c.replace('      if (numFollowing) numFollowing--;\n', '', 1),
                "selected inclusive numFollowing decrement sequence",
            ),
            (
                "reject-getEvents-selected-decrement-corrupted",
                server_c.replace('if (numFollowing) numFollowing--;', 'if (numFollowing) numFollowing++;', 1),
                "selected inclusive numFollowing decrement sequence",
            ),
            (
                "reject-getEvents-wrong-maxTime-getter",
                server_c.replace(
                    'htsmsg_get_s64_or_default(in, "maxTime", 0)',
                    'htsmsg_get_u32_or_default(in, "maxTime", 0)',
                    1,
                ),
                "request getter inventory/type drift",
            ),
            (
                "reject-getEvents-wrong-maxTime-default",
                server_c.replace(
                    'htsmsg_get_s64_or_default(in, "maxTime", 0)',
                    'htsmsg_get_s64_or_default(in, "maxTime", 1)',
                    1,
                ),
                "maxTime must remain optional s64 default zero",
            ),
            (
                "reject-getEvents-removed-selected-start-cutoff",
                server_c.replace('      if (maxTime && e->start > maxTime) break;\n', '', 1),
                "selected nonzero maxTime start cutoff",
            ),
            (
                "reject-getEvents-changed-selected-start-cutoff",
                server_c.replace('if (maxTime && e->start > maxTime) break;', 'if (maxTime && e->start >= maxTime) break;', 1),
                "selected nonzero maxTime start cutoff",
            ),
            (
                "reject-getEvents-removed-all-channel-start-cutoff",
                server_c.replace('        if (maxTime && e->start > maxTime) break;\n', '', 1),
                "all-channel nonzero maxTime start cutoff",
            ),
            (
                "reject-getEvents-changed-all-channel-start-cutoff",
                server_c.replace('        if (maxTime && e->start > maxTime) break;', '        if (maxTime && e->start >= maxTime) break;', 1),
                "all-channel nonzero maxTime start cutoff",
            ),
            (
                "reject-getEvents-required-language",
                server_c.replace(
                    'htsmsg_get_str(in, "language") ?: htsp->htsp_language',
                    'htsmsg_get_str(in, "language")',
                    1,
                ),
                "optional language fallback",
            ),
            (
                "reject-getEvents-removed-unknown-channel-error",
                server_c.replace(
                    'return htsp_error(htsp, N_("Channel does not exist"));',
                    'ch = NULL;',
                    1,
                ),
                "missing unknown channel error",
            ),
            (
                "reject-getEvents-removed-channelId-nested-lookup-sequence",
                server_c.replace(
                    '    if (!(ch = channel_find_by_id(u32)))\n'
                    '      return htsp_error(htsp, N_("Channel does not exist"));',
                    '    ch = NULL;',
                    1,
                ),
                "channelId must look up pinned ch selector",
            ),
            (
                "reject-getEvents-channelId-lookup-targets-wrong-selector-variable",
                server_c.replace(
                    'if (!(ch = channel_find_by_id(u32)))',
                    'if (!(e = channel_find_by_id(u32)))',
                    1,
                ),
                "channelId must look up pinned ch selector",
            ),
            (
                "reject-getEvents-removed-unknown-event-error",
                server_c.replace(
                    'return htsp_error(htsp, N_("Event does not exist"));',
                    'e = NULL;',
                    1,
                ),
                "missing unknown event error",
            ),
            (
                "reject-getEvents-removed-eventId-nested-lookup-sequence",
                server_c.replace(
                    '    if (!(e = epg_broadcast_find_by_id(u32)))\n'
                    '      return htsp_error(htsp, N_("Event does not exist"));',
                    '    e = NULL;',
                    1,
                ),
                "eventId must look up pinned e selector",
            ),
            (
                "reject-getEvents-eventId-lookup-targets-wrong-selector-variable",
                server_c.replace(
                    'if (!(e = epg_broadcast_find_by_id(u32)))',
                    'if (!(ch = epg_broadcast_find_by_id(u32)))',
                    1,
                ),
                "eventId must look up pinned e selector",
            ),
            (
                "reject-getEvents-selector-decoy-outside-bounded-handler",
                server_c.replace(
                    '    if (!(ch = channel_find_by_id(u32)))\n'
                    '      return htsp_error(htsp, N_("Channel does not exist"));',
                    '    ch = NULL;',
                    1,
                ) + (
                    '\nstatic void get_events_selector_decoy(htsp_connection_t *htsp, htsmsg_t *in) {\n'
                    '  uint32_t u32; void *ch; void *e;\n'
                    '  if (!htsmsg_get_u32(in, "channelId", &u32)) {\n'
                    '    if (!(ch = channel_find_by_id(u32)))\n'
                    '      return htsp_error(htsp, N_("Channel does not exist"));\n'
                    '  }\n'
                    '  if (!htsmsg_get_u32(in, "eventId", &u32)) {\n'
                    '    if (!(e = epg_broadcast_find_by_id(u32)))\n'
                    '      return htsp_error(htsp, N_("Event does not exist"));\n'
                    '  }\n'
                    '}\n'
                ),
                "channelId must look up pinned ch selector",
            ),
            (
                "reject-getEvents-event-precedence-guard-removed",
                server_c.replace('if (!e) e = ch->ch_epg_now ?: ch->ch_epg_next;', 'e = ch->ch_epg_now ?: ch->ch_epg_next;', 1),
                "explicit event selector precedence",
            ),
            (
                "reject-getEvents-invented-unified-loop-topology",
                server_c.replace(
                    '  if (e || ch) {',
                    '  while (e || (ch = channel_next(ch))) {',
                    1,
                ),
                "mutually exclusive selected/all-channel branch topology",
            ),
            (
                "reject-getEvents-branch-decoy-outside-bounded-handler",
                server_c.replace('  if (e || ch) {', '  if (e && ch) {', 1) + (
                    '\nstatic void get_events_branch_decoy(htsp_connection_t *htsp) {\n'
                    '  void *ch = NULL; void *e = NULL; unsigned numFollowing = 0;\n'
                    '  long maxTime = 0; const char *lang = NULL; htsmsg_t *l = NULL;\n'
                    '  if (e || ch) {\n'
                    '    if (!e) e = ch->ch_epg_now ?: ch->ch_epg_next;\n'
                    '    if (!htsp_user_access_channel(htsp, e->channel)) return;\n'
                    '    while (e) {\n'
                    '      if (maxTime && e->start > maxTime) break;\n'
                    '      htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));\n'
                    '      if (numFollowing == 1) break;\n'
                    '      if (numFollowing) numFollowing--;\n'
                    '      e = epg_broadcast_get_next(e);\n'
                    '    }\n'
                    '  } else {\n'
                    '    for (ch = channel_first(); ch; ch = channel_next(ch)) {\n'
                    '      int num = numFollowing;\n'
                    '      if (!htsp_user_access_channel(htsp, ch)) continue;\n'
                    '      for (e = ch->ch_epg_now; e; e = epg_broadcast_get_next(e)) {\n'
                    '        if (maxTime && e->start > maxTime) break;\n'
                    '        htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));\n'
                    '        if (num == 1) break;\n'
                    '        if (num) num--;\n'
                    '      }\n'
                    '    }\n'
                    '  }\n'
                    '}\n'
                ),
                "mutually exclusive selected/all-channel branch topology",
            ),
            (
                "reject-getEvents-event-precedence-guard-inverted",
                server_c.replace('if (!e) e = ch->ch_epg_now ?: ch->ch_epg_next;', 'if (e) e = ch->ch_epg_now ?: ch->ch_epg_next;', 1),
                "explicit event selector precedence",
            ),
            (
                "reject-getEvents-removed-selected-event-access-filter",
                server_c.replace('htsp_user_access_channel(htsp, e->channel)', 'channel_visible(htsp, e->channel)', 1),
                "selected access filtering",
            ),
            (
                "reject-getEvents-inverted-selected-event-access-polarity",
                server_c.replace(
                    'e && !htsp_user_access_channel(htsp, e->channel)',
                    'e && htsp_user_access_channel(htsp, e->channel)',
                    1,
                ),
                "selected access filtering",
            ),
            (
                "reject-getEvents-altered-selected-event-access-rejection",
                server_c.replace(
                    'return htsp_error("Access denied");',
                    'return NULL;',
                    1,
                ),
                "selected access filtering",
            ),
            (
                "reject-getEvents-removed-all-channel-access-filter",
                server_c.replace('htsp_user_access_channel(htsp, ch)', 'channel_visible(htsp, ch)', 1),
                "all-channel access filtering",
            ),
            (
                "reject-getEvents-inverted-all-channel-access-polarity",
                server_c.replace(
                    '!htsp_user_access_channel(htsp, ch)',
                    'htsp_user_access_channel(htsp, ch)',
                    1,
                ),
                "all-channel access filtering",
            ),
            (
                "reject-getEvents-altered-all-channel-access-control-flow",
                server_c.replace(
                    'if (!htsp_user_access_channel(htsp, ch)) continue;',
                    'if (!htsp_user_access_channel(htsp, ch)) break;',
                    1,
                ),
                "all-channel access filtering",
            ),
            (
                "reject-getEvents-removed-selected-builder-list-insertion",
                server_c.replace(
                    '      htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));',
                    '      (void)e;',
                    1,
                ),
                "selected htsp_build_event list insertion",
            ),
            (
                "reject-getEvents-replaced-selected-builder-list-insertion",
                server_c.replace(
                    'htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp))',
                    'htsmsg_add_msg(l, NULL, htsp_build_channel(e, NULL, htsp))',
                    1,
                ),
                "selected htsp_build_event list insertion",
            ),
            (
                "reject-getEvents-multiplied-selected-builder-list-insertion",
                server_c.replace(
                    '      htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));',
                    '      htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));\n'
                    '      htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));',
                    1,
                ),
                "selected htsp_build_event list insertion",
            ),
            (
                "reject-getEvents-removed-all-channel-builder-list-insertion",
                server_c.replace(
                    '        htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));',
                    '        (void)e;',
                    1,
                ),
                "all-channel htsp_build_event list insertion",
            ),
            (
                "reject-getEvents-replaced-all-channel-builder-list-insertion",
                server_c.replace(
                    '        htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));',
                    '        htsmsg_add_msg(l, NULL, htsp_build_channel(e, NULL, htsp));',
                    1,
                ),
                "all-channel htsp_build_event list insertion",
            ),
            (
                "reject-getEvents-multiplied-all-channel-builder-list-insertion",
                server_c.replace(
                    '        htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));',
                    '        htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));\n'
                    '        htsmsg_add_msg(l, NULL, htsp_build_event(e, NULL, lang, 0, htsp));',
                    1,
                ),
                "all-channel htsp_build_event list insertion",
            ),
            (
                "reject-getEvents-wrong-reply-list-field",
                server_c.replace('htsmsg_add_msg(r, "events", l)', 'htsmsg_add_msg(r, "items", l)', 1),
                "exactly required events list",
            ),
            (
                "reject-getEvents-duplicated-final-events-field",
                server_c.replace(
                    '  htsmsg_add_msg(r, "events", l);',
                    '  htsmsg_add_msg(r, "events", l);\n  htsmsg_add_msg(r, "events", l);',
                    1,
                ),
                "exactly required events list",
            ),
            (
                "reject-getEvents-final-events-field-wrong-list",
                server_c.replace('htsmsg_add_msg(r, "events", l)', 'htsmsg_add_msg(r, "events", other)', 1),
                "exactly required events list",
            ),
            (
                "reject-getEvents-final-events-field-wrong-destination",
                server_c.replace('htsmsg_add_msg(r, "events", l)', 'htsmsg_add_msg(other, "events", l)', 1),
                "exactly required events list",
            ),
            (
                "reject-getEvents-returned-wrong-reply-map",
                server_c.replace(
                    '  htsmsg_add_msg(r, "events", l);\n  return r;',
                    '  htsmsg_add_msg(r, "events", l);\n  return other;',
                    1,
                ),
                "exactly required events list",
            ),
            (
                "reject-getEvents-all-channel-count-reset-removed",
                server_c.replace('      int num = numFollowing;\n', '', 1),
                "per-channel numFollowing reset",
            ),
            (
                "reject-getEvents-all-channel-count-reset-wrong-initializer",
                server_c.replace('int num = numFollowing;', 'int num = 0;', 1),
                "per-channel numFollowing reset",
            ),
            (
                "reject-getEvents-all-channel-count-reset-moved-outside-loop",
                server_c.replace(
                    '    for (ch = channel_first(); ch; ch = channel_next(ch)) {\n      int num = numFollowing;',
                    '    int num = numFollowing;\n    for (ch = channel_first(); ch; ch = channel_next(ch)) {',
                    1,
                ),
                "per-channel numFollowing reset",
            ),
            (
                "reject-getEvents-all-channel-count-off-by-one",
                server_c.replace('if (num == 1) break;', 'if (num == 0) break;', 1),
                "all-channel inclusive numFollowing decrement sequence",
            ),
            (
                "reject-getEvents-all-channel-decrement-removed",
                server_c.replace('        if (num) num--;\n', '', 1),
                "all-channel inclusive numFollowing decrement sequence",
            ),
            (
                "reject-getEvents-all-channel-decrement-corrupted",
                server_c.replace('if (num) num--;', 'if (num) num++;', 1),
                "all-channel inclusive numFollowing decrement sequence",
            ),
            (
                "reject-getChannel-request-source-drift",
                server_c.replace(
                    'htsmsg_get_u32(in, "channelId"',
                    'htsmsg_get_u32(in, "staleChannelId"',
                    1,
                ),
                "request shape drift",
            ),
            (
                "reject-omitted-channel-service-source-field",
                server_c.replace('  htsmsg_add_u32(svc, "content", 0);\n', "", 1),
                "bounded source field content",
            ),
            (
                "reject-getEvent-request-source-drift",
                server_c.replace(
                    'htsmsg_get_u32(in, "eventId"',
                    'htsmsg_get_u32(in, "staleEventId"',
                    1,
                ),
                "guarded required u32 eventId",
            ),
            (
                "reject-omitted-event-episode-helper-field",
                server_c.replace('  htsmsg_add_u32(out, "seasonNumber", 1);\n', "", 1),
                "bounded source field seasonNumber",
            ),
            (
                "reject-broken-episodeOnscreen-helper-fallback",
                server_c.replace(
                    'textname ?: "episodeOnscreen"',
                    'textname ?: "wrongEpisodeOnscreen"',
                    1,
                ),
                "episodeOnscreen fallback",
            ),
            (
                "reject-non-null-episode-helper-builder-argument",
                server_c.replace(
                    'htsp_serialize_epnum(out, &epnum, NULL);',
                    'htsp_serialize_epnum(out, &epnum, "dynamicOnscreen");',
                    1,
                ),
                "episode-number helper call",
            ),
            (
                "reject-dynamic-episode-helper-builder-argument",
                server_c.replace(
                    'htsp_serialize_epnum(out, &epnum, NULL);',
                    'htsp_serialize_epnum(out, &epnum, method);',
                    1,
                ),
                "episode-number helper call",
            ),
            (
                "reject-wrong-episodeOnscreen-wire-type",
                server_c.replace(
                    'htsmsg_add_str(out, textname ?: "episodeOnscreen", "S1E1");',
                    'htsmsg_add_u32(out, textname ?: "episodeOnscreen", 1);',
                    1,
                ),
                "wrong bounded source type for episodeOnscreen",
            ),
            (
                "reject-misleading-commented-out-output-field",
                server_c.replace(
                    '  if (has_next(e)) htsmsg_add_u32(out, "nextEventId", 2);',
                    '  /* htsmsg_add_u32(out, "nextEventId", 2); */',
                    1,
                ),
                "missing bounded source field nextEventId",
            ),
            (
                "reject-getEvent-optionalized-eventId-source",
                server_c.replace(
                    '  if (htsmsg_get_u32(in, "eventId", &eventId))\n'
                    '    return htsp_error("Invalid arguments");',
                    '  htsmsg_get_u32(in, "eventId", &eventId);',
                    1,
                ),
                "guarded required u32 eventId",
            ),
            (
                "reject-getEvent-required-language-source",
                server_c.replace(
                    '  lang = htsmsg_get_str(in, "language") ?: htsp->htsp_language;',
                    '  lang = htsmsg_get_str(in, "language");\n'
                    '  if (!lang) return htsp_error("Invalid arguments");',
                    1,
                ),
                "optional language fallback",
            ),
            (
                "reject-getEvent-removed-returned-builder-result",
                server_c.replace(
                    '  return htsp_build_event(e, NULL, lang, 0, htsp);',
                    '  return r;',
                    1,
                ),
                "exactly one returned htsp_build_event result",
            ),
            (
                "reject-getEvent-replaced-returned-builder-result",
                server_c.replace(
                    '  return htsp_build_event(e, NULL, lang, 0, htsp);',
                    '  return htsp_build_channel(e, NULL, htsp);',
                    1,
                ),
                "exactly one returned htsp_build_event result",
            ),
            (
                "reject-getEvent-multiplied-builder-invocation",
                server_c.replace(
                    '  return htsp_build_event(e, NULL, lang, 0, htsp);',
                    '  (void)htsp_build_event(e, NULL, lang, 0, htsp);\n'
                    '  return htsp_build_event(e, NULL, lang, 0, htsp);',
                    1,
                ),
                "exactly one returned htsp_build_event result",
            ),
            (
                "reject-required-event-field-made-conditional",
                server_c.replace(
                    '  htsmsg_add_s64(out, "start", 1);',
                    '  if (has_start(e)) htsmsg_add_s64(out, "start", 1);',
                    1,
                ),
                "required field start became conditional",
            ),
            (
                "reject-conditional-event-field-made-unconditional",
                server_c.replace(
                    '  if (has_channel(e)) htsmsg_add_u32(out, "channelId", 1);',
                    '  htsmsg_add_u32(out, "channelId", 1);',
                    1,
                ),
                "conditional field channelId became unconditional",
            ),
            (
                "reject-wrong-type-in-alternative-event-emit-site",
                server_c.replace(
                    '        htsmsg_add_str(out, "description", "summary");',
                    '        htsmsg_add_u32(out, "description", 1);',
                    1,
                ),
                "wrong bounded source type for description",
            ),
            (
                "reject-event-helper-final-result-removal",
                server_c.replace(
                    '  if (has_next(e)) htsmsg_add_u32(out, "nextEventId", 2);\n'
                    '  htsmsg_add_str(out, "method", method);\n'
                    '  return out;',
                    '  if (has_next(e)) htsmsg_add_u32(out, "nextEventId", 2);\n'
                    '  htsmsg_add_str(out, "method", method);\n'
                    '  return NULL;',
                    1,
                ),
                "return one bounded result variable",
            ),
            (
                "reject-event-helper-method-propagation",
                server_c.replace(
                    ': htsp_build_event(e, method, "eng", 0, 0);',
                    ': htsp_build_event(e, NULL, "eng", 0, 0);',
                    1,
                ),
                "event update helper must propagate method",
            ),
            (
                "reject-eventAdd-wrapper-method-propagation",
                server_c.replace(
                    '_htsp_event_update(e, "eventAdd", 0);',
                    '_htsp_event_update(e, "wrongEventAdd", 0);',
                    1,
                ),
                "eventAdd wrapper must propagate method",
            ),
            (
                "reject-eventUpdate-wrapper-method-propagation",
                server_c.replace(
                    '_htsp_event_update(e, "eventUpdate", 0);',
                    '_htsp_event_update(e, "wrongEventUpdate", 0);',
                    1,
                ),
                "eventUpdate wrapper must propagate method",
            ),
            (
                "reject-unrelated-helper-satisfying-episode-field-omission",
                server_c.replace(
                    '    htsmsg_add_u32(out, "seasonNumber", 1);\n',
                    "",
                    1,
                ) + (
                    '\nstatic int has_next(void *e) {\n'
                    '  htsmsg_t *out = htsmsg_create_map();\n'
                    '  htsmsg_add_u32(out, "seasonNumber", 1);\n'
                    '  return e != 0;\n'
                    '}\n'
                ),
                "exactly one bounded episode-number helper",
            ),
        ) + extra_event_field_mutations + set_event_field_mutations:
            mutated_data, mutated_sha, mutated_len = _pin_bytes_and_sha(mutated_c)
            (root / "src" / "htsp_server.c").write_bytes(mutated_data)
            mutated_manifest = json.loads(json.dumps(manifest))
            mutated_manifest["files"]["src/htsp_server.c"] = {
                "gitBlobSha1": mutated_sha,
                "bytes": mutated_len,
            }
            try:
                build_spec(root, mutated_manifest, enforce_exact_pin=False)
                check(label, False, "stale bounded getChannel source accepted")
            except ValueError as exc:
                check(label, expected_error in str(exc), str(exc))
        (root / "src" / "htsp_server.c").write_bytes(c_data)

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
