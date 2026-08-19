# HTSP binary wire format

The golden frame fixtures under `src/test/resources/htsp-wire/` record the
codec's deliberate differences from the pinned TVHeadend server.

Each `.hex` file holds one complete frame. Whitespace separates two-digit
hexadecimal bytes, and `#` starts a comment that runs to the end of the line.
The test loader rejects any other token.

Most fixtures are decoded and then re-encoded to the same bytes. A few are decode-only,
because the pinned server's output cannot always be reproduced byte-for-byte by
the local encoder. Decode-only fixtures pin the exact bytes and the decoded
values and are explicitly exempt from re-encode. `boolean-false.hex` is one:
the pinned server emits false as a zero-length BOOL, while the local encoder
emits one Boolean byte.

The `scripted-*-response.hex` fixtures are deterministic server replies used by
the public socket-factory integration test. They drive `hello`, anonymous
`authenticate`, and `getSysTime` in request order without opening a network
connection.

## Frame layout

A frame starts with a four-byte unsigned big-endian body length, followed by
that many body bytes. A map or list body is a sequence of fields. Each field
contains:

1. a one-byte type ID;
2. a one-byte UTF-8 name length (zero for nameless list members);
3. a four-byte unsigned big-endian data length;
4. the name bytes; and
5. the data bytes.

Nested MAP and LIST data recursively uses the same field sequence, without
another four-byte root prefix.

| ID | Local type | Pinned serializer |
|---:|---|---|
| 1 | MAP | yes |
| 2 | S64 | yes |
| 3 | STR (UTF-8 bytes) | yes |
| 4 | BIN | yes |
| 5 | LIST | yes |
| 6 | DBL (eight-byte little-endian IEEE 754) | **no** |
| 7 | BOOL | yes |
| 8 | UUID (16 bytes) | yes |

The local codec keeps DBL read/write behavior for compatibility, so
`scalar-types.hex` pins all eight local IDs and re-encodes them locally. That
DBL field is local-only: at the pinned TVHeadend source, `htsmsg_binary_des0`
has no `HMF_DBL` decode case, and the `htsmsg_binary_write` length switch has
no `HMF_DBL` case and falls through to `default: abort()`. Treat DBL as local
compatibility evidence, not as something the pinned server can serialize.

## Signed 64-bit values

S64 data is little-endian and variable length. Zero has data length zero.
Positive values use the shortest byte sequence that retains all nonzero bits.
Negative values from the local encoder use all eight little-endian bytes. These
rules match the unsigned-bit-pattern loop in pinned `htsmsg_binary_count` and
the byte loop in `htsmsg_binary_write`; `htsmsg_binary_des0` rebuilds the value
from the last data byte toward the first.

Zero-length S64 decodes as `0L` on both implementations. S64 payloads longer
than eight bytes keep their low eight little-endian bytes on both; the local
tests also pin following-field alignment in
`HtspMalformedFrameCatalogTest.signed64LongerThanEight_usesLowEightLittleEndianBytesAndPreservesAlignment`.

## Boolean false

The pinned serializer normally emits false with data length zero. The
decode-only `boolean-false.hex` fixture pins its exact 30-byte body and the
`false` decode in
`HtspGoldenCorpusTest.booleanFalse_decodeOnlyPinsPinnedZeroLengthEncoding`.

## Where the local codec is more lenient

The local codec is intentionally more permissive than the pinned server for
forward compatibility with other TVHeadend builds. The draining described
below applies only inside a scalar field's declared data slice;
residue at the root or inside a container is parsed as another field, never
silently drained.

