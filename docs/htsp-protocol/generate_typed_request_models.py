#!/usr/bin/env python3
"""Generate the reviewed typed HTSP request, response, and codec models."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tempfile
from dataclasses import asdict, replace
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from htsp_surface import (
    CATALOG,
    GENERATED_OUTPUT_BY_KEY,
    REPLY_FIELDS_BY_METHOD,
    REQUEST_AUXILIARY_DECLARATIONS,
    REQUEST_MODEL_CATALOG,
    REQUEST_NESTED_KDOCS,
    REQUEST_PUBLIC_DECLARATIONS,
    REQUEST_REPLY_NESTED_SHAPES,
    REQUEST_REPLY_TERMINAL_NESTED_TARGETS,
    REQUEST_SPEC_CONSISTENCY_STATUS,
    REQUEST_SPEC_WAIVERS,
    REQUEST_VERBATIM_ESCAPES,
    self_test_generated_output_metadata,
    validate_generated_output_metadata,
    REQUEST_FIELDS_BY_METHOD,
    REQUEST_WIRE_FIELDS,
    REPLY_WIRE_FIELDS,
    REPLY_CONDITIONAL_PRESENCE_RULES,
    REPLY_COUPLED_PRESENCE_GROUPS,
    ConditionalPresenceRule,
    CoupledPresenceGroup,
    Entry,
    KotlinDeclaration,
    KotlinProperty,
    NestedShape,
    RequestModelSpec,
    WireField,
)

ROOT = SCRIPT_DIR.parents[1]
OUTPUTS = {
    key: ROOT / GENERATED_OUTPUT_BY_KEY[key].relative_path
    for key in ("request-models", "jsonapi-models")
}


def _kdoc(text: str | None) -> list[str]:
    if text is None:
        return []
    if "\n" not in text:
        return [f"/** {text} */"]
    return ["/**", *(" *" if not line else f" * {line}" for line in text.splitlines()), " */"]


def _property_line(prop: KotlinProperty, indent: str = "    ") -> str:
    default = "" if prop.default is None else f" = {prop.default}"
    modifier = "" if not prop.modifier else f"{prop.modifier} "
    visibility = "" if prop.visibility is None else f"{prop.visibility} "
    return f"{indent}{modifier}{visibility}val {prop.name}: {prop.kotlin_type}{default},"


def _standard_declaration(value: KotlinDeclaration) -> list[str]:
    lines = [*_kdoc(value.kdoc), *value.annotations]
    if value.kind == "data object":
        return [*lines, f"{value.visibility} data object {value.name}", ""]
    if value.kind == "sealed interface":
        return [*lines, f"{value.visibility} sealed interface {value.name}", ""]
    if value.kind == "enum class":
        lines.append(f"{value.visibility} enum class {value.name} {{")
        lines.extend(f"    {item}," for item in value.enum_values)
        return [*lines, "}", ""]
    supertype = "" if value.supertype is None else f" : {value.supertype}"
    if len(value.properties) == 1:
        prop = value.properties[0]
        modifier = "" if not prop.modifier else f"{prop.modifier} "
        lines.append(
            f"{value.visibility} {value.kind} {value.name}("
            f"{modifier}public val {prop.name}: {prop.kotlin_type}){supertype}"
        )
        return [*lines, ""]
    lines.append(f"{value.visibility} {value.kind} {value.name}(")
    lines.extend(_property_line(prop) for prop in value.properties)
    lines.append(f"){supertype}")
    lines.append("")
    return lines


def _special_declaration(value: KotlinDeclaration) -> list[str]:
    prefix = _kdoc(value.kdoc) + list(value.annotations)
    if value.feature == "hello-response":
        return [*prefix,
            "public class HelloResponse(",
            "    public val htspVersion: Long,", "    public val serverName: String?,",
            "    public val serverVersion: String?,", "    public val challenge: HtspBinary,",
            "    public val webRoot: String?,", "    public val language: String?,",
            "    serverCapabilities: List<String>?,", "    public val apiVersion: Long?,", ") {",
            "    public val serverCapabilities: List<String>? = serverCapabilities?.immutableSnapshot()", "",
            "    override fun equals(other: Any?): Boolean =", "        this === other ||",
            "            other is HelloResponse &&", "            htspVersion == other.htspVersion &&",
            "            serverName == other.serverName &&", "            serverVersion == other.serverVersion &&",
            "            challenge == other.challenge &&", "            webRoot == other.webRoot &&",
            "            language == other.language &&", "            serverCapabilities == other.serverCapabilities &&",
            "            apiVersion == other.apiVersion", "", "    override fun hashCode(): Int {",
            "        var result = htspVersion.hashCode()", "        result = 31 * result + (serverName?.hashCode() ?: 0)",
            "        result = 31 * result + (serverVersion?.hashCode() ?: 0)",
            "        result = 31 * result + challenge.hashCode()", "        result = 31 * result + (webRoot?.hashCode() ?: 0)",
            "        result = 31 * result + (language?.hashCode() ?: 0)",
            "        result = 31 * result + (serverCapabilities?.hashCode() ?: 0)",
            "        result = 31 * result + (apiVersion?.hashCode() ?: 0)", "        return result", "    }", "",
            "    override fun toString(): String =",
            '        "HelloResponse(htspVersion=$htspVersion, serverName=$serverName, " +',
            '            "serverVersion=$serverVersion, challenge=$challenge, webRoot=$webRoot, " +',
            '            "language=$language, serverCapabilities=$serverCapabilities, apiVersion=$apiVersion)"',
            "}", ""]
    if value.feature == "epg-query-response":
        return [*prefix, "public sealed interface EpgQueryResponse {",
            "    public data class EventIds(public val eventIds: List<Long>) : EpgQueryResponse",
            "    public data class Events(public val events: List<HtspEvent>) : EpgQueryResponse",
            "}", ""]
    if value.feature == "dvr-mutation-response":
        return [*prefix, "public sealed interface HtspDvrMutationResponse {",
            "    public val success: Long?", "    public val error: String?", "    public val entryId: Long?",
            "        get() = null", "}", ""]
    if value.feature == "recording-rule-channel":
        return [*prefix, "public sealed interface HtspRecordingRuleChannel {",
            "    /** Select one channel by its complete unsigned HTSP channel ID. */", "    @JvmInline",
            "    public value class Id(public val channelId: Long) : HtspRecordingRuleChannel {", "        init {",
            '            requireU32("channelId", channelId)', "        }", "    }", "",
            "    /**", "     * Emit the v25 signed `-1` any-channel sentinel.", "     *",
            "     * Pinned update source also clears to any channel when `channel` is omitted;",
            "     * this selector makes that intent explicit. Add support requires HTSP v25.", "     */",
            "    public data object Any : HtspRecordingRuleChannel", "}", ""]
    if value.feature == "get-ticket-selector":
        return [*prefix, "public sealed interface GetTicketSelector {",
            "    /** Select one channel by its complete unsigned HTSP channel ID. */", "    @JvmInline",
            "    public value class Channel(public val channelId: Long) : GetTicketSelector {", "        init {",
            '            requireU32("channelId", channelId)', "        }", "    }", "",
            "    /** Select one DVR entry by its complete unsigned HTSP DVR ID. */", "    @JvmInline",
            "    public value class Dvr(public val dvrId: Long) : GetTicketSelector {", "        init {",
            '            requireU32("dvrId", dvrId)', "        }", "    }", "}", ""]
    if value.feature == "get-ticket-response":
        return [*prefix, "public class GetTicketResponse(", "    public val path: String,",
            "    public val ticket: String,", ") {", "    override fun toString(): String =",
            '        "GetTicketResponse(path=<redacted>, ticket=<redacted>)"', "}", ""]
    if value.feature == "api-response":
        return [*prefix, "public sealed interface ApiResponse {", "    /** A successful map or list payload. */",
            "    @HtspJsonApi", "    public data class Payload(public val value: HtspApiContainer) : ApiResponse", "",
            "    /** A successful callback that supplied no response payload. */", "    @HtspJsonApi",
            "    public data object NoPayload : ApiResponse", "}", ""]
    if value.feature == "add-dvr-selector":
        return [*prefix, "public sealed interface AddDvrEntrySelector {",
            "    public data class Event(public val eventId: Long) : AddDvrEntrySelector {", "        init {",
            '            requireU32("eventId", eventId)', "        }", "    }", "",
            "    public data class ExplicitChannelTime(", "        public val channelId: Long,",
            "        public val start: Long,", "        public val stop: Long,", "    ) : AddDvrEntrySelector {",
            "        init {", '            requireU32("channelId", channelId)', "        }", "    }", "}", ""]
    if value.feature == "subscribe-channel":
        return [*prefix, "public sealed interface SubscribeChannel {",
            "    public data class Id(public val channelId: Long) : SubscribeChannel {", "        init {",
            '            requireU32("channelId", channelId)', "        }", "    }", "",
            "    public data class Name(public val channelName: String) : SubscribeChannel", "}", ""]
    if value.feature == "subscription-seek-position":
        return [*prefix, "public sealed interface SubscriptionSeekPosition {",
            "    public data class Time(public val time: Long) : SubscriptionSeekPosition",
            "    public data class Size(public val size: Long) : SubscriptionSeekPosition", "}", ""]
    raise ValueError(f"unsupported declaration feature: {value.feature}")


def render_declaration(value: KotlinDeclaration) -> list[str]:
    return _standard_declaration(value) if value.feature == "standard" else _special_declaration(value)


MODELS = {value.method: value for value in REQUEST_MODEL_CATALOG}
ENTRIES = {value.method: value for value in CATALOG}
AUXILIARY = {method: value for method, value in REQUEST_AUXILIARY_DECLARATIONS}


def render_request(entry: Entry, model: RequestModelSpec) -> list[str]:
    lines = [*_kdoc(model.kdoc), *model.annotations]
    parameters = model.constructor_parameters
    one_line = len(parameters) == 1 and entry.method in {
        "getChannel", "stopDvrEntry", "cancelDvrEntry", "deleteDvrEntry",
        "deleteAutorecEntry", "deleteTimerecEntry", "getDvrCutpoints",
        "fileOpen", "fileStat",
    }
    if not parameters:
        lines.append(f"public {model.kind} {entry.request} : HtspRequest<{entry.response}>(")
    elif one_line:
        parameter = parameters[0]
        lines.append(
            f"public {model.kind} {entry.request}(public val {parameter.name}: {parameter.type}) : "
            f"HtspRequest<{entry.response}>("
        )
    else:
        lines.append(f"public {model.kind} {entry.request}(")
        for parameter in parameters:
            default = "" if parameter.default is None else f" = {parameter.default}"
            stored = "" if parameter.name in model.unstored_parameters else "public val "
            lines.append(f"    {stored}{parameter.name}: {parameter.type}{default},")
        lines.append(f") : HtspRequest<{entry.response}>(")
    lines.extend((
        f'    method = "{entry.method}",',
        f"    access = HtspAccess.{entry.access},",
    ))
    expression = model.minimum_expression
    if expression is None:
        expression = "null" if entry.minimum_version is None else str(entry.minimum_version)
    expression_lines = expression.splitlines()
    lines.append(f"    minimumProtocolVersion = {expression_lines[0]}")
    lines.extend(f"    {line}" for line in expression_lines[1:])
    lines[-1] += ","
    suffix = ")"
    if model.interfaces:
        suffix += ", " + ", ".join(model.interfaces)
    has_body = bool(model.snapshots or model.validations or model.body_feature != "standard")
    if not has_body:
        return [*lines, suffix, ""]
    lines.append(suffix + " {")
    for name in model.snapshots:
        parameter = next(value for value in parameters if value.name == name)
        lines.append(f"    public val {name}: {parameter.type} = {name}?.immutableSnapshot()")
    if model.snapshots and model.validations:
        lines.append("")
    if model.validations:
        lines.append("    init {")
        for validation in model.validations:
            validation_lines = validation.splitlines()
            lines.extend(f"        {line}" for line in validation_lines)
        lines.append("    }")
    if model.body_feature == "file-open-redaction":
        if model.validations:
            lines.append("")
        lines.append('    override fun toString(): String = "FileOpenRequest(file=<redacted>)"')
    lines.extend(("}", ""))
    return lines


def _source(field: WireField) -> str:
    return field.source_expression or f"request.{field.kotlin_property}"


def _optional_value(field: WireField) -> str:
    source = _source(field)
    if field.decoder == "flag":
        return f"{source}?.toWireFlag()"
    return source


def _render_field_encoder(entry: Entry) -> list[str]:
    fields = REQUEST_FIELDS_BY_METHOD[entry.method]
    if not fields:
        return [f"        is {entry.request} -> linkedMapOf()"]
    required_prefix: list[WireField] = []
    for field in fields:
        if field.presence != "required" or field.decoder not in {"direct", "bounded-file-size"}:
            break
        required_prefix.append(field)
    if required_prefix:
        map_type = "<String, Any?>" if len(fields) > len(required_prefix) else ""
        if len(required_prefix) == 1:
            first = required_prefix[0]
            lines = [f'        is {entry.request} -> linkedMapOf{map_type}("{first.wire_names[0]}" to {_source(first)})']
        else:
            lines = [f"        is {entry.request} -> linkedMapOf{map_type}("]
            lines.extend(f'            "{field.wire_names[0]}" to {_source(field)},' for field in required_prefix)
            lines.append("        )")
    else:
        lines = [f"        is {entry.request} -> linkedMapOf<String, Any?>()"]
    for field in fields[len(required_prefix):]:
        wire = field.wire_names[0]
        if field.presence == "optional" and field.decoder in {"direct", "flag", "bounded-file-size"}:
            lines.append(f'            .putIfNotNull("{wire}", {_optional_value(field)})')
        elif field.presence == "required" and field.decoder in {"direct", "bounded-file-size"}:
            lines.append(f'            .apply {{ put("{wire}", {_source(field)}) }}')
        else:
            raise ValueError(f"{entry.method}: field feature requires named encoder: {field.decoder}")
    return lines


def _render_special_encoder(entry: Entry, feature: str) -> list[str]:
    if feature == "get-ticket-selector":
        return ["        is GetTicketRequest -> when (val selector = request.selector) {",
            '            is GetTicketSelector.Channel -> linkedMapOf("channelId" to selector.channelId)',
            '            is GetTicketSelector.Dvr -> linkedMapOf("dvrId" to selector.dvrId)', "        }"]
    if feature == "add-dvr-selector":
        fields = REQUEST_FIELDS_BY_METHOD[entry.method]
        optional = [field for field in fields if field.decoder == "direct"]
        return ["        is AddDvrEntryRequest -> linkedMapOf<String, Any?>().apply {",
            "            if (request.selector is AddDvrEntrySelector.ExplicitChannelTime) {",
            '                put("channelId", request.selector.channelId)', "            }",
            "            if (request.selector is AddDvrEntrySelector.Event) {",
            '                put("eventId", request.selector.eventId)', "            }",
            *[f'            putIfNotNull("{field.wire_names[0]}", request.{field.kotlin_property})'
              for field in optional[:2]],
            "            if (request.selector is AddDvrEntrySelector.ExplicitChannelTime) {",
            '                put("start", request.selector.start)', '                put("stop", request.selector.stop)',
            "            }",
            *[f'            putIfNotNull("{field.wire_names[0]}", request.{field.kotlin_property})'
              for field in optional[2:]], "        }"]
    if feature == "subscribe-selector":
        return ["        is SubscribeRequest -> linkedMapOf<String, Any?>(",
            '            "subscriptionId" to request.subscriptionId,', "        ).apply {",
            "            when (val channel = request.channel) {",
            '                is SubscribeChannel.Id -> put("channelId", channel.channelId)',
            '                is SubscribeChannel.Name -> put("channelName", channel.channelName)', "            }",
            '            putIfNotNull("profile", request.profile)', '            putIfNotNull("weight", request.weight)',
            '            putIfNotNull("90khz", request.ninetyKhz)',
            '            putIfNotNull("timeshiftPeriod", request.timeshiftPeriodSeconds)',
            '            putIfNotNull("queueDepth", request.queueDepth)', "        }"]
    if feature == "seek-selector":
        return [f"        is {entry.request} -> linkedMapOf<String, Any?>(",
            '            "subscriptionId" to request.subscriptionId,', "        ).apply {",
            "            when (val position = request.position) {",
            '                is SubscriptionSeekPosition.Time -> put("time", position.time)',
            '                is SubscriptionSeekPosition.Size -> put("size", position.size)', "            }",
            '            putIfNotNull("absolute", request.absolute)', "        }"]
    if feature == "recording-rule":
        fields = REQUEST_FIELDS_BY_METHOD[entry.method]
        first = fields[0]
        lines = [f'        is {entry.request} -> linkedMapOf<String, Any?>("{first.wire_names[0]}" to request.{first.kotlin_property})']
        for field in fields[1:]:
            if field.decoder == "recording-rule-channel":
                lines.append("            .putRecordingRuleChannel(request.channel)")
            else:
                lines.append(f'            .putIfNotNull("{field.wire_names[0]}", {_optional_value(field)})')
        return lines
    if feature == "file-whence":
        return ["        is FileSeekRequest -> linkedMapOf<String, Any?>(",
            '            "id" to request.id,', '            "offset" to request.offset,', "        ).putIfNotNull(",
            '            "whence",', "            request.whence?.let { whence ->", "                when (whence) {",
            '                    FileSeekWhence.SET -> "SEEK_SET"',
            '                    FileSeekWhence.CURRENT -> "SEEK_CUR"',
            '                    FileSeekWhence.END -> "SEEK_END"', "                }", "            },", "        )"]
    raise ValueError(f"unsupported encoder feature: {feature}")


def render_encoder() -> list[str]:
    order = ("api", "hello", "authenticate", *(entry.method for entry in CATALOG[:36]))
    lines = ["    @JvmSynthetic", "    internal fun encode(request: HtspRequest<*>): LinkedHashMap<String, Any?> = when (request) {"]
    for method in order:
        entry = ENTRIES[method]
        feature = MODELS[method].encoder_feature
        if method == "api":
            branch = ['        is ApiRequest -> linkedMapOf<String, Any?>("path" to request.path)',
                '            .putIfNotNull("args", request.args?.toWireValue())']
        elif method == "getEpgObject":
            branch = ['        is GetEpgObjectRequest -> linkedMapOf<String, Any?>("id" to request.id)',
                "            .putIfNotNull(", '                "type",', "                request.objectType?.let { objectType ->",
                "                    when (objectType) {", "                        HtspEpgObjectType.BROADCAST -> 1L",
                "                    }", "                },", "            )"]
        elif feature == "fields":
            branch = _render_field_encoder(entry)
        else:
            branch = _render_special_encoder(entry, feature)
        lines.extend(branch)
        lines.append("")
    lines.extend(("        else -> malformedReply()", "    }", ""))
    return lines


def _decode_field(field: WireField) -> str:
    wire = field.wire_names[0]
    required = field.presence == "required"
    if field.decoder == "u32":
        return f'fields.{"required" if required else "optional"}U32("{wire}")'
    if field.decoder == "s64":
        return f'fields.{"required" if required else "optional"}S64("{wire}")'
    if field.decoder == "s32":
        value = f'fields.{"required" if required else "optional"}S32("{wire}")'
        return value + (".toLong()" if field.kotlin_property == "unixTimeSeconds" else "")
    if field.decoder == "string":
        return f'fields.{"required" if required else "optional"}String("{wire}")'
    if field.decoder == "observed-string":
        return f'fields.observedString("{wire}")'
    if field.decoder == "observed-u32":
        return f'fields.observedU32("{wire}")'
    if field.decoder == "observed-flag":
        return f'fields.observedFlag("{wire}")'
    if field.decoder == "observed-string-list":
        return f'fields.observedStringList("{wire}")'
    if field.decoder == "object-list":
        shape = next(value for value in REQUEST_REPLY_NESTED_SHAPES if value.name == field.nested_shape)
        return f'fields.{"required" if required else "optional"}ObjectList("{wire}", ::{shape.decoder})'
    if field.decoder == "u32-list":
        return f'fields.{"required" if required else "optional"}U32List("{wire}")'
    if field.decoder == "binary-value":
        return f'HtspBinary(fields.requiredBinary("{wire}"))'
    if field.decoder == "non-negative-s64":
        return f'fields.requiredS64("{wire}").also {{ if (it < 0L) malformedReply() }}'
    raise ValueError(f"unsupported generic reply decoder: {field.decoder}")


def _render_generic_decoder(entry: Entry) -> list[str]:
    fields = REPLY_FIELDS_BY_METHOD[entry.method]
    if entry.response == "HtspEmptyResponse":
        return [f"        is {entry.request} -> HtspEmptyResponse"]
    if entry.method == "getSysTime":
        return ["        is GetSysTimeRequest -> GetSysTimeResponse(",
            "            // Pinned v44 source emits s32 even though the official method page says s64.",
            '            unixTimeSeconds = fields.requiredS32("time").toLong(),',
            '            legacyTimezoneHoursWestOfGmt = fields.requiredS32("timezone"),',
            '            gmtOffsetMinutes = fields.optionalS32("gmtoffset"),', "        )"]
    if not fields:
        return [f"        is {entry.request} -> {entry.response}"]
    if len(fields) == 1:
        field = fields[0]
        return [f"        is {entry.request} -> {entry.response}({_decode_field(field)})"]
    lines = [f"        is {entry.request} -> {entry.response}("]
    lines.extend(f"            {field.kotlin_property} = {_decode_field(field)}," for field in fields)
    lines.append("        )")
    return lines


def _render_special_decoder(entry: Entry, feature: str) -> list[str]:
    if feature == "hello":
        return ["        is HelloRequest -> HelloResponse(",
            '            htspVersion = fields.requiredU32("htspversion"),',
            '            serverName = fields.observedString("servername"),',
            '            serverVersion = fields.observedString("serverversion"),',
            "            challenge = HtspBinary(", '                fields.requiredBinary("challenge").also { challenge ->',
            "                    if (challenge.size != 32) malformedReply()", "                },", "            ),",
            '            webRoot = fields.observedString("webroot"),', '            language = fields.observedString("language"),',
            '            serverCapabilities = fields.observedStringList("servercapability"),',
            '            apiVersion = fields.observedU32("api_version"),', "        )"]
    if feature == "authenticate":
        fields = REPLY_FIELDS_BY_METHOD[entry.method]
        return ["        is AuthenticateRequest -> AuthenticateResponse(",
            *(f"            {field.kotlin_property} = {_decode_field(field)}," for field in fields), "        )"]
    if feature == "api":
        return ["        is ApiRequest -> decodeApiResponse(fields)"]
    if feature == "channel":
        return ["        is GetChannelRequest -> decodeChannel(fields, protocolVersion)"]
    if feature == "event":
        return ["        is GetEventRequest -> GetEventResponse(eventFromFields(fields))"]
    if feature == "epg-query":
        return ["        is EpgQueryRequest -> decodeEpgQuery(request, fields)"]
    if feature == "epg-object":
        return ["        is GetEpgObjectRequest -> GetEpgObjectResponse(epgBroadcastObjectFromFields(fields))"]
    if feature == "add-dvr-mutation":
        return ["        is AddDvrEntryRequest -> decodeDvrMutation(fields) { success, error ->",
            '            val entryId = if (fields.containsKey("id")) {', '                fields.optionalU32("id")',
            "            } else {", '                fields.optionalU32("dvrId")', "            }",
            "            AddDvrEntryResponse(success, entryId, error)", "        }"]
    if feature == "dvr-mutation":
        return [f"        is {entry.request} -> decodeDvrMutation(fields, ::{entry.response})"]
    if feature == "recording-rule-add":
        return [f"        is {entry.request} -> decodeRecordingRuleAdd(fields, ::{entry.response})"]
    if feature == "recording-rule-ack":
        return [f"        is {entry.request} -> decodeRecordingRuleAcknowledgement(fields, {entry.response})"]
    if feature == "cutpoints":
        return ["        is GetDvrCutpointsRequest -> GetDvrCutpointsResponse(",
            '            fields.optionalObjectList("cutpoints") { cutpoint ->', "                HtspDvrCutpoint(",
            '                    start = cutpoint.requiredU32("start"),',
            '                    end = cutpoint.requiredU32("end"),',
            '                    type = cutpoint.requiredU32("type"),', "                )", "            },", "        )"]
    if feature == "get-ticket":
        return ["        is GetTicketRequest -> GetTicketResponse(",
            '            path = fields.requiredString("path"),', '            ticket = fields.requiredString("ticket"),', "        )"]
    if feature == "file-open":
        return ["        is FileOpenRequest -> decodeFileOpen(fields)"]
    if feature == "file-read":
        return ['        is FileReadRequest -> FileReadResponse(HtspBinary(fields.requiredBinary("data")))']
    if feature == "strict-file-close":
        return ["        is FileCloseRequest -> decodeFileClose(fields)"]
    if feature == "file-stat":
        return ["        is FileStatRequest -> decodeFileStat(fields)"]
    if feature == "file-seek":
        return ["        is FileSeekRequest -> FileSeekResponse(",
            '            offset = fields.requiredS64("offset").also { offset ->',
            "                if (offset < 0L) malformedReply()", "            },", "        )"]
    if feature == "strict-empty":
        return [f"        is {entry.request} -> {{", '            if (fields.keys.any { it != "seq" }) malformedReply()',
            "            HtspEmptyResponse", "        }"]
    if feature == "lenient-empty":
        return [f"        is {entry.request} -> HtspEmptyResponse"]
    raise ValueError(f"unsupported decoder feature: {feature}")


def render_decoder() -> list[str]:
    order = ("api", "hello", "authenticate", *(entry.method for entry in CATALOG[:36]))
    lines = ["    @JvmSynthetic", '    @Suppress("UNCHECKED_CAST")', "    internal fun <R> decode(",
        "        request: HtspRequest<R>,", "        fields: Map<String, Any?>,", "        protocolVersion: Int,",
        "    ): R = when (request) {"]
    for method in order:
        entry = ENTRIES[method]
        feature = MODELS[method].decoder_feature
        lines.extend(_render_generic_decoder(entry) if feature == "fields" else _render_special_decoder(entry, feature))
        lines.append("")
    lines.extend(("        else -> malformedReply()", "    } as R", ""))
    return lines


def render_codec_helpers() -> list[str]:
    return [
        "    private fun decodeChannel(fields: Map<String, Any?>, protocolVersion: Int): GetChannelResponse {",
        '        val channelUuid = fields.optionalString("channelIdStr")',
        "        if (protocolVersion >= 41 && channelUuid == null) malformedReply()",
        '        val services = fields.requiredObjectList("services") { service ->',
        "            HtspChannelService(", '                name = service.requiredString("name"),',
        '                type = service.requiredString("type"),', '                content = service.requiredU32("content"),',
        '                conditionalAccessId = service.optionalU32("caid"),',
        '                conditionalAccessName = service.optionalString("caname"),',
        '                providerName = service.optionalString("providername"),', "            )", "        }",
        "        return GetChannelResponse(", "            HtspChannel(",
        '                channelId = fields.requiredU32("channelId"),', "                channelUuid = channelUuid,",
        '                channelNumber = fields.requiredU32("channelNumber"),',
        '                channelNumberMinor = fields.optionalU32("channelNumberMinor"),',
        '                channelName = fields.requiredString("channelName"),',
        '                channelIcon = fields.optionalString("channelIcon"),',
        '                currentEventId = fields.requiredU32("eventId"),',
        '                nextEventId = fields.requiredU32("nextEventId"),', "                services = services,",
        '                tagIds = fields.requiredU32List("tags"),', "            ),", "        )", "    }", "",
        "    private fun decodeApiResponse(fields: Map<String, Any?>): ApiResponse {",
        '        val permitted = setOf("seq", "noaccess", "response")',
        "        if (fields.keys.any { it !in permitted }) malformedReply()",
        '        if (!fields.containsKey("response")) return ApiResponse.NoPayload',
        '        val value = fields["response"].toApiValue()',
        "        return ApiResponse.Payload(value as? HtspApiContainer ?: malformedReply())", "    }", "",
        "    private fun decodeEpgQuery(", "        request: EpgQueryRequest,", "        fields: Map<String, Any?>,",
        "    ): EpgQueryResponse = if (request.full == null || request.full == 0L) {",
        '        if (fields.containsKey("events")) malformedReply()', "        EpgQueryResponse.EventIds(",
        '            if (fields.containsKey("eventIds")) fields.requiredU32List("eventIds") else emptyList(),',
        "        )", "    } else {", '        if (fields.containsKey("eventIds")) malformedReply()',
        "        EpgQueryResponse.Events(", '            if (fields.containsKey("events")) {',
        '                fields.requiredObjectList("events", ::eventFromFields)', "            } else {",
        "                emptyList()", "            },", "        )", "    }", "",
        "    private fun decodeFileStat(fields: Map<String, Any?>): FileStatResponse {",
        '        val hasSize = fields.containsKey("size")', '        val hasModifiedAt = fields.containsKey("mtime")',
        "        if (!hasSize && !hasModifiedAt) return FileStatResponse(null, null)",
        "        if (!hasSize || !hasModifiedAt) malformedReply()", '        val sizeBytes = fields.requiredS64("size")',
        "        if (sizeBytes < 0L) malformedReply()", "        return FileStatResponse(",
        "            sizeBytes = sizeBytes,", '            modifiedAtUnixSeconds = fields.requiredS64("mtime"),',
        "        )", "    }", "",
        "    private fun decodeFileOpen(fields: Map<String, Any?>): FileOpenResponse {",
        '        val hasSize = fields.containsKey("size")', '        val hasModifiedAt = fields.containsKey("mtime")',
        "        if (hasSize != hasModifiedAt) malformedReply()",
        '        val sizeBytes = if (hasSize) fields.requiredS64("size") else null',
        "        if (sizeBytes != null && sizeBytes < 0L) malformedReply()", "        return FileOpenResponse(",
        '            id = fields.requiredU32("id"),', "            sizeBytes = sizeBytes,",
        '            modifiedAtUnixSeconds = if (hasModifiedAt) fields.requiredS64("mtime") else null,',
        "        )", "    }", "",
        "    private fun decodeFileClose(fields: Map<String, Any?>): FileCloseResponse {",
        '        if (fields.keys.any { it != "seq" }) malformedReply()', "        return FileCloseResponse", "    }", "",
        "    private fun <R> decodeDvrMutation(", "        fields: Map<String, Any?>,",
        "        response: (Long?, String?) -> R,", "    ): R {",
        '        val success = fields.optionalU32("success")', '        val error = fields.optionalString("error")',
        "        if (success == null && error == null) malformedReply()", "        return response(success, error)",
        "    }", "", "    private fun <R> decodeRecordingRuleAdd(fields: Map<String, Any?>, response: (String) -> R): R {",
        "        fields.requireStrictSuccess()", '        return response(fields.requiredString("id"))', "    }", "",
        "    private fun <R> decodeRecordingRuleAcknowledgement(fields: Map<String, Any?>, response: R): R {",
        "        fields.requireStrictSuccess()", "        return response", "    }",
    ]


API_HELPERS = """
private fun maxVersion(base: Int?, vararg selected: Int?): Int? =
    listOfNotNull(base, *selected).maxOrNull()

