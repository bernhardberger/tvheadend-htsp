# HTSP protocol reference

This directory contains protocol reference notes for the hand-maintained HTSP
surface. The typed surface was reviewed against TVHeadend revision
`f082b430ae66f1761c2e82c34549b759c168f3c6`, which reports HTSP v44.

## Artifacts

| Path | Description |
|---|---|
| [`upstream.json`](upstream.json) | Declarative pin record: repository, revision, source blob hashes and sizes, and documentation URLs. It is no longer machine-reverified. |
| [`WIRE_FORMAT.md`](WIRE_FORMAT.md) | Binary framing, codec limits, and golden fixtures. |

The upstream source bodies are not vendored here.

## Version posture

The client requests HTSP v44 by default, and servers clamp the negotiated
version with `MIN(server, requested)`. The minimum supported server version is
HTSP 36, first introduced upstream in 2021-08 (revision `81403634`). Servers
reporting a lower version are rejected before authentication with
`UNSUPPORTED_SERVER_VERSION`, not `INCOMPATIBLE_SERVER` (a protocol violation).
Callers may explicitly request another version at or above 36. Servers at
v36–v43 omit newer fields; these observations remain nullable rather than being
invented by the client. The typed surface's coverage ceiling is TVHeadend master at
`f082b430`, which reports HTSP v44. v44 adds only `feAbsoluteSNR` and
`feAbsoluteSignal` on `signalStatus`; both are decoded as optional fields.
The server emits them according to the frontend's signal scale, not the
negotiated version (pinned `src/htsp_server.c:4523-4530` has no version
guard), so a v44 server may send them on a v43 link and an older server never
sends them. The pinned server has no other behavior that depends on a
negotiated version of 43 or 44.

HTSP protocol versions are not TVHeadend release versions. Bumping the pin is a
manual, reviewed change: read the upstream diff, edit the Kotlin, add focused
tests, and update `upstream.json` and the changelog.

## Authority

