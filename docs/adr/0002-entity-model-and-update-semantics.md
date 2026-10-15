# ADR 0002: entity model and update semantics before 1.0

Status: accepted 2026-10-15, before 1.0.0. Amends ADR 0001 D2 and D7.

## Context

A pre-1.0 audit found that five metadata families (channel, tag, DVR entry,
autorec, timerec) declare identical Add and Update field lists twice, and that
`HtspEventUpdateMessage` repeats every `HtspEvent` field. Channels and events
are also decoded twice, once for pushed messages and once for request replies,
with different keys and requiredness. `docs/public-api.md` told reducers to
merge updates with `copy()`, which keeps values the server has cleared.

The pinned server (`f082b430`, `src/htsp_server.c`) sends updates as follows:

| Method | Emitted shapes |
|---|---|
| `autorecEntryUpdate`, `timerecEntryUpdate`, `eventUpdate` | Always the full builder shared with Add |
| `tagUpdate` | Full builder; initial-sync `tagAdd` omits `members` (`:1709`), the following `tagUpdate` includes it (`:1719`) |
| `channelUpdate` | Full `htsp_build_channel`, or now/next with only `channelId`, `eventId`, `nextEventId` (`htsp_channel_update_nownext`, `:3805`) |
| `dvrEntryUpdate` | Full `htsp_build_dvrentry`, or stats-only for HTSP > 25 (`htsp_dvr_entry_update_stats`, `:3936`) |

No other emitter of these methods exists in the pinned source.

Established JVM clients separate full and sparse shapes: fabric8 watch events
carry the complete object, Kord models a sparse message update as its own
partial type, and both share one entity type between request replies and pushed
events (Kord `common.entity`, JDA `api.entities`, fabric8 model modules). State
caches with old/new values live in a layer above the protocol client. Here that
layer is `tvheadend-sdk`.

## Decisions

- **E1: `model` package.** Amends D2: the production packages are `wire`,
  `model`, `requests`, `messages`, `connection`, and `jsonapi`. `model` holds
  the shared entities and their nested value types and never depends on
  `requests`, `messages`, or `connection`. D7's fail-closed package check
  covers the six packages.
- **E2: one entity per family.** `HtspChannel`, `HtspTag`, `HtspEvent`,
  `HtspDvrEntry`, `HtspAutorecEntry`, and `HtspTimerecEntry`. Pushed messages
  and request replies use the same entity, decoded by one internal decoder per
  entity. `HtspEvent` gains `genre`, `episodeId`, and `seriesLinkId`. Entities
  follow the data-class evolution recipe in `docs/public-api.md`, with
  defensive list snapshots and redacted `toString`.
- **E3: requiredness.** A property is non-null only when every supported server
  version (HTSP 36 through 44) always emits it in the full builder. Fields
  introduced later are checked against the negotiated version, as the request
  decoders already do (for example `channelIdStr` from v41). Every other
  property is nullable and null means "not set".
- **E4: update semantics.** Add and full Update messages are thin wrappers
  around the same entity, and a full Update replaces the stored entity: a null
  property means the server no longer sets it. `HtspTag.members` is the
  exception; null there means "not included in this message". The sparse
  shapes are separate flat message types: `HtspChannelNowNextMessage` and
  `HtspDvrEntryStatsMessage`, which apply only their own fields. Delete
  messages carry identity only.
- **E5: shape detection.** The decoder chooses the full shape when the
  discriminator is present (`channelName` for channels, `enabled` for DVR
  entries) and validates the full field set. Otherwise it requires exactly the
  sparse field set. A message matching neither is malformed. Regression
  fixtures pin both shapes, damaged full messages, and version-dependent
  fields.
- **E6: malformed metadata.** A malformed metadata message no longer closes the
  connection. It is skipped and reported through a payload-free,
  generation-scoped transport event with a count. The event is coalesced and
  counted against the metadata budget. It ends a pending initial-sync wait as
  incomplete, before any later `initialSyncCompleted` is processed. Envelope
  and subscription-control faults still close the connection.
- **E7: metadata hierarchy.** A sealed sub-interface `HtspMetadataMessage`
  types `HtspTransportEvent.ServerMessage.message`. Like `HtspServerMessage`,
  it is open for new subtypes in minor releases, and `docs/versioning.md`
  lists it.
- **E8: connection lifecycle.**
  - `HtspConnectionState.Closed` and `HtspTransportFailureKind.CLOSED` report a
    closed client, distinct from an unsolicited failure.
  - `HtspConnectionState.Connected` carries its `HtspLiveConnection`; the
    separate `liveConnection` flow is removed.
  - `HtspTransportEvent.ConnectionFailure.generation` is non-null.
  - `HtspConnectionGeneration` keeps its public constructor: it is an identity
    fence, not a credential, and custom `HtspConnection` implementations need
    it.
- **E9: consistency.**
  - Recording-rule update requests require an explicit
    `HtspRecordingRuleChannel`, including `Any`, instead of defaulting to a
    cleared restriction.
  - The DVR configuration UUID is named `dvrConfigUuid` everywhere.
  - Minutes since midnight are `Int` everywhere.
  - The negotiated protocol version is `Long`.
  - The mux frame type is an enum with `UNKNOWN`, open for new entries.
  - `HtspServerFacts` snapshots its lists.
- **E10: scope.** The library stays stateless: no entity cache, reducers, or
  merge helpers. The non-breaking audit fixes (nested `toString` redaction, a
  cancellation check on reused connections, accepting `noaccess: 0` in empty
  replies) land as separate `fix:` commits.

## Consequences

- The changes ship as one breaking `feat(htsp)!:` change with a regenerated
  `api/htsp.api`, a `BREAKING` changelog entry, and updates to
  `docs/public-api.md`, `docs/versioning.md`, `AGENTS.md`, and the package
  architecture tests.
- `tvheadend-sdk` receives a migration handoff. Its reducers replace entities
  on full updates and apply only the sparse messages, which fixes its current
  loss of server-side clears. They treat the malformed-metadata event as cache
  invalidation, and the SDK drops its own stats-only detection.
