# Documentation

Guides for people using the library, and references for people working on it.

## Using the library

- [`public-api.md`](public-api.md): how calls report success and failure,
  covering typed outcomes, cancellation, and what error values never carry.
- [`versioning.md`](versioning.md): the current version, its provisional compatibility status,
  and what compatibility you can expect.
- [`releasing.md`](releasing.md): one-time GitHub Environment setup and the
  exact-tag automatic Central and GitHub prerelease path.
- [`CHANGELOG.md`](../CHANGELOG.md): the `0.2.0` provisional release notes.
- [`licensing.md`](licensing.md): GPLv3 obligations, attribution, and project
  lineage.

## Repository internals

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

Current repository code and tests override any generic guidance in these
documents. Nothing here authorizes publication, signing, release, or other
release-stage operations.
