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

- One primary owns the task end-to-end. Routine work and ordinary releases need
  no planner, reviewer, package chain or coordinator. Split only for a real
  dependency, ownership or authorization boundary, not for workflow stages.
- For non-trivial non-UX changes, use independent Astra primary and Opus second
  reviewers on the same bounded change and evidence. Keep the second initial
  packet free of the first reviewer's verdict and findings. Routine low-impact
  work needs no pair. UX-specific roles are not engineering-review substitutes.
- Before any child dispatch or reviewer follow-up, read
  `docs/review-routing.md` for packet requirements, writable-child limits, Opus
  preflight, fallback and adjudication. Keep configured role settings and contracts.
- Existing admitted manifests retain their explicit authority and gates; do not
  silently weaken an in-flight package.

## Context and outcome routing

Load only the procedure relevant to the current work:

| Work | Local context |
|---|---|
| Instruction/harness changes or authorized task delivery | `docs/ai-engineering-harness.md` |
| Child dispatch or review follow-up | `docs/review-routing.md` |
| Protocol method or wire-field changes | `docs/htsp-protocol/README.md` and its pinned evidence |
| Public API or ABI changes | `docs/public-api.md` and the ABI workflow in `docs/htsp-protocol/README.md` |
| Kotlin ownership/type design or coroutine semantics | Matching local skill, then only its relevant reference |
| Release preparation, signing or publication | `docs/releasing.md` |

Own the authorized outcome through verification and delivery, including ordinary
in-scope recovery. Stop for missing authority, a consequential unresolved product
choice or a demonstrated blocker; a failed first approach alone is not a stop.
Coordinate actual conflicting edits and Git/build actions. For centrally admitted
work, repository/resource overlap alone is not a scheduling gate; the restricted
writable-child rules still apply.

## Build and verify

The Gradle wrapper is the build prerequisite; JDK toolchains resolve
automatically. CI (`.github/workflows/ci.yml`) is the authoritative gate.

- Run affected tests while developing and `./gradlew build check` for the final
  code gate. Stage publication only for publication/build changes or release.
  Do not clean by default or repeat successful unchanged gates for review.
  Test concrete behavior, not model names, prompt prose or hypothetical scope.
- Review-routing verification: `./test-review-routing.sh`.
- Low-impact instruction-only cleanup uses affected existing static/routing and
  fresh-loading checks plus final diff/settings inspection; no mandatory reviewer
  pair, product build or new prompt-wording tests. Explicit admitted gates remain.

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

- One authorized task may prepare, verify, tag, publish and confirm availability.
  No separate release-preparation, review, convergence or verification packages
  are required. Read `docs/releasing.md` before release work; it owns the
  authorization, credential and artifact-verification procedure.

- The tagged release workflow (GitHub Actions on repository `main`, exact tag
  `v*`) is the only publication path. Preparing or checking release files never
  authorizes a tag, credential operation, publication, or release.
- Commits, pushes, credential use, tags, signing and publication require explicit
  maintainer authority covering those operations and targets. An already-authorized
  task may carry them through without repeated per-operation approval; ordinary
  repository delivery does not authorize release operations. Never expose secrets.
