# Versioning

The artifact is `htsp` in the group `at.bernhardberger.tvheadend`. `main`
currently carries `0.1.0-alpha.1-SNAPSHOT`, and this repository makes no external publication or availability claim: the
coordinate will not resolve from any public repository today.

## What to expect while the version is 0.x

The public API is provisional. Names, signatures, and behavior can change
between snapshots without deprecation cycles, and no source or binary
compatibility is promised until a stable baseline exists. Pin an exact version
once releases begin, and read the release notes before upgrading.

The planned first immutable baseline is `0.1.0-alpha.1`. Tagging, signing, and
publishing it are separate release-stage work, not something that happens as a
side effect of other changes.

## For contributors

Public ABI changes are fail-closed: they need an explicit versioned change,
updated API evidence, and consumer review. Current source layout is an
extraction baseline, not a stability promise.
