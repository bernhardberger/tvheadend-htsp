# Documentation

Guides for people using the library, and references for people working on it.

## Using the library

- [`public-api.md`](public-api.md): how calls report success and failure,
  covering typed outcomes, cancellation, and what error values never carry.
- [`versioning.md`](versioning.md): the current version, its pre-release status,
  and what compatibility you can expect.
- [`releasing.md`](releasing.md): the local-only staging boundary and the policy
  for the planned first release.
- [`CHANGELOG.md`](../CHANGELOG.md): unreleased, non-normative baseline notes.
- [`licensing.md`](licensing.md): GPLv3 obligations, attribution, and project
  lineage.

## Repository internals

- [`htsp-protocol/README.md`](htsp-protocol/README.md): the pinned HTSP v44
  protocol evidence, wire-level reference, and code generators behind the
  typed catalog.
- [`adr/0001-standalone-protocol-decisions.md`](adr/0001-standalone-protocol-decisions.md):
  the settled design decisions and the ideas deliberately left out.
- [`extraction/manifest.json`](extraction/manifest.json): the frozen record of
  the source extraction this repository started from.
- [`wrapper-provenance.md`](wrapper-provenance.md): where the Gradle wrapper
  bytes came from and which distribution they download.

Current repository code and tests override any generic guidance in these
documents. Nothing here authorizes publication, signing, release, or other
release-stage operations.