Use the TVHeadend sources named in `upstream.json` first. The official
[Communication](https://docs.tvheadend.org/documentation/development/htsp/communication),
[client RPC](https://docs.tvheadend.org/documentation/development/htsp/client-to-server-rpc-methods),
[server message](https://docs.tvheadend.org/documentation/development/htsp/server-to-client-methods),
and [protocol change](https://docs.tvheadend.org/documentation/development/htsp/protocol-changes)
pages are secondary. `lib/py/tvh/htsp.py` is only a protocol-33 cross-check for
hello, authentication, and asynchronous metadata. Current repository code and
tests define accepted local behavior.

## Coverage

| Surface | Count | Meaning |
|---|---:|---|
| Client to server | 39 | Methods reviewed in the pinned TVHeadend source. |
| Referenced method names | **39** | Exact method literals found during the source review. |
| Outgoing request names | **39** | Distinct names assigned to outgoing `method` fields. |
| Typed client requests | **39** | Reviewed request/response models with `HtspConnection` extensions. |
| Server to client | 30 | Asynchronous messages reviewed in the pinned source. |
| Handled server messages | **30** | Exact message literals found during the source review. |
| Typed server messages | **32** | Payload models for 30 methods, including two separate sparse shapes. |

Keep three distinctions clear:

- A referenced name is not proof that a method is called. `subscriptionSeek`
  and `subscriptionSkip`, for example, are separate wire names for one handler.
- Typed request coverage means a reviewed `HtspRequest` and typed connection
  extension. It does not say which servers or configurations support the call.
- Typed server-message coverage means payload models and a finite decoder.
  Channel, tag, EPG, DVR, autorec, timerec, and event messages publish through
  the global metadata flow. All eleven subscription message types publish only
  through the registered ordered per-subscription flow. Decoding is strict:
  malformed present fields in recognized timerec add/update messages are not
  converted into absence.

Autorec and timerec Add/Update/Delete messages are finite read-only metadata.
`descrambleInfo` completes the typed subscription catalog and publishes through
the matching registered subscription stream. The six autorec/timerec RPCs also
have finite mappings; they do not add schedule publication, lifecycle, retry, or
DVR policy.

## Protocol quirks and version notes

`subscriptionStart` can contain duplicate top-level `meta` keys
(`src/htsp_server.c:4370–4374`; v36 :4219–4223). For every duplicate map key,
at any depth, the codec keeps the first value, matching upstream
`htsmsg_field_find` (`src/htsmsg.c:171–182`), which every `htsmsg_get_*` lookup
uses. The decoded `meta` is therefore the codec header of the first stream that
has one, with no stream association on the wire; nested stream `meta` is ignored.

- Wire BOOL is true only for exactly one nonzero data byte; every other length
  decodes false (`src/htsmsg_binary.c:133–135,146–147`). The binary format has no double
  type: type 6 has no decoder or serializer case (`src/htsmsg_binary.c:88–143,225–255,275–350`,
  revision `f082b430ae66f1761c2e82c34549b759c168f3c6` from `upstream.json`). The local
  decoder preserves type-6 data as unknown bytes; encoding Double/Float is rejected.

Recording-file `info` is a list of stream maps (`src/dvr/dvr_rec.c:1465–1513`):
optional type, language, audio codes, video dimensions/aspect ratio,
90 kHz frame duration (normalized to microseconds), and subtitle IDs. Channel
service `hbbtv` maps section strings to application lists with localized titles,
URL and visibility strings (`src/input/mpegts/dvb_psi_hbbtv.c:104–183`). Both
representations snapshot their collections and tolerate legacy nested data:
the server copies saved configuration back unchecked (`src/service.c:1788–1794`,
`src/dvr/dvr_db.c:1108–1110`). Missing, wrongly typed, or out-of-domain recording
stream fields become null; non-map stream elements become all-null placeholders
to preserve positions. Outer containers (`hbbtv` map, `info` list, and `credits`
map) remain strict; credit names and roles also retain strict string validation.
Packet `com` uses shared enum constants for unknown/commercial/non-commercial;
future u32 codes map to `UNRECOGNIZED` (`src/streaming.h:59–63`).

### Integer flags

The typed API exposes integer 0/1 flags as Boolean values, preserving nullable
absence. Request encoding uses integer 0/1, not HTSP's separate BOOL type, where
the pinned server reads an integer. Incoming message and reply data flags accept zero as false and any
nonzero integer as true; non-integer types remain malformed. This includes
normalized flags as well as raw `isNew` (`src/epg.c:1839`) and timeshift `full`.
Reply envelope `success`, `noaccess`, and `connlimit` remain strict 0/1.
Evidence below refers to pinned `src/htsp_server.c`:

| Kotlin property / surface | Wire field | Lines |
|---|---|---|
| DVR update request `enabled` | `enabled` (s64 reader) | 2196 |
| DVR add/update message `enabled` | `enabled` | 981 |
| DVR add/update message `duplicate` | `duplicate` | 1188 (explicit 0/1 expression) |
| Autorec add/update request `fullText`, `mergeText` | `fulltext`, `mergetext` (u32 readers) | 608-611; normalized output at 1251-1252 |
| Event `isNew` | `isNew` | 1437 |
| EPG query request `full` | `full` (u32 reader) | 1915 |
| Async metadata request `epg` | `epg` (u32 reader) | 1660-1661 |
| Subscribe request `ninetyKhz` | `90khz` (u32 reader) | 2658 |
| Seek/skip request `absolute` | `absolute` (u32 reader) | 2824-2834 |
| Skip message `absolute`, `error` | `absolute`, `error` | 4625-4628 (presence of 1; `error` is not a numeric code) |
| Subscription stream `rdsUecp` | `rds_uecp` | 4367 (explicit 0/1 expression) |
| Timeshift status `full` | `full` | 4596 |

`tagTitledIcon` remains numeric: line 949 forwards `ct_titled_icon` without
establishing a 0/1 domain in this source. `duplicateDetection` (`dupDetect`) and
`broadcastType` remain numeric policy values (lines 612-619, 1244-1246), not
enablement flags. Likewise `priority`, `audioType`, and `audioVersion` remain
numeric codes (lines 665-666, 4360-4362); service `content` explicitly has three
values (line 904). Counters, identifiers, masks, and measurements retain their
integer widths. `epgQuery.fullText`/`mergeText` already use Boolean properties and
the native BOOL wire type read by lines 1883-1885; that encoding is unchanged.

### Units

Wire keys below match the request and server-message decoders. Lines refer to
pinned `src/htsp_server.c` at f082b430; detailed EPG serialization is delegated
to `epg_object_serialize` there rather than expanded in the HTSP sender.

| Current property | Wire field | Unit | Pinned lines |
|---|---|---|---|
| Event/event-update `startEpochSeconds`, `stopEpochSeconds` | `start`, `stop` | Epoch seconds | 1342–1343 |
| Event/event-update `firstAiredEpochSeconds` | `firstAired` | Epoch seconds | 1435 |
| DVR add/update, recording file, explicit-channel selector, update request `startEpochSeconds`, `stopEpochSeconds` | `start`, `stop` | Epoch seconds | 996–997, 1136–1139, 2085–2095, 2198–2199 |
| DVR update `startExtraMinutes`, `stopExtraMinutes` | `startExtra`, `stopExtra` | Minutes | 998–999, 2200–2201 |
| DVR update `retentionDays`, `removalDays` | `retention`, `removal` | Days or DVR policy sentinel | 1002–1007, 2202–2203 |
| DVR update `playPositionSeconds` | `playposition` | Seconds | 1078, 2235, 3066 |
| DVR cutpoint `startMs`, `endMs` | `start`, `end` | Milliseconds | 2562–2563 |
| Get-events `maxTimeEpochSeconds` | `maxTime` | Epoch seconds | 1813–1828 |
| Async metadata `lastUpdateEpochSeconds`, `epgMaxTimeEpochSeconds` | `lastUpdate`, `epgMaxTime` | Epoch seconds | 1662–1679 |
| Detailed EPG `updatedEpochSeconds`, `startEpochSeconds`, `stopEpochSeconds`, `firstAiredEpochSeconds` | `up`, `start`, `stop`, `fair` | Epoch seconds | 1953–1978 (delegated serialization) |
| File open/stat `modifiedAtEpochSeconds` | `mtime` | Epoch seconds | 799–800, 3099–3100 |
| System-time `timeEpochSeconds` | `time` | Epoch seconds | 1636 |
| File read `sizeBytes` | `size` | Bytes | 3007–3030 |
| File read/seek, seek response `offsetBytes` | `offset` | Bytes | 3015–3016, 3122–3147 |
| Subscription byte seek `sizeBytes` | `size` | Bytes | 2832–2835 |
| Subscribe `queueDepthBytes` | `queueDepth` | Payload bytes | 2676–2677, 4191–4197 |
| Hello request/response, connected state `protocolVersion` | `htspversion` | Unitless version | 1474–1487 |
| Queue `delayUs` | `delay` | Microseconds, normalized from negotiated clock | Queued muxpkt `dts`: 4222; queue DTS scan: 4263–4278; difference emitted: 4279 |
| Subscription stream `frameDurationUs` | `duration` | Microseconds, normalized from negotiated clock | 4350–4351 |
| Timeshift `shift`, `start`, `end` | `shift`, `start`, `end` | Negotiated 90 kHz ticks or microseconds | 4597–4601 |
| Subscription seek/skip `time` | `time` | Negotiated 90 kHz ticks or microseconds | 2827–2831, 4630 |
| Descramble `ecmTime` | `ecmtime` | Not established by pinned sender | 4559 |

### Other protocol behavior

- `ServerError.serverMessage` preserves the reply's string `error`. Pinned
  `src/htsp_server.c:488-494` passes `htsp_error`'s fixed `N_()` literals through
  `tvh_gettext_lang` for the connection language; examples include the DVR
  refusals at lines 2148-2160. The `api` error conversion at lines 1558-1572
  maps permission errors to `noaccess: 1`, missing/unimplemented methods to an
  empty reply, and other failures to the literal `N_("Bad request")`, not backend
  error text. These messages are untrusted display text, not stable codes.
- DVR mutations require `success: 1` for typed success. `addDvrEntry` emits
  `id` plus `success: 1` in pinned `src/htsp_server.c:2128-2129`; the decoder
  requires `id`. The unevidenced `dvrId` alias is ignored and cannot replace it.
  Its failure branch at lines 2132-2133 emits `success: 0` plus the fixed
  `error: "Could not add dvrEntry"`. `updateDvrEntry`, `stopDvrEntry`,
  `cancelDvrEntry`, and `deleteDvrEntry` return `htsp_success()` at lines
  2243, 2261, 2279, and 2297 respectively (`success: 1`, lines 499-504).
  Their DVR-entry lookup refusals use `htsp_error` via `htsp_findDvrEntry`
  at lines 2143-2160. Integer `success: 0` is `ServerError`, with or without
  an `error` string. Without an explicit error, missing or wrong-typed `success`,
  integers other than 0 or 1, or a malformed success payload, are `MalformedReply`.
- Malformed `noaccess` (not an integer 0 or 1), non-string `error`, and typed
  reply mapping failures are `MalformedReply`, not server refusals. Explicit
  `noaccess: 1` remains `AccessDenied` unless integer `connlimit: 1` refines it
  to `ConnectionLimit`; other `connlimit` values do not invalidate that denial.
- Connection limits apply only to streaming. Since upstream `1ddc5d10`, the
  server admits any HTSP connection and checks the user's connection limits when
  the connection starts streaming: on `subscribe` and on `fileOpen` of `dvr/` or
  `dvrfile/` paths. A refusal is the reply `{noaccess: 1, connlimit: 1}`, which
  maps to `HtspResult.ConnectionLimit`; for `subscribe` the registered stream
  then ends with `Terminated(SUBSCRIBE_REJECTED)`. Older servers also check the
  limits at connect time.
- The `api` method cannot report "not found". In pinned
  `src/htsp_server.c:1540-1581`, `api_exec` results `ENOENT` and `ENOSYS` are
  answered with an empty successful reply, the same as a call that produced no
  response. `src/api.c:86` returns `ENOSYS` for an unknown path, and handlers
  such as `src/api/api_idnode.c:670` return `ENOENT` for a missing object.
  `ApiResponse.NoPayload` therefore covers all three cases. `EPERM`/`EACCES`
  reply `noaccess` (`HtspResult.AccessDenied`); other errors reply
  `"Bad request"` (`HtspResult.ServerError`).
- `queueStatus.errors` is an optional u32 cumulative data-error counter, exposed
  as nullable `HtspQueueStatusMessage.errorCount`. Absence is not converted to
  zero. In pinned `src/htsp_server.c:4183-4184`, packet errors accumulate in
  `hs_data_errors`; lines 4249-4250 emit `errors` only when that count is nonzero.
  There is no negotiated-version guard on this field. The
  counter is distinct from `Bdrops`, `Pdrops`, and `Idrops`.
- Interpret fields by direction, wire or container type, presence, and known
  minimum version. Named nested shapes may be complete, partial, dynamic or
  opaque, known-empty, alternative, or unknown. Global RPC fields `seq`,
  `error`, and `noaccess` are separate from method fields, and access masks are
  provenance rather than an authorization API. Documentation TODOs, `???`,
  source heuristics, and the protocol-33 demo remain explicitly uncertain.
- Recorded minima include channel services at v5; channel minor numbers and
  selected DVR observations at v13; stream metadata at v5/v11 and top-level
  codec metadata at v17; subscription errors and satellite source metadata at
  v20; service provider names at v38; string UUIDs guaranteed from v41; and
  absolute signal/SNR observations at v44. DVR `ageRating` is guaranteed from
  v37, but server-dependent at v36; see the introduction and gate history below.
  A minimum is compatibility evidence,
  while an unknown minimum remains `null`.
- `api` records this `acceptedVocabulary`: `map`, `list`, `str`, `s64`, `bin`, `bool`, and fixed-width
  16-byte `uuid`, with decode and serialization evidence in
  `src/htsmsg_binary.c`. It excludes `dbl`: `HMF_DBL=6` exists in `htsmsg.h`, but
  the pinned decoder rejects it through the default branch and the serializer
  reaches its default abort. The bridge therefore has no `Double` or `Float`.
- `hello` requires u32 `htspversion` and string `clientname`, does not read
  `clientversion`, and sets the connection version to
  `MIN(HTSP_PROTO_VERSION, requested)`. Its reply always has a 32-byte challenge
  and six other observations; `webroot` and `language` are conditional.
  `authenticate` reads no method-specific fields. Denial emits only
  `noaccess=1`; granted rights above v25 emit ten access/limit/UI fields, while
  v25 and earlier emit an empty method payload.
- `getEvents` is v4. Its optional `channelId`, `eventId`, `language`,
  `numFollowing`, and signed-s64 `maxTime` filters have v6 compatibility
  evidence. The method reply is required `events:list -> event`.
- `getEpgObject` requires u32 `id`, accepts optional u32 `type`, needs streaming
  access, and has no evidenced minimum. The enum has undefined and broadcast,
  but only broadcast has a serializer. Its reply combines base and broadcast
  fields: required `id`, broadcast `tp`, signed-s64 `up`, `start`, and `stop`,
  plus bounded optional scalars, zero/nonzero flags, language maps, episode
  numbers, genres, and string lists. `lang_str_serialize_map` gives language
  maps strict string keys and values; string-list output is sorted and unique. `time_t` values remain Unix
  seconds. `cred` maps person names to role strings, produced by
  `src/epggrab/module/xmltv.c:726–740` and serialized by `src/epg.c:1766–1768`.
  `ratingLabel` is a rating-label UUID (`src/epg.c:1740–1742`). The official reply is `TODO`.
- `getDvrCutpoints` preserves `dc_start_ms` and `dc_end_ms`, source TAILQ order,
  overlaps, and duplicates. The official page does not define the millisecond
  origin, chronology, overlap, or uniqueness semantics.
- `getTicket` accepts one full-u32 `channelId` or `dvrId`; at least one is
  required and the pinned source checks `channelId` first. The reply requires
  string `path` and `ticket`. The both-present source state is not exposed.
- `fileOpen`, `fileRead`, `fileClose`, and `fileSeek` are v8 recorder calls.
  `fileOpen` requires string `file`, strips at most one leading slash inside the
  server, and returns u32 `id` plus coupled signed-s64 `size` and `mtime` after a
  successful `fstat`. `fileRead` uses a connection-owned default-zero handle,
  requires signed-s64 `size`, accepts signed-s64 `offset`, and returns required
  binary `data`, including an empty payload. A typed read is limited to 0..16
  MiB without changing codec, reader, chunking, EOF, handle, or playback rules.
  `fileClose` keeps the id-only form and accepts full-u32 `playposition` and
  `playcount` from v27. Before v27 a recording-backed close increments playcount;
  from v27 an omitted `playcount` defaults to `HTSP_DVR_PLAYCOUNT_INCR` and also
  increments. `playposition` updates whole recording-position seconds from v27.
  `fileSeek` requires signed-s64 `offset`, allows `SEEK_SET`, `SEEK_CUR`, or
  `SEEK_END`, defaults to `SEEK_SET`, and returns a non-negative signed-s64
  absolute offset.
- `fileStat` is v8 with recorder access. It reads u32 `id` with zero default and
  searches the current connection's files. Absent, malformed, unknown, and zero
  IDs use `Invalid file` unless zero owns a handle. When `fstat(fd, &st) == 0`, the reply contains
  coupled signed-s64 `size = st.st_size` then `mtime = st.st_mtime`; a failed
  `fstat` returns an empty success map. There are no other outputs. The official
  docs call these u64, mark them independently optional, and omit empty success
  and the mtime unit; the library leaves POSIX `st_mtime` unchanged.
- `stopDvrEntry` has no evidenced introduction version. Its recorder handler
  uses the DVR-entry helper in write mode, returns its bounded error, calls only
  `dvr_entry_stop`, and returns `success:u32 = 1`. Cancel and delete use other
  operations; later asynchronous DVR metadata is authoritative.
- `subscriptionChangeWeight` is v5 with streaming access. It requires u32
  `subscriptionId`, defaults optional u32 `weight` to zero, searches
  `htsp_subscriptions` for exact `hs_sid`, and returns the missing-subscription error when absent. It queues one
  empty reply before one `subscription_change_weight` call; that order does not
  prove the weight is applied.
- `subscribe` exposes nullable Boolean `ninetyKhz`, encoded as u32 `90khz`:
  omission and false select native 1 MHz values, while true selects 90 kHz
  (pinned `src/htsp_server.c:2658`). Reply `90khz` and `normts`
  are strict optional flags exposed as nullable booleans; `normts` reports
  timestamp-origin normalization and does not select a rate. A subscription id
  is reserved before the request write and cannot be reused in the connection
  generation, so messages that overtake the reply still use an unambiguous
  clock. Timeout or caller cancellation retains that reservation because the
  server may complete the request later.
- `subscriptionLive` is v9 with streaming access. It requires u32
  `subscriptionId`, rejects a missing subscription, zero-initializes
  `streaming_skip_t`, sets only `SMT_SKIP_LIVE`, calls `subscription_set_skip`,
  then queues one empty reply. This does not guarantee delivery order or settled
  state; asynchronous `subscriptionSkip` remains authoritative. On the pinned
  server, this path also selects an `INT64_MAX` return-live operation whose
  timeshift reader can flush the remaining buffer without normal pacing.
- `subscriptionSeek` and `subscriptionSkip` are distinct v44 dispatch names for
  `htsp_method_skip`, a streaming handler with minimum v9. It requires u32
  `subscriptionId`, defaults optional u32 `absolute` to 0, takes signed-s64
  `time` before signed-s64 `size`, and errors if neither is present. Nonzero
  `absolute` selects absolute semantics. It calls `subscription_set_skip` before
  queuing an empty reply. The official docs call seek a synonym, describe
  time/size as optional u64, and omit the either/or rule.
- `subscriptionSkipNearLive` is an additive client helper, not another wire
  method. It requires observed start/end bounds, an explicit matching timestamp
  clock, and a positive caller-selected margin. It validates `end - margin`
  against the observed buffer and the pinned server's multiply-before-divide
  conversion range, then sends one absolute time `subscriptionSkip`. It never
  invokes or falls back to `subscriptionLive`, does not guarantee `TS_LIVE`, and
  leaves the ordered asynchronous timeshift/skip events authoritative.
- `subscriptionFilterStream` is v12 with streaming access. It requires u32
  `subscriptionId`, processes optional `enable:list[u32]` before
  `disable:list[u32]`, and accepts only `HMF_S64` members. It returns one empty
  map. Only indexes 0..511 can alter the `NUM_FILTERED_STREAMS=(64*8)` bitmap;
  larger values are ignored, overlap ends disabled, and an omitted or empty list
  leaves that side unchanged.
- The complete shared `service` shape contains name, type, content, conditional
  access, provider, and a typed `hbbtv` child. `getChannel`, `channelAdd`, and
  full `channelUpdate` share one entity decoder. Now/next is a separate sparse
  message (see below). Shared
  `stream` and `sourceInfo` are partial: stream index/type are required, known
  metadata is optional, and source metadata is independently optional. Their
  minima do not require those containers in `subscriptionStart`.
- The complete shared `event` shape from `htsp_build_event` is used by `getEvent`,
  `getEvents`, full `epgQuery`, `eventAdd`, and `eventUpdate`. Category and keyword
  are ordered string lists; credits are immutable
  ordered `HtspProgrammeCredit` lists (`src/htsp_server.c:1373–1374`).
  The producer appends one field per person and role, so names can repeat
  (`src/epggrab/module/xmltv.c:727,740`); the list keeps every entry in wire
  order, unlike other maps, where the first field per name wins.
  All paths require `eventId`, `start`, and `stop`; channel is conditional
  (`src/htsp_server.c:1339–1343`). Full updates replace the previous event,
  including absent optional fields. All paths pass the negotiated version to
  the shared entity decoder and accept only evidenced field names (see below).
- The 39 canonical request extensions mirror constructors, add timeout and
  generation controls, construct one request, and delegate once to `execute`.
  `fileClose` accepts optional recording position and play-count values. DVR
  event/explicit-time and subscription ID/name choices have wrapper-free
  conveniences. Seek and skip use `SubscriptionSeekPosition.Time` and `.Size`
  because both values are `Long`.

Persisted HbbTV service data is copied on load/save without schema validation
(`src/service.c:1673–1679,1788–1794`). Missing or wrongly typed application
URL/visibility and title language become null; absent title lists become empty,
and unusable nested entries are skipped. Recording-file `info` is likewise
copied from DVR logs (`src/dvr/dvr_db.c:1108–1110,3362–3367`): stream fields are
optional, with missing, wrongly typed, or out-of-domain fields decoded as null.
Non-map stream elements become all-null placeholders, preserving later stream
indexes. The outer `hbbtv` map, `info` list, and `credits` map types remain strict.
These snapshots compare structurally.

New `addDvrEntry` creation options have no additional minimum-version gate:
their introduction versions are unknown. Older servers predating an option
silently ignore it and may still answer Ok.
- The internal `decodeHtspServerMessage` uses the negotiated protocol version
  (standalone tests default to v44); it is not public API. Every `seq` reply envelope, unknown or
  missing/non-string method, as unknown; malformed recognized messages are
  malformed-known. `descrambleInfo` is emitted from
  v24 unless anonymized and requires full-u32 `subscriptionId`, `pid`, `caid`,
  `provid`, `ecmtime`, and `hops`; strings `cardsystem`, `reader`, `from`, and
  `protocol` are optional and strict, with wire `from` exposed as `source`.
  `HtspService` publishes it through the matching registered subscription
  stream rather than global metadata events. Full metadata Add and Update use
  identical requiredness from the builders (table below). DVR files use
  `filename` for their path. Autorec `broadcastType` is nullable:
  it was introduced in HTSP 39 (`fc5a1672`, 2024-08-25), and is absent on
  v36–v38 servers, but required when negotiating v39+. Autorec `comment` is nullable: v36
  `htsp_build_autorecentry` does not send it at all
  (upstream revision `81403634`, `src/htsp_server.c`, `htsp_build_autorecentry`).
  Its `name`, `owner`, and `creator` values use TVHeadend's nullable
  `htsmsg_add_str2` emitter and are therefore nullable;
  other source-conditional observations remain nullable. Delete requires string `id`.
  The shared `htsp_build_timerecentry` add/update shape conditionally omits
  `channel`, `name`, and `title`; an absent channel means all channels. Channel
  IDs retain their complete unsigned-u32 domain. Add requires signed-s32
  `start` and `stop`: `-1` is exposed as nullable unset, `0..1439` are valid
  minutes since midnight, and every other value is malformed. Full Update has
  the same required fields and strict domains as Add.
  `queueStatus.delay` keeps its recorded requiredness uncertainty.
- Mux PTS and DTS remain signed s64 and are never masked or unwrapped at 33
  bits. `HtspService` rescales 90 kHz PTS, DTS, and required u32 duration to
  microseconds with the pinned truncation-toward-zero rule; native values pass
  through unchanged. Missing PTS/DTS stays null. Missing frame type, wire value
  zero, and explicit signed `frametype=-1` all decode to `HtspFrameType.UNKNOWN`;
  wire ASCII I/P/B decode to the corresponding enum entries. Other codes remain
  malformed. The standalone versionless decoder has no
  subscribe context and therefore interprets mux timing as native microseconds.
- No real-server long-run or post-timeshift capture was available for this
  slice, so no claim is made about normalized first-frame behavior after seek.
  That observation remains deferred until a fixture or hardware run supplies
  evidence; the implementation adds no speculative timestamp unwrapping.

## Shared entity and update evidence

`model` owns one entity and one decoder per family; it depends on neither
requests, messages nor connection. Full updates replace snapshots, rather than
merging only non-null fields. All fields outside the required sets below are
nullable; present malformed values remain errors. Line numbers refer to pinned
`src/htsp_server.c`.

| Entity | Always emitted at supported v36–44 (wire names) | Evidence |
|---|---|---|
| Channel | `channelId`, `channelNumber`, `channelName`, `eventId`, `nextEventId`, `services`, `tags` | :859–925 |
| Tag | `tagId`, `tagIndex`, `tagName`, `tagTitledIcon` | :932–958 |
| Event | `eventId`, `start`, `stop` | :1339–1343 |
| DVR entry | `id`, `enabled`, `start`, `stop`, `startExtra`, `stopExtra`, `retention`, `removal`, `priority`, `contentType`, `state` | :977–1014,1190; v36 :953–1125 |
| Autorec | `id`, `enabled`, `maxDuration`, `minDuration`, `retention`, `removal`, `daysOfWeek`, `approxTime`, `start`, `startWindow`, `priority`, `startExtra`, `stopExtra`, `dupDetect`, `maxCount` | :1215–1245 |
| Timerec | `id`, `enabled`, `daysOfWeek`, `retention`, `removal`, `priority`, `start`, `stop` | :1280–1293; required times use -1 for unset |

Required-from-N decoding is used only where upstream history shows that every
server able to negotiate N emits the field at that negotiated version. Negotiation
uses `MIN(HTSP_PROTO_VERSION, requested)` (:1502), so negotiated N implies a server
at least at N. An historical `htsp_version` gate or introduction at the protocol
bump establishes the guarantee; the pinned v44 builder alone does not.

Channel `channelIdStr`, tag `tagIdStr`, and DVR `idStr` are required at v41+
but nullable below v41. Commit `f9d5e885` (2024-09-02, at protocol 40) introduced
them behind `htsp->htsp_version > 40`; it precedes the v41 bump `18ff23f9` and
every upstream ref at v41+. Autorec `broadcastType` is required at v39+ but
nullable below v39: `fc5a1672` introduced it unconditionally in the v39 bump.
Conditional strings remain nullable even after their introduction:
autorec/timerec comments use `htsmsg_add_str2` (:1247,1294); configuration UUIDs
(`dvrConfigUuid`, wire `configId`) require a linked configuration
(:1153–1154,1263–1264,1306–1307). Tag members are an inclusion exception:
`channelIds == null` means not included, and empty means included with no members
(:948–958; initial-sync omission/inclusion :1709,1719).

DVR `ageRating` is required at v37+ but optional at v36 (public type `Long?`).
The v36 pin `81403634` predates its introduction: `d5010599` (2023-08-02, still
protocol 36) added it behind `htsp_version > 35`. The v37 bump `b061e641` changed
the gate to `> 36`; `d5010599` precedes that bump and all v37+ refs. Thus v36
emission depends on the server, while every server negotiating v37+ emits it.
Commit `55404da6` ("Remove HTSP client version test for rating labels and string
UUIDs") later removed these gates, explaining the unconditional rating (:1015)
and UUID emissions in pinned v44. Present malformed values fail at every version.

`channelUpdate` containing `channelName` must validate the full channel. Without
it, only `{channelId,eventId,nextEventId}` plus `method` is accepted and yields
`HtspChannelNowNextMessage` (:3805–3822). `dvrEntryUpdate` containing `enabled`
must validate the full recording. Otherwise `HtspDvrEntryStatsMessage` accepts
only the fields outside `!statsonly` (:980–1155): `id`, v41+ `idStr`, required
`state`, optional `duplicate`, `error`, `subscriptionError`, `streamErrors`,
`dataErrors`, and `dataSize`, plus `method` (:977–980,1157–1201). Duplicate is
emitted for upcoming recordings; errors depend on recording/completed state;
subscription error is emitted for recording failure; error counters require
nonzero values; size requires a known recording/completed file size. Null stats
clear their corresponding old observations, leaving non-statistics fields alone.
Missing discriminators with other full fields and unexpected sparse fields are
malformed. RPC `seq` envelopes are classified before this dispatch.

Decoders accept only field names emitted for that entity/message by a supported
server. The audit compares minimum-supported v36 (`81403634`) and pinned v44
(`f082b430`), both `src/htsp_server.c`; no dual-name aliases are needed. DVR
`channel` is canonical (v36 :969; v44 :983), whereas events use `channelId`
(v36 :1255; v44 :1341). Recording files use `filename` (v36 :1055–1058;
v44 :1129–1132); only the enclosing DVR entity uses `path` (:1077 / :1151).
Recording-file entries have no `id`: the builder copies stored `de_files` maps
and sets `filename`, `info`, `start`, `stop`, and `size` (:1125–1146). Stored
entries are populated by pinned `src/dvr/dvr_rec.c` (`filename` :1311,
`info`/`start` :1534–1535, `stop` :2104); no writer sets an `id` key.
Unknown keys are ignored in full snapshots, but cannot replace required fields;
the exact sparse shapes above reject unknown keys. Event `category` is a list,
and episode ordinals/counts are flat, separately named fields (:706–725 / :711–730).

Autorec `approxTime`, `start`, and `startWindow` are required wire s32 values;
`-1` means unset and decodes to null (v36 :1151–1163; v44 :1228–1240).
All other values must be in `0..1439`; out-of-range values are malformed.
Pinned `src/dvr/dvr_autorec.c:733–750` (`dvr_autorec_entry_class_time_set`,
check :744) maps any value >= 1440, empty input, or non-digit input to -1 before
the builder emits `start`/`startWindow` (:1239–1240). `approxTime` is the circular
midpoint modulo 1440 of a set start/window pair, or unset when either is unset.
Timerec uses the same domain: `src/dvr/dvr_timerec.c:367–384` (check :378) applies
the same setter normalization before `start`/`stop` emission (:1292–1293).
Request-side time validation also uses `0..1439`.

## Maintenance

The weekly upstream-drift workflow maintains one `upstream-drift` issue per
pinned revision when upstream master differs from the pinned sources or HTSP
protocol version. It updates the body quietly, comments only on
protocol-version changes (including a moved or unparsable `HTSP_PROTO_VERSION`
define), and closes the issue without a comment when the pins match again or a
re-pin supersedes it. An issue closed by a maintainer stays closed and is
updated quietly until the next re-pin opens a new one; protocol-version changes
are still commented on it, because they are the alert this workflow exists for.
Re-pins remain manual, reviewed changes updating `upstream.json` and the source
line references in these notes. GitHub disables scheduled workflows after 60
days without repository activity, and the repository-name guard skips every run
after a rename or transfer; either stops drift monitoring silently, so re-enable
or adjust the workflow then.

Request models and connection extensions are grouped by domain in
`requests/Htsp*Requests.kt`; server-message models are grouped in
`messages/Htsp*Messages.kt`, with request codec and server decoder/dispatch
files beside them. The JSON API call is in `jsonapi/HtspJsonApiCall.kt`, and
shared field reading is in `wire/HtspFieldReader.kt`. Shared entities, their
nested values and full-builder decoding live in `model/`.

A method or wire-field change ships with a focused regression test. Keep public
KDoc accurate. An intentional public API or ABI change also follows the
documented API dump workflow.

## License and attribution

This GPLv3 library is an independently maintained descendant of
[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream). It is not official
TVHeadend software and is not affiliated with or endorsed by the TVHeadend
project. See [`licensing.md`](../licensing.md) and [`NOTICE.md`](../../NOTICE.md).
