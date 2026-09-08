# Versioning and compatibility

The current immutable release coordinate is `at.bernhardberger.tvheadend:htsp:0.10.0`.
`0.10.0` is a provisional major-zero release that exposes optional
`queueStatus.errors` as `HtspQueueStatusMessage.errorCount: Long?`. It preserves
absence separately from zero and the full unsigned-u32 range. The data-class
constructor and generated `copy` JVM signatures change; recompile consumers.
Existing ordinary Kotlin constructor calls can omit the trailing defaulted
property. Published release bytes are immutable and must never be replaced.

The `0.10.0` coordinate is available from
[Maven Central](https://central.sonatype.com/artifact/at.bernhardberger.tvheadend/htsp/0.10.0),
with its [repository files](https://repo1.maven.org/maven2/at/bernhardberger/tvheadend/htsp/0.10.0/)
available directly. Publication and availability remain independently verified
external state for every release.

The [exact-tag release workflow](https://github.com/bernhardberger/tvheadend-htsp/actions/runs/34248983225)
passed on `a32af6157a3c29fe6e54fa732eb32927664dbea9`, verifying all 20 signed and
checksummed Central members and both
[GitHub prerelease assets](https://github.com/bernhardberger/tvheadend-htsp/releases/tag/v0.10.0).
The public manifest identifies that commit and the dedicated signing fingerprint
`EAB02E488E7B944EAA6D65814BF0412FD2A3B741`. A separate public Central JAR download
on 2026-09-08 matched its manifest SHA-256:
`7e62397d5af399dec4436e58afc98a0c6a340e37578a29fcf85b8fbbb416ad7f`.

## Provisional 0.x policy

While the major version is zero, the public API and behavior are provisional.
No source, binary, or behavioral compatibility is promised for the provisional
0.x line. A known breaking change requires the next minor version, not a patch
version. Patch versions are reserved for backward-compatible fixes.

Read the `0.10.0` release notes before using it as a baseline. Local checks and
candidate CI do not establish publication, availability, distribution, Java 17
runtime support, or release readiness.
