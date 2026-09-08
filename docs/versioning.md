# Versioning and compatibility

The current immutable release coordinate is `at.bernhardberger.tvheadend:htsp:0.9.0`.
`0.9.0` is a provisional major-zero release that repairs transport cancellation,
request deadlines, and silence watchdogs, and removes a payload copy from typed
file reads. Timeout and cancellation behavior changes; Kotlin source and JVM
signatures are unchanged. Published release bytes are immutable and must never
be replaced.

The `0.9.0` coordinate is available from
[Maven Central](https://central.sonatype.com/artifact/at.bernhardberger.tvheadend/htsp/0.9.0),
with its [repository files](https://repo1.maven.org/maven2/at/bernhardberger/tvheadend/htsp/0.9.0/)
available directly. Publication and availability remain independently verified
external state for every release.

The [exact-tag release workflow](https://github.com/bernhardberger/tvheadend-htsp/actions/runs/34176273370)
passed on `5f8dedb44544c054fac23b9673cea8df3466071c`, verifying the signed Central
members and both [GitHub prerelease assets](https://github.com/bernhardberger/tvheadend-htsp/releases/tag/v0.9.0).

## Provisional 0.x policy

While the major version is zero, the public API and behavior are provisional.
No source, binary, or behavioral compatibility is promised for the provisional
0.x line. A known breaking change requires the next minor version, not a patch
version. Patch versions are reserved for backward-compatible fixes.

Read the `0.9.0` release notes before using it as a baseline. Local checks and
candidate CI do not establish publication, availability, distribution, Java 17
runtime support, or release readiness.
