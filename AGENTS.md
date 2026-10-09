# HTSP library engineering guide

Guidance for anyone changing this repository, whether working by hand or with
a coding agent. Repository tests and CI override generic advice, including the
optional skills under `.opencode/skills/`.

## Provenance

This GPLv3 library is an independently maintained descendant of
`Preclikos/tvhstream`. Preserve its attribution, notices, license, and the
recorded extraction provenance in `docs/extraction/`. Do not describe it as
official TVHeadend software or as wholly original work.

## Working style

- Keep changes minimal and scoped. Check `git status` before editing and never
  overwrite someone else's uncommitted changes.
- Prefer standard, maintained ecosystem tooling (Gradle, Kotlin plugins,
  detekt, Konsist, Dokka, GitHub Actions) over bespoke scripts. Do not add new
  repository tooling, checkers, generators, or languages without explicit
  maintainer approval.
- Behavior changes ship with a focused regression test.
- Use Conventional Commit subjects (`feat(htsp):`, `fix:`, `docs:`, `chore:`),
  with `!` and a `BREAKING` changelog note for incompatible changes.
- Non-trivial changes to the protocol surface, public API/ABI, concurrency, or
  the release path deserve an independent review before they land. The
  optional `htsp-reviewer` agent in `.opencode/agents/` is one way to get it.

## Where to look

| Work | Read first |
|---|---|
| Protocol method or wire-field changes | `docs/htsp-protocol/README.md` and its pinned evidence |
| Public API, outcomes, cancellation | `docs/public-api.md` |
| Versioning and compatibility | `docs/versioning.md` |
| Release preparation, signing, publication | `docs/releasing.md` |
| Kotlin API or coroutine design questions | Optional skills in `.opencode/skills/` |

## Invariants

- Production declarations live only in the five shallow packages `connection`,
  `jsonapi`, `messages`, `requests`, and `wire` under
  `at.bernhardberger.tvheadend.htsp`.
- Production code depends only on sibling declarations, the Kotlin/JDK standard
  libraries, and `kotlinx-coroutines-core`. Never add Android, Media3, native,
  or application code.
- Public suspending server round trips return typed outcomes; cancellation
  propagates as cancellation. Preserve the transport-owned lifecycle scopes.
  Error values never carry client secrets, credentials, endpoints or throwables;
  `ServerError` may carry the server's own error text, which `toString` never renders.
- `docs/htsp-protocol/` holds the upstream pin record and protocol notes. The
  client requests HTSP v44 by default, matching the typed surface's v44
  coverage ceiling. The protocol surface is hand-maintained; a method or
  wire-field change ships with a focused regression test in the same change.
- The public ABI is tracked in `api/htsp.api` by Kotlin Gradle plugin ABI
  validation. `checkKotlinAbi` runs in `check`; after an intentional public API
  change, regenerate the dump with `./gradlew updateKotlinAbi` and never edit it
  by hand. From 1.0.0, a dump diff that removes or changes a declaration (other
  than an old overload becoming `synthetic`) requires a new major version; follow
  the compatibility rules in `docs/versioning.md` and the data-class recipe in
  `docs/public-api.md`.

## Build and verify

The Gradle wrapper is the only build prerequisite; JDK toolchains resolve
automatically. CI (`.github/workflows/ci.yml`) is the authoritative gate.

- Run affected tests while developing and `./gradlew build check` before
  submitting. Do not clean by default.
- Publication or build-logic changes also run the staged publication and
  consumer-contract steps from `ci.yml`.

## Release trust boundary

- The tagged release workflow (GitHub Actions on repository `main`, exact tag
  `v*`) is the only publication path. Read `docs/releasing.md` before release
  work; it owns the authorization, credential and artifact-verification
  procedure.
- Preparing or checking release files never authorizes a tag, credential
  operation, publication, or release. Commits, pushes, tags, signing, credential
  use and publication require explicit maintainer authority for those
  operations and targets. Never expose secrets.
