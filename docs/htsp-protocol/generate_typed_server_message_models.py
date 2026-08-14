#!/usr/bin/env python3
"""Generate typed asynchronous HTSP server-message models from reviewed data."""

from __future__ import annotations

import argparse
import sys
import tempfile
from dataclasses import replace
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from htsp_surface import (
    SERVER_ALIAS_CONSTANTS,
    SERVER_DECLARATIONS,
    SERVER_MESSAGE_CATALOG,
    SERVER_NESTED_SHAPES,
    SERVER_VERBATIM_ESCAPES,
    SERVER_WIRE_FIELDS,
    KotlinDeclaration,
    KotlinProperty,
    ValidationFeature,
    WireField,
)

OUTPUT = (
    SCRIPT_DIR.parents[1]
    / "sdk/htsp-protocol/src/main/kotlin/at/bernhardberger/tvheadend/htsp/GeneratedHtspServerMessages.kt"
)


def validate_catalog(
    catalog=SERVER_MESSAGE_CATALOG,
    declarations=SERVER_DECLARATIONS,
    nested_shapes=SERVER_NESTED_SHAPES,
    escapes=SERVER_VERBATIM_ESCAPES,
) -> None:
    methods = tuple(entry.method for entry in catalog)
    message_types = tuple(entry.message_type for entry in catalog)
    decoders = tuple(entry.decoder for entry in catalog)
    if len(catalog) != 30 or len(set(methods)) != 30:
        raise ValueError("server-model catalog must contain exactly 30 unique messages")
    if len(set(message_types)) != 30 or len(set(decoders)) != 30:
        raise ValueError("server-model message types and decoders must be one-to-one")
    declaration_names = tuple(declaration.name for declaration in declarations)
    if len(declaration_names) != len(set(declaration_names)):
        raise ValueError("server declaration names must be unique")
    support_names = {"HtspServerMessage", "HtspBinary"}
    if support_names & set(declaration_names):
        raise ValueError("hand-maintained support declarations must not be generated")
    if len(declarations) != 33:
        raise ValueError("server surface must contain exactly 33 generated declarations")
    for declaration in declarations:
        if declaration.visibility not in ("public", "internal", "private"):
            raise ValueError(f"invalid declaration visibility: {declaration.name}")
        for prop in declaration.properties:
            if prop.stored != (prop.visibility is not None):
                raise ValueError(f"property visibility/storage drift: {declaration.name}.{prop.name}")
        if declaration.equality is not None and declaration.equality.feature != "byte-array-content":
            raise ValueError(f"invalid equality feature: {declaration.name}")
    if not set(message_types) <= set(declaration_names):
        raise ValueError("every message type must have one structured declaration")
    declarations_by_name = {declaration.name: declaration for declaration in declarations}
    for entry in catalog:
        if entry.minimum_version is not None and entry.minimum_version not in range(1, 45):
            raise ValueError(f"invalid message minimum version: {entry.method}")
        declaration = declarations_by_name[entry.message_type]
        property_names = tuple(prop.name for prop in declaration.properties)
        field_names = tuple(field.kotlin_property for field in entry.fields)
        if any(name not in property_names for name in field_names):
            raise ValueError(f"decoder property is absent from declaration: {entry.method}")
    shape_names = tuple(shape.name for shape in nested_shapes)
    if len(shape_names) != len(set(shape_names)):
        raise ValueError("nested shape names must be unique")
    valid_owners = set(methods) | set(shape_names) | {"event", "service", "stream", "source-info", "dvr-file"}
    for entry in catalog:
        if any(field.owner != entry.method for field in entry.fields):
            raise ValueError(f"message field owner drift: {entry.method}")
    for shape in nested_shapes:
        if any(field.owner != shape.name for field in shape.fields):
            raise ValueError(f"nested field owner drift: {shape.name}")
        declaration = declarations_by_name.get(shape.kotlin_type)
        if declaration is None:
            # HtspEvent and HtspChannelService are shared request-surface types.
            if shape.kotlin_type not in ("HtspEvent", "HtspChannelService"):
                raise ValueError(f"nested shape lacks a declaration: {shape.name}")
        elif tuple(field.kotlin_property for field in shape.fields) != tuple(
            prop.name for prop in declaration.properties
        ):
            raise ValueError(f"nested mapper order differs from declaration: {shape.name}")
    all_fields = tuple(field for entry in catalog for field in entry.fields) + tuple(
        field for shape in nested_shapes for field in shape.fields
    )
    valid_decoders = {
        "binary-value", "bounded-int", "event-categories", "event-genre", "event-u32",
        "flag", "minutes", "object", "object-list", "root-shape", "s32", "s64",
        "string", "string-list", "u32", "u32-list",
    }
    scalar_lenient_decoders = {
        "bounded-int", "flag", "minutes", "s32", "s64", "string", "u32",
    }
    shape_names_set = set(shape_names)
    for field in all_fields:
        if field.owner not in valid_owners or not field.kotlin_property:
            raise ValueError("invalid structured wire field")
        if field.direction != "server-to-client":
            raise ValueError(f"invalid direction: {field.owner}.{field.kotlin_property}")
        if field.decoder != "root-shape" and not field.wire_names:
            raise ValueError(f"wire field lacks a wire name: {field.owner}.{field.kotlin_property}")
        if len(field.wire_names) != len(set(field.wire_names)) or any(not name for name in field.wire_names):
            raise ValueError(f"invalid wire names: {field.owner}.{field.kotlin_property}")
        if field.presence not in ("required", "optional"):
            raise ValueError(f"invalid presence: {field.owner}.{field.kotlin_property}")
        if field.decoder not in valid_decoders:
            raise ValueError(f"invalid decoder: {field.owner}.{field.kotlin_property}")
        if field.minimum_version is not None and field.minimum_version not in range(1, 45):
            raise ValueError(f"invalid field minimum version: {field.owner}.{field.kotlin_property}")
        requires_shape = field.decoder in {"object", "object-list", "root-shape"}
        if requires_shape != (field.nested_shape is not None):
            raise ValueError(f"invalid nested linkage: {field.owner}.{field.kotlin_property}")
        if field.nested_shape is not None and field.nested_shape not in shape_names_set:
            raise ValueError(f"unknown nested shape: {field.owner}.{field.kotlin_property}")
        if field.decoder == "event-u32":
            if not field.nested_wire_names:
                raise ValueError(f"event field lacks nested wire names: {field.owner}.{field.kotlin_property}")
        elif field.nested_wire_names:
            raise ValueError(f"unexpected nested wire names: {field.owner}.{field.kotlin_property}")
        if field.lenient_malformed and (
            field.presence != "optional" or field.decoder not in scalar_lenient_decoders
        ):
            raise ValueError(f"invalid lenient field: {field.owner}.{field.kotlin_property}")
    for owner, reason in escapes:
        if owner not in methods or not reason.strip() or "\n" in reason:
            raise ValueError("verbatim escapes require one message and a one-line reason")


