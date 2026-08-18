# Versioning and compatibility

The current immutable release coordinate is `at.bernhardberger.tvheadend:htsp:0.2.0`.
`0.2.0` is a provisional major-zero release with intentional source and binary
changes from `0.1.1`. The signed `v0.1.0` tag stopped before publication and is
not reused. Published release bytes are immutable and must never be replaced.

Availability from Maven Central or any other public repository is independently
verified external state. This repository does not claim that publication or
availability has occurred.

## Provisional 0.x policy

While the major version is zero, the public API and behavior are provisional.
No source, binary, or behavioral compatibility is promised for the provisional
0.x line. A known breaking change requires the next minor version, not a patch
version. Patch versions are reserved for backward-compatible fixes.

Read the `0.2.0` release notes before using it as a baseline. Local checks and
candidate CI do not establish publication, availability, distribution, Java 17
runtime support, or release readiness.
