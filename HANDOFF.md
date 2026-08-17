# Remediation handoff (temporary)

This file is the binding engagement spec for the implementing agent. It is
temporary: delete it as the final step of the last slice. `AGENTS.md` governs
the project generally; where the two appear to conflict, follow this file and
note the conflict in your report.

## Mission

This repository is a small Kotlin/JVM library (~7.5k lines of production
Kotlin, ~4.7k of it generated) surrounded by ~32k lines of bespoke Python
verification and governance tooling. The maintainer has decided: **keep the
pinned protocol-evidence and code-generation pipeline; replace everything else
with standard ecosystem tooling.** Your job is to execute that, in the slices
below, with minimal diffs and zero new bespoke machinery.

## Step 0 — working tree

Run `git status -sb`. Work only on branch `refactor/gradle9-tooling`. If the
current branch is `main` and the dirty set is either empty or exactly
`AGENTS.md`, `HANDOFF.md`, `.opencode/opencode.json`, and
`tools/check-standalone-repository`, create the branch with
`git switch -c refactor/gradle9-tooling`. If another branch is active, or any
other path is dirty, **STOP and ask the maintainer.** The listed changes are
the reviewed remediation bootstrap; if present, run the existing policy check
and commit them as `chore: add remediation harness`. Never stash, revert, or
modify pre-existing work, including parked release-lane work.

Once Step 0 passes, execute every slice in order without waiting for routine
maintainer approval between slices. Keep a per-slice change and verification
log, and stop only for a STOP condition defined at the end of this file.

## Operating rules (binding)

1. Minimal diffs. No drive-by refactors, renames, or reformatting outside a
   slice's owned paths.
2. Create only the files a slice explicitly lists. No new helpers, frameworks,
   abstraction layers, config files, or documentation files.
3. No new languages, tools, or Gradle plugins beyond this approved set:
   **detekt, Konsist, Dokka, Foojay toolchain resolver**. Use the current
   stable version of each at implementation time; verify coordinates and
   versions from the official Gradle Plugin Portal / project docs; pin them in
   `gradle/libs.versions.toml`. The only approved agent skills are the two
   selectively vendored Chris Banes skills in slice 2.4a; do not install any
   complete skill catalog or routing plugin.
4. **Never** write checks that hash, AST-pin, fingerprint, or otherwise
   self-verify the repository's own tooling. Git history is the tamper record.
   This project was already burned once by that pattern; it is forbidden.
5. No ceremony: no gate codes, phase codes, evidence-class labels, or policy
   dialect in docs, identifiers, or error messages. Plain sentences only.
6. Never hand-edit `src/main/kotlin/**/Generated*.kt`. Regeneration happens
   only through the documented generators in `docs/htsp-protocol/README.md`.
7. Do not modify pinned protocol evidence (`docs/htsp-protocol/upstream.json`,
   `docs/htsp-protocol/htsp_spec.json`) or `docs/extraction/`. If a change
   seems necessary, STOP and report.
8. Do not port a Python checker's self-test scaffolding, fixtures, or CLI
   plumbing. Port the **rule**, as a short readable test, then delete the
   checker. Read the checker first and write the rule it enforces as a plain
   comment above the new test.
9. The public API must not change: `./gradlew check` must pass with an
   unmodified `api/htsp.api`.
10. After each slice's complete gate passes, stage only that slice's files and
    create one concise, non-amended commit. Never commit a failing or partial
    slice. Never push, tag, merge, stash, reset, rebase, amend, or skip hooks.
    Record each commit SHA and gate output in the final report; do not pause
    after a green slice solely for review. The maintainer reviews and merges
    the completed branch.
11. Verification is empirical: run the gate commands and paste the real output
    into your report. Never claim green without output.
12. If you find yourself writing more than ~50 lines of non-test code to
    replace something, STOP and reconsider; that is the failure mode this
    engagement exists to fix.
13. Do not ask the maintainer to choose implementation details already settled
    here. Use the smallest standard ecosystem solution consistent with the
    slice and continue.

## Forbidden paths for the whole engagement

- `release/**`, `.github/workflows/release.yml`, `tools/publish-central-release`,
  `docs/releasing.md`, `release/openpgp/**` (release lane is parked; separate phase)