def render_alias_constants() -> str:
    return "\n".join(
        f'private val {name} = listOf({", ".join(f"{value!r}" for value in values)})'.replace("'", '"')
        for name, values in SERVER_ALIAS_CONSTANTS
    )


def render_property(prop: KotlinProperty, indent: str) -> str:
    prefix = f"{prop.visibility} val " if prop.stored and prop.visibility else ""
    default = "" if prop.default is None else f" = {prop.default}"
    return f"{indent}{prefix}{prop.name}: {prop.kotlin_type}{default},"


def render_snapshot_expression(expression: str) -> str:
    if not expression.startswith("event.copy("):
        return expression
    return (
        "event.copy(\n"
        "        categories = event.categories?.immutableSnapshot(),\n"
        "        keywords = event.keywords?.immutableSnapshot(),\n"
        "    )"
    )


def render_validation(feature: ValidationFeature, declaration: KotlinDeclaration) -> list[str]:
    if feature.feature == "u32":
        lines = []
        for label, expression in feature.fields:
            prop = next((p for p in declaration.properties if p.name == expression), None)
            nullable = prop is not None and prop.kotlin_type.endswith("?")
            lines.append(
                f'{expression}?.let {{ requireU32("{label}", it) }}'
                if nullable else f'requireU32("{label}", {expression})'
            )
        return lines
    if feature.feature == "u32-list":
        label, expression = feature.fields[0]
        return [f'{expression}?.forEach {{ requireU32("{label}", it) }}']
    if feature.feature == "channel-services":
        return [
            "this.services?.forEach { service ->",
            '    requireU32("service.content", service.content)',
            '    service.conditionalAccessId?.let { requireU32("service.conditionalAccessId", it) }',
            "}",
        ]
    if feature.feature == "u32-group":
        label = feature.fields[0][0]
        values = ",\n            ".join(expression for _, expression in feature.fields)
        return [f"listOfNotNull(\n            {values},\n        ).forEach {{ requireU32(\"{label}\", it) }}"]
    if feature.feature == "timerec":
        nullable = declaration.name.endswith("UpdateMessage")
        prefix = "channelId == null || " if nullable else ""
        start_prefix = "startMinutesSinceMidnight == null || " if nullable else ""
        stop_prefix = "stopMinutesSinceMidnight == null || " if nullable else ""
        return [
            f'require({prefix}channelId >= 0) {{ "channelId must be non-negative" }}',
            f'require({start_prefix}startMinutesSinceMidnight in 0..1_440) {{\n    "startMinutesSinceMidnight must be between 0 and 1440"\n}}',
            f'require({stop_prefix}stopMinutesSinceMidnight in 0..1_440) {{\n    "stopMinutesSinceMidnight must be between 0 and 1440"\n}}',
            'daysOfWeekMask?.let { requireU32("daysOfWeekMask", it) }',
            'priority?.let { requireU32("priority", it) }',
            'retentionDays?.let { requireU32("retentionDays", it) }',
        ]
    if feature.feature == "event-add":
        values = (
            "this.event.channelId", "this.event.contentType", "this.event.ageRating",
            "this.event.starRating", "this.event.copyrightYear", "this.event.isNew",
            "this.event.seasonNumber", "this.event.seasonCount", "this.event.episodeNumber",
            "this.event.episodeCount", "this.event.partNumber", "this.event.partCount",
            "this.event.dvrId", "this.event.nextEventId", "episodeId", "seriesLinkId",
        )
        return [
            'requireU32("eventId", this.event.eventId)',
            "listOfNotNull(\n            " + ",\n            ".join(values) + ',\n        ).forEach { requireU32("event field", it) }',
        ]
    raise ValueError(f"unsupported validation feature: {feature.feature}")


