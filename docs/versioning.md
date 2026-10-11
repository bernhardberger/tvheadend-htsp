# Versioning and compatibility

The current immutable release coordinate is
`at.bernhardberger.tvheadend:htsp:1.0.0-beta.1`, the first pre-release of
`1.0.0`. It is a source- and binary-incompatible upgrade from `0.10.0`; the
release notes list every breaking change. Like every pre-release it makes no
compatibility promise toward later pre-releases or `1.0.0`. Published release
bytes are immutable and must never be replaced.

The `1.0.0-beta.1` coordinate is available from
[Maven Central](https://central.sonatype.com/artifact/at.bernhardberger.tvheadend/htsp/1.0.0-beta.1),
with its [repository files](https://repo1.maven.org/maven2/at/bernhardberger/tvheadend/htsp/1.0.0-beta.1/)
available directly. Publication and availability remain independently verified
external state for every release.

The [exact-tag release workflow](https://github.com/bernhardberger/tvheadend-htsp/actions/runs/38098163117)
passed on `593a570c5f42b98301233d40ff8c5c2e7e3fed88`, verifying all 20 signed and
checksummed Central members and both
[GitHub prerelease assets](https://github.com/bernhardberger/tvheadend-htsp/releases/tag/v1.0.0-beta.1).
The public manifest identifies that commit and the dedicated signing fingerprint
`EAB02E488E7B944EAA6D65814BF0412FD2A3B741`. A separate public Central JAR download
on 2026-10-11 matched its manifest SHA-256:
`824e24dde59891ad2670df6ca927382e4764a10073491a72f5f1ef9fb2addc68`.

## Provisional 0.x policy

While the major version is zero, the public API and behavior are provisional.
No source, binary, or behavioral compatibility is promised for the provisional
0.x line. A known breaking change requires the next minor version, not a patch
version. Patch versions are reserved for backward-compatible fixes.

Read the release notes before using a release as a baseline. Local checks and
candidate CI do not establish publication, availability, distribution, Java 17
runtime support, or release readiness.

## Pre-releases

Pre-release versions (`X.Y.Z-alpha.N`, `-beta.N`, `-rc.N`) are previews of
`X.Y.Z` and promise no compatibility with each other or with the final release.

## Stable 1.x policy

From `1.0.0`, stable releases follow Semantic Versioning. A patch release fixes
bugs compatibly, a minor release adds functionality compatibly, and any
incompatible change requires a new major version.

### What is covered

- JVM binary compatibility of the public API recorded in `api/htsp.api`,
  including overloads that a later release keeps only for binary compatibility.
- Kotlin source compatibility of that API, except inferred callable references
  such as `::HtspChannelAddMessage` or `message::copy`, whose arity changes when
  a parameter is added.
- The behavior documented in [`public-api.md`](public-api.md), KDoc, and the
  protocol notes: typed outcomes, cancellation, stream ordering and buffering
  contracts, and redaction of secrets, paths and identifiers in `toString`.
- Java 17 as the minimum supported runtime.
- HTSP 36 as the minimum supported server protocol version.

Not covered: Java source compatibility, the opt-in `@HtspJsonApi` bridge, the
exact `toString` format, internal declarations, and undocumented behavior.

### Compatible changes in minor releases

- New request types, server messages, properties, functions, and optional
  parameters. Data classes evolve as described in
  [`public-api.md`](public-api.md#evolving-data-classes): new properties are
  appended with a default, and the previous constructor and `copy` remain
  binary-compatible.
- New subtypes or entries of the open types below. Their KDoc says so; keep an
  `else` branch when matching them:
  `HtspServerMessage`, `HtspTransportEvent`, `HtspSubscriptionEvent`, `HtspSubscriptionTermination`,
  `HtspTransportFailureKind`, `HtspEpgObjectType`, `HtspLogLevel`, `HtspAccess`,
  `HtspDvrMutationRequest`, and `HtspDvrMutationResponse`.
  `HtspTransportEvent` remains Kotlin-sealed, like `HtspServerMessage`; "open"
  describes its evolution policy, not permission for consumer-defined implementations.
- A higher default requested HTSP version when a release extends the typed
  protocol coverage to it; servers still negotiate down.
- Lowering the minimum supported HTSP server protocol version.
- Default queue budgets may change in minor releases. Pass explicit
  `HtspEventBufferOptions` when fixed numbers are required. The documented overflow
  behavior remains covered by the compatibility policy.
- New `HtspConnection` members, always with a default implementation, so
  custom implementations such as test fakes keep compiling and linking.
- A higher minimum Kotlin or `kotlinx-coroutines` version, stated in the
  release notes.

All other sealed hierarchies and enums are closed for the 1.x line and safe to
match exhaustively, including `HtspResult`, `HtspFailure` (whose 1.0 members include
`MalformedReply` as distinct from `ServerError`),
`HtspConnectOutcome`, `HtspConnectionState`, and the
request selector types. A new kind of server failure is reported through an
existing category, such as `ServerError`, until 2.0.
Match that data class with `is HtspResult.ServerError`; its optional
`serverMessage` is untrusted server text, not a stable code, and participates
in equality without being rendered by `toString()`.

### Incompatible changes

Removing, renaming, or changing the signature of a public declaration;
reordering data-class properties; adding a subtype or entry to a closed type;
adding an `HtspConnection` member without a default; changing documented
behavior; raising the minimum supported HTSP server protocol version; and
raising the minimum Java runtime above 17 all require a new
major version.
