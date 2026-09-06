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
- Supply the relevant diff, evidence, question and stop condition. Children keep
  their configured permissions. The primary adjudicates and fixes findings;
  re-review only a specific fix whose correctness remains uncertain.
- `htsp-implementer` is the one writable child. It may edit and run Gradle
  inside a single delegated slice with named paths, tests and gate, but never
  commits, tags, publishes or reaches a server. The primary reviews its diff,
  runs the final gate and owns commits. Never run it while another writer is
  editing the same worktree.
- Before every Opus review or follow-up dispatch, execute
  `./review-provider-route.sh select eligible`; only successful `opus` output
  permits dispatch. Never source the selector or its credential file. Unknown or
  unavailable quota returns `astra` for an independent Astra fallback.
  Record the reason and absent Opus coverage.
- If Opus hits quota exhaustion despite preflight, abort that exact reviewer
  through supported session control and verify it stopped. Do not wait for quota
  reset, retry in a loop or spawn repeated Opus replacements. Continue fallback
  and independent authorized work. Explicitly non-substitutable admitted gates
  require central reconciliation, not silent waiver.
- No review packages, automatic third reviewer or repeated broad audits. Model
  and effort assignments otherwise remain in configuration; retired roles stay
  retired. The implementing primary adjudicates both reviews and fixes supported
  findings; follow up only unresolved findings or material changes.
- Existing admitted manifests retain their explicit authority and gates; do not
  silently weaken an in-flight package.

## Build and verify

The Gradle wrapper is the build prerequisite; JDK toolchains resolve
automatically. CI (`.github/workflows/ci.yml`) is the authoritative gate.

- Run affected tests while developing and `./gradlew build check` for the final
  code gate. Stage publication only for publication/build changes or release.
  Do not clean by default or repeat successful unchanged gates for review.
  Test concrete behavior, not model names, prompt prose or hypothetical scope.
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

- One authorized task may prepare, verify, tag, publish and confirm availability.
  No separate release-preparation, review, convergence or verification packages
  are required. Use `docs/releasing.md`; retain its artifact checks and the
  exact publication authorization below.

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