def indent_multiline(text: str, indent: str) -> list[str]:
    return [indent + line if line else line for line in text.splitlines()]


def render_declaration(declaration: KotlinDeclaration) -> str:
    lines: list[str] = []
    if declaration.kdoc:
        lines.append(f"/** {declaration.kdoc} */")
    visibility = f"{declaration.visibility} "
    suffix = "" if declaration.supertype is None else f" : {declaration.supertype}"
    if declaration.kind == "sealed interface":
        lines.append(f"{visibility}sealed interface {declaration.name}{suffix}")
        return "\n".join(lines)
    if declaration.kind == "data object":
        lines.append(f"{visibility}data object {declaration.name}{suffix}")
        return "\n".join(lines)
    if declaration.equality is not None:
        feature = declaration.equality
        if feature.feature != "byte-array-content" or len(declaration.properties) != 1:
            raise ValueError(f"unsupported equality feature: {declaration.name}")
        prop = declaration.properties[0]
        lines.extend((
            f"{visibility}{declaration.kind} {declaration.name}({prop.name}: {prop.kotlin_type}) {{",
            f"    private val {feature.backing_property}: ByteArray = {feature.source_parameter}.copyOf()",
            "",
            f"    /** {feature.accessor_kdoc} */",
            f"    public fun {feature.accessor}(): ByteArray = {feature.backing_property}.copyOf()",
            "",
            "    override fun equals(other: Any?): Boolean =",
            f"        other is {declaration.name} && {feature.backing_property}.contentEquals(other.{feature.backing_property})",
            "",
            f"    override fun hashCode(): Int = {feature.backing_property}.contentHashCode()",
            "",
            f'    override fun toString(): String = "{feature.to_string_label}(size=${{{feature.backing_property}.size}})"',
            "}",
        ))
        return "\n".join(lines)
    if len(declaration.properties) == 1 and not declaration.snapshots and not declaration.validations:
        prop = declaration.properties[0]
        lines.append(f"{visibility}{declaration.kind} {declaration.name}(public val {prop.name}: {prop.kotlin_type}){suffix}")
        return "\n".join(lines)
    lines.append(f"{visibility}{declaration.kind} {declaration.name}(")
    lines.extend(render_property(prop, "    ") for prop in declaration.properties)
    if not declaration.snapshots and not declaration.validations:
        lines.append(f"){suffix}")
        return "\n".join(lines)
    lines.append(f"){suffix} {{")
    for snapshot in declaration.snapshots:
        expression = render_snapshot_expression(snapshot.expression)
        rendered = f"public val {snapshot.property}: {snapshot.kotlin_type} = {expression}"
        lines.extend(indent_multiline(rendered, "    "))
    if declaration.snapshots and declaration.validations:
        lines.append("")
    if declaration.validations:
        lines.append("    init {")
        for feature in declaration.validations:
            for rendered in render_validation(feature, declaration):
                lines.extend(indent_multiline(rendered, "        "))
        lines.append("    }")
    lines.append("}")
    return "\n".join(lines)


def helper_name(field: WireField) -> str:
    required = field.presence == "required"
    prefix = "requiredServer" if required else "optionalServer"
    alias = len(field.wire_names) > 1
    suffix = {
        "s64": "S64", "s32": "S32", "u32": "U32", "str": "String",
        "bin": "Binary", "bool": "Flag", "list": "ObjectList", "map": "Object",
    }[field.wire_type]
    return prefix + ("Alias" if alias else "") + suffix


def render_names(field: WireField) -> str:
    if len(field.wire_names) == 1:
        return f'"{field.wire_names[0]}"'
    preferred = {
        "channelId": "EVENT_CHANNEL_KEYS",
        "channelNumber": "CHANNEL_NUMBER_KEYS",
        "tagIds": "CHANNEL_TAG_KEYS",
        "tagId": "TAG_ID_KEYS",
        "tagName": "TAG_NAME_KEYS",
        "tagIndex": "TAG_INDEX_KEYS",
        "entryId": "DVR_ID_KEYS",
        "playPositionSeconds": "DVR_PLAY_POSITION_KEYS",
        "playCount": "DVR_PLAY_COUNT_KEYS",
        "path": "DVR_FILE_PATH_KEYS",
        "eventId": "EVENT_ID_KEYS",
        "start": "EVENT_START_KEYS",
        "stop": "EVENT_STOP_KEYS",
        "title": "EVENT_TITLE_KEYS",
        "contentType": "EVENT_CONTENT_KEYS",
        "subscriptionId": "SUBSCRIPTION_ID_KEYS",
        "status": "STATUS_KEYS",
        "subscriptionError": "SUBSCRIPTION_ERROR_KEYS",
    }
    if field.owner.startswith("dvrEntry") and field.kotlin_property == "channelId":
        preferred["channelId"] = "DVR_CHANNEL_KEYS"
    preferred_name = preferred.get(field.kotlin_property)
    constant = preferred_name if preferred_name and dict(SERVER_ALIAS_CONSTANTS).get(preferred_name) == field.wire_names else next((name for name, values in SERVER_ALIAS_CONSTANTS if values == field.wire_names), None)
    if constant:
        return constant
    return "listOf(" + ", ".join(f'"{name}"' for name in field.wire_names) + ")"


