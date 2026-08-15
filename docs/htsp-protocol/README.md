# HTSP protocol evidence

Machine-readable inventory and generated human matrix for **HTSP protocol
version 44**, derived from a pinned public TVHeadend revision. These artifacts
are **repository engineering evidence**. They are not a public API, support
matrix, stability promise, shipping contract, or completeness guarantee.

## When to read this

Read this directory when a slice:

- implements an HTSP client→server method;
- maps a new HTSP wire field on a request, reply, or server message; or
- needs current-source protocol vocabulary, version-gate evidence, or SDK
  coverage counts against the pinned upstream dispatch table.

Do not treat this directory as permission to change runtime behavior, module
boundaries, or public Kotlin API.

## Provisional protocol and transport boundary

The standalone `htsp` artifact owns exactly five shallow production packages below
`at.bernhardberger.tvheadend.htsp`: `.wire`, `.requests`, `.messages`,
`.connection`, and `.jsonapi`. The flat root is empty and no deeper/sixth package
or old-root compatibility shim exists. Every production Kotlin/Java source in
the module may depend only on sibling declarations in that root tree, Kotlin/JDK
runtime facilities, and
`kotlinx.coroutines`. It never
depends on core/domain, metadata repositories, `TvheadendClient`, playback or
session implementation, Android/Media3, native/decoder, test fixtures, legacy
`at.bernhardberger.tvhplayer`, application, or other third-party/project code.

Run `./tools/check-htsp-protocol-boundary` to scan the whole production source
tree and enforce its package/dependency surface. `HtspService`, the codec, raw
per-message mappers, and the catalog helper remain internal. The one public
finite decoder intentionally accepts a raw map at its explicit boundary. This
boundary does not add a
support, completeness, or stability claim to the evidence below.

## Static Quick Start identity

The independent `consumer-contract` source below is byte-identical to its moved
fixture, and its build declares exactly the provisional coordinate shown here.
P3-E1 checks that identity statically and rejects project, file, included-build,
Maven Local, or repository bypasses. It does not resolve this coordinate or
compile the fixture. P3-E2 owns the first isolated external-coordinate compile
and staging/artifact evidence. Endpoint and credential values remain
caller-owned inputs.

<!-- dependency-static:htsp -->
```kotlin
dependencies {
    implementation("at.bernhardberger.tvheadend:htsp:0.1.0-alpha.1-SNAPSHOT")
}
```

<!-- source-static:htsp -->
```kotlin
package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOptions
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.HtspFailure
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportEvent
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.connection.execute
import at.bernhardberger.tvheadend.htsp.connection.fold
import at.bernhardberger.tvheadend.htsp.connection.getOrElse
import at.bernhardberger.tvheadend.htsp.connection.getOrNull
import at.bernhardberger.tvheadend.htsp.connection.map
import at.bernhardberger.tvheadend.htsp.connection.onFailure
import at.bernhardberger.tvheadend.htsp.messages.HtspServerMessage
import at.bernhardberger.tvheadend.htsp.requests.GetEventsRequest
import at.bernhardberger.tvheadend.htsp.requests.getDiskSpace
import at.bernhardberger.tvheadend.htsp.requests.getProfiles
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProtocolFailurePolicy {
    CHECK_ACCESS,
    RETRY_LATER,
    RECONNECT,
    UNSUPPORTED,
    REJECTED,
}

data class ProtocolSnapshot(
    val freeBytes: Long?,
    val serverUnixTimeSeconds: Long?,
    val profileCount: Int?,
    val failures: List<ProtocolFailurePolicy>,
)

sealed interface ProtocolQuickStartOutcome {
    data class Connected(val snapshot: ProtocolSnapshot) : ProtocolQuickStartOutcome

    data object ConnectionFailed : ProtocolQuickStartOutcome
}

suspend fun runProtocolQuickStart(
    ioDispatcher: CoroutineDispatcher,
    endpoint: HtspEndpoint,
    epgChannelId: Long,
    epgMaximumEvents: Long,
    epgLanguage: String?,
    options: HtspConnectOptions = HtspConnectOptions(),
    onServerMessage: suspend (HtspServerMessage) -> Unit,
): ProtocolQuickStartOutcome = coroutineScope {
    val connection = createHtspConnection(ioDispatcher = ioDispatcher)
    val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
        connection.events
            .filterIsInstance<HtspTransportEvent.ServerMessage>()
            .collect { event -> onServerMessage(event.message) }
    }

    try {
        when (val connectOutcome = connection.connect(endpoint, options)) {
            is HtspConnectOutcome.Failed -> ProtocolQuickStartOutcome.ConnectionFailed
            is HtspConnectOutcome.Connected -> {
                val generation = connectOutcome.connection.generation
                val failures = mutableListOf<ProtocolFailurePolicy>()
                val eventsRequest = GetEventsRequest(
                    channelId = epgChannelId,
                    language = epgLanguage,
                    numFollowing = epgMaximumEvents,
                )
                connection.execute(eventsRequest, expectedGeneration = generation)
                    .onFailure { failure -> failures += policyFor(failure) }
                val diskSpace = connection.getDiskSpace(expectedGeneration = generation)
                    .onFailure { failure -> failures += policyFor(failure) }
                    .getOrNull()
                val serverUnixTimeSeconds = connection
                    .getSysTime(expectedGeneration = generation)
                    .fold(
                        onOk = { response -> response.unixTimeSeconds },
                        onFailure = { failure ->
                            failures += policyFor(failure)
                            null
                        },
                    )
                val profileCount = connection
                    .getProfiles(expectedGeneration = generation)
                    .map { response -> response.profiles?.size }
                    .getOrElse { failure ->
                        failures += policyFor(failure)
                        null
                    }
                ProtocolQuickStartOutcome.Connected(
                    ProtocolSnapshot(
                        freeBytes = diskSpace?.freeBytes,
                        serverUnixTimeSeconds = serverUnixTimeSeconds,
                        profileCount = profileCount,
                        failures = failures.toList(),
                    ),
                )
            }
        }
    } finally {
        withContext(NonCancellable) {
            eventCollector.cancelAndJoin()
            try {
                connection.disconnect()
            } finally {
                connection.close()
            }
        }
    }
}

private fun policyFor(failure: HtspFailure): ProtocolFailurePolicy = when (failure) {
    HtspResult.AccessDenied -> ProtocolFailurePolicy.CHECK_ACCESS
    HtspResult.ConnectionLimit -> ProtocolFailurePolicy.RETRY_LATER
    HtspResult.Timeout -> ProtocolFailurePolicy.RETRY_LATER
    HtspResult.TransportUnavailable -> ProtocolFailurePolicy.RECONNECT
    HtspResult.NotSupported -> ProtocolFailurePolicy.UNSUPPORTED
    HtspResult.ServerError -> ProtocolFailurePolicy.REJECTED
}
```