private fun <V> LinkedHashMap<String, Any?>.putIfNotNull(
    name: String,
    value: V?,
): LinkedHashMap<String, Any?> = apply {
    if (value != null) put(name, value)
}

private fun LinkedHashMap<String, Any?>.putRecordingRuleChannel(
    channel: HtspRecordingRuleChannel?,
): LinkedHashMap<String, Any?> = apply {
    when (channel) {
        null -> Unit
        is HtspRecordingRuleChannel.Id -> put("channelId", channel.channelId)
        HtspRecordingRuleChannel.Any -> put("channelId", -1L)
    }
}

private fun Boolean.toWireFlag(): Long = if (this) 1L else 0L

@OptIn(HtspJsonApi::class)
private fun HtspApiValue.toWireValue(): Any = when (this) {
    is HtspApiString -> value
    is HtspApiLong -> value
    is HtspApiBoolean -> value
    is HtspApiBinary -> bytes()
    is HtspApiUuid -> HtspWireUuid(bytes())
    is HtspApiObject -> LinkedHashMap<String, Any>(size).also { result ->
        forEachEntry { name, value -> result[name] = value.toWireValue() }
    }
    is HtspApiList -> ArrayList<Any>(size).also { result ->
        forEachValue { value -> result += value.toWireValue() }
    }
}