def render_field_expression(field: WireField) -> str:
    names = render_names(field) if field.wire_names else ""
    if field.decoder == "root-shape":
        shape = next(shape for shape in SERVER_NESTED_SHAPES if shape.name == field.nested_shape)
        expression = f"{shape.decoder}(fields)"
    elif field.decoder == "event-genre":
        expression = "fields.optionalServerEventGenre()"
    elif field.decoder == "event-categories":
        expression = "fields.optionalServerEventCategories()"
    elif field.decoder == "event-u32":
        top = "listOf(" + ", ".join(f'"{name}"' for name in field.wire_names) + ")"
        nested = "listOf(" + ", ".join(f'"{name}"' for name in field.nested_wire_names) + ")"
        expression = f"fields.optionalServerEventU32({top}, {nested})"
    elif field.decoder == "bounded-int":
        prefix = "requiredServer" if field.presence == "required" else "optionalServer"
        expression = f"fields.{prefix}BoundedInt({names}, 0..Int.MAX_VALUE)"
    elif field.decoder == "minutes":
        prefix = "requiredServer" if field.presence == "required" else "optionalServer"
        expression = f"fields.{prefix}BoundedInt({names}, 0..1_440)"
    elif field.decoder == "string-list":
        expression = f"fields.optionalServerStringList({names})"
    elif field.decoder == "u32-list":
        required = field.presence == "required"
        prefix = "requiredServer" if required else "optionalServer"
        alias = "Alias" if len(field.wire_names) > 1 else ""
        expression = f"fields.{prefix}{alias}U32List({names})"
    elif field.decoder == "object-list":
        shape = next(shape for shape in SERVER_NESTED_SHAPES if shape.name == field.nested_shape)
        prefix = "requiredServer" if field.presence == "required" else "optionalServer"
        expression = f'fields.{prefix}ObjectList({names}, ::{shape.decoder})'
    elif field.decoder == "object":
        shape = next(shape for shape in SERVER_NESTED_SHAPES if shape.name == field.nested_shape)
        expression = f"if (fields.containsKey({names})) {{\n    {shape.decoder}(fields.requiredServerObject({names}))\n}} else {{\n    null\n}}"
    elif field.decoder == "binary-value":
        expression = f"HtspBinary(fields.requiredServerBinary({names}))" if field.presence == "required" else f"fields.optionalServerBinary({names})?.let(::HtspBinary)"
    else:
        expression = f"fields.{helper_name(field)}({names})"
    if field.lenient_malformed:
        expression = f"optionalTimerecValue {{ {expression} }}"
    return expression


INDENTED_DECODERS = {"decodeAutorecEntryAdd", "decodeAutorecEntryUpdate", "decodeTimerecEntryAdd", "decodeTimerecEntryUpdate", "decodeEventAdd", "decodeSubscriptionStart", "decodeSubscriptionStop", "decodeSubscriptionGrace", "decodeSubscriptionStatus", "decodeDescrambleInfo", "decodeSubscriptionSpeed", "decodeTimeshiftStatus", "decodeSubscriptionSkip"}


def render_decoder(entry) -> str:
    if not entry.fields:
        return f"@JvmSynthetic\ninternal fun {entry.decoder}(fields: Map<String, Any?>): HtspServerMessage =\n    {entry.message_type}"
    if len(entry.fields) == 1 and entry.fields[0].kotlin_property not in ("event",):
        expression = render_field_expression(entry.fields[0])
        return f"@JvmSynthetic\ninternal fun {entry.decoder}(fields: Map<String, Any?>): HtspServerMessage =\n    {entry.message_type}({expression})"
    indent = "        " if entry.decoder in INDENTED_DECODERS else "    "
    first = f"@JvmSynthetic\ninternal fun {entry.decoder}(fields: Map<String, Any?>): HtspServerMessage ="
    if entry.decoder in INDENTED_DECODERS:
        first += f"\n    {entry.message_type}("
    else:
        first += f" {entry.message_type}("
    lines = [first]
    for field in entry.fields:
        expression = render_field_expression(field)
        rendered = f"{field.kotlin_property} = {expression},"
        lines.extend(indent_multiline(rendered, indent))
    lines.append("    )" if entry.decoder in INDENTED_DECODERS else ")")
    return "\n".join(lines)


def render_nested_decoder(shape) -> str:
    lines = [f"private fun {shape.decoder}(fields: Map<*, *>): {shape.kotlin_type} = {shape.kotlin_type}("]
    for field in shape.fields:
        expression = render_field_expression(field).replace("fields.", "fields.")
        lines.append(f"    {field.kotlin_property} = {expression},")
    lines.append(")")
    return "\n".join(lines)


