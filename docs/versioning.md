# Versioning and compatibility

The current coordinate is
`at.bernhardberger.tvheadend:htsp:0.1.0-alpha.1-SNAPSHOT`.
`0.1.0-alpha.1-SNAPSHOT` is a mutable, checkout-local snapshot. It is not
available from any public repository.

An immutable release must use a new version; published release bytes must never
be replaced. Snapshots can change while retaining their coordinate and must not
be treated as immutable releases.

## Provisional 0.x policy

While the major version is zero, the public API and behavior are provisional.
No source, binary, or behavioral compatibility is promised for the provisional
0.x line. A known breaking change requires the next minor version, not a patch
version. Patch versions are reserved for backward-compatible fixes.

The planned first pre-release is `0.1.0-alpha.1`. Read its release notes before
using it as a baseline. Local checks and CI staging do not establish
publication, distribution, Java 17 runtime support, or release readiness.
