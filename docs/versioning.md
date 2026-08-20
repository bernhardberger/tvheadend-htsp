# Versioning and compatibility

The current immutable release coordinate is `at.bernhardberger.tvheadend:htsp:0.5.0`.
`0.5.0` is a provisional major-zero release with an intentional Kotlin source
and behavioral change from `0.4.0`. Published release bytes are immutable and
must never be replaced.

The `0.5.0` coordinate is available from
[Maven Central](https://central.sonatype.com/artifact/at.bernhardberger.tvheadend/htsp/0.5.0),
with its [repository files](https://repo1.maven.org/maven2/at/bernhardberger/tvheadend/htsp/0.5.0/)
available directly. Publication and availability remain independently verified
external state for every release.

## Provisional 0.x policy

While the major version is zero, the public API and behavior are provisional.
No source, binary, or behavioral compatibility is promised for the provisional
0.x line. A known breaking change requires the next minor version, not a patch
version. Patch versions are reserved for backward-compatible fixes.

Read the `0.5.0` release notes before using it as a baseline. Local checks and
candidate CI do not establish publication, availability, distribution, Java 17
runtime support, or release readiness.