HELPERS = r'''private class `HtspServerMessageMappingException-internal` : IllegalArgumentException()

private typealias HtspServerMessageMappingException =
    `HtspServerMessageMappingException-internal`

private inline fun <T> optionalTimerecValue(block: () -> T?): T? = try {
    block()
} catch (_: HtspServerMessageMappingException) {
    null
}

private fun Map<*, *>.firstPresentServerName(names: List<String>): String? =
    names.firstOrNull(::containsKey)

private fun Map<*, *>.requiredServerAliasS64(names: List<String>): Long =
    requiredServerS64(firstPresentServerName(names) ?: throw HtspServerMessageMappingException())

private fun Map<*, *>.optionalServerAliasS64(names: List<String>): Long? =
    firstPresentServerName(names)?.let(::requiredServerS64)

private fun Map<*, *>.requiredServerAliasU32(names: List<String>): Long =
    requiredServerU32(firstPresentServerName(names) ?: throw HtspServerMessageMappingException())

private fun Map<*, *>.optionalServerAliasU32(names: List<String>): Long? =
    firstPresentServerName(names)?.let(::requiredServerU32)

private fun Map<*, *>.requiredServerAliasString(names: List<String>): String =
    requiredServerString(firstPresentServerName(names) ?: throw HtspServerMessageMappingException())

private fun Map<*, *>.optionalServerAliasString(names: List<String>): String? =
    firstPresentServerName(names)?.let(::requiredServerString)

private fun Map<*, *>.optionalServerAliasU32List(names: List<String>): List<Long>? =
    firstPresentServerName(names)?.let(::requiredServerU32List)

private fun Map<*, *>.optionalServerEventGenre(): String? {
    if (containsKey("genre")) return requiredServerString("genre")
    if (!containsKey("category")) return null
    return when (this["category"]) {
        is String -> requiredServerString("category")
        is List<*> -> null
        else -> throw HtspServerMessageMappingException()
    }
}

private fun Map<*, *>.optionalServerEventCategories(): List<String>? {
    if (!containsKey("category")) return null
    return when (this["category"]) {
        is String -> null
        is List<*> -> requiredServerStringList("category")
        else -> throw HtspServerMessageMappingException()
    }
}

private fun Map<*, *>.optionalServerEventU32(
    topLevelNames: List<String>,
    nestedNames: List<String>,
): Long? {
    optionalServerAliasU32(topLevelNames)?.let { return it }
    if (!containsKey("episode")) return null
    return requiredServerObject("episode").optionalServerAliasU32(nestedNames)
}

private fun Map<*, *>.requiredServerS64(name: String): Long =
    this[name] as? Long ?: throw HtspServerMessageMappingException()

private fun Map<*, *>.optionalServerS64(name: String): Long? =
    if (containsKey(name)) requiredServerS64(name) else null

private fun Map<*, *>.requiredServerS32(name: String): Int {
    val value = requiredServerS64(name)
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        throw HtspServerMessageMappingException()
    }
    return value.toInt()
}

private fun Map<*, *>.optionalServerS32(name: String): Int? =
    if (containsKey(name)) requiredServerS32(name) else null

private fun Map<*, *>.requiredServerBoundedInt(name: String, range: IntRange): Int {
    val value = requiredServerS64(name)
    if (value !in range.first.toLong()..range.last.toLong()) {
        throw HtspServerMessageMappingException()
    }
    return value.toInt()
}

private fun Map<*, *>.optionalServerBoundedInt(name: String, range: IntRange): Int? =
    if (containsKey(name)) requiredServerBoundedInt(name, range) else null

private fun Map<*, *>.requiredServerFlag(name: String): Boolean = when (requiredServerS64(name)) {
    0L -> false
    1L -> true
    else -> throw HtspServerMessageMappingException()
}

private fun Map<*, *>.optionalServerFlag(name: String): Boolean? =
    if (containsKey(name)) requiredServerFlag(name) else null

private fun Map<*, *>.requiredServerU32(name: String): Long {
    val value = requiredServerS64(name)
    if (value !in 0L..HTSP_U32_MAX) throw HtspServerMessageMappingException()
    return value
}

private fun Map<*, *>.optionalServerU32(name: String): Long? =
    if (containsKey(name)) requiredServerU32(name) else null

private fun Map<*, *>.requiredServerString(name: String): String =
    this[name] as? String ?: throw HtspServerMessageMappingException()

private fun Map<*, *>.optionalServerString(name: String): String? =
    if (containsKey(name)) requiredServerString(name) else null

private fun Map<*, *>.requiredServerBinary(name: String): ByteArray =
    (this[name] as? ByteArray)?.copyOf() ?: throw HtspServerMessageMappingException()

private fun Map<*, *>.optionalServerBinary(name: String): ByteArray? =
    if (containsKey(name)) requiredServerBinary(name) else null

private fun Map<*, *>.requiredServerObject(name: String): Map<*, *> =
    this[name] as? Map<*, *> ?: throw HtspServerMessageMappingException()

private fun Map<*, *>.requiredServerU32List(name: String): List<Long> {
    val source = this[name] as? List<*> ?: throw HtspServerMessageMappingException()
    return source.map { value ->
        val decoded = value as? Long ?: throw HtspServerMessageMappingException()
        if (decoded !in 0L..HTSP_U32_MAX) throw HtspServerMessageMappingException()
        decoded
    }.immutableSnapshot()
}

private fun Map<*, *>.optionalServerU32List(name: String): List<Long>? =
    if (containsKey(name)) requiredServerU32List(name) else null

private fun Map<*, *>.requiredServerStringList(name: String): List<String> {
    val source = this[name] as? List<*> ?: throw HtspServerMessageMappingException()
    return source.map { it as? String ?: throw HtspServerMessageMappingException() }
        .immutableSnapshot()
}

private fun Map<*, *>.optionalServerStringList(name: String): List<String>? =
    if (containsKey(name)) requiredServerStringList(name) else null

private fun <T> Map<*, *>.requiredServerObjectList(
    name: String,
    mapper: (Map<*, *>) -> T,
): List<T> {
    val source = this[name] as? List<*> ?: throw HtspServerMessageMappingException()
    return source.map { value ->
        mapper(value as? Map<*, *> ?: throw HtspServerMessageMappingException())
    }.immutableSnapshot()
}

private fun <T> Map<*, *>.optionalServerObjectList(
    name: String,
    mapper: (Map<*, *>) -> T,
): List<T>? = if (containsKey(name)) requiredServerObjectList(name, mapper) else null'''

