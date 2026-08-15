# HTSP protocol reference

Contributor reference for the library's protocol coverage: a machine-readable
inventory of HTSP version 44 ([`htsp_spec.json`](htsp_spec.json)), a generated
human-readable matrix ([`HTSP_METHOD_MATRIX.md`](HTSP_METHOD_MATRIX.md)), and
the Python generators that produce the typed Kotlin catalog. Everything here
derives from one pinned TVHeadend source revision; see [Exact pin](#exact-pin).

These files are engineering evidence for people changing the library. They are
not consumer documentation, not a public API, and not a support, stability, or
completeness promise.

## When to read this

Reach for this directory when you are:

- implementing a client-to-server HTSP method;
- mapping a new wire field on a request, reply, or server message; or
- checking protocol vocabulary, version-gate evidence, or SDK coverage against
  the pinned upstream dispatch table.

Changing runtime behavior, package boundaries, or the public Kotlin API takes
more than editing these files; the repository `AGENTS.md` explains the rules.

## Package and dependency boundary

The `htsp` artifact owns exactly five shallow packages under
`at.bernhardberger.tvheadend.htsp`: `wire`, `requests`, `messages`,
`connection`, and `jsonapi`. The package root itself is empty; there are no
deeper packages and no compatibility shims at the pre-extraction location.

Production code may depend only on sibling declarations in that tree,
Kotlin/JDK facilities, and `kotlinx.coroutines`. It never depends on Android,
Media3, native decoders, test fixtures, the legacy `at.bernhardberger.tvhplayer`
root, or application code. `./tools/check-htsp-protocol-boundary` scans the
whole production source tree and fails on any violation.

`HtspService`, the codec, the raw per-message mappers, and the catalog helper
stay internal. The one public finite decoder accepts a raw map at an explicit,
documented boundary.

## The quick-start snippets are checked

The snippets in the root `README.md` and in this section are kept
byte-identical to the independent consumer fixture under `consumer-contract/`
by a static repository check, which also rejects project, file, included-build,
Maven Local, and repository bypasses in the fixture build. The check compares
text only: it does not resolve the coordinate, compile the fixture, contact a
TVHeadend server, or establish release readiness. Compiling the fixture against
a published artifact is separate release-stage work. Endpoint and credential
values remain caller-owned inputs.

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

Typed request coverage is 39 of 39 pinned methods. `execute` is the canonical
path and takes an already-constructed request; the generated extensions are
convenience delegates with their own parameter lists. External consumers cannot
subclass `HtspRequest`, so execution stays limited to the finite catalog and
never becomes a raw or custom request escape hatch.

## Where protocol facts come from

In order of authority:

1. **Primary:** the pinned TVHeadend server source (`src/htsp_server.c`,
   `src/htsp_server.h`, `src/epg.c`, `src/epg.h`, `src/lang_str.c`,
   `src/string_list.c`, `src/api.c`, `src/api/api_idnode.c`, `src/htsmsg.h`,
   and `src/htsmsg_binary.c`) at the revision in
   [`upstream.json`](upstream.json).
2. **Secondary:** the official HTSP docs
   ([Communication](https://docs.tvheadend.org/documentation/development/htsp/communication),
   [Client-to-Server RPC methods](https://docs.tvheadend.org/documentation/development/htsp/client-to-server-rpc-methods),
   [Server-to-Client methods](https://docs.tvheadend.org/documentation/development/htsp/server-to-client-methods),
   [Protocol Changes](https://docs.tvheadend.org/documentation/development/htsp/protocol-changes)).
3. **Narrow cross-check only:** `lib/py/tvh/htsp.py`, a demo client at protocol
   33 covering hello/authenticate/enableAsyncMetadata. Never a completeness
   authority.
4. **Local acceptance:** this repository's production sources and tests.

Where the docs are missing or stale, the pinned source wins, and the gap is
recorded in `htsp_spec.json` and the matrix.

## The two catalogs

Two files here look like catalogs; they have different jobs.

[`htsp_surface.py`](htsp_surface.py) is the reviewed authority for the
generated Kotlin surface: Kotlin names and types, constructor and property
order, wire-name aliases, field gates, nested mappings, validation, redaction,
and the 39 requests, 11 convenience overloads, and 30 server messages. It is
maintained by hand, never scraped from Kotlin source.

[`htsp_spec.json`](htsp_spec.json) is the immutable checked-in derivation of
the pinned upstream evidence. It is not the Kotlin or API authority.
`./tools/check-htsp-generated-drift` resolves every catalog wire-name
occurrence to the exact method or named shape in that evidence and compares its
normalized direction, wire type, and explicit field minimum version. Synthetic
`<root>` entries link a method projection to a reviewed nested shape; they are
not literal wire fields.

When the two disagree, shipped compatibility behavior wins, and each such
occurrence carries one exact, reasoned waiver in `htsp_surface.py`. The drift
gate requires the waiver to be consumed by that mismatch and rejects malformed,
duplicate, blank, missing, or unused waivers, including a waiver placed on an
exact match.

## Answering routine questions

The pinned upstream source is not vendored in this repository;
[`htsp_spec.json`](htsp_spec.json) is its derived, checked-in form. Read the
local files first. A remote lookup for a fact already recorded here is wasted
work, and every such round trip is repeated by each fresh writer session.

Answer these from `htsp_spec.json` or the matrix, with no remote lookup:

- whether a method or server message exists, and its exact wire name;
- its request and reply field names and wire types;
- its required access level;
- whether the handler branches on protocol version; and
- whether this SDK already references it.

Go to the docs site or the pinned source only for what the derivation does not
capture: field semantics, units, and value ranges; either/or request
requirements, which the derived `required` marks cannot express; which specific
field a version gate guards; and behavior of a shared upstream converter across
sibling methods.

The *Derivation confidence* section of the matrix states which columns are
reliable and which are approximate. Verify an approximate column before relying
on it; do not re-derive a reliable one.

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

## Files in this directory

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

The derived facts, provenance records, and generator/checker code in this
directory are original to this repository and remain under its GPLv3 license.
Upstream TVHeadend source bodies are not vendored here.

## Coverage against the pinned dispatch table

The byte-identical specification, matrix, catalog, and wire-format evidence
keep their historical `sdk/` path labels: the recorded string-literal scan
covered `sdk/htsp-protocol/src/main`, `sdk/htsp/src/main`, and
`sdk/playback-media3/src/main` only (production Kotlin/Java; tests and fixtures
excluded). Those labels describe frozen pre-extraction evidence, not this
repository's current layout.

| Surface | Count | Meaning |
|---|---:|---|
| Client→server methods | 39 | Pinned `htsp_methods[]` dispatch table |
| Referenced method names | **39** | Exact literals present in production sources |
| Outgoing request names | **39** | Distinct names assigned to outgoing `method = ...` requests |
| Public typed client requests | **39** | Reviewed request/response models with generated `HtspConnection` extensions |
| Server→client messages | 30 | Current emitted async/server messages |
| Handled server messages | **30** | Exact literals present in production sources |
| Public typed server messages | **30** | Reviewed payload models with a public finite decode-result boundary; runtime publication remains a separate internal policy |

Read these numbers with three distinctions in mind:

- **Referenced is not called.** `subscriptionSeek` and `subscriptionSkip` are
  distinct outgoing wire names for one shared pinned handler. Never claim a
  method is implemented or called merely because its name is referenced.
- **Typed request coverage** means a reviewed public `HtspRequest` model plus a
  generated `HtspConnection` extension. It is not a support, stability, or
  completeness claim.
- **Typed server-message coverage** means public payload models plus a public
  finite decoder. It does not prove runtime consumption. Selected client
  channel/tag/EPG/DVR metadata and subscription-status consumers use the
  decoder, while low-level wire parsing and the opt-in playback SPI keep their
  bounded raw integration. Decoding is strict, with one retained compatibility
  rule: malformed optional timerec add/update fields decode as omitted/null
  while valid siblings survive; required add fields and update identity stay
  strict.

The inbound autorec and timerec Add/Update/Delete families are finite read-only
protocol metadata, and `descrambleInfo` completes the typed catalog without
changing its playback consumer or runtime publication. The six sibling outbound
autorec/timerec RPCs have finite typed protocol mappings as well. None of this
adds client-runtime schedule publication, lifecycle, retry, or DVR policy.

## Per-method notes and documentation gaps

These notes record pinned-source facts that the official docs get wrong, get
incomplete, or do not state at all. They are evidence annotations, not runtime
gates, upstream support promises, or SDK policy. The matrix's *Documentation
limitations* section lists each source/docs divergence with its governing
source location and docs URL; a recorded mismatch is never a decision to coerce
SDK values.

General rules:

- Mechanical field types come from structurally bounded handlers, emitters, and
  helpers plus a reviewed exact-pin annotation catalog; the generator does not
  claim generic regular-expression parsing is complete.
- Every field records direction, wire/container type, presence, evidence,
  confidence, and an evidenced minimum version or `null`. Nested values use
  named shape references; each shape is explicitly complete, partial,
  dynamic/opaque, known-empty, alternative, or unknown.
- Recorded version minima are compatibility evidence, not gates: channel
  services at v5, channel minor numbers and selected DVR observations at v13,
  stream metadata at v5/v11 with top-level codec metadata at v17, subscription
  errors and satellite source metadata at v20, service provider names at v38,
  UUID/rating observations at v41, and absolute signal/SNR observations at v44.
  Unknown minima stay null.
- Documentation TODOs, `???`, source heuristics, and the stale demo client are
  labeled as such and never promoted to confident contracts.
- Global RPC fields `seq`, `error`, and `noaccess` are tracked separately from
  method-specific fields.
- Dispatch-table access masks are raw provenance, not an SDK authorization API.

Methods and shapes:

- `api` carries an exact machine-readable `acceptedVocabulary` fact: the bridge
  admits `map`, `list`, `str`, `s64`, `bin`, `bool`, and fixed-width 16-byte
  `uuid`, each with decode and serialization evidence in `src/htsmsg_binary.c`,
  and excludes `dbl`. Although `HMF_DBL=6` exists in `htsmsg.h`, the pinned
  binary decoder has no double case and rejects it through its default branch,
  and the serializer likewise reaches its default abort. The bridge therefore
  does not model `Double` or `Float`.
- `hello` and `authenticate` have exact bounded current-source shapes. The
  anonymous-access `hello` handler requires only u32 `htspversion` and string
  `clientname`, never reads `clientversion`, emits a required 32-byte challenge
  and six other unconditional observations, emits `webroot` and `language`
  conditionally, and assigns the connection version with
  `MIN(HTSP_PROTO_VERSION, requested)`. `authenticate` reads no method-specific
  fields: a denied grant emits only `noaccess=1`, granted rights above v25 emit
  the exact ten access/limit/UI fields, and granted rights at v25 or earlier
  emit an empty method payload.
- `getEvents` is a version-4 method whose five optional filters (`channelId`,
  `eventId`, `language`, `numFollowing`, and signed-s64 `maxTime`) each carry
  version-6 compatibility evidence. Its complete method-specific reply is
  exactly required `events:list -> event`.
- `getEpgObject` has required u32 `id`, optional u32 `type`, streaming access,
  and no evidenced method minimum. The pinned enum contains only undefined and
  broadcast, and only broadcast has a serializer. The finite broadcast reply
  follows the base plus broadcast serializers: strict required `id`, broadcast
  `tp`, signed-s64 `up`/`start`/`stop`, and the recorded bounded optional
  scalar, true-only flag, language-map, episode-number, genre, and string-list
  shapes. `lang_str_serialize_map` establishes strict string keys and values;
  the sorted RB-tree string-list implementation establishes sorted unique
  output. Pinned `time_t` members are carried as unchanged Unix seconds. The
  unconstrained copied `cred` object stays an explicit opaque shape and is
  deliberately omitted from the public response. The official docs leave this
  reply literally `TODO`, so the pinned source is normative here.
- `getDvrCutpoints`: the official page does not define the millisecond
  coordinate origin or chronological ordering, overlap, or uniqueness
  semantics. Pinned source serializes `dc_start_ms`/`dc_end_ms` and traverses
  the source TAILQ; the SDK preserves observed values, order, and duplicates
  without interpretation.
- `getTicket`: the official page marks `channelId` and `dvrId` optional and
  both reply strings required, but does not state that at least one selector is
  required or that pinned source checks `channelId` first. The SDK makes only
  one full-u32 selector representable, strictly requires the returned `path`
  and `ticket` strings, and does not expose the source's both-present state.
- `fileOpen`, `fileRead`, `fileClose`, and `fileSeek` are annotated for HTSP v8
  with recorder access. Pinned `fileOpen` requires exact string `file`, strips
  at most one leading slash only inside the server, and returns required u32
  `id` plus coupled signed-s64 `size`/`mtime` when `fstat` succeeds. `fileRead`
  uses the same connection-owned default-zero handle lookup, requires
  signed-s64 `size`, accepts optional signed-s64 `offset`, and always emits a
  required binary `data` field on success, including an empty payload; the
  typed request additionally bounds one read to 0..16 MiB without changing any
  codec, reader, chunking, EOF, handle-lifecycle, or playback behavior. Typed
  `fileClose` preserves the exact raw id-only request and adds optional
  full-u32 `playposition` and `playcount` controls gated to v27 when present;
  the pinned server increments playcount for a recording-backed handle
  unconditionally before v27, and at v27 or newer an omitted `playcount`
  defaults to `HTSP_DVR_PLAYCOUNT_INCR` and still increments. Supplied
  `playposition` updates whole recording-position seconds at v27 or newer.
  `fileSeek` requires signed-s64 `offset`, accepts only `SEEK_SET`, `SEEK_CUR`,
  or `SEEK_END`, defaults an omitted whence to `SEEK_SET`, and always returns
  the non-negative signed-s64 absolute offset after success.
- `fileStat` is annotated for HTSP v8 with recorder access. Its helper reads
  one u32 `id` with zero default and searches only the current connection's
  file list; absent, malformed, unknown, and zero IDs share the global
  `Invalid file` path unless zero identifies an owned handle. On
  `fstat(fd, &st) == 0` the handler emits signed-s64 `size = st.st_size`
  followed by signed-s64 `mtime = st.st_mtime`; otherwise it returns the
  successful empty map. The fields are coupled and there are no other
  method-specific outputs. Official docs say u64 and independently optional,
  omit empty-success behavior, and do not define mtime's unit or epoch; the SDK
  preserves unchanged POSIX `st_mtime` without conversion.
- `stopDvrEntry` has no evidenced introduction version. Its recorder-access
  handler uses the shared DVR-entry helper in write mode, returns the helper's
  bounded error result, calls exactly `dvr_entry_stop`, and returns the exact
  standard `success:u32 = 1` map; cancel and delete call different operations.
  The official page omits stop, so neither the inventory nor the SDK invents a
  lifecycle transition; later asynchronous DVR metadata remains authoritative.
- `subscriptionChangeWeight` (v5, streaming access) requires decoded u32
  `subscriptionId`, reads optional u32 `weight` with default zero, searches
  `htsp_subscriptions` by exact `hs_sid`, and returns the exact
  missing-subscription error when no match exists. It queues exactly one empty
  reply before exactly one `subscription_change_weight` call; that ordering is
  acknowledgement evidence, not proof that the weight is settled or applied.
- `subscriptionLive` (v9, streaming access) requires decoded u32
  `subscriptionId`, rejects a missing match, zero-initializes one
  `streaming_skip_t`, sets only `SMT_SKIP_LIVE`, calls exactly one
  `subscription_set_skip`, then queues exactly one empty reply. The
  action-before-reply source topology does not guarantee wire delivery order or
  settled live state; the separate asynchronous `subscriptionSkip` outcome
  remains authoritative.
- `subscriptionSeek` and `subscriptionSkip` are distinct v44 dispatch names
  that both call `htsp_method_skip` (streaming access, annotated minimum v9).
  The handler requires exact u32 `subscriptionId`, reads optional u32
  `absolute` with default 0, accepts signed-s64 `time` first otherwise
  signed-s64 `size`, and errors when neither coordinate exists; nonzero
  `absolute` selects absolute skip semantics. It calls `subscription_set_skip`
  then queues an empty RPC reply. Official docs call `subscriptionSeek` a
  synonym but list time/size as optional u64 and omit the either/or rule;
  pinned source wins.
- `subscriptionFilterStream` (v12, streaming access) requires decoded u32
  `subscriptionId`, then processes optional `enable:list[u32]` before optional
  `disable:list[u32]`, accepting only `HMF_S64` members, and returns exactly
  one empty map. The helpers mutate the filtered-stream bitmap only for
  converted unsigned indexes below `NUM_FILTERED_STREAMS=(64*8)`: at this pin
  0..511 can affect it, 512 and larger are ignored, overlap ends disabled, and
  omitted or empty lists make no change for that side.
- The shared `service` shape is the complete bounded current-source
  name/type/content/conditional-access/provider object used by channel replies;
  its dynamic `hbbtv` child points to a separate opaque named shape rather than
  a guessed schema. Complete `getChannel` reply evidence does not make partial
  `channelUpdate` semantics complete.
- The shared `stream` and `sourceInfo` shapes are partial field inventories:
  required stream index/type and optional known metadata stay strict, while
  source metadata is independently optional. Their recorded version minima do
  not require those containers in `subscriptionStart`.
- The shared `event` shape is the complete bounded current `htsp_build_event`
  result used by `getEvent`, `getEvents`, and `eventAdd`. Category and keyword
  are ordered string-list shapes; credits are a separately named opaque dynamic
  object. Update compatibility is represented separately: only `eventId` is
  required, every non-key field may be omitted, and consumers merge present
  fields by `eventId`, without claiming the pinned source actually omits
  builder-required fields.

## Regenerating the artifacts

Public generated KDoc is owned by exact catalog declaration and callable keys
in `htsp_surface.py`; generated Kotlin is never edited for prose. The catalog
retains its truthful pre-extraction output paths, so do not run the generators
with `--write` at this repository root: the drift checker validates a projected
copy instead. Relocate that path authority together with any catalog or
generated-output change in one coordinated slice. The projection checker
requires exactly 185 public types and 73 public functions, including the 14
reviewed nested types and every same-name overload, and rejects missing, extra,
blank, placeholder, duplicate, detached, stale, or generated-output-mismatched
documentation.

`derive.py` needs an external TVHeadend source root containing the eleven
pinned files. It validates the immutable repository/revision/version, the exact
eleven-file key set, Git-blob SHA-1 values, byte counts, and the official URL
set without trusting external Git metadata, and it rejects symlink roots or
components, non-regular files, and out-of-root paths. It never mutates the
external tree.

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
# catalog retains its pre-extraction output paths):
./tools/check-htsp-generated-drift
python3 docs/htsp-protocol/report.py --check
```

An optional explicit fetch downloads the eleven pinned raw files only, and is
never part of repository or CI checks. It validates the immutable pin and the
complete destination plan before any network activity, rejects
repository-contained or symlinked/no-overwrite targets, verifies all response
bytes and final raw-GitHub URLs before writing, and creates files exclusively:

```bash
python3 docs/htsp-protocol/derive.py \
  --fetch-pinned /path/to/empty/tvheadend-htsp-pin \
  --write
python3 docs/htsp-protocol/report.py --write
```

What the generated catalog contains: the 39 canonical request extensions mirror
request constructors, add common timeout and generation controls, construct one
matching request, and delegate once to canonical `execute`. The source-safe
`fileCloseWithProgress(id, playPositionSeconds, playCount, timeoutMs, expectedGeneration)`
overload keeps the older `(id, timeoutMs, expectedGeneration)` positional
meaning intact. Event-versus-explicit-time DVR selection and ID-versus-name
subscription selection have wrapper-free conveniences; subscription seek and
skip reuse `SubscriptionSeekPosition.Time` and `.Size` case-typed overloads,
because both payloads are `Long` and wrapper-free overloads would be
signature-identical. The resulting 50 declarations include typed `api`,
`hello`, and fieldless `authenticate`.

The typed server-message catalog fixes exactly 30 names, public model types,
and provenance-only minima, and its generated Kotlin owns only finite dispatch.
`decodeHtspServerMessage(Map<String, Any?>)` returns the public sealed result:
every `seq`-bearing reply envelope is unknown, an unknown, missing, or
non-string method is unknown, a malformed recognized method is malformed-known,
and a valid recognized method carries its decoded typed message. The decoder is
not a protocol-version gate. Raw per-message mappers and the catalog helper
remain non-public. `descrambleInfo` is a strict complete snapshot (required
full-u32 `subscriptionId`, `pid`, `caid`, `provid`, `ecmtime`, and `hops`, plus
optional strict strings `cardsystem`, `reader`, `from`, and `protocol`, with
wire `from` exposed as `source`), is emitted only at v24 or newer, and is
omitted when anonymization applies; `HtspService` decodes it through the public
finite decoder but excludes it from typed `HtspTransportEvent.ServerMessage`
publication. For the versionless typed decoder, `channelAdd` requires only
`channelId`, `tagAdd` only `tagId`, `dvrEntryAdd` only `entryId`, and
`eventAdd` requires `eventId`, `start`, and `stop`; nullable channel/tag names
and the conditional event channel remain strict when present. Nested DVR files
expose one canonical path, strictly choosing the first present
`filename`/`path` wire alias in that order. The pinned
`htsp_build_autorecentry` add snapshot strictly requires every unconditional
scalar/string emitter and keeps only source-conditional observations nullable;
autorec update requires exact string `id` and makes every other field nullable,
and delete requires exact string `id`. `queueStatus.delay` keeps its recorded
requiredness uncertainty rather than becoming a support promise.

## Self-tests

`derive.py`, `report.py`, all four generators, and the drift gate carry
mutation-based self-tests. Each models the bounded handler behavior behind the
recorded facts (the `hello`/`authenticate` shapes, the `getEvents` selection
branches, `getDvrCutpoints`, `getTicket`, the four bounded file operations and
`fileStat`, the six recording-rule handlers and their shared converter family,
the inbound autorec/timerec snapshots, `stopDvrEntry`, the
`subscriptionChangeWeight`/`Live`/`Seek`/`Skip`/`FilterStream` handlers, and
the API-bridge vocabulary including the `dbl` exclusion) and then independently
mutates every accepted fact to prove the checker rejects the drift. The exact
mutation lists live in the self-test code.

```bash
python3 docs/htsp-protocol/generate_typed_requests.py --self-test
python3 docs/htsp-protocol/generate_typed_request_models.py --self-test
python3 docs/htsp-protocol/generate_typed_server_messages.py --self-test
python3 docs/htsp-protocol/generate_typed_server_message_models.py --self-test
./tools/check-htsp-generated-drift --self-test
```

`./tools/verify-htsp --non-gradle` runs the standalone static checker, the four
generator self-tests and projected byte checks, and `report.py --check`, with
no network and no upstream checkout.

## Standing regeneration rule

Any change that implements an HTSP method or maps a new wire field must
regenerate `htsp_spec.json` and `HTSP_METHOD_MATRIX.md` and include the
resulting diff in the same commit. See the repository `AGENTS.md`.

## License and attribution

This library is GPLv3 and an independently maintained descendant of
[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream). It is not
official TVHeadend software and is not affiliated with or endorsed by the
TVHeadend project. Protocol facts are derived from publicly available TVHeadend
sources and docs; see [`licensing.md`](../licensing.md) and
[`NOTICE.md`](../../NOTICE.md).