- `docs/extraction/**` (immutable provenance)
- `docs/htsp-protocol/` except running its existing `--check` commands
- `src/main/kotlin/**/Generated*.kt` (never hand-edit)

## Landmines (verified — read before each slice)

- `tools/check-standalone-repository` (until deleted in slice 2.4) enforces
  **closed inventories**: `ROOT_ENTRIES`, `DOC_FILES`, `TOOL_FILES`,
  `FIXTURE_FILES`, `OPENPGP_FILES`. Any slice that adds or removes a tracked
  file must update these inventories in the same change, until 2.4 removes the
  checker entirely. Known upcoming additions: `gradle.properties`,
  `detekt.yml`.
- The same checker enforces **governance tokens** that must appear (and must
  not appear) across `docs/README.md`, `docs/releasing.md`, `AGENTS.md`, and
  `release/openpgp/README.md` (see `governance_errors`). The new `AGENTS.md`
  satisfies them; keep it that way when editing docs.
- The same checker embeds **byte-identical copies of `.github/workflows/ci.yml`**
  (`CI_ACTIVE_POLICY` / `EXPECTED_CI_ACTIVE`). Editing `ci.yml` before slice
  2.4 breaks the build. CI rewrite happens only in slice 2.5, after the
  checker is gone. `.github/workflows/ci.yml` also has maintainer WIP: diff it
  against HEAD before rewriting; preserve WIP changes unrelated to
  `verify-htsp`; leave anything release-related alone.
- The same checker pins **wrapper and provenance digests**. The wrapper bump
  in slice 1.1 must update those constants in the same change (grep the
  checker for the sha256 constants), plus `docs/wrapper-provenance.md`.
- `tools/check-published-jvm-compatibility` asserts the exact staged archive
  inventory. If a slice changes jar contents (e.g. Dokka output), update that
  checker's expectations in the same change. This checker stays; do not
  otherwise refactor it.
- `tools/check-htsp-generated-drift` stays as-is. Do not refactor or "improve"
  it. Same for everything in `docs/htsp-protocol/`.
- `consumer-contract/build.gradle.kts` and its `settings.gradle.kts` are
  token-validated by the checker until 2.4; slice 2.6 therefore comes after
  2.4.
- Known existing bugs to fix in scope: `tools/verify-htsp:49` gates the
  consumer-contract check on the version string (silently disabled for
  non-SNAPSHOT versions — moot once 2.5/2.6 land); dead monorepo constants
  (`SDK_ROOT = Path("sdk")`, `playback-media3`, `decoder-ffmpeg-binary`) exist
  in both `tools/check-single-package-per-module` and
  `tools/check-htsp-json-api-containment` (both files are deleted in 2.2);
  `HtspServiceRequestTimeoutTest.noPendingIdleTimeoutCyclesKeepConnectionLiveAndResponsive`
  is wall-clock flaky (fixed in 2.1).

## Phase 1 — build hygiene

### Slice 1.1: wrapper, environment, config cache

- Bump the wrapper to the latest Gradle 9.x patch release:
  `./gradlew wrapper --gradle-version <version> --distribution-sha256-sum <sum>`
  with the official sum from <https://gradle.org/release-checksums/>. This
  rewrites `gradlew`, `gradlew.bat`, `gradle/wrapper/*` — recompute their
  SHA-256s and update `docs/wrapper-provenance.md` and the digest constants in
  `tools/check-standalone-repository`.
- Create `gradle.properties`: `org.gradle.configuration-cache=true`,
  `org.gradle.caching=true`. Add `gradle.properties` to `ROOT_ENTRIES`.
- Run `./gradlew updateDaemonJvm --jvm-version=21` to generate
  `gradle/gradle-daemon-jvm.properties`.
- Add the Foojay resolver settings plugin in `settings.gradle.kts`
  (`org.gradle.toolchains.foojay-resolver-convention`, current stable).
- Fix whatever the configuration-cache problem report flags in the custom
  tasks (`verifyClassMajor61`, `verifyProductionDependencyGraph`,
  `writePublicationChecksums`, the `afterEvaluate` sourcesJar assertion) using
  Provider-based wiring. Fix only what the report flags.
- **Gate:** `./gradlew clean build check` green twice in a row (second run
  must show configuration-cache reuse); `./tools/check-standalone-repository`
  green.