def render_structured_server_model(
    declarations=SERVER_DECLARATIONS,
    catalog=SERVER_MESSAGE_CATALOG,
    nested_shapes=SERVER_NESTED_SHAPES,
    escapes=SERVER_VERBATIM_ESCAPES,
) -> str:
    sections = [render_alias_constants()]
    sections.extend(f"// Verbatim escape for {owner}: {reason}" for owner, reason in escapes)
    sections.extend(render_declaration(declaration) for declaration in declarations)
    sections.append(HELPERS.split("\n\nprivate inline", 1)[0])
    sections.extend(render_decoder(entry) for entry in catalog)
    sections.extend(render_nested_decoder(shape) for shape in nested_shapes)
    sections.append("private inline" + HELPERS.split("\n\nprivate inline", 1)[1])
    return "\n\n".join(sections)


def render() -> str:
    validate_catalog()
    return (
        "// Generated by docs/htsp-protocol/generate_typed_server_message_models.py. DO NOT EDIT.\n"
        "// The reviewed server surface in htsp_surface.py is the finite model authority.\n"
        "package at.bernhardberger.tvheadend.htsp\n\n"
        f"{render_structured_server_model()}\n"
    )


def wire_field_semantic_tuple(field: WireField) -> tuple[object, ...]:
    return (
        field.owner,
        field.kotlin_property,
        field.wire_names,
        field.wire_type,
        field.presence,
        field.decoder,
        field.minimum_version,
        field.nested_shape,
        field.nested_wire_names,
        field.lenient_malformed,
        field.direction,
    )


def semantic_catalog_fingerprint(
    catalog=SERVER_MESSAGE_CATALOG,
    declarations=SERVER_DECLARATIONS,
    nested_shapes=SERVER_NESTED_SHAPES,
    escapes=SERVER_VERBATIM_ESCAPES,
) -> tuple[object, ...]:
    return (
        tuple(
            (
                entry.method,
                entry.message_type,
                entry.decoder,
                entry.minimum_version,
                tuple(wire_field_semantic_tuple(field) for field in entry.fields),
            )
            for entry in catalog
        ),
        tuple(declarations),
        tuple(
            (
                shape.name,
                shape.kotlin_type,
                shape.decoder,
                tuple(wire_field_semantic_tuple(field) for field in shape.fields),
            )
            for shape in nested_shapes
        ),
        tuple(escapes),
    )


def semantic_mutation_detected(**kwargs) -> bool:
    try:
        validate_catalog(
            kwargs.get("catalog", SERVER_MESSAGE_CATALOG),
            kwargs.get("declarations", SERVER_DECLARATIONS),
            kwargs.get("nested_shapes", SERVER_NESTED_SHAPES),
            kwargs.get("escapes", SERVER_VERBATIM_ESCAPES),
        )
        changed = semantic_catalog_fingerprint(
            kwargs.get("catalog", SERVER_MESSAGE_CATALOG),
            kwargs.get("declarations", SERVER_DECLARATIONS),
            kwargs.get("nested_shapes", SERVER_NESTED_SHAPES),
            kwargs.get("escapes", SERVER_VERBATIM_ESCAPES),
        )
        return changed != semantic_catalog_fingerprint()
    except ValueError:
        return True