@OptIn(HtspJsonApi::class)
private fun Any?.toApiValue(): HtspApiValue = when (this) {
    is String -> HtspApiString(this)
    is Long -> HtspApiLong(this)
    is Boolean -> HtspApiBoolean(this)
    is ByteArray -> HtspApiBinary(this)
    is HtspWireUuid -> HtspApiUuid(bytes().also { if (it.size != 16) malformedReply() })
    is Map<*, *> -> {
        val result = ArrayList<Pair<String, HtspApiValue>>(size)
        forEach { (key, value) ->
            result += (key as? String ?: malformedReply()) to value.toApiValue()
        }
        HtspApiObject.create(result.toTypedArray())
    }
    is List<*> -> HtspApiList.create(map { value -> value.toApiValue() }.toTypedArray())
    else -> malformedReply()
}
""".strip("\n")


REQUEST_VALIDATION_HELPERS = """
private fun AddAutorecEntryRequest.validateAutorecU32Fields() {
    validateAutorecU32Fields(
        minDurationSeconds, maxDurationSeconds, fullText, mergeText, duplicateDetection,
        maximumRecordingCount, broadcastType, retentionDays, removalDays, priority, daysOfWeekMask,
    )
}

private fun UpdateAutorecEntryRequest.validateAutorecU32Fields() {
    validateAutorecU32Fields(
        minDurationSeconds, maxDurationSeconds, fullText, mergeText, duplicateDetection,
        maximumRecordingCount, broadcastType, retentionDays, removalDays, priority, daysOfWeekMask,
    )
}

