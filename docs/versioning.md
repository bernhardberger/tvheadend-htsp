# Versioning

The root project and artifact are named `htsp`, with group
`at.bernhardberger.tvheadend`. Before the first immutable release baseline,
`main` uses `0.1.0-alpha.1-SNAPSHOT`. That coordinate is provisional and this
repository currently makes no external publication or availability claim.

The planned first immutable baseline is `0.1.0-alpha.1`. Creating publication
configuration, staging artifacts, compiling an isolated external-coordinate
consumer, changing the group fallback, signing, tagging, or releasing belongs
to separately authorized later work. P3-E1 does none of those operations.

Compatibility policy is fail-closed: public ABI changes require an explicit
versioned slice, updated API evidence, consumer review, and the applicable
publication-stage gates. Current source paths are extraction baselines, not a
stability promise.