This proves only static documentation/fixture/build identity. It does not
compile or resolve the external coordinate, contact or validate a TVHeadend
server, operate a device, test runtime behavior, establish Java 17 runtime
compatibility, publish externally, authorize distribution, or establish release
readiness. The API and mutable snapshot coordinate remain provisional;
typed-request coverage is 39 of 39
pinned methods. execute is the canonical constructed-request path, and generated
extensions are convenience delegates with their existing parameter signatures;
missing methods belong in the typed request catalog. External consumers cannot
subclass `HtspRequest`, so execution remains limited to the finite catalog rather
than becoming a raw or custom request escape hatch.

## Authority order

1. **Primary:** pinned TVHeadend server source
   (`src/htsp_server.c`, `src/htsp_server.h`, `src/epg.c`, `src/epg.h`,
   `src/lang_str.c`, `src/string_list.c`, `src/api.c`,
   `src/api/api_idnode.c`, `src/htsmsg.h`, and `src/htsmsg_binary.c`) at the revision in
   [`upstream.json`](upstream.json).
2. **Secondary:** official HTSP docs
   ([Communication](https://docs.tvheadend.org/documentation/development/htsp/communication),
   [Client-to-Server RPC methods](https://docs.tvheadend.org/documentation/development/htsp/client-to-server-rpc-methods),
   [Server-to-Client methods](https://docs.tvheadend.org/documentation/development/htsp/server-to-client-methods),
   [Protocol Changes](https://docs.tvheadend.org/documentation/development/htsp/protocol-changes)).
3. **Narrow cross-check only:** `lib/py/tvh/htsp.py` (demo client at protocol 33;
   hello/authenticate/enableAsyncMetadata only). Never completeness authority.
4. **Local acceptance:** this repository's production sources and tests.

Where docs are missing or stale, the pinned server source wins and the gap is
recorded explicitly in `htsp_spec.json` / `HTSP_METHOD_MATRIX.md`.

## Reviewed Kotlin surface and pinned evidence

[`htsp_surface.py`](htsp_surface.py) is the stdlib-only reviewed authority for
the generated Kotlin surface and retained compatibility behavior: Kotlin names
and types, constructor and property order, wire-name aliases, field gates,
nested mappings, validation, redaction, and the 39 requests, 11 convenience
overloads, and 30 server messages. It is maintained data, never scraped from
Kotlin source.

[`htsp_spec.json`](htsp_spec.json) is different: it is the immutable checked-in
derivation of pinned upstream HTSP v44 evidence. It is not the Kotlin/API
authority. `./tools/check-htsp-generated-drift` resolves every catalog wire-name
occurrence to the exact method or named shape in that evidence and compares its
normalized request, reply, message, or nested direction, wire type, and explicit
field minimum version. Synthetic `<root>` entries link a method projection to a
reviewed nested shape; they are not literal wire fields.

Shipped compatibility behavior wins when the two authorities differ. Each such
catalog occurrence has one exact, reasoned waiver in `htsp_surface.py`. The gate
requires the waiver to be consumed by that mismatch and rejects malformed,
duplicate, blank, missing, and unused waivers, including a waiver placed on a
canonical exact match.

## Lookup order for routine facts

The pinned upstream source is **not** vendored in this repository;
[`htsp_spec.json`](htsp_spec.json) is its derived, checked-in form. Read the
local file first. A remote lookup for a fact already present here is wasted
work, and every such round trip is repeated by each fresh writer session.

Answer these from `htsp_spec.json` or
[`HTSP_METHOD_MATRIX.md`](HTSP_METHOD_MATRIX.md) with no remote lookup:

- whether a method or server message exists, and its exact wire name;
- its request and reply field names and wire types;
- its required access level;
- whether the handler branches on protocol version; and
- whether this SDK already references it.

Consult the docs site, the `tvheadend-docs` MCP, or the pinned source only for
what the derivation does not capture:

- field **semantics**, units, and value ranges;
- either/or request requirements, which the derived `required` marks cannot
  express;
- which specific field a version gate guards; and
- behavior of a shared upstream converter across sibling methods.

The *Derivation confidence* section of the matrix states exactly which columns
are reliable and which are approximate. Verify an approximate column before
relying on it; do not re-derive a reliable one.

## Exact pin

| Item | Value |
|---|---|
| Repository | `https://github.com/tvheadend/tvheadend` |
| Revision | `27295c5a48f2c575678bb224014cb9a26a773083` |
| `HTSP_PROTO_VERSION` | `44` |
| `src/htsp_server.c` | git blob `2837efd3b41ae0ba7f82de2853d8a1d4a1ea88e1`, 134765 bytes |
| `src/htsp_server.h` | git blob `3b6470d51ab45e1d9bc9bacc710b0e1f6f49b1b0`, 2050 bytes |
| `lib/py/tvh/htsp.py` | git blob `bab234beafc924a608e830d32cc4596152df0863`, 2963 bytes |
| `src/epg.c` | git blob `7d95b27466e070a6c76b37ef3a945cd9e980d683`, 88770 bytes |
| `src/epg.h` | git blob `cce9c09d25612f1abc892c7a0071dca9481030e9`, 22374 bytes |
| `src/lang_str.c` | git blob `c0cfbe016938472778ef6aec0e6e0b829a0abd31`, 8481 bytes |
| `src/string_list.c` | git blob `cfe0fa03415abf649c94737d599561556b5e0a76`, 4655 bytes |
| `src/api.c` | git blob `d86fbda01312b97c451242ee24c01a384744141b`, 4440 bytes |
| `src/api/api_idnode.c` | git blob `1f0f9b697feb30e16ce9b61b4693eb2090b5ee49`, 18834 bytes |
| `src/htsmsg.h` | git blob `82787d4cc4d18436653ab0c19fc2e49ee930a013`, 14265 bytes |
| `src/htsmsg_binary.c` | git blob `48a1bf985ed554df473adb3a9251b479dfcdaf26`, 7750 bytes |

Full machine-readable pin metadata lives in [`upstream.json`](upstream.json).

## Artifacts

| Path | Role |
|---|---|
| `upstream.json` | Pinned revision, blob SHA-1, sizes, docs URLs |
| `htsp_spec.json` | Generated inventory: 39 client methods, 30 server messages, fields, evidence, coverage |
| `HTSP_METHOD_MATRIX.md` | Generated human matrix from the JSON |
| `derive.py` | Verifies pinned inputs and regenerates `htsp_spec.json` |
| `report.py` | Validates the JSON and regenerates/checks the matrix |
| `htsp_surface.py` | Reviewed Kotlin surface plus exact package/output metadata shared by all four Kotlin generators |
| `generate_typed_requests.py` | Generates `requests/GeneratedHtspExtensions.kt` for ordinary conveniences and `jsonapi/GeneratedHtspJsonApiExtensions.kt` for `api` (39 canonical requests plus 11 reviewed overloads total) |
| `generate_typed_request_models.py` | Generates `requests/GeneratedHtspRequests.kt` and JSON-specific `jsonapi/GeneratedHtspJsonApiModels.kt` |
| `generate_typed_server_messages.py` | Generates `messages/GeneratedHtspServerMessageDispatch.kt` finite dispatch and public decode result |
| `generate_typed_server_message_models.py` | Generates `messages/GeneratedHtspServerMessages.kt` payload models and decoders |
| `../../tools/check-htsp-generated-drift` | Offline stdlib-only generated-byte and catalog/spec consistency gate |
| `../../tools/check-htsp-public-kdoc` | Exact 258-unit public type/function KDoc inventory, catalog/output identity, and hostile self-test gate |

Derived vocabulary/facts, provenance records, and the generator/checker code in
this directory are original to this repository and remain under the repository
GPLv3. Upstream TVHeadend source bodies are **not** vendored here.

## Pinned pre-extraction coverage evidence

The byte-identical specification, matrix, catalog, and wire-format evidence retain
their exact historical `sdk/` path labels. In particular, the recorded
string-literal scan covered `sdk/htsp-protocol/src/main`, `sdk/htsp/src/main`, and
`sdk/playback-media3/src/main` only (production Kotlin/Java; tests and testing
fixtures excluded). Those labels describe frozen source evidence, not the
standalone repository's current topology:

| Surface | Count | Meaning |
|---|---:|---|
| Client→server methods | 39 | Pinned `htsp_methods[]` dispatch table |
| Referenced method names | **39** | Exact literals present in production sources |
| Outgoing request names | **39** | Distinct names assigned to outgoing `method = ...` requests |
| Public typed client requests | **39** | Reviewed request/response models with generated `HtspConnection` extensions |
| Server→client messages | 30 | Current emitted async/server messages |
| Handled server messages | **30** | Exact literals present in production sources |
| Public typed server messages | **30** | Reviewed payload models with a public finite decode-result boundary; runtime publication remains a separate internal policy |

Important distinctions:

- **Referenced ≠ called.** Both `subscriptionSeek` and `subscriptionSkip` are
  distinct outgoing wire names for one shared pinned handler; `client-htsp`
  continues to send only `subscriptionSeek`. Typed coverage remains separate from
  both literal metrics; all pinned methods are now referenced and outgoing.
- Never claim 39 methods are implemented/called merely because 39 names are
  referenced.
- Typed coverage is separate from literal reference/outgoing coverage. It means
  only that the reviewed catalog has a public `HtspRequest` model and generated
  `HtspConnection` extension; it is not a public support, stability, or
  completeness claim.
- Typed server-message coverage is a separate reviewed 30/30 catalog. The metric
  means only that public protocol-data models and a public finite decoder
  exist; it does not prove support or runtime consumption. Selected client
  channel/tag/EPG/DVR metadata and subscription-status consumers use that
  decoder, while low-level protocol wire parsing and the opt-in playback SPI
  retain their bounded raw integration. Decoder fields remain strict except for
  the retained timerec add/update compatibility rule: malformed optional timerec
  fields decode as omitted/null while valid siblings survive; required add
  fields and update identity remain strict.
- All 30 emitted server-message names are now handled by the exact-literal metric.
  The inbound autorec and timerec Add/Update/Delete families are finite read-only
  protocol metadata. `descrambleInfo` completes the finite typed catalog but does
  not alter its existing playback consumer or runtime publication. The six sibling
  outbound autorec/timerec RPCs also have finite typed
  protocol mappings, but none of this adds client-runtime schedule publication,
  lifecycle, retry, or DVR policy.

## Approximation boundaries

- Mechanical field types come from structurally bounded handlers, emitters, and
  helpers plus a reviewed exact-pin annotation catalog. The generator does not
  claim generic regular-expression parsing is complete.
- Every field records direction, wire/container type, presence, evidence,
  confidence, and an evidenced minimum version or `null`. Nested values use
  named shape references; each request/reply/message shape is explicitly
  complete, partial, dynamic/opaque, known-empty, alternative, or unknown.
- Established compatibility minima are evidence annotations, not runtime gates
  or support claims. The inventory records channel services at v5, channel
  minor numbers and selected DVR observations at v13, stream metadata at v5/v11
  with top-level codec metadata at v17, subscription errors and satellite source
  metadata at v20, service provider names at v38, UUID/rating observations at
  v41, and absolute signal/SNR observations at v44. Unknown minima remain null.
- Unknown or approximate evidence is labeled explicitly. Documentation TODOs,
  `???`, source heuristics, and the stale demo client are never promoted to
  confident contracts.
- Global RPC fields `seq`, `error`, and `noaccess` are tracked separately from
  method-specific fields.
- The `api` method carries an exact machine-readable `acceptedVocabulary` fact.
  It enumerates the SDK-admitted recursive round-trip subset
  `map`, `list`, `str`, `s64`, `bin`, `bool`, and fixed-width 16-byte `uuid`,
  records `src/htsmsg_binary.c` decode and serialization evidence for every
  admitted type, and separately records upstream `dbl` as excluded. Although
  `HMF_DBL=6` exists in `htsmsg.h`, the pinned binary decoder has no double case
  and rejects it through its default branch; the serializer likewise has no
  double case and reaches its default abort. The SDK bridge therefore does not
  model `Double` or `Float`.
- Dispatch-table access masks are raw provenance, not an SDK authorization API.
- Source-derived doc limitations currently include pinned `getDiskSpace` source
  emitting `useddiskspace` while the official method page documents only
  `freediskspace` and `totaldiskspace`; pinned `getSysTime` source emitting
  `time` through `htsmsg_add_s32` while the official page specifies required s64
  Unix time; pinned `getEvents` reading `maxTime` as signed s64 with zero default
  while the official page says u64, and that page not specifying event-selector
  precedence, positive inclusive count, per-channel count reset, nonzero start
  cutoff, or access-filter interactions; channel service `content` and dynamic
  `hbbtv` fields omitted from
  the official Server-to-Client methods `channelAdd` section (the governing
  field list, with `hbbtv` retained as an explicit opaque shape); pinned current
  `htsp_build_event` emitting signed-64 `start`/`stop` and u32 `isNew` while the
  official Server-to-Client `eventAdd` section describes u64/str, omits current
  fields, and includes historical IDs not emitted by the current builder; the
  official timerec add section omitting the pinned string `id`, containing stale
  autorec/`enabled` wording, and documenting u32 `start`/`stop` where the pinned
  builder emits s32, while pinned source-only u32 `removal` remains deliberately
  outside the public SDK model;
  missing `stopDvrEntry` on that page; the official Client-to-Server page leaving
  `subscriptionChangeWeight` omitted-weight default and acknowledgement/application
  ordering unspecified;   the `subscriptionLive` page not clearly distinguishing
  empty RPC acknowledgement from the separate asynchronous `subscriptionSkip`
  outcome or defining delivery ordering/settled-live semantics; the official
  Client-to-Server page calling `subscriptionSeek` a synonym of
  `subscriptionSkip` while listing time/size as optional u64 and omitting the
  pinned either/or signed-s64 rule and default-zero absolute flag; the
  `subscriptionFilterStream` page omitting the pinned 512-index effective range,
  disable-wins overlap precedence, and omitted/empty no-change behavior; the
  file-operation pages using unsigned file size/mtime/read/seek values, marking
  seek whence required despite documenting its default, and omitting coupled
  open metadata, the required successful seek offset, successful empty
  binary-read behavior, and recording-backed `fileClose` progress defaults; the
  `fileStat` page describing independently optional u64 fields while omitting the
  pinned signed-s64, both-or-neither, successful-empty-map, and `mtime` unit/epoch
  behavior; missing
  `descrambleInfo` on the server-message page; the official `hello` contract
  requiring `clientversion` even though the pinned handler does not read it and
  omitting the emitted `language` and `api_version`; the official `authenticate`
  contract documenting only `noaccess` while pinned source also has a complete
  rights-at-v26 branch and a rights-at-v25-or-earlier empty branch; and the stale
  protocol-changes page. The `getSysTime` mismatch records source/docs evidence,
  not a decision to coerce or truncate the SDK public value. Source facts do not
  create an upstream support promise.
- The official Client-to-Server `getDvrCutpoints` method page does not define
  the millisecond coordinate origin or chronological ordering, overlap, or
  uniqueness semantics. Pinned source serializes `dc_start_ms`/`dc_end_ms` and
  traverses the source TAILQ; the SDK preserves observed values, order, and
  duplicates without interpretation. No source fact is promoted into an
  invented coordinate or sorting guarantee.
- The official `getTicket` method page marks `channelId` and `dvrId` optional
  and both reply strings required, but does not state that at least one selector
  is required or that pinned source checks `channelId` first. The SDK makes only
  one full-u32 channel/DVR selector representable, strictly requires returned
  `path` and `ticket` strings, and does not expose the source's both-present
  state. This is exact-pin behavior, not an upstream compatibility promise.
- `fileStat` is annotated for HTSP v8 with recorder access. Its exact helper reads
  one u32 `id` with zero default and searches only the current connection's file
  list; absent, malformed, unknown, and zero IDs therefore share the global
  `Invalid file` path unless zero identifies an owned handle. The handler captures
  that handle's fd, unlocks, creates a fresh reply map, and on
  `fstat(fd, &st) == 0` emits signed-s64 `size = st.st_size` followed by signed-s64
  `mtime = st.st_mtime`; otherwise it returns the successful empty map. The fields
  are coupled and there are no other method-specific outputs. Official docs say
  u64 and independently optional, omit empty-success behavior, and do not define
  mtime's unit or epoch. The SDK preserves unchanged POSIX `st_mtime` without
  conversion and adds no handle lifecycle policy.
- `fileOpen`, `fileRead`, `fileClose`, and `fileSeek` are likewise annotated for
  HTSP v8 with recorder access. Pinned `fileOpen` requires exact string `file`,
  strips at most one leading slash only inside the server, and returns required
  u32 `id` plus coupled signed-s64 `size`/`mtime` when `fstat` succeeds.
  `fileRead` uses the same connection-owned default-zero handle lookup, requires
  signed-s64 `size`, accepts optional signed-s64 `offset`, and always emits a
  required binary `data` field on success, including an empty payload.
  typed `fileClose` preserves the exact raw id-only request and adds optional
  full-u32 `playposition` and `playcount` controls gated to v27 when present.
  Pinned server behavior
  increments playcount for a recording-backed handle unconditionally before v27;
  at v27 or newer, omitted `playcount` defaults to
  `HTSP_DVR_PLAYCOUNT_INCR` and therefore also increments, while another explicit
  value does not. Supplied `playposition` updates whole recording-position
  seconds at v27 or newer; omission leaves it unchanged. Non-recording
  and image handles have no associated DVR entry to mutate, and the existing
  opted-in recording close remains separate.
  `fileSeek` requires signed-s64 `offset`, accepts only `SEEK_SET`, `SEEK_CUR`, or
  `SEEK_END`, defaults an omitted whence to `SEEK_SET`, and always returns the
  non-negative signed-s64 absolute offset after success. The ordinary typed
  `fileRead` additionally bounds one request to 0..16 MiB; this changes no codec,
  reader, chunking, EOF, handle-lifecycle, or playback behavior.
- `hello` and `authenticate` have exact bounded current-source shapes rather
  than approximate field inventories. The anonymous-access `hello` handler
  requires only u32 `htspversion` and string `clientname`, never reads
  `clientversion`, emits a required 32-byte challenge and the six other
  unconditional observations, emits `webroot` and `language` conditionally, and
  assigns the connection version with `MIN(HTSP_PROTO_VERSION, requested)`.
  `authenticate` reads no method-specific fields. No granted privilege emits
  only `noaccess=1`; granted rights above v25 emit the exact ten access/limit/UI
  fields; granted rights at v25 or earlier emit an empty method payload. The
  derivation self-test mutates each method's dispatch handler and access mask,
  each required getter, unconditional/conditional topology, challenge length,
  version-min assignment, absent `clientversion` read, empty authenticate input,
  no-access alternative, complete v26 branch, and v25 empty branch
  independently. The report self-test independently mutates both methods'
  handler, access mask, and absent method minimum as well as their exact shapes.
  Every mutation proves its intended method changed before requiring a
  method-specific rejection. These source facts do not establish public
  stability, runtime server support, or an authorization policy.
- The shared `service` named shape is the complete bounded current-source
  name/type/content/conditional-access/provider object used by channel replies.
  Its dynamic `hbbtv` child points to a separate opaque named shape rather than
  being flattened or exposed as a guessed schema. Complete `getChannel` reply
  evidence does not make partial `channelUpdate` semantics complete.
- The shared `stream` and `sourceInfo` named shapes are partial field inventories:
  required stream index/type and optional known metadata remain strict, while
  source metadata is independently optional. Their recorded version minima do
  not require those containers in `subscriptionStart` or claim negotiated
  runtime support.
- The shared `event` shape is the complete bounded current `htsp_build_event`
  result used by `getEvent`, `getEvents`, and `eventAdd`. Category and keyword
  are ordered string-list shapes; credits are a separately named opaque dynamic
  object. Pinned current `eventUpdate` call sites also send that shared full
  builder snapshot, but update compatibility is represented separately: only
  `eventId` is required, every non-key field may be omitted, and consumers merge
  present fields by `eventId`. This partial-update contract does not claim the
  pinned current source actually omits builder-required fields.
- `getEvents` remains a version-4 method whose five optional filters
  (`channelId`, `eventId`, `language`, `numFollowing`, and signed-s64 `maxTime`)
  each carry version-6 compatibility evidence. Its complete method-specific
  reply is exactly required `events:list -> event`. These are pinned current
  source facts, not an upstream support or completeness promise.
- `getEpgObject` has required u32 `id`, optional u32 `type`, streaming access,
  and no evidenced method minimum. The pinned enum contains only undefined and
  broadcast, and only broadcast has a serializer. The complete finite broadcast
  reply follows the base plus broadcast serializers: strict required `id`,
  broadcast `tp`, signed-s64 `up`/`start`/`stop`, and the recorded bounded
  optional scalar, true-only flag, language-map, episode-number, genre, and
  string-list shapes. `lang_str_serialize_map` establishes strict string keys
  and values; the sorted RB-tree string-list implementation establishes sorted
  unique serializer output. Pinned `time_t` members are carried as unchanged
  Unix seconds under the repository EPG convention. The unconstrained copied
  `cred` object remains an explicit opaque shape and is deliberately omitted
  from the public response. Official RPC documentation leaves this reply
  literally `TODO`, so the exact pinned source set is normative for this finite
  mapping rather than a remote completeness claim.
- `stopDvrEntry` has no evidenced introduction version. Its recorder-access
  dispatch points to a handler that uses the shared DVR-entry helper in write
  mode, returns the helper's bounded error result, calls exactly
  `dvr_entry_stop`, and returns the exact standard `success:u32 = 1` map. Cancel
  and delete call different operations. The official Client-to-Server page omits
  stop, so neither the inventory nor the SDK invents a lifecycle transition;
  later asynchronous DVR metadata remains authoritative.
- `subscriptionChangeWeight` is annotated as available since version 5 and its
  dispatch requires streaming access. The exact bounded handler requires decoded
  u32 `subscriptionId`, reads optional u32 `weight` with default zero, searches
  `htsp_subscriptions` by exact `hs_sid`, and returns the exact missing-subscription
  error when no match exists. It queues exactly one empty reply before exactly one
  `subscription_change_weight` call. That ordering is acknowledgement evidence,
  not proof that weight is settled or applied; the official Client-to-Server page
  does not specify the omitted default or acknowledgement/application ordering.
- `subscriptionLive` is annotated as available since version 9 and its dispatch
  requires streaming access. The exact bounded handler requires decoded u32
  `subscriptionId`, searches `htsp_subscriptions` by exact `hs_sid`, rejects a
  missing match, zero-initializes one `streaming_skip_t`, sets only
  `SMT_SKIP_LIVE`, emits only the bounded trace, calls exactly one
  `subscription_set_skip` on the matched subscription, then queues exactly one
  empty reply and returns `NULL`. The action-before-reply source topology does
  not guarantee wire delivery order or settled live state; the separate
  asynchronous `subscriptionSkip` outcome remains authoritative.
- `subscriptionSeek` and `subscriptionSkip` are distinct v44 dispatch names that
  both call `htsp_method_skip` with streaming access and annotated minimum v9.
  The shared handler requires exact u32 `subscriptionId`, looks up that
  connection-owned subscription, reads optional u32 `absolute` with default 0,
  accepts signed-s64 `time` first otherwise signed-s64 `size`, and errors when
  neither coordinate exists. Nonzero `absolute` selects absolute skip semantics.
  It calls `subscription_set_skip` then queues an empty RPC reply. The separate
  asynchronous `subscriptionSkip` message remains authoritative; do not infer
  ordering, application, or settlement from the empty acknowledgement. Official
  docs call `subscriptionSeek` a synonym but incorrectly/ambiguously list
  time/size as optional u64 and omit the either/or rule; pinned source wins.
- `subscriptionFilterStream` is annotated as available since version 12 and its
  dispatch requires streaming access. The exact bounded handler requires decoded
  u32 `subscriptionId`, searches `htsp_subscriptions` by exact `hs_sid`, rejects a
  missing match, then processes optional `enable:list[u32]` before optional
  `disable:list[u32]`, accepting only `HMF_S64` members, and returns exactly one
  empty map. The helpers mutate the filtered-stream bitmap only for converted
  unsigned indexes below `NUM_FILTERED_STREAMS=(64*8)`: at this pin 0..511 can
  affect it, 512 and larger are ignored, overlap ends disabled, and omitted or
  empty lists make no change for that side. Those helper-range, ordering,
  malformed-member, and action-before-return facts are pinned current-source
  evidence, not an SDK input range, upstream support, future-version,
  acknowledgement-order, effective-stream, mux-settlement, or Media3 track-state
  promise.

## Regeneration

Public generated KDoc is owned by exact catalog declaration and callable keys in
`htsp_surface.py`; generated Kotlin is never the primary prose edit. P3-E1 keeps
the catalog, generators, and generated Kotlin byte-identical, including their
truthful pre-bootstrap output paths. Direct generator `--write` mode is therefore
not a standalone-root workflow. A later coordinated slice must relocate that
path authority together with any catalog or generated-output change. The current
projection checker requires exactly 185 public types and 73 public functions,
including the 14 reviewed nested types and every same-name overload, and rejects
missing, extra, blank, placeholder, duplicate, detached, stale, or
generated-output-mismatched documentation.

Requires an external TVHeadend source root that contains the eleven pinned files.
`derive.py` validates the immutable repository/revision/version, exact eleven-file
key set, Git-blob SHA-1 values, byte counts, and official URL set without
trusting external Git metadata. It rejects symlink roots/components,
non-regular files, and out-of-root paths, and never mutates the external tree.

```bash
# Run all four generator self-tests/projected checks plus catalog/spec consistency:
./tools/check-htsp-generated-drift

# Derive JSON from a local checkout of the pinned revision:
python3 docs/htsp-protocol/derive.py \
  --source-root /path/to/tvheadend \
  --write

# Regenerate the human matrix:
python3 docs/htsp-protocol/report.py --write

# Non-mutating exact reproducibility check against a local pinned-source tree:
python3 docs/htsp-protocol/derive.py \
  --source-root /path/to/pinned-tvheadend-source \
  --check

# Drift check (no network; uses a temporary projection because the immutable
# catalog retains its truthful pre-bootstrap output paths):
./tools/check-htsp-generated-drift
python3 docs/htsp-protocol/report.py --check
```

Optional explicit fetch of the eleven pinned raw files only (never part of
repository/CI checks). Fetch validates the immutable pin and the complete
destination plan before network activity, rejects repository-contained or
symlinked/no-overwrite targets, verifies all response bytes and final raw-GitHub
URLs before writing, and creates files exclusively:

```bash
python3 docs/htsp-protocol/derive.py \
  --fetch-pinned /path/to/empty/tvheadend-htsp-pin \
  --write
python3 docs/htsp-protocol/report.py --write
```

Self-tests (temporary local fixtures only, no network):

```bash
python3 docs/htsp-protocol/generate_typed_requests.py --self-test
python3 docs/htsp-protocol/generate_typed_request_models.py --self-test
python3 docs/htsp-protocol/generate_typed_server_messages.py --self-test
python3 docs/htsp-protocol/generate_typed_server_message_models.py --self-test
./tools/check-htsp-generated-drift --self-test
```

The reviewed surface catalog, rather than a heuristic source scan, is the
constructor/type/default/access/method-minimum and reviewed-overload authority for
typed request coverage. Its 39 canonical extensions mirror request constructors,
then add the common timeout and generation controls, construct one matching
request, and delegate once to canonical `execute`. The source-safe
`fileCloseWithProgress(id, playPositionSeconds, playCount, timeoutMs, expectedGeneration)`
overload keeps the older `(id, timeoutMs, expectedGeneration)` positional meaning
intact. No extension accepts a request object. Event versus explicit-time DVR selection and ID versus name
subscription selection have wrapper-free conveniences. Subscription seek and skip
instead reuse `SubscriptionSeekPosition.Time` and `.Size` case-typed overloads:
both payloads are `Long`, so wrapper-free overloads would be signature-identical.
`derive.py` imports and validates that catalog when recording the distinct typed
coverage field, and repository checks require generated Kotlin output to match
it exactly. The resulting 50 declarations include typed `api`, `hello`, and
fieldless `authenticate`.

The typed server-message catalog is independent of both typed-request and
exact-literal handled-message coverage. It fixes exactly 30 names, public model
types, and provenance-only minima; its generated Kotlin owns only finite
dispatch. The public top-level sealed result and its decoded, unknown-method, and
malformed-known-message cases are returned by
`decodeHtspServerMessage(Map<String, Any?>)`. Every `seq`-bearing reply envelope
is unknown, as is an unknown, missing, or non-string method; malformed recognized
methods are malformed-known and valid recognized methods carry their decoded
typed message. The decoder is not a protocol-version gate. Raw per-message
mappers and the catalog helper remain non-public.
`descrambleInfo` is a strict complete bounded source snapshot: required full-u32
`subscriptionId`, `pid`, `caid`, `provid`, `ecmtime`, and `hops`, plus optional
strict strings `cardsystem`, `reader`, `from`, and `protocol`. Kotlin exposes
wire `from` as `source`. Pinned source emits it only at v24 or newer and omits it
when anonymization applies; the official server-message page still omits the
method. Nested subscription-stream `meta` is optional defensive binary data, and
an optional present timeshift `speed` is strict signed s32. These mappings add no
runtime publication or consumption claim. `HtspService` deliberately retains the
pre-slice publication set: `descrambleInfo` is decoded by the public finite
decoder but excluded from typed `HtspTransportEvent.ServerMessage` publication.
Field requiredness and partial-update presence remain hand-reviewed against the
pinned source because mechanical emission heuristics cannot establish every
either/or or compatibility rule. In particular, `queueStatus.delay` retains its
recorded source/documentation requiredness uncertainty rather than becoming a
support promise. For the versionless typed decoder, `channelAdd` requires only
`channelId`, `tagAdd` only `tagId`, `dvrEntryAdd` only `entryId`, and `eventAdd`
requires `eventId`, `start`, and `stop`; nullable channel/tag names and the
conditional event channel remain strict when present. P5 still owns admission of
new full snapshots. Nested DVR files expose one canonical path, strictly choosing
the first present `filename`/`path` wire alias in that order; ordered typed files
then support first-non-blank selection before typed top-level-path fallback.
The complete pinned `htsp_build_autorecentry` add snapshot strictly requires
every unconditional scalar/string emitter and keeps only source-conditional
title/text flags, directory, channel, series-link, and config observations
nullable. Autorec update requires exact string `id` and makes every other field
nullable; delete requires exact string `id`. Present malformed autorec fields
reject the known message without using the timerec-only compatibility exception.
Neither generator establishes complete HTSP support.

The derive self-test models the pinned `getEvents` handler's mutually exclusive
selected/all-channel branches, including the two list-insertion sites and the
per-channel local count reset, and independently mutates each bounded behavior.
It also models the exact handler-scoped `getDvrCutpoints` id, lookup, access,
optional-list traversal, item construction/append, result insertion, cleanup,
and return topology, with independent mutations and out-of-handler decoys. The
derive fixture separately bounds `getTicket` dispatch/access/version, strict
channel-first getter/fallback behavior, both lookup/access/path/ticket branches,
the neither-selector error, and the exact ordered required reply map. Independent
exact-target mutations reject drift in every accepted source fact, and the report
rechecks freshly derived selector/reply shapes, notes, coverage, and the recorded
  documentation limitation. The derive fixture also bounds all four typed bounded
  file operations: exact dispatch/access/minimum, open file/slash and coupled
  reply behavior, required bounded-read source fields and empty binary success,
  generic-close raw-field separation, recording-backed guard, pre-v27
  unconditional playcount increment, v27+ omitted-playcount increment default,
  optional playposition update, empty reply, and finite/defaulted seek plus
  required non-negative reply offset. Independent exact-target mutations reject
  each accepted source fact, and report mutations reject field evidence, shape,
  exact notes, typed coverage, and source/docs-limitation drift. It also bounds `fileStat`
  dispatch/access/version, exact default-zero u32 same-connection handle lookup,
  invalid-file path, fd association, fresh map, unlocked `fstat`, exact ordered
  signed-s64 expressions, both-or-neither emission, successful empty-map behavior,
  relock/return topology, and absence of extra outputs. Exact-target mutations
  independently reject every accepted fact; report self-tests independently
  reject drift in the complete request/reply shapes, notes, typed coverage, and
  source/docs limitation. The
derive fixture also bounds all six recording-rule handlers and the shared
`htsp_serierec_convert` family split, exact getters/types, add/update flags,
channel version branch, mutation targets, and finite reply topology. Independent
mutations cover both autorec and timerec add/update/delete actions, family leaks,
field types, selector versioning, and success discriminators. The report imports
those freshly derived six contracts and independently rejects request, reply,
access, and family mutations. The same exact-pin fixture separately verifies the
inbound `htsp_build_autorecentry` field order, wire types, top-level requiredness,
conditional optionality, and shared Add/Update topology; independent derive and
report mutations reject requiredness, type, partial-update, delete-identity, and
coverage drift. The
derive self-test additionally scopes `stopDvrEntry` dispatch, helper, handler,
and standard-success proofs and independently mutates ID requiredness/name/type,
lookup/error/access/write mode, stop/cancel/delete topology, success shape, and
external decoys. The report self-test imports the derivation module under an
  isolated name. The API-bridge fixture includes every admitted container and
  scalar decode/count/write branch, independently mutates decode and
  serialization evidence for each admitted type (with shared recursion plus
  separate map/list discriminators), and proves both decode rejection and
  serialization abort remain the only pinned `dbl` paths. Report self-tests
  independently mutate every admitted vocabulary element, the `dbl` exclusion,
  every decode/serialization round-trip element, source identity, and UUID
  width. The derive fixture also scopes `subscriptionChangeWeight` dispatch
and the complete handler body, independently mutating required ID/type/error,
default-zero weight, exact lookup/guard, empty-reply ordering, single change call,
tracked-object/output/helper topology, and external decoys. The derive self-test
also validates the exact `subscriptionLive` dispatch and handler body,
independently mutating required ID/type/error, lookup/guard, zero-init/skip-type,
trace, matched-object/pointer/call count, reply shape/order, alias/helper/output,
return, and external decoys. It also bounds the shared `subscriptionSeek` /
`subscriptionSkip` dispatch pair and `htsp_method_skip` body, independently
mutating required ID/type/error, lookup/guard, default-zero absolute, time-first
either/or coordinates, neither-coordinate error, set-skip/reply ordering, dual
dispatch handler/access, and comment/string decoys. It additionally bounds the exact
`subscriptionFilterStream` dispatch, handler, and enable/disable helper bodies,
independently mutating ID requiredness/name/type/error, lookup/guard, list
names/types/order, `HMF_S64` gates, helper target/direction/topology, the 512
bound, bitmap direction, empty return, aliases/output/duplicates, and external
  decoys. The report validates freshly derived
  `getEvents`/event/recording-rule/stop/weight/live/filter evidence plus
  exact cutpoint request/reply/nested shapes, versions, access, coverage, notes,
  completeness, inbound timerec coverage and source/docs gaps, and documented
  limitations in addition to the committed
  deterministic artifact.

`./tools/verify-htsp --non-gradle` runs the standalone static checker, the four
generator self-tests and projected byte checks, and `report.py --check`. It uses
neither network nor an upstream checkout. Historical coverage roots embedded in
the immutable catalog and matrix describe their frozen monorepo evidence; they
are not current standalone paths.

## Standing regeneration rule

Any future slice that implements an HTSP method or maps a new wire field must
regenerate `htsp_spec.json` and `HTSP_METHOD_MATRIX.md` and include any resulting
diff in that same logical slice / authorized commit. See `AGENTS.md`.

## License and attribution

This library is GPLv3 and an independently maintained descendant of
[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream). It is not official
TVHeadend software and is not affiliated with or endorsed by the TVHeadend
project. Protocol facts are derived from publicly available TVHeadend sources
and docs; see [`licensing.md`](../licensing.md)
and [`NOTICE.md`](../../NOTICE.md).