### Slice 1.2: catalog and dependency verification

- Create `gradle/libs.versions.toml`; move every dependency and plugin
  coordinate in `build.gradle.kts` and `consumer-contract/` into it (the
  consumer build is separate; duplicating the two versions it needs is
  acceptable — do not build shared-catalog machinery).
- Enable Gradle dependency verification
  (`gradle/verification-metadata.xml`, generated via the documented
  `--write-verification-metadata` workflow, PGP+SHA-256 for the fixed
  dependency set).
- Delete the now-redundant reproducible-archive configuration block in
  `build.gradle.kts` (default behavior in Gradle 9).
- Do **not** add dependency locking, Build Scans, or Develocity.
- **Gate:** `./gradlew clean build check stageLocalPublication` green;
  dependency verification active (deliberately corrupt one entry locally,
  observe failure, restore).

### Slice 1.3: plugins

- Apply detekt (minimal `detekt.yml` at repo root — add it to `ROOT_ENTRIES`),
  Dokka (wire `javadocJar` to Dokka HTML output; check
  `tools/check-published-jvm-compatibility` expectations and update them in
  the same change), and Konsist (`testImplementation`, no Gradle plugin
  exists — it is a library).
- **Gate:** `./gradlew clean build check stageLocalPublication` green.

## Phase 2 — tooling consolidation

### Slice 2.1: JUnit 5 and the flaky test (do first, so all new tests are JUnit 5)

- Replace JUnit 4.13.2 with JUnit Jupiter (catalog), `useJUnitPlatform()` on
  the test task, mechanical migration across `src/test/`
  (`org.junit.Test` → `org.junit.jupiter.api.Test`,
  `Assert.*` → `Assertions.*`, `@Before` → `@BeforeEach`, etc.; grep for
  `@Rule`/`@Ignore`/`@RunWith` first and report if any exist).
- Fix
  `HtspServiceRequestTimeoutTest.noPendingIdleTimeoutCyclesKeepConnectionLiveAndResponsive`:
  convert to `runTest` virtual time, or widen the wall-clock bounds with a
  one-line justification comment. Do not otherwise refactor the test suite.
- **Gate:** 10 consecutive full-suite runs green locally; `./gradlew check`
  green.

### Slice 2.2: Konsist architecture tests; delete four Python checkers

- Create `src/test/kotlin/at/bernhardberger/tvheadend/htsp/architecture/` with
  one focused Konsist test class per rule, ported from (read each checker,
  write the rule as a comment, implement the test, mutation-verify it by
  injecting a violation and watching it fail, revert):
  - `tools/check-htsp-protocol-boundary` → production code lives only in the
    five packages and depends only on siblings, Kotlin/JDK, coroutines.
  - `tools/check-single-package-per-module` → exact production package set.
  - `tools/check-htsp-json-api-containment` → JSON API bridge ownership.
  - `tools/check-public-api-outcomes` → public suspending round trips return
    typed outcomes.
- Delete the four Python checkers and their `TOOL_FILES` entries and their
  lines in `tools/verify-htsp`. Do not port their self-tests or the dead
  monorepo constants.
- **Gate:** `./gradlew check` green; each rule demonstrably red under
  injection; `./tools/check-standalone-repository` green.

### Slice 2.3: detekt absorbs KDoc policy

- Enable detekt's undocumented-public-API rules for `src/main`, excluding
  `Generated*.kt`. Delete `tools/check-htsp-public-kdoc` (+ `TOOL_FILES`,
  `verify-htsp` lines).
- **Gate:** `./gradlew check` green; detekt demonstrably red on an injected
  undocumented public declaration.

### Slice 2.4: governance test; delete the remaining policy checkers

- Create one JUnit 5 test (e.g. `GovernanceDocumentationTest`) asserting:
  the exact required attribution/independence phrases currently enforced by
  `tools/check-license-attribution` (extract the exact strings from the
  checker before deleting it) in `NOTICE.md`/`README.md`/docs, and the two
  extraction-provenance SHA-256 digests currently pinned in
  `tools/check-standalone-repository` (`MAP_HASH`, `MANIFEST_HASH` — copy the
  constants).
