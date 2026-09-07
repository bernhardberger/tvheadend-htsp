# Audit Repair Scope

P30-H1 starts from `accc658e25bc2750cae1bcba52e28545364d9d04` (published
0.7.0). The selected source repairs are HTSP-01, HTSP-05 and HTSP-06. The 0.8.0
minor version reflects the changed subscription completion contract. Local
verification or this record does not establish publication or consumer adoption.

## Repaired Boundaries

- **HTSP-01:** `Stopped` is a nonterminal, ordered stream interruption. Repeated
  `Started` messages retain replacement descriptors/source metadata and packets
  on the same collection. Successful unsubscribe acknowledgement drains and
  completes; transport/local retirement appends `Terminated` even after a stop.
  `HtspServiceSubscriptionEventTest` covers three source/codec changes with a
  retained 90 kHz clock, unsubscribe cleanup, late acknowledgements after timeout
  and cancellation, collector tombstones, and replacement/EOF after a stop.
  `HtspSubscriptionEventBufferTest` covers pressure/drop ordering across stop/start
  and retirement; existing control-backpressure tests remain in place.
- **HTSP-05:** only root integer sequences in `0..4294967295` are correlated.
  The internal signed key preserves all u32 bits; outgoing sequences use canonical
  nonnegative wire values through rollover. Invalid envelopes retire the transport
  before either pending or late-reply callbacks run. Oversized root S64 sequences
  cannot hide high bytes behind the codec's general scalar compatibility rule.
  `HtspCodecTest` covers types/ranges and oversized encodings;
  `HtspServiceTypedEventTest` covers pending and late unsubscribe aliases and
  signed-boundary/full-u32 rollover. Pinned `src/htsp_server.c:510-515` reads and
  echoes `seq` as u32; no healthy-server aliasing behavior is alleged.
- **HTSP-06:** JSON API string, object/list, request, and containing result
  diagnostics omit arbitrary strings, keys, paths, and recursive payload content.
  `HtspApiBridgeTest` checks synthetic sentinels at each boundary while retaining
  exact getters and wire round trips. This repairs diagnostic rendering exposure,
  not evidence of an observed production log leak.

The [subscription contract](public-api.md) records the pinned upstream stop/start
evidence. SDK P30-S1 was given that contract separately from publication readiness.
The SDK's `SubscriptionStateMachine` still treats `Stopped` as terminal and rejects
a repeated `Started` as `TrackReconfigurationUnsupported`. This library release
alone does not close SDK/application playback reconfiguration; decoder/state-machine
integration remains separate. No SDK/Player files or live server/device were used.

## Remaining Open

- **HTSP-02:** caller-context blocking connect/write and write-time bounds were not
  selected. Main-safety and stalled-write cancellation need their own evidence;
  this change does not move transport ownership or dispatchers.
- **HTSP-03:** deferred, not rejected. The typed error path still checks live
  transport state as part of generation currency after reader retirement. Ordinary
  EOF may therefore become synthetic stale-generation cancellation. Separating
  identity from liveness must also preserve replacement, handshake and recapture
  fencing; that broader lifecycle change is outside this selected subset.
- **HTSP-04:** deferred, not rejected. Mid-frame timeout retry can outlive the
  reader silence watchdog. A progress deadline needs a bounded policy and tests
  that preserve transient partial-frame recovery; no speculative timeout was added.
- **HTSP-07:** initial-sync retry semantics were not selected or re-audited.
- **HTSP-08:** metadata backpressure remains the existing explicit never-drop
  policy. A transport-wide redesign was not selected; no performance closure is
  claimed from the subscription stop fix.
- **HTSP-09:** event-count versus byte-memory limits were not selected or
  re-audited. No byte budget or backpressure redesign was introduced.

No considered finding is dismissed as unsupported. The unselected IDs remain open.
Focused tests run under the shared-host Gradle lock, with an empty inherited
environment and a loopback-only network namespace, using `--offline --no-daemon`.
The final source gate is `./gradlew build check --no-daemon` under that isolation;
release staging, consumer checks, independent review and remote verification are
additional delivery evidence, not implied by this scope record.
