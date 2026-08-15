# Contributing

Start with `AGENTS.md` and the [documentation index](docs/README.md); they
explain how the repository is organized and which document governs which kind
of change.

A few rules matter for every change:

- Keep the five-package protocol boundary, the typed-outcome error policy, the
  generated protocol evidence, and the attribution notices intact.
- Runtime, lifecycle, protocol, or API changes need a failing regression test
  first.
- A change that adds an HTSP method or maps a new wire field must regenerate
  the protocol evidence in the same commit (see
  [`docs/htsp-protocol/README.md`](docs/htsp-protocol/README.md)).
- Run `./tools/verify-htsp --non-gradle` before pushing. CI runs the same
  checks plus the Gradle build.

Publication, signing, release, Android, Media3, and application work are out of
scope for ordinary library changes.