- Delete `tools/check-license-attribution`,
  `tools/check-api-compatibility-policy` (KGP `abiValidation` + `api/htsp.api`
  already cover it — keep the documented ABI dump workflow), and
  `tools/check-standalone-repository` **in full** (its wrapper validation is
  replaced in 2.5; its release-doc token checks are dropped because the
  release lane is being redesigned separately).
- From this slice on, closed inventories no longer exist; later slices do not
  update them.
- **Gate:** remaining checks green; governance test red under phrase removal
  injection, then green after revert.

### Slice 2.4a: selectively vendor two Kotlin agent skills

- Do this only after slice 2.4 removes the closed file inventories. Vendor
  exactly these two directories, byte-for-byte, from
  <https://github.com/chrisbanes/skills> release `2026.8.16`, commit
  `a354bb0230d43396bdd3bd7c82aa7276aa1e1fae`:
  - `skills/kotlin-concurrency-and-flow/` →
    `.opencode/skills/kotlin-concurrency-and-flow/`
  - `skills/kotlin-api-design/` →
    `.opencode/skills/kotlin-api-design/`
- Copy the upstream Apache-2.0 license to
  `.opencode/skills/chrisbanes-skills-LICENSE.txt` and create
  `.opencode/skills/UPSTREAM.md` containing only the repository URL, release,
  commit, copied skill paths, and license path. Do not modify the vendored
  skill text.
- Do **not** add the `chrisbanes-skills` OpenCode plugin, router, Compose
  skills, workflow skills, or any other upstream skill. `.opencode/skills/`
  is discovered automatically; `opencode.json` needs no plugin entry.
- Add concise caveats under `AGENTS.md`'s working-style section: repository
  rules and tests override skills; coroutine advice must preserve intentional
  transport-owned lifecycle scopes and cancellation contracts; API-design
  advice applies only to hand-written public APIs and cannot authorize ABI
  changes or edits to generated sources.
- These skills are for future maintenance. Do not use their presence to reopen
  or expand earlier remediation slices.
- **Gate:** vendored files match the pinned upstream commit byte-for-byte;
  Apache license and pin record are present. Record that the maintainer must
  restart OpenCode after completion to confirm exactly the two approved skills
  are discoverable; this deferred restart does not block later slices.

### Slice 2.5: delete `verify-htsp`; rewrite CI

- Delete `tools/verify-htsp`. Rewrite `.github/workflows/ci.yml` (WIP rules
  from the landmines section apply) as: `gradle/actions/wrapper-validation`
  (current stable) → Temurin 21 → `./gradlew --no-daemon clean build check
  stageLocalPublication` → `python3 docs/htsp-protocol/report.py --check` and
  the four generator `--self-test` invocations →
  `./tools/check-htsp-generated-drift` →
  `./tools/check-published-jvm-compatibility` → consumer-contract step (2.6).
- Grep the whole repo for `verify-htsp` references and update them (README,
  CONTRIBUTING, docs — but not `docs/releasing.md`, which is parked).
- **Gate:** CI green on the pushed branch (or a maintained local equivalent
  if pushing is not authorized — report which).

### Slice 2.6: consumer-contract simplified and always run

- Replace the ~257-line `verifyConsumerDependencyGraph` task with a compact
  (~30-line) check: the consumer compiles against the staged publication and
  the resolved `htsp` jar bytes equal the staged jar bytes. Derive the
  expected version from the root project instead of hardcoding
  `0.1.0-SNAPSHOT`.
- Wire it into CI after `stageLocalPublication`, unconditionally.
- **Gate:** consumer step green in CI against staged bytes; red when the
  staged jar is perturbed (inject, observe, revert).

### Slice 2.7: close out

- Delete this file (`HANDOFF.md`).
- Full fresh-clone verification: every command a new contributor would run,
  in order, pasted into the report.
- Report: per-slice summary, files created/deleted, final tool inventory,
  final Python line count vs. the ~32.5k starting point, gate outputs.

## STOP conditions (report immediately, do not improvise)

- Working tree not clean at Step 0, or becomes dirty in a way you did not cause.
- Any slice seems to require touching forbidden paths or protocol evidence.
- The public ABI (`api/htsp.api`) would change.
- A deleted checker turns out to be the only thing covering an invariant that
  the approved toolset cannot express — report it instead of inventing a new
  bespoke replacement.
- Anything in this file contradicts the actual repository state.
