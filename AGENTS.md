# HTSP library engineering guide

This GPLv3 library is an independently maintained descendant of
`Preclikos/tvhstream`. Preserve its attribution, notices, license, and the
recorded extraction provenance in `docs/extraction/`. Do not describe it as
official TVHeadend software or as wholly original work.

## Working style

- Keep changes minimal and scoped. Inspect `git status -sb` before editing and
  never overwrite existing uncommitted changes.
- Prefer standard, maintained ecosystem tooling (Gradle, Kotlin plugins,
  detekt, Konsist, Dokka, GitHub Actions) over bespoke scripts. Do not add new
  repository tooling, checkers, generators, or languages without explicit
  maintainer approval.
- Behavior changes ship with a focused regression test.
- Repository rules and tests override skill guidance.
- Coroutine advice must preserve intentional transport-owned lifecycle scopes
  and cancellation contracts.
- API-design advice applies only to hand-written public APIs and cannot
  authorize ABI changes or edits to generated sources.

## Build and verify

The Gradle wrapper is the build prerequisite; JDK toolchains resolve
automatically. CI (`.github/workflows/ci.yml`) is the authoritative gate.

- Local verification: `./gradlew clean build check stageLocalPublication`.
- Protocol evidence and generated-source drift:
  `./tools/check-htsp-generated-drift` and
  `python3 docs/htsp-protocol/report.py --check`. These are plain Python and
  never need Gradle.

## Invariants

- Production declarations live only in the five shallow packages `connection`,
  `jsonapi`, `messages`, `requests`, and `wire` under
  `at.bernhardberger.tvheadend.htsp`.
- Production code depends only on sibling declarations, the Kotlin/JDK standard
  libraries, and `kotlinx-coroutines-core`. Never add Android, Media3, native,
  or application code.
- Public suspending server round trips return typed outcomes; cancellation
  propagates as cancellation. Error values never carry secrets or credentials.
- `docs/htsp-protocol/` holds the pinned HTSP v44 evidence. A method or
  wire-field change updates the spec, matrix, catalog, and generated Kotlin in
  the same change. `Generated*.kt` files are never hand-edited; regenerate them
  with the commands documented in `docs/htsp-protocol/README.md`.
- The public ABI is tracked in `api/htsp.api` through Kotlin Gradle plugin ABI
  validation; update it only through the documented ABI dump workflow.

## Release trust boundary

- The tagged release workflow (GitHub Actions on repository `main`, exact tag
  `v*`) is the only publication path. Preparing or checking release files never
  authorizes a tag, credential operation, publication, or release.
- One-time setup places `MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE`, and
  `CENTRAL_PORTAL_TOKEN` in the `central` GitHub Environment. Only the release
  publish step receives them.
- Never print release secrets or place them in source, process arguments,
  artifacts, logs, reports, or generated output. The dedicated Maven/OpenPGP
  key remains separate from any Android APK signing key. The tracked public key
  and its full primary fingerprint are the signature-verification authority.
- Never run `git tag`, `git push`, signing, or publication steps without an
  explicit maintainer instruction for that specific operation.