def self_test() -> None:
    generated = render()
    if generated != render():
        raise AssertionError("server-model generation is not deterministic")
    if generated.count("@JvmSynthetic\ninternal fun decode") != 30:
        raise AssertionError("generated source must contain exactly 30 public-message decoders")
    if sum(generated.count(f"{declaration.name}") > 0 for declaration in SERVER_DECLARATIONS) != 33:
        raise AssertionError("generated source must contain all 33 structured declarations")
    for support_name in ("HtspServerMessage", "HtspBinary"):
        if any(declaration.name == support_name for declaration in SERVER_DECLARATIONS):
            raise AssertionError(f"support declaration remains in generated catalog: {support_name}")
        if f"public class {support_name}" in generated or f"public sealed interface {support_name}" in generated:
            raise AssertionError(f"support declaration remains in generated output: {support_name}")
    for support_fragment in (
        "const val HTSP_U32_MAX", "fun requireU32", "fun <T> List<T>.immutableSnapshot",
        "import java.util.Collections",
    ):
        if support_fragment in generated:
            raise AssertionError(f"support implementation remains in generated output: {support_fragment}")
    for entry in SERVER_MESSAGE_CATALOG:
        if generated.count(f"internal fun {entry.decoder}(") != 1:
            raise AssertionError(f"expected one decoder declaration: {entry.decoder}")
    flattened_fields = tuple(
        field for entry in SERVER_MESSAGE_CATALOG for field in entry.fields
    ) + tuple(field for shape in SERVER_NESTED_SHAPES for field in shape.fields)
    if SERVER_WIRE_FIELDS != flattened_fields:
        raise AssertionError("SERVER_WIRE_FIELDS must preserve complete reviewed decode order")
    expected_explicit_minima = {
        (owner, property_name): minimum_version
        for owner, property_name, minimum_version in (
            ("channelAdd", "channelUuid", 41),
            ("channelAdd", "channelNumberMinor", 13),
            ("channelAdd", "services", 5),
            ("channelUpdate", "channelUuid", 41),
            ("channelUpdate", "channelNumberMinor", 13),
            ("channelUpdate", "services", 5),
            ("tagAdd", "tagUuid", 41),
            ("tagAdd", "tagIndex", 18),
            ("tagUpdate", "tagUuid", 41),
            ("tagUpdate", "tagIndex", 18),
            ("dvrEntryAdd", "entryUuid", 41),
            ("dvrEntryAdd", "eventId", 13),
            ("dvrEntryAdd", "autorecEntryUuid", 13),
            ("dvrEntryAdd", "startExtraMinutes", 13),
            ("dvrEntryAdd", "stopExtraMinutes", 13),
            ("dvrEntryAdd", "retentionDays", 13),
            ("dvrEntryAdd", "priority", 13),
            ("dvrEntryAdd", "contentType", 13),
            ("dvrEntryAdd", "ratingAuthority", 41),
            ("dvrEntryAdd", "ratingCountry", 41),
            ("dvrEntryAdd", "subscriptionError", 20),
            ("dvrEntryUpdate", "entryUuid", 41),
            ("dvrEntryUpdate", "eventId", 13),
            ("dvrEntryUpdate", "autorecEntryUuid", 13),
            ("dvrEntryUpdate", "startExtraMinutes", 13),
            ("dvrEntryUpdate", "stopExtraMinutes", 13),
            ("dvrEntryUpdate", "retentionDays", 13),
            ("dvrEntryUpdate", "priority", 13),
            ("dvrEntryUpdate", "contentType", 13),
            ("dvrEntryUpdate", "ratingAuthority", 41),
            ("dvrEntryUpdate", "ratingCountry", 41),
            ("dvrEntryUpdate", "subscriptionError", 20),
            ("eventUpdate", "ratingAuthority", 41),
            ("eventUpdate", "ratingCountry", 41),
            ("subscriptionStart", "codecMetadata", 17),
            ("subscriptionStop", "subscriptionError", 20),
            ("subscriptionStatus", "subscriptionError", 20),
            ("signalStatus", "absoluteSnr", 44),
            ("signalStatus", "absoluteSignal", 44),
            ("descrambleInfo", "subscriptionId", 24),
            ("service", "providerName", 38),
            ("event", "ratingAuthority", 41),
            ("event", "ratingCountry", 41),
            ("stream", "compositionId", 5),
            ("stream", "ancillaryId", 5),
            ("stream", "aspectNumerator", 5),
            ("stream", "aspectDenominator", 5),
            ("stream", "audioType", 11),
            ("stream", "channelCount", 5),
            ("stream", "sampleRate", 5),
            ("stream", "codecMetadata", 17),
            ("source-info", "satellitePosition", 20),
        )
    }
    actual_explicit_minima = {
        (field.owner, field.kotlin_property): field.minimum_version
        for field in SERVER_WIRE_FIELDS
        if field.minimum_version is not None
    }
    if actual_explicit_minima != expected_explicit_minima:
        raise AssertionError("reviewed explicit server field minima drifted")

    first_decl = SERVER_DECLARATIONS[0]
    first_property_declaration_index = next(
        i for i, declaration in enumerate(SERVER_DECLARATIONS) if len(declaration.properties) >= 2
    )
    first_property_declaration = SERVER_DECLARATIONS[first_property_declaration_index]
    first_prop = first_property_declaration.properties[0]
    second_prop = first_property_declaration.properties[1]
    first_entry = SERVER_MESSAGE_CATALOG[0]
    first_field = first_entry.fields[0]
    alias_field_index = next(i for i, field in enumerate(first_entry.fields) if len(field.wire_names) > 1)
    alias_field = first_entry.fields[alias_field_index]
    nested_link_index = next(i for i, field in enumerate(first_entry.fields) if field.nested_shape)
    nested_wire_shape_index = next(
        i for i, shape in enumerate(SERVER_NESTED_SHAPES)
        if any(field.nested_wire_names for field in shape.fields)
    )
    nested_wire_shape = SERVER_NESTED_SHAPES[nested_wire_shape_index]
    nested_wire_field_index = next(
        i for i, field in enumerate(nested_wire_shape.fields) if len(field.nested_wire_names) > 1
    )
    nested_wire_field = nested_wire_shape.fields[nested_wire_field_index]
    mutations = (
        ("catalog size", {"catalog": SERVER_MESSAGE_CATALOG[:-1]}),
        ("method", {"catalog": (replace(first_entry, method=SERVER_MESSAGE_CATALOG[1].method),) + SERVER_MESSAGE_CATALOG[1:]}),
        ("property name", {"declarations": SERVER_DECLARATIONS[:first_property_declaration_index] + (replace(first_property_declaration, properties=(replace(first_prop, name="changedName"),) + first_property_declaration.properties[1:]),) + SERVER_DECLARATIONS[first_property_declaration_index + 1:]}),
        ("property type", {"declarations": SERVER_DECLARATIONS[:first_property_declaration_index] + (replace(first_property_declaration, properties=(replace(first_prop, kotlin_type="Int"),) + first_property_declaration.properties[1:]),) + SERVER_DECLARATIONS[first_property_declaration_index + 1:]}),
        ("property order", {"declarations": SERVER_DECLARATIONS[:first_property_declaration_index] + (replace(first_property_declaration, properties=(second_prop, first_prop) + first_property_declaration.properties[2:]),) + SERVER_DECLARATIONS[first_property_declaration_index + 1:]}),
        ("visibility", {"declarations": (replace(first_decl, visibility="internal"),) + SERVER_DECLARATIONS[1:]}),
        ("decoder", {"catalog": (replace(first_entry, fields=(replace(first_field, decoder="unexpected-decoder"),) + first_entry.fields[1:]),) + SERVER_MESSAGE_CATALOG[1:]}),
        ("wire type", {"catalog": (replace(first_entry, fields=(replace(first_field, wire_type="s64"),) + first_entry.fields[1:]),) + SERVER_MESSAGE_CATALOG[1:]}),
        ("alias order", {"catalog": (replace(first_entry, fields=first_entry.fields[:alias_field_index] + (replace(alias_field, wire_names=tuple(reversed(alias_field.wire_names))),) + first_entry.fields[alias_field_index + 1:]),) + SERVER_MESSAGE_CATALOG[1:]}),
        ("minimum_version", {"catalog": (replace(first_entry, fields=(replace(first_field, minimum_version=1),) + first_entry.fields[1:]),) + SERVER_MESSAGE_CATALOG[1:]}),
        ("direction", {"catalog": (replace(first_entry, fields=(replace(first_field, direction="client-to-server"),) + first_entry.fields[1:]),) + SERVER_MESSAGE_CATALOG[1:]}),
        ("presence", {"catalog": (replace(first_entry, fields=(replace(first_field, presence="optional"),) + first_entry.fields[1:]),) + SERVER_MESSAGE_CATALOG[1:]}),
        ("lenient_malformed", {"catalog": (replace(first_entry, fields=(replace(first_field, lenient_malformed=True),) + first_entry.fields[1:]),) + SERVER_MESSAGE_CATALOG[1:]}),
        ("nested_shape", {"catalog": (replace(first_entry, fields=first_entry.fields[:nested_link_index] + (replace(first_entry.fields[nested_link_index], nested_shape="missing-shape"),) + first_entry.fields[nested_link_index + 1:]),) + SERVER_MESSAGE_CATALOG[1:]}),
        ("nested_wire_names", {"nested_shapes": SERVER_NESTED_SHAPES[:nested_wire_shape_index] + (replace(nested_wire_shape, fields=nested_wire_shape.fields[:nested_wire_field_index] + (replace(nested_wire_field, nested_wire_names=tuple(reversed(nested_wire_field.nested_wire_names))),) + nested_wire_shape.fields[nested_wire_field_index + 1:]),) + SERVER_NESTED_SHAPES[nested_wire_shape_index + 1:]}),
        ("KDoc", {"declarations": SERVER_DECLARATIONS[:6] + (replace(SERVER_DECLARATIONS[6], kdoc="Changed KDoc."),) + SERVER_DECLARATIONS[7:]}),
        ("validation", {"declarations": SERVER_DECLARATIONS[:1] + (replace(SERVER_DECLARATIONS[1], validations=SERVER_DECLARATIONS[1].validations[:-1]),) + SERVER_DECLARATIONS[2:]}),
        ("verbatim escape", {"escapes": ((first_entry.method, "Reason changed for mutation proof."),)}),
    )
    for dimension, mutation in mutations:
        if not semantic_mutation_detected(**mutation):
            raise AssertionError(f"semantic {dimension} mutation was accepted")
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "GeneratedHtspServerMessages.kt"
        path.write_text(generated, encoding="utf-8", newline="\n")
        if path.read_bytes() != generated.encode("utf-8"):
            raise AssertionError("temporary generated output drifted")


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    generated = render()
    if args.write:
        OUTPUT.write_text(generated, encoding="utf-8", newline="\n")
        return 0
    if not OUTPUT.is_file() or OUTPUT.read_bytes() != generated.encode("utf-8"):
        raise SystemExit(f"generated typed HTSP server-message models are stale: {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
