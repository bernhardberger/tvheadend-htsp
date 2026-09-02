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
  authorize ABI changes.

## Delegation and review routing

- Repository-local `htsp-*` subagents are read-only advisers. The package
  primary keeps planning, write, gate, adjudication, and completion authority.
  Give each child a bounded question, named evidence, and required output; do
  not ask a child to remediate its own findings.
- Use `htsp-locator` for exact repository evidence, `htsp-planner` for bounded
  implementation plans, `htsp-analyze` for root-cause and design analysis, and
  `htsp-research` for upstream protocol research. These roles do not replace
  the package primary or create an external orchestrator.
- Package primaries invoke the fixed repository `htsp-review-sol` and
  `htsp-review-opus` Task subagents directly. Never ask the coordinator to
  select or launch them, and never launch them as external top-level sessions.
- Only genuinely critical or complex non-release review packets are eligible
  for Opus. After the candidate commit and gates are frozen, run the mandatory
  `htsp-review-sol`, then immediately run `./review-provider-route.sh select
  eligible`; add `htsp-review-opus` only when the selector returns `opus`.
- Routine, trivial, lower-stakes, documentation, test-only, configuration-only,
  and release packets are Sol-only and must not run the Opus selector. A
  primary's effort or model variant never makes a packet Opus-eligible.
- Classify executable credential-boundary or reviewer-routing changes by their
  actual security complexity, not by their effort label. Package primaries must
  never source review-selector credentials. Optional-provider failure is
  non-blocking, but a clean mandatory Sol review is not optional. Independently
  adjudicate every completed finding against the frozen evidence; a material
  correction requires new gates, a new frozen candidate, and a new review round.
- Do not restore removed generation tooling, remediation permissions, legacy
  commands, package arrays, or an external orchestration layer as part of
  delegation work.

## Build and verify

The Gradle wrapper is the build prerequisite; JDK toolchains resolve
automatically. CI (`.github/workflows/ci.yml`) is the authoritative gate.

- Local verification: `./gradlew clean build check stageLocalPublication`.
- Review-routing verification: `./test-review-routing.sh`.

## Invariants

- Production declarations live only in the five shallow packages `connection`,
  `jsonapi`, `messages`, `requests`, and `wire` under
  `at.bernhardberger.tvheadend.htsp`.
- Production code depends only on sibling declarations, the Kotlin/JDK standard
  libraries, and `kotlinx-coroutines-core`. Never add Android, Media3, native,
  or application code.
- Public suspending server round trips return typed outcomes; cancellation
  propagates as cancellation. Error values never carry secrets or credentials.
- `docs/htsp-protocol/` holds the upstream pin record and protocol notes. The
  client requests HTSP v43 by default, while the typed surface has a v44
  coverage ceiling. The protocol surface is hand-maintained; a method or
  wire-field change ships with a focused regression test in the same change.
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
