# HTSP binary wire format

This test authority describes the checked-in golden frames under
`sdk/htsp-protocol/src/test/resources/htsp-wire/`. It does not add a public API,
support claim, or malformed-frame policy; a malformed/lenient catalog is reserved
for C3.

Each `.hex` file contains one complete frame. Whitespace separates two-digit
hexadecimal bytes and `#` starts a comment through the end of the line. The
deterministic test loader rejects any other token.

## Layout

A frame starts with a four-byte unsigned big-endian body length, followed by that
many body bytes. A map or list body is a sequence of fields. Each field contains:

1. one-byte type ID;
2. one-byte UTF-8 name length (zero for nameless list members);
3. four-byte unsigned big-endian data length;
4. the name bytes; and
5. the data bytes.

Nested MAP and LIST data recursively use the same field sequence without another
four-byte root prefix.

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

The local codec intentionally retains DBL read/write behavior, so the corpus pins
all eight local IDs. At the pinned TVHeadend source, `htsmsg_binary_des0` has no
`HMF_DBL` decode case, and the `htsmsg_binary_write` length switch has no
`HMF_DBL` case and reaches `default: abort()`. Therefore DBL is local
compatibility evidence, not a claim about pinned-server serialization.

## Signed 64-bit values

S64 data is little-endian and variable length. Zero has data length zero.
Positive values use the shortest byte sequence that retains all nonzero bits.
Negative local encoder values use all eight little-endian bytes. These rules
match the unsigned-bit-pattern loop in pinned `htsmsg_binary_count` and the byte
loop in `htsmsg_binary_write`; `htsmsg_binary_des0` rebuilds the value from the
last data byte toward the first.

## Pinned source

The fixtures were derived by hand from TVHeadend
`src/htsmsg_binary.c` at revision
`27295c5a48f2c575678bb224014cb9a26a773083`, Git blob
`48a1bf985ed554df473adb3a9251b479dfcdaf26`. The governing functions are
`htsmsg_binary_deserialize`, `htsmsg_binary_des0`, `htsmsg_binary_count`,
`htsmsg_binary_write`, and `htsmsg_binary_serialize`. The exact immutable source
is available at
<https://github.com/tvheadend/tvheadend/blob/27295c5a48f2c575678bb224014cb9a26a773083/src/htsmsg_binary.c>.
