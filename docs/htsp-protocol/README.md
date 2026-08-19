# HTSP protocol reference

This directory contains protocol reference notes for the hand-maintained HTSP
surface. The typed surface was reviewed against TVHeadend revision
`27295c5a48f2c575678bb224014cb9a26a773083`, which reports HTSP v44.

## Artifacts

| Path | Description |
|---|---|
| [`upstream.json`](upstream.json) | Declarative pin record: repository, revision, source blob hashes and sizes, and documentation URLs. It is no longer machine-reverified. |
| [`WIRE_FORMAT.md`](WIRE_FORMAT.md) | Binary framing, codec limits, and golden fixtures. |

The upstream source bodies are not vendored here.

## Version posture

The client requests HTSP v43 by default, and servers clamp the negotiated
version with `MIN(server, requested)`. Callers may explicitly request another
version. The typed surface's coverage ceiling is TVHeadend master at
`27295c5a`, which reports HTSP v44. v44 adds only `feAbsoluteSNR` and
`feAbsoluteSignal` on `signalStatus`; both are decoded as optional fields and
are absent on v43 links.

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
| Typed server messages | **30** | Payload models with a finite decoder. |

Keep three distinctions clear:

- A referenced name is not proof that a method is called. `subscriptionSeek`
  and `subscriptionSkip`, for example, are separate wire names for one handler.
- Typed request coverage means a reviewed `HtspRequest` and typed connection
  extension. It does not say which servers or configurations support the call.
- Typed server-message coverage means payload models and a finite decoder.
  Channel, tag, EPG, DVR, autorec, timerec, and event messages publish through
  the global metadata flow. All eleven subscription message types publish only
  through the registered ordered per-subscription flow. Decoding is strict
  except that malformed optional timerec add/update fields become omitted or
  null while valid siblings survive; required add fields and update identity
  remain strict.

Autorec and timerec Add/Update/Delete messages are finite read-only metadata.
`descrambleInfo` completes the typed subscription catalog and publishes through
the matching registered subscription stream. The six autorec/timerec RPCs also
have finite mappings; they do not add schedule publication, lifecycle, retry, or
DVR policy.

## Protocol quirks and version notes

- Interpret fields by direction, wire or container type, presence, and known
  minimum version. Named nested shapes may be complete, partial, dynamic or
  opaque, known-empty, alternative, or unknown. Global RPC fields `seq`,
  `error`, and `noaccess` are separate from method fields, and access masks are
  provenance rather than an authorization API. Documentation TODOs, `???`,
  source heuristics, and the protocol-33 demo remain explicitly uncertain.
- Recorded minima include channel services at v5; channel minor numbers and
  selected DVR observations at v13; stream metadata at v5/v11 and top-level
  codec metadata at v17; subscription errors and satellite source metadata at
  v20; service provider names at v38; UUID and rating observations at v41; and
  absolute signal/SNR observations at v44. A minimum is compatibility evidence,
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
  plus bounded optional scalars, true-only flags, language maps, episode
  numbers, genres, and string lists. `lang_str_serialize_map` gives language
  maps strict string keys and values; string-list output is sorted and unique. `time_t` values remain Unix
  seconds. The unconstrained copied `cred` object remains opaque and is omitted
  from the public response. The official reply is `TODO`.
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
  and the mtime unit; the SDK leaves POSIX `st_mtime` unchanged.
- `stopDvrEntry` has no evidenced introduction version. Its recorder handler
  uses the DVR-entry helper in write mode, returns its bounded error, calls only
  `dvr_entry_stop`, and returns `success:u32 = 1`. Cancel and delete use other
  operations; later asynchronous DVR metadata is authoritative.
- `subscriptionChangeWeight` is v5 with streaming access. It requires u32
  `subscriptionId`, defaults optional u32 `weight` to zero, searches
  `htsp_subscriptions` for exact `hs_sid`, and returns the missing-subscription error when absent. It queues one
  empty reply before one `subscription_change_weight` call; that order does not
  prove the weight is applied.
- `subscriptionLive` is v9 with streaming access. It requires u32
  `subscriptionId`, rejects a missing subscription, zero-initializes
  `streaming_skip_t`, sets only `SMT_SKIP_LIVE`, calls `subscription_set_skip`,
  then queues one empty reply. This does not guarantee delivery order or settled
  state; asynchronous `subscriptionSkip` remains authoritative.