private fun validateAutorecU32Fields(
    minDurationSeconds: Long?,
    maxDurationSeconds: Long?,
    fullText: Long?,
    mergeText: Long?,
    duplicateDetection: Long?,
    maximumRecordingCount: Long?,
    broadcastType: Long?,
    retentionDays: Long?,
    removalDays: Long?,
    priority: Long?,
    daysOfWeekMask: Long?,
) {
    minDurationSeconds?.let { requireU32("minduration", it) }
    maxDurationSeconds?.let { requireU32("maxduration", it) }
    fullText?.let { requireU32("fulltext", it) }
    mergeText?.let { requireU32("mergetext", it) }
    duplicateDetection?.let { requireU32("dupDetect", it) }
    maximumRecordingCount?.let { requireU32("maxCount", it) }
    broadcastType?.let { requireU32("broadcastType", it) }
    retentionDays?.let { requireU32("retention", it) }
    removalDays?.let { requireU32("removal", it) }
    priority?.let { requireU32("priority", it) }
    daysOfWeekMask?.let { requireU32("daysOfWeek", it) }
}

private fun AddTimerecEntryRequest.validateTimerecU32Fields() {
    validateTimerecU32Fields(
        startMinutesSinceMidnight, stopMinutesSinceMidnight, retentionDays, removalDays, priority, daysOfWeekMask,
    )
}

