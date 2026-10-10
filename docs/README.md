# Documentation

Guides for people using the library, and references for people working on it.

## Using the library

- [`public-api.md`](public-api.md): how calls report success and failure,
  covering typed outcomes, cancellation, and what error values never carry.
- [`versioning.md`](versioning.md): the current version, its provisional compatibility status,
  and what compatibility you can expect.
- [`releasing.md`](releasing.md): one-time GitHub Environment setup and the
  exact-tag automatic Central and GitHub release path, including pre-releases.
- [`CHANGELOG.md`](../CHANGELOG.md): released and upcoming changes.
- [`licensing.md`](licensing.md): GPLv3 obligations, attribution, and project
  lineage.

## Repository internals

- [`../AGENTS.md`](../AGENTS.md): engineering rules and invariants for
  contributors and coding agents; `.opencode/` holds an optional reviewer agent
  and Kotlin skills.
- [`htsp-protocol/README.md`](htsp-protocol/README.md): the upstream pin,
  wire-level reference, and maintenance notes for the hand-maintained typed
  catalog.
- [`adr/0001-standalone-protocol-decisions.md`](adr/0001-standalone-protocol-decisions.md):
  the settled design decisions and the ideas deliberately left out.
- [`extraction/manifest.json`](extraction/manifest.json): the frozen record of
  the source extraction this repository started from.
- [`wrapper-provenance.md`](wrapper-provenance.md): where the Gradle wrapper
  bytes came from and which distribution they download.
- [`../release/openpgp/README.md`](../release/openpgp/README.md): the dedicated
  release-key trust model, tracked public key, and exact primary fingerprint.

Dependabot proposes monthly grouped dependency updates; there is no auto-merge.
Bot PRs are expected to fail dependency verification until a maintainer regenerates
`gradle/verification-metadata.xml` with `--write-verification-metadata pgp,sha256
--export-keys` and reviews the keys, never trusting new keys blindly. Kotlin
and kotlinx-coroutines bumps also need the exact `kotlin-stdlib`, coroutines and
`org.jetbrains:annotations` pins in `build.gradle.kts`
(`verifyProductionDependencyGraph`) and `tools/check-published-jvm-compatibility`
updated, because consumers see them; a higher Kotlin minimum goes into the
release notes (see [`versioning.md`](versioning.md)). Security updates, when
enabled in the repository settings, arrive as separate pull requests outside
the monthly group. Check that each update PR covers the Gradle wrapper
(including `distributionSha256Sum`) and the foojay settings plugin in
`settings.gradle.kts`; update them by hand when it does not.

## Benchmarks

Run on JDK 21 with `./gradlew --no-daemon benchmark`, or use
`./gradlew --no-daemon smokeBenchmark` for a quick fixture/harness check.
The dedicated `src/benchmark/kotlin` source set uses kotlinx-benchmark and JMH;
`check` compiles it but never runs benchmarks. It is not published with the library.

The main configuration uses one fork, five 1-second warmups and five 1-second
measurements; smoke uses one 100-ms warmup and measurement. Both report average
nanoseconds per operation and GC allocation metrics in
`build/reports/benchmarks/{main,smoke}/benchmark.json`. Since kotlinx-benchmark
0.5.0 has no profiler option, the generated harness runs via JMH's standard CLI
with `-prof gc` (and a fixed 256–512 MiB heap).

Fixtures are built in setup using the library encoder. Measurements cover wire
and typed decoding, request encoding, and bounded queues. Wire/mux cases compare
`bytes` with `transport`: the latter uses the production 64-KiB buffered stream
and `HtspTransportInputStream`, including `beginFrame()` on each frame. Both reuse
their stream stack, resetting the fully consumed byte fixture between frames.
Loopback measurements use only an in-process fake server on `127.0.0.1`, excluding handshake/setup;
32-packet bursts are normalized to **ns/packet**. Per-invocation `runBlocking`,
`withTimeout` and socket-closing watchdog costs are amortized over those 32 packets:
use this for before/after comparisons, not an absolute dispatch cost. The optional
competing coroutine
makes real system-time RPCs with a 1-ms pause between replies; it is a modest
contention workload, not a lock-only microbenchmark. Its allocation metrics also
include allocations from the competing coroutine's RPCs. It exercises the private
reader dispatch/connection lock through the public API without changing production
visibility. Buffer results are per offer/drain batch, not per event.

Results are machine-specific, not a CI performance gate. Compare runs on the same
idle machine/JDK; retain the JSON and note the commit and machine. Local baselines
may be copied to `build/review/benchmarks/baseline-<commit>/` (untracked), but copy
baselines you want to retain outside `build/` so they survive `clean`.

Current repository code and tests override any generic guidance in these
documents. Nothing here authorizes publication, signing, release, or other
release-stage operations.