- `subscriptionSeek` and `subscriptionSkip` are distinct v44 dispatch names for
  `htsp_method_skip`, a streaming handler with minimum v9. It requires u32
  `subscriptionId`, defaults optional u32 `absolute` to 0, takes signed-s64
  `time` before signed-s64 `size`, and errors if neither is present. Nonzero
  `absolute` selects absolute semantics. It calls `subscription_set_skip` before
  queuing an empty reply. The official docs call seek a synonym, describe
  time/size as optional u64, and omit the either/or rule.
- `subscriptionFilterStream` is v12 with streaming access. It requires u32
  `subscriptionId`, processes optional `enable:list[u32]` before
  `disable:list[u32]`, and accepts only `HMF_S64` members. It returns one empty
  map. Only indexes 0..511 can alter the `NUM_FILTERED_STREAMS=(64*8)` bitmap;
  larger values are ignored, overlap ends disabled, and an omitted or empty list
  leaves that side unchanged.
- The complete shared `service` shape contains name, type, content, conditional
  access, provider, and an opaque dynamic `hbbtv` child. A complete `getChannel`
  reply does not make partial `channelUpdate` semantics complete. Shared
  `stream` and `sourceInfo` are partial: stream index/type are required, known
  metadata is optional, and source metadata is independently optional. Their
  minima do not require those containers in `subscriptionStart`.
- The complete shared `event` shape from `htsp_build_event` is used by `getEvent`, `getEvents`, and
  `eventAdd`. Category and keyword are ordered string lists; credits are opaque.
  Update compatibility requires only `eventId`, allows every other field to be
  omitted, and merges present fields by `eventId` without claiming that pinned
  builders omit otherwise required fields.
- The 39 canonical request extensions mirror constructors, add timeout and
  generation controls, construct one request, and delegate once to `execute`.
  `fileClose` accepts optional recording position and play-count values. DVR
  event/explicit-time and subscription ID/name choices have wrapper-free
  conveniences. Seek and skip use `SubscriptionSeekPosition.Time` and `.Size`
  because both values are `Long`.
- `decodeHtspServerMessage(Map<String, Any?>)` is the versionless finite decoder. It treats every `seq` reply envelope, unknown or
  missing/non-string method, as unknown; malformed recognized messages are
  malformed-known. It is not a version gate. `descrambleInfo` is emitted from
  v24 unless anonymized and requires full-u32 `subscriptionId`, `pid`, `caid`,
  `provid`, `ecmtime`, and `hops`; strings `cardsystem`, `reader`, `from`, and
  `protocol` are optional and strict, with wire `from` exposed as `source`.
  `HtspService` decodes it but does not publish it as typed
  `HtspTransportEvent.ServerMessage`. Versionless add minima are `channelId` for
  `channelAdd`, `tagId` for `tagAdd`, `entryId` for `dvrEntryAdd`, and
  `eventId`/`start`/`stop` for `eventAdd`; optional names and event channel stay
  strict when present. DVR files choose the first `filename`/`path` alias in
  that order. The `htsp_build_autorecentry` autorec add requires every unconditional emitter and makes only
  source-conditional observations nullable; update requires string `id` and
  makes all other fields nullable; delete requires string `id`.
  `queueStatus.delay` keeps its recorded requiredness uncertainty.

## Maintenance

Request models and connection extensions are grouped by domain in
`requests/Htsp*Requests.kt`; server-message models are grouped in
`messages/Htsp*Messages.kt`, with request codec and server decoder/dispatch
files beside them. The JSON API call is in `jsonapi/HtspJsonApiCall.kt`, and
shared field reading is in `wire/HtspFieldReader.kt`.

A method or wire-field change ships with a focused regression test. Keep public
KDoc accurate. An intentional public API or ABI change also follows the
documented API dump workflow.

## License and attribution

This GPLv3 library is an independently maintained descendant of
[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream). It is not official
TVHeadend software and is not affiliated with or endorsed by the TVHeadend
project. See [`licensing.md`](../licensing.md) and [`NOTICE.md`](../../NOTICE.md).