private fun UpdateTimerecEntryRequest.validateTimerecU32Fields() {
    validateTimerecU32Fields(
        startMinutesSinceMidnight, stopMinutesSinceMidnight, retentionDays, removalDays, priority, daysOfWeekMask,
    )
}

private fun validateTimerecU32Fields(
    startMinutesSinceMidnight: Long?,
    stopMinutesSinceMidnight: Long?,
    retentionDays: Long?,
    removalDays: Long?,
    priority: Long?,
    daysOfWeekMask: Long?,
) {
    startMinutesSinceMidnight?.let { requireU32("start", it) }
    stopMinutesSinceMidnight?.let { requireU32("stop", it) }
    retentionDays?.let { requireU32("retention", it) }
    removalDays?.let { requireU32("removal", it) }
    priority?.let { requireU32("priority", it) }
    daysOfWeekMask?.let { requireU32("daysOfWeek", it) }
}
""".strip("\n")


SCALAR_DECODER_HELPERS = """
private fun Map<*, *>.requireStrictSuccess() {
    if (requiredU32("success") != 1L) malformedReply()
}

private fun Map<*, *>.requiredS64(name: String): Long =
    this[name] as? Long ?: malformedReply()

private fun Map<*, *>.optionalS64(name: String): Long? =
    if (containsKey(name)) requiredS64(name) else null

private fun Map<*, *>.requiredS32(name: String): Int {
    val value = requiredS64(name)
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) malformedReply()
    return value.toInt()
}

private fun Map<*, *>.optionalS32(name: String): Int? =
    if (containsKey(name)) requiredS32(name) else null

private fun Map<*, *>.requiredU32(name: String): Long {
    val value = requiredS64(name)
    if (value !in 0L..HTSP_U32_MAX) malformedReply()
    return value
}

private fun Map<*, *>.optionalU32(name: String): Long? =
    if (containsKey(name)) requiredU32(name) else null

private fun Map<*, *>.requiredString(name: String): String =
    this[name] as? String ?: malformedReply()

private fun Map<*, *>.requiredBinary(name: String): ByteArray =
    this[name] as? ByteArray ?: malformedReply()

private fun Map<*, *>.optionalString(name: String): String? =
    if (containsKey(name)) requiredString(name) else null

private fun Map<*, *>.observedString(name: String): String? = this[name] as? String

private fun Map<*, *>.observedU32(name: String): Long? =
    (this[name] as? Long)?.takeIf { it in 0L..HTSP_U32_MAX }

private fun Map<*, *>.observedFlag(name: String): Boolean? = when (this[name]) {
    0L -> false
    1L -> true
    else -> null
}

private fun Map<*, *>.observedStringList(name: String): List<String>? {
    val source = this[name] as? List<*> ?: return null
    if (source.any { it !is String }) return null
    return source.map { it as String }.immutableSnapshot()
}

private fun Map<*, *>.requiredU32List(name: String): List<Long> {
    val source = this[name] as? List<*> ?: malformedReply()
    return source.map { value ->
        val decoded = value as? Long ?: malformedReply()
        if (decoded !in 0L..HTSP_U32_MAX) malformedReply()
        decoded
    }.immutableSnapshot()
}

private fun <R> Map<*, *>.requiredObjectList(
    name: String,
    mapper: (Map<*, *>) -> R,
): List<R> {
    val source = this[name] as? List<*> ?: malformedReply()
    return source.map { value -> mapper(value as? Map<*, *> ?: malformedReply()) }
        .immutableSnapshot()
}

private fun <R> Map<*, *>.optionalObjectList(
    name: String,
    mapper: (Map<*, *>) -> R,
): List<R>? = if (containsKey(name)) requiredObjectList(name, mapper) else null
""".strip("\n")


def _shape_decode(field: WireField) -> str:
    wire = field.wire_names[0]
    required = field.presence == "required"
    prefix = "required" if required else "optional"
    return {
        "u32": f'fields.{prefix}U32("{wire}")',
        "s64": f'fields.{prefix}S64("{wire}")',
        "string": f'fields.{prefix}String("{wire}")',
        "string-list": f'fields.optionalStringList("{wire}")',
        "true-flag": f'fields.optionalTrueFlag("{wire}")',
        "string-map": f'fields.optionalStringMap("{wire}")',
        "episode-number": f'fields.optionalEpgEpisodeNumber("{wire}")',
        "u32-list": f'fields.optionalU32List("{wire}")',
        "sorted-unique-string-list": f'fields.optionalSortedUniqueStringList("{wire}")',
    }[field.decoder]


def render_nested_decoders() -> list[str]:
    lines: list[str] = []
    for shape in REQUEST_REPLY_NESTED_SHAPES:
        if shape.name in {"channel-service", "cutpoint", "epg-episode-number"}:
            continue
        if shape.name == "epg-broadcast":
            lines.extend((
                "private fun epgBroadcastObjectFromFields(fields: Map<*, *>): HtspEpgBroadcastObject {",
                '    if (fields.requiredU32("tp") != 1L) malformedReply()',
                "    return HtspEpgBroadcastObject(",
            ))
            for field in shape.fields:
                if field.kotlin_property == "objectType":
                    continue
                lines.append(f"        {field.kotlin_property} = {_shape_decode(field)},")
            lines.extend(("    )", "}", ""))
            continue
        lines.append(f"private fun {shape.decoder}(fields: Map<*, *>): {shape.kotlin_type} = {shape.kotlin_type}(")
        lines.extend(f"    {field.kotlin_property} = {_shape_decode(field)}," for field in shape.fields)
        lines.extend((")", ""))
    return lines


STRUCTURED_DECODER_HELPERS = """
private fun Map<*, *>.optionalTrueFlag(name: String): Boolean {
    if (!containsKey(name)) return false
    if (requiredU32(name) != 1L) malformedReply()
    return true
}