| Input | Local behavior | Pinned server | Classification | Pinning test |
|---|---|---|---|---|
| Unknown type ID | Preserve the complete field data as a raw `ByteArray`. | Frees the field and rejects the whole message. | Local leniency diverging from pin. | `HtspMalformedFrameCatalogTest.unknownTypeId_decodesExactRawBytes` |
| DBL type 6, including eight-byte data | Decode eight-byte little-endian IEEE 754; wrong lengths drain the declared slice and decode `0.0`. | No decode case: rejects every type 6; serializer has no case and aborts. | Local-only type, not server-producible. | `HtspGoldenCorpusTest.scalarTypes_pinS64Utf8BinaryDoubleBooleanAndUuidBytes`; `HtspMalformedFrameCatalogTest.doubleWrongLength_decodesZeroAndPreservesFollowingFieldAlignment` |
| BOOL data length greater than one | Use whether the first byte is nonzero, then drain the declared field tail and preserve alignment. | True only when length is exactly one; otherwise false. | Bounded local divergence; conforming pinned serialization emits only lengths zero or one. | `HtspMalformedFrameCatalogTest.booleanLengthGreaterThanOne_usesFirstByteAndPreservesNextFrameAlignment` |
| One to five residual bytes after the last complete root/container field | Attempts another field and rejects; one deterministic residual root byte reports `HtspFramingException` with `field byte exceeds enclosing frame`. | Rejects when the decode loop returns with any residue. | Both reject; the local failure taxonomy differs. | `HtspMalformedFrameCatalogTest.oneResidualRootByteAfterCompleteField_isNotSilentlyDrained` |

## Malformed and truncated input

`HtspFramingException` carries the local failure taxonomy and byte offset.
Physical EOF while reading a declared header, name, or data region remains
`EOFException`.

| Condition | Local outcome | Pinned server | Pinning test |
|---|---|---|---|
| Root length zero or greater than 32 MiB | `HtspFramingException` with `invalid root length` at byte offset 0. | Local safety bound and taxonomy; not asserted as pinned-server behavior. | `HtspMalformedFrameCatalogTest.rootLengthZero_isInvalidRootFramingFailure`; `rootLengthAbove32MiB_isInvalidRootFramingFailure` |
| EOF inside the four-byte root header, a declared field name, or declared field data | `EOFException`. | Physical truncation is rejected by both; `EOFException` is local taxonomy. | `HtspMalformedFrameCatalogTest.eofMidRootHeader_isEof`; `eofMidFieldName_isEof`; `eofMidFieldData_isEof` |
| Field name plus data exceeds its enclosing root or container bound | `HtspFramingException` with `field name and data exceed enclosing frame`. | Rejects an over-bound field. | `HtspMalformedFrameCatalogTest.fieldNameAndDataBeyondEnclosingRoot_isFramingFailure`; `fieldNameAndDataBeyondEnclosingContainer_isFramingFailure` |
| Nesting beyond 32 containers | `HtspFramingException` with `nesting exceeds limit`; the 32-container boundary remains parseable. | The depth limit and taxonomy are local safety policy. | `HtspMalformedFrameCatalogTest.nestingBeyond32_isFramingFailure`; `nestingAt32_remainsParseable` |

`HtspCodecDeterministicFuzzTest` supplements the catalog with fixed-seed,
CI-bounded mutation of every golden frame, including the decode-only BOOL false
fixture, and with generation of small random bodies. It permits only a decoded
message, `EOFException`, or `HtspFramingException`, and it guards reads at four
bytes plus the unsigned declared root length. Its extension test separately
pins that physical bytes beyond a smaller declared root remain unread.

## Pinned source

The fixtures were derived by hand from TVHeadend `src/htsmsg_binary.c` at
revision `27295c5a48f2c575678bb224014cb9a26a773083`, Git blob
`48a1bf985ed554df473adb3a9251b479dfcdaf26`. The governing functions are
`htsmsg_binary_deserialize`, `htsmsg_binary_des0`, `htsmsg_binary_count`,
`htsmsg_binary_write`, and `htsmsg_binary_serialize`. The exact immutable
source is at
<https://github.com/tvheadend/tvheadend/blob/27295c5a48f2c575678bb224014cb9a26a773083/src/htsmsg_binary.c>.