private fun Map<*, *>.optionalStringMap(name: String): Map<String, String>? {
    if (!containsKey(name)) return null
    val source = this[name] as? Map<*, *> ?: malformedReply()
    val result = LinkedHashMap<String, String>(source.size)
    source.forEach { (key, value) ->
        result[key as? String ?: malformedReply()] = value as? String ?: malformedReply()
    }
    return Collections.unmodifiableMap(result)
}

private fun Map<*, *>.optionalEpgEpisodeNumber(name: String): HtspEpgEpisodeNumber? {
    if (!containsKey(name)) return null
    val source = this[name] as? Map<*, *> ?: malformedReply()
    val result = HtspEpgEpisodeNumber(
        episodeNumber = source.optionalU32("enum"),
        episodeCount = source.optionalU32("ecnt"),
        seasonNumber = source.optionalU32("snum"),
        seasonCount = source.optionalU32("scnt"),
        partNumber = source.optionalU32("pnum"),
        partCount = source.optionalU32("pcnt"),
        text = source.optionalString("text"),
    )
    if (result == HtspEpgEpisodeNumber(null, null, null, null, null, null, null)) malformedReply()
    return result
}

private fun Map<*, *>.optionalU32List(name: String): List<Long>? =
    if (containsKey(name)) requiredU32List(name) else null

private fun Map<*, *>.optionalSortedUniqueStringList(name: String): List<String>? {
    val values = optionalStringList(name) ?: return null
    if (values.zipWithNext().any { (previous, next) -> compareUtf8(previous, next) >= 0 }) malformedReply()
    return values
}

private fun compareUtf8(left: String, right: String): Int {
    val leftBytes = left.toByteArray(Charsets.UTF_8)
    val rightBytes = right.toByteArray(Charsets.UTF_8)
    for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
        val difference = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
        if (difference != 0) return difference
    }
    return leftBytes.size - rightBytes.size
}

private fun Map<*, *>.optionalStringList(name: String): List<String>? {
    if (!containsKey(name)) return null
    val source = this[name] as? List<*> ?: malformedReply()
    return source.map { value -> value as? String ?: malformedReply() }.immutableSnapshot()
}

private fun malformedReply(): Nothing = throw HtspProtocolMappingException()
""".strip("\n")


def render_request_models() -> str:
    output = GENERATED_OUTPUT_BY_KEY["request-models"]
    lines = [
        "// Generated by docs/htsp-protocol/generate_typed_request_models.py. DO NOT EDIT.",
        "// The reviewed semantic catalog is docs/htsp-protocol/htsp_surface.py.",
        f"package {output.package}", "",
        "import at.bernhardberger.tvheadend.htsp.connection.HtspProtocolMappingException",
        "import at.bernhardberger.tvheadend.htsp.jsonapi.*",
        "import at.bernhardberger.tvheadend.htsp.wire.*",
        "import java.util.Collections", "",
    ]
    for declaration in REQUEST_PUBLIC_DECLARATIONS:
        if declaration.output == output.key:
            lines.extend(render_declaration(declaration))
    for method in ("hello", "authenticate"):
        lines.extend(render_request(ENTRIES[method], MODELS[method]))
    for entry in CATALOG:
        if entry.model_output != output.key or entry.method in {"hello", "authenticate"}:
            continue
        if entry.method in AUXILIARY:
            lines.extend(render_declaration(AUXILIARY[entry.method]))
        lines.extend(render_request(entry, MODELS[entry.method]))
    lines.extend(("@OptIn(HtspJsonApi::class)", "internal object `HtspRequestCodecs-internal` {"))
    lines.extend(render_encoder())
    lines.extend(render_decoder())
    lines.extend(render_codec_helpers())
    lines.extend(("}", "", "internal typealias HtspRequestCodecs = `HtspRequestCodecs-internal`", ""))
    for section in (API_HELPERS, REQUEST_VALIDATION_HELPERS, SCALAR_DECODER_HELPERS):
        lines.extend(section.splitlines())
        lines.append("")
    lines.extend(render_nested_decoders())
    lines.extend(STRUCTURED_DECODER_HELPERS.splitlines())
    return "\n".join(lines) + "\n"


def render_jsonapi_models() -> str:
    output = GENERATED_OUTPUT_BY_KEY["jsonapi-models"]
    lines = [
        "// Generated by docs/htsp-protocol/generate_typed_request_models.py. DO NOT EDIT.",
        "// The reviewed semantic catalog is docs/htsp-protocol/htsp_surface.py.",
        f"package {output.package}", "",
        "import at.bernhardberger.tvheadend.htsp.requests.HtspAccess",
        "import at.bernhardberger.tvheadend.htsp.requests.HtspRequest", "",
    ]
    for entry in CATALOG:
        if entry.model_output == output.key:
            lines.extend(render_request(entry, MODELS[entry.method]))
    for declaration in REQUEST_PUBLIC_DECLARATIONS:
        if declaration.output == output.key:
            lines.extend(render_declaration(declaration))
    return "\n".join(lines)


def render_outputs() -> dict[str, str]:
    return {
        "request-models": render_request_models(),
        "jsonapi-models": render_jsonapi_models(),
    }


def render() -> str:
    outputs = render_outputs()
    return outputs["request-models"] + outputs["jsonapi-models"]


def semantic_fingerprint(
    models: tuple[RequestModelSpec, ...] = REQUEST_MODEL_CATALOG,
    request_fields: tuple[WireField, ...] = REQUEST_WIRE_FIELDS,
    reply_fields: tuple[WireField, ...] = REPLY_WIRE_FIELDS,
    conditional_rules: tuple[ConditionalPresenceRule, ...] = REPLY_CONDITIONAL_PRESENCE_RULES,
    coupled_groups: tuple[CoupledPresenceGroup, ...] = REPLY_COUPLED_PRESENCE_GROUPS,
) -> str:
    payload = {
        "entries": [asdict(value) for value in CATALOG],
        "models": [asdict(value) for value in models],
        "requestFields": [asdict(value) for value in request_fields],
        "replyFields": [asdict(value) for value in reply_fields],
        "conditionalPresenceRules": [asdict(value) for value in conditional_rules],
        "coupledPresenceGroups": [asdict(value) for value in coupled_groups],
        "terminalNestedTargets": REQUEST_REPLY_TERMINAL_NESTED_TARGETS,
        "declarations": [asdict(value) for value in REQUEST_PUBLIC_DECLARATIONS],
        "auxiliary": [(method, asdict(value)) for method, value in REQUEST_AUXILIARY_DECLARATIONS],
        "waivers": REQUEST_SPEC_WAIVERS,
        "status": REQUEST_SPEC_CONSISTENCY_STATUS,
        "escapes": REQUEST_VERBATIM_ESCAPES,
        "nestedKdocs": REQUEST_NESTED_KDOCS,
    }
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def _extract_kdocs(source: str) -> tuple[str, ...]:
    result = []
    for match in re.finditer(r"/\*\*(.*?)\*/", source, re.DOTALL):
        body = match.group(1)
        lines = body.splitlines()
        normalized = []
        for line in lines:
            line = re.sub(r"^\s*\* ?", "", line).strip()
            normalized.append(line)
        while normalized and not normalized[0]:
            normalized.pop(0)
        while normalized and not normalized[-1]:
            normalized.pop()
        result.append("\n".join(normalized))
    return tuple(result)


def inherited_kdocs() -> tuple[str, ...]:
    return tuple(
        value for value in (
            *(declaration.kdoc for declaration in REQUEST_PUBLIC_DECLARATIONS),
            *(declaration.kdoc for _, declaration in REQUEST_AUXILIARY_DECLARATIONS),
            *(model.kdoc for model in REQUEST_MODEL_CATALOG),
            *REQUEST_NESTED_KDOCS,
        ) if value is not None
    )


def validate_catalog(
    *,
    entries: tuple[Entry, ...] = CATALOG,
    models: tuple[RequestModelSpec, ...] = REQUEST_MODEL_CATALOG,
    request_fields_by_method: dict[str, tuple[WireField, ...]] = REQUEST_FIELDS_BY_METHOD,
    reply_fields_by_method: dict[str, tuple[WireField, ...]] = REPLY_FIELDS_BY_METHOD,
    nested_shapes: tuple[NestedShape, ...] = REQUEST_REPLY_NESTED_SHAPES,
    conditional_rules: tuple[ConditionalPresenceRule, ...] = REPLY_CONDITIONAL_PRESENCE_RULES,
    coupled_groups: tuple[CoupledPresenceGroup, ...] = REPLY_COUPLED_PRESENCE_GROUPS,
    spec_status: str = REQUEST_SPEC_CONSISTENCY_STATUS,
    spec_waivers: tuple[tuple[str, str], ...] = REQUEST_SPEC_WAIVERS,
) -> None:
    validate_generated_output_metadata(entries=entries)
    methods = tuple(entry.method for entry in entries)
    if len(methods) != 39 or len(set(methods)) != 39:
        raise ValueError("request catalog must contain exactly 39 canonical entries")
    if tuple(model.method for model in models) != methods:
        raise ValueError("request model catalog must align exactly with extension entries")
    for entry, model in zip(entries, models):
        if model.constructor_parameters != entry.parameters:
            raise ValueError(f"{entry.method}: request model constructor diverges from its authoritative entry")
    if set(request_fields_by_method) != set(methods) or set(reply_fields_by_method) != set(methods):
        raise ValueError("every request must expose stable request and reply field collections")
    if REQUEST_VERBATIM_ESCAPES:
        raise ValueError("G2 must not use verbatim Kotlin escapes")
    if spec_status != "verified-v44":
        raise ValueError("request catalog must retain verified-v44 spec consistency status")
    for waiver in spec_waivers:
        if (
            not isinstance(waiver, tuple)
            or len(waiver) != 2
            or not all(isinstance(value, str) for value in waiver)
            or not waiver[0]
            or not waiver[1].strip()
        ):
            raise ValueError("request spec waivers require one exact identity and a nonblank reason")
    request_fields = tuple(
        field for entry in entries for field in request_fields_by_method[entry.method]
    )
    method_reply_fields = tuple(
        field for entry in entries for field in reply_fields_by_method[entry.method]
    )
    nested_reply_fields = tuple(field for shape in nested_shapes for field in shape.fields)
    reply_fields = (*method_reply_fields, *nested_reply_fields)
    for entry in entries:
        for field in request_fields_by_method[entry.method]:
            if field.owner != entry.method:
                raise ValueError(f"{entry.method}.{field.kotlin_property}: request field owner drift")
        for field in reply_fields_by_method[entry.method]:
            if field.owner != entry.method:
                raise ValueError(f"{entry.method}.{field.kotlin_property}: reply method field owner drift")
    shape_names = tuple(shape.name for shape in nested_shapes)
    if len(shape_names) != len(set(shape_names)):
        raise ValueError("reply nested shape names must be unique")
    for shape in nested_shapes:
        if shape.spec_domain not in {"shape", "reply-shape"} or not shape.spec_owner:
            raise ValueError(f"{shape.name}: nested reply shape lacks an exact spec owner mapping")
        for field in shape.fields:
            if field.owner != shape.name:
                raise ValueError(f"{shape.name}.{field.kotlin_property}: nested reply field owner drift")
    nested_targets = set(shape_names) | set(REQUEST_REPLY_TERMINAL_NESTED_TARGETS)
    nested_link_decoders = {
        "api-container", "episode-number", "object-list", "observed-string-list",
        "root-shape", "sorted-unique-string-list", "string-list", "string-map", "u32-list",
    }
    for field in reply_fields:
        has_link = field.nested_shape is not None
        if has_link != (field.decoder in nested_link_decoders):
            raise ValueError(f"{field.owner}.{field.kotlin_property}: nested decoder/link mismatch")
        if has_link and field.nested_shape not in nested_targets:
            raise ValueError(f"{field.owner}.{field.kotlin_property}: unknown nested target {field.nested_shape}")
    get_event_fields = reply_fields_by_method["getEvent"]
    if len(get_event_fields) != 1:
        raise ValueError("getEvent must have exactly one method-owned root link")
    get_event_root = get_event_fields[0]
    if (
        get_event_root.kotlin_property != "event"
        or get_event_root.wire_names != ("<root>",)
        or get_event_root.wire_type != "map"
        or get_event_root.presence != "required"
        or get_event_root.decoder != "root-shape"
        or get_event_root.nested_shape != "event"
    ):
        raise ValueError("getEvent must link its method-owned root to the event nested shape")
    for field in request_fields:
        if field.direction != "client-to-server":
            raise ValueError(f"{field.owner}.{field.kotlin_property}: invalid request direction")
    for field in reply_fields:
        if field.direction != "server-to-client":
            raise ValueError(f"{field.owner}.{field.kotlin_property}: invalid reply direction")
    for field in (*request_fields, *reply_fields):
        if field.presence not in {"required", "optional", "alternative"}:
            raise ValueError(f"{field.owner}.{field.kotlin_property}: invalid presence")
        if not field.wire_names or not field.wire_type or not field.decoder:
            raise ValueError(f"{field.owner}.{field.kotlin_property}: incomplete wire metadata")

    def reply_field(owner: str, wire_name: str) -> WireField:
        if owner not in reply_fields_by_method:
            raise ValueError(f"presence metadata references unknown reply owner {owner}")
        matches = tuple(
            field for field in reply_fields_by_method[owner]
            if wire_name in field.wire_names
        )
        if len(matches) != 1:
            raise ValueError(f"{owner}.{wire_name}: presence metadata must reference exactly one wire field")
        return matches[0]

    conditional_keys: set[tuple[str, str]] = set()
    for rule in conditional_rules:
        key = (rule.owner, rule.wire_name)
        if key in conditional_keys:
            raise ValueError(f"{rule.owner}.{rule.wire_name}: duplicate conditional presence rule")
        conditional_keys.add(key)
        if rule.kind != "required-at-or-above-version":
            raise ValueError(f"{rule.owner}.{rule.wire_name}: invalid conditional presence rule kind")
        if not 1 <= rule.minimum_version <= 44:
            raise ValueError(f"{rule.owner}.{rule.wire_name}: conditional version is outside the v44 evidence bounds")
        field = reply_field(rule.owner, rule.wire_name)
        if field.presence != "optional" or field.minimum_version != rule.minimum_version:
            raise ValueError(f"{rule.owner}.{rule.wire_name}: conditional presence disagrees with base field metadata")
    if ("getChannel", "channelIdStr") not in conditional_keys:
        raise ValueError("getChannel.channelIdStr requires structured conditional presence metadata")

    group_keys: set[tuple[str, str]] = set()
    grouped_fields: set[tuple[str, str]] = set()
    for group in coupled_groups:
        key = (group.owner, group.name)
        if key in group_keys:
            raise ValueError(f"{group.owner}.{group.name}: duplicate coupled presence group")
        group_keys.add(key)
        if group.kind != "all-or-none":
            raise ValueError(f"{group.owner}.{group.name}: invalid coupled presence group kind")
        if len(group.wire_names) < 2 or len(group.wire_names) != len(set(group.wire_names)):
            raise ValueError(f"{group.owner}.{group.name}: coupled group must contain complete unique membership")
        for wire_name in group.wire_names:
            field_key = (group.owner, wire_name)
            if field_key in grouped_fields:
                raise ValueError(f"{group.owner}.{wire_name}: reply field belongs to multiple coupled groups")
            grouped_fields.add(field_key)
            if reply_field(group.owner, wire_name).presence != "optional":
                raise ValueError(f"{group.owner}.{wire_name}: coupled fields must retain truthful optional base presence")
    required_groups = {
        ("fileOpen", "size", "mtime"),
        ("fileStat", "size", "mtime"),
    }
    actual_groups = {
        (group.owner, *group.wire_names)
        for group in coupled_groups
        if group.owner in {"fileOpen", "fileStat"}
    }
    if actual_groups != required_groups:
        raise ValueError("fileOpen/fileStat size and mtime must retain complete all-or-none groups")
    source = render()
    if sorted(_extract_kdocs(source)) != sorted(inherited_kdocs()):
        raise ValueError("generated KDoc multiset must exactly match inherited catalog KDoc")


def self_test() -> None:
    self_test_generated_output_metadata()
    validate_catalog()

    def expect_catalog_rejection(label: str, **changes: object) -> None:
        try:
            validate_catalog(**changes)
        except ValueError:
            return
        raise AssertionError(f"catalog mutation was accepted: {label}")

    file_close_model_index = next(
        index for index, model in enumerate(REQUEST_MODEL_CATALOG)
        if model.method == "fileClose"
    )
    divergent_file_close_model = replace(
        REQUEST_MODEL_CATALOG[file_close_model_index],
        constructor_parameters=REQUEST_MODEL_CATALOG[file_close_model_index].constructor_parameters[:-1],
    )
    expect_catalog_rejection(
        "fileClose model/entry constructor divergence",
        models=(
            *REQUEST_MODEL_CATALOG[:file_close_model_index],
            divergent_file_close_model,
            *REQUEST_MODEL_CATALOG[file_close_model_index + 1:],
        ),
    )
    expect_catalog_rejection("pending spec consistency status", spec_status="pending-g3")
    expect_catalog_rejection(
        "blank spec waiver reason",
        spec_waivers=((REQUEST_SPEC_WAIVERS[0][0], " "),),
    )

    get_event_fields = REPLY_FIELDS_BY_METHOD["getEvent"]
    owner_drift = dict(REPLY_FIELDS_BY_METHOD)
    owner_drift["getEvent"] = (replace(get_event_fields[0], owner="event"),)
    expect_catalog_rejection("getEvent method owner drift", reply_fields_by_method=owner_drift)

    target_drift = dict(REPLY_FIELDS_BY_METHOD)
    target_drift["getEvent"] = (replace(get_event_fields[0], nested_shape="profile"),)
    expect_catalog_rejection("getEvent nested target drift", reply_fields_by_method=target_drift)

    event_shape_index = next(
        index for index, shape in enumerate(REQUEST_REPLY_NESTED_SHAPES)
        if shape.name == "event"
    )
    event_shape = REQUEST_REPLY_NESTED_SHAPES[event_shape_index]
    nested_owner_drift = replace(
        event_shape,
        fields=(replace(event_shape.fields[0], owner="getEvent"), *event_shape.fields[1:]),
    )
    expect_catalog_rejection(
        "event nested-field owner drift",
        nested_shapes=(
            *REQUEST_REPLY_NESTED_SHAPES[:event_shape_index],
            nested_owner_drift,
            *REQUEST_REPLY_NESTED_SHAPES[event_shape_index + 1:],
        ),
    )

    conditional_version_drift = (
        replace(REPLY_CONDITIONAL_PRESENCE_RULES[0], minimum_version=40),
        *REPLY_CONDITIONAL_PRESENCE_RULES[1:],
    )
    expect_catalog_rejection(
        "conditional presence version drift",
        conditional_rules=conditional_version_drift,
    )
    expect_catalog_rejection(
        "conditional presence rule kind drift",
        conditional_rules=(
            replace(REPLY_CONDITIONAL_PRESENCE_RULES[0], kind="optional-below-version"),
            *REPLY_CONDITIONAL_PRESENCE_RULES[1:],
        ),
    )
    expect_catalog_rejection(
        "conditional presence version outside evidence bounds",
        conditional_rules=(
            replace(REPLY_CONDITIONAL_PRESENCE_RULES[0], minimum_version=45),
            *REPLY_CONDITIONAL_PRESENCE_RULES[1:],
        ),
    )
    expect_catalog_rejection(
        "conditional presence unknown wire field",
        conditional_rules=(
            replace(REPLY_CONDITIONAL_PRESENCE_RULES[0], wire_name="channelUuid"),
            *REPLY_CONDITIONAL_PRESENCE_RULES[1:],
        ),
    )
    coupled_member_drift = (
        replace(REPLY_COUPLED_PRESENCE_GROUPS[0], wire_names=("size",)),
        *REPLY_COUPLED_PRESENCE_GROUPS[1:],
    )
    expect_catalog_rejection(
        "coupled presence group drift",
        coupled_groups=coupled_member_drift,
    )
    expect_catalog_rejection(
        "coupled presence rule kind drift",
        coupled_groups=(
            replace(REPLY_COUPLED_PRESENCE_GROUPS[0], kind="at-least-one"),
            *REPLY_COUPLED_PRESENCE_GROUPS[1:],
        ),
    )
    expect_catalog_rejection(
        "coupled presence cross-owner membership drift",
        coupled_groups=(
            replace(REPLY_COUPLED_PRESENCE_GROUPS[0], owner="getChannel"),
            *REPLY_COUPLED_PRESENCE_GROUPS[1:],
        ),
    )
    expect_catalog_rejection(
        "coupled presence unknown wire field",
        coupled_groups=(
            replace(REPLY_COUPLED_PRESENCE_GROUPS[0], wire_names=("size", "modified")),
            *REPLY_COUPLED_PRESENCE_GROUPS[1:],
        ),
    )

    for key, output in render_outputs().items():
        if not output.endswith("\n") or output.endswith("\n\n"):
            raise ValueError(f"{key} output must end with exactly one LF")

    first = render()
    if first != render():
        raise ValueError("request-model rendering must be deterministic")
    fingerprint = semantic_fingerprint()
    if fingerprint != semantic_fingerprint():
        raise ValueError("semantic fingerprint must be deterministic")
    mutated_field = replace(REQUEST_WIRE_FIELDS[0], minimum_version=44)
    if semantic_fingerprint(request_fields=(mutated_field, *REQUEST_WIRE_FIELDS[1:])) == fingerprint:
        raise ValueError("fingerprint missed non-rendered request field metadata mutation")
    malformed_index = next(index for index, field in enumerate(REPLY_WIRE_FIELDS) if field.lenient_malformed)
    malformed_field = replace(REPLY_WIRE_FIELDS[malformed_index], lenient_malformed=False)
    mutated_replies = (*REPLY_WIRE_FIELDS[:malformed_index], malformed_field, *REPLY_WIRE_FIELDS[malformed_index + 1:])
    if semantic_fingerprint(reply_fields=mutated_replies) == fingerprint:
        raise ValueError("fingerprint missed reply leniency metadata mutation")
    model_index = next(index for index, model in enumerate(REQUEST_MODEL_CATALOG) if model.method == "fileOpen")
    changed_model = replace(REQUEST_MODEL_CATALOG[model_index], kdoc="mutated")
    models = (*REQUEST_MODEL_CATALOG[:model_index], changed_model, *REQUEST_MODEL_CATALOG[model_index + 1:])
    if semantic_fingerprint(models=models) == fingerprint:
        raise ValueError("fingerprint missed rendered model mutation")
    if semantic_fingerprint(conditional_rules=conditional_version_drift) == fingerprint:
        raise ValueError("fingerprint missed conditional presence version mutation")
    if semantic_fingerprint(coupled_groups=coupled_member_drift) == fingerprint:
        raise ValueError("fingerprint missed coupled presence group mutation")
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "GeneratedHtspRequests.kt"
        path.write_text(first, encoding="utf-8")
        if path.read_text(encoding="utf-8") != first:
            raise ValueError("request-model generation must round-trip bytes")


def write_output() -> None:
    validate_catalog()
    for key, generated in render_outputs().items():
        OUTPUTS[key].parent.mkdir(parents=True, exist_ok=True)
        OUTPUTS[key].write_text(generated, encoding="utf-8")


def check_output() -> None:
    validate_catalog()
    for key, expected in render_outputs().items():
        output = OUTPUTS[key]
        if not output.is_file():
            raise SystemExit(f"error: generated request model source is missing: {output}")
        if output.read_text(encoding="utf-8") != expected:
            raise SystemExit(
                "error: generated request model source is stale; run "
                "python3 docs/htsp-protocol/generate_typed_request_models.py --write"
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args()
    if arguments.write:
        write_output()
    elif arguments.check:
        check_output()
    else:
        self_test()


if __name__ == "__main__":
    main()
